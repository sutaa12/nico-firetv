package app.nicotv.firetv.repairs

import android.content.ContextWrapper
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import app.nicotv.firetv.AppContainer
import app.nicotv.firetv.comments.CommentCache
import app.nicotv.firetv.comments.CommentLoader
import app.nicotv.firetv.data.*
import app.nicotv.firetv.data.demo.DemoProvider
import app.nicotv.firetv.data.local.*
import app.nicotv.firetv.data.nico.NicoProvider
import app.nicotv.firetv.data.nico.WebViewCookieJar
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.queuePage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Expected-behavior regression tests. All requests are intercepted; no real accounts/device. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepairRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private val target = ThreadTarget("100", "main")
    private val source = CommentSourceInfo("https://comments.invalid", "fixture", "{}", "sm1", listOf(target))
    private val cleanup = ArrayList<() -> Unit>()

    @org.junit.After fun close() { cleanup.asReversed().forEach { it() } }
    private fun field(o: Any, n: String) = o.javaClass.getDeclaredField(n).apply { isAccessible = true }
    private fun replace(o: Any, n: String, v: Any) { field(o, n).set(o, v) }
    private fun context(dir: File) = object : ContextWrapper(null) { override fun getFilesDir() = dir }
    private fun isolatedStore(store: LocalStore) { cleanup.add { store.close() } }
    private fun container(): AppContainer = AppContainer(context(temp.newFolder())).also {
        isolatedStore(it.store)
        cleanup.add { it.ioScope.cancel(); it.nico.close() }
        cookies(it.cookieJar)
    }
    private fun cookies(jar: WebViewCookieJar): FixtureCookies = FixtureCookies().also { replace(jar, "manager\$delegate", lazyOf(it)) }
    private fun provider(): NicoProvider = NicoProvider(WebViewCookieJar().also { cookies(it) }).also { p -> cleanup.add { p.close() } }
    private fun stub(p: NicoProvider, handler: (Request) -> Pair<Int, String>) {
        p.onQualityKnown = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Offline fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        replace(p, "http", http)
        cleanup.add {
            (field(p, "appScope").get(p) as CoroutineScope).cancel()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
    }
    private fun watch(id: String, status: Int = 200, available: Boolean = true) =
        """{"meta":{"status":$status},"data":{"video":{"id":"$id","title":"Fixture","duration":120},"payment":{"video":{"isPpv":true}},"media":{"domand":{"videos":[{"isAvailable":$available}]}}}}"""
    private fun record(no: Long, past: Boolean = false, posted: Long = 1_700_000_000_000L) = CommentRecord(
        "sm1", "100", "main", no, null, 1000L, posted, "fixture-$no", null,
        CommentPosition.NAKA, CommentSize.MEDIUM, 0xFFFFFFFF, false, past,
    )
    private fun catalog(fetch: suspend (Long?, ThreadTarget?) -> List<CommentRecord>) = object : CatalogProvider by DemoProvider() {
        override suspend fun fetchComments(source: CommentSourceInfo, whenEpochSec: Long?, only: ThreadTarget?) =
            LoadResult.Ok(CommentBatch(fetch(whenEpochSec, only), null))
    }

    @Test fun differentUserIdsWithSameNicknameMustNotShareHistory() = runBlocking {
        val c = container()
        var id = 101
        stub(c.nico) { 200 to """{"meta":{"status":200},"data":{"user":{"id":$id,"nickname":"same-name","isPremium":false}}}""" }
        c.refreshAuth()
        val first = c.store.scope.value
        c.store.saveProgress(WatchProgress(Provider.NICO, "sm1", "Account A history"))
        id = 202
        c.refreshAuth()
        assertNotEquals("different user IDs must select different local partitions", first, c.store.scope.value)
        assertNull(c.store.progressOf(Provider.NICO, "sm1"))
    }

    @Test fun loggedInDetailCacheMustNotCrossAccounts() = runBlocking {
        val p = provider()
        val ck = cookies(p.cookieJar)
        stub(p) { 200 to watch("sm1") }
        assertTrue(p.videoDetail("sm1") is LoadResult.Ok)
        ck.value = "user_session=fixture-account-b"
        assertNull("account B must not reuse account A's entitlement", p.cachedDetail("sm1"))
    }

    @Test fun explicitDetailReloadMustObserveNewEntitlement() = runBlocking {
        val p = provider()
        var requests = 0
        stub(p) { requests++; 200 to watch("sm1", if (requests == 1) 403 else 200, requests != 1) }
        assertEquals(Entitlement.PURCHASE_REQUIRED, (p.videoDetail("sm1") as LoadResult.Ok).value.entitlement)
        val second = (p.videoDetail("sm1", force = true) as LoadResult.Ok).value
        assertEquals("the UI reload calls videoDetail again, but must bypass stale watch data", Entitlement.AVAILABLE, second.entitlement)
    }

    @Test fun unopenedTenSecondClipMustNotCountAsFinished() {
        val p = WatchProgress(Provider.NICO, "sm1", "short clip", durationSec = 10, positionMs = 0)
        assertFalse("zero playback cannot finish a short clip", p.isFinished)
    }

    @Test fun timeMachineMustRespectTotalCommentCap() = runBlocking {
        val cache = CommentCache(temp.newFolder())
        cache.write("sm1", (1..30_000).map { record(it.toLong(), past = true) })
        var pages = 0
        val cat = catalog { whenSec, _ ->
            if (whenSec == null || pages++ > 0) emptyList()
            else (30_001..31_000).map { record(it.toLong(), past = true, posted = 1_500_000_000_000L) }
        }
        val loader = CommentLoader(this, cat, source, cache)
        try {
            loader.loadNormal()
            (field(loader, "normalJob").get(loader) as Job).join()
            assertEquals(30_000, loader.comments.value.size)
            loader.loadAt(1_600_000_000, "old point")
            (field(loader, "atJob").get(loader) as Job).join()
            println("TIME_MACHINE retained=${loader.comments.value.size} cap=${CommentLoader.SAFETY_CAP}")
            assertTrue("time-machine fetch must respect the same hard cap", loader.comments.value.size <= CommentLoader.SAFETY_CAP)
        } finally { loader.cancel() }
    }

    @Test fun stoppingExtraFetchMustCancelTimeMachine() = runTest {
        var requests = 0
        val loader = CommentLoader(this, catalog { _, _ ->
            requests++
            delay(1000)
            listOf(record(requests.toLong(), past = true, posted = 1_500_000_000_000L - requests * 1000))
        }, source)
        try {
            loader.loadAt(1_600_000_000, "old point")
            runCurrent()
            assertEquals(1, requests)
            loader.stopBoost()
            advanceUntilIdle()
            assertEquals("stop button must cancel atJob as well as pastJob", 1, requests)
        } finally { loader.cancel() }
    }

    @Test fun currentCommentMustRefreshCachedClassificationAndScore() = runBlocking {
        val cache = CommentCache(temp.newFolder())
        val old = record(1, past = true)
        cache.write("sm1", listOf(old))
        val loader = CommentLoader(this, catalog { _, _ -> listOf(old.copy(fromPastLog = false, score = -6000)) }, source, cache)
        try {
            loader.loadNormal()
            (field(loader, "normalJob").get(loader) as Job).join()
            val actual = loader.comments.value.single()
            assertFalse("latest normal comment must be eligible for normal mode", actual.fromPastLog)
            assertEquals(-6000, actual.score)
        } finally { loader.cancel() }
    }

    @Test fun commentRetentionAgeMustNotResetForOldRecords() {
        val cache = CommentCache(temp.newFolder(), maxAgeMs = 30_000)
        val start = 1_000_000L
        cache.write("sm1", listOf(record(1)), start)
        val old = cache.read("sm1", start + 29_000)!!.comments
        // Same merge-and-rewrite path used when a new comment arrives on a later visit.
        cache.write("sm1", old + record(2), start + 29_000)
        val after = cache.read("sm1", start + 31_000)
        assertFalse("old comments must expire on their original retention deadline", after?.comments?.any { it.no == 1L } == true)
    }

    @Test fun expiredSnapshotCannotReappearAfterItsCacheWasRemoved() {
        val cache = CommentCache(temp.newFolder(), maxAgeMs = 1000)
        val comments = listOf(record(1))
        cache.write("sm1", comments, now = 100, originSavedAt = 100)
        assertNull(cache.read("sm1", now = 1200))
        cache.write("sm1", comments + record(2), now = 1300, originSavedAt = 100)
        assertNull(cache.read("sm1", now = 1400))
    }

    @Test fun memoryCacheMustEnforceItsByteBudgetAcrossUnevenEntries() {
        val cache = MetaCache(null)
        repeat(20) { cache.write("small-$it", "a".repeat(95_000)) }
        cache.write("large", "b".repeat(1_000_000))
        val retained = field(cache, "memoryChars").getLong(cache)
        println("META_CACHE retainedChars=$retained advertisedLimit=2000000")
        assertTrue("one insertion can require eviction of more than one old entry", retained <= 2_000_000)
    }

    @Test fun saveFailurePreservesPreviousFileAndCanRetry() = runBlocking {
        val dir = temp.newFolder()
        val store = LocalStore(context(dir))
        isolatedStore(store)
        store.saveProgress(WatchProgress(Provider.NICO, "sm1", "old"))
        assertTrue(store.flush())
        val file = File(dir, "nicotv-store.json")
        val before = file.readText()
        File(dir, "nicotv-store.json.tmp").mkdir()
        store.saveProgress(WatchProgress(Provider.NICO, "sm1", "new"))
        assertFalse(store.flush())
        assertNotNull(store.saveError.value)
        assertEquals(before, file.readText())
        File(dir, "nicotv-store.json.tmp").delete()
        assertTrue(store.flush())
        assertNull(store.saveError.value)
        val restored = LocalStore(context(dir)).also { isolatedStore(it) }
        assertEquals("new", restored.progressOf(Provider.NICO, "sm1")?.title)
    }

    @Test fun seriesWithoutItemsMustNotBecomeSuccessfulEmptyList() = runBlocking {
        val p = provider()
        stub(p) { 200 to """{"meta":{"status":200},"data":{"detail":{"title":"fixture"},"totalCount":2}}""" }
        assertTrue("schema mismatch must be UNKNOWN_FORMAT, not an empty successful series", p.series("1", true) is LoadResult.Failed)
    }

    @Test fun watchCacheEvictsExpiredEntriesAndBoundsLiveEntries() = runBlocking {
        val p = provider()
        stub(p) { r -> 200 to watch(r.url.pathSegments.last()) }
        for (i in 1..100) assertTrue(p.videoDetail("sm$i") is LoadResult.Ok)
        val cache = field(p, "watchCache").get(p) as Map<*, *>
        assertTrue(cache.size <= 8)
        cache.values.forEach { field(it!!, "at").setLong(it, 0L) }
        p.videoDetail("sm101")
        val expired = cache.values.count { field(it!!, "at").getLong(it) == 0L }
        println("WATCH_CACHE retained=${cache.size} expired=$expired")
        assertEquals(1, cache.size)
        assertEquals(0, expired)
    }

    @Test fun newlyOpenedLongEpisodeNowStaysOnThatEpisode() {
        val c = container()
        c.setDataSource(Provider.DEMO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val app = AppState(c, scope) {}
            app.resume(WatchProgress(Provider.DEMO, "demo2", "episode 2", durationSec = 120, positionMs = 0, seriesId = "s1"))
            val route = app.current.route as Route.Player
            assertEquals("demo2", route.queue[route.index].video.id)
        } finally { scope.cancel() }
    }
    @Test fun hidingDuringPreparationOrCountdownRevokesAutoplay() {
        val gate = app.nicotv.firetv.ui.player.PlaybackGate(true)
        assertTrue(gate.canPlay)
        gate.hideScreen()
        assertFalse(gate.canPlay)
        assertFalse(gate.requestPlay())
        gate.resumeScreen()
        assertFalse("returning to screen must not restart pending preparation", gate.canPlay)
        assertTrue(gate.requestPlay())
        gate.pause()
        assertFalse(gate.canPlay)
    }

    @Test fun nicknameChangeKeepsHistoryAndLegacyIsNeverAssignedAutomatically() = runBlocking {
        val c = container()
        var name = "old-name"
        stub(c.nico) { 200 to """{"meta":{"status":200},"data":{"user":{"id":101,"nickname":"$name","isPremium":false}}}""" }
        c.store.setScope("nico:old-name")
        c.store.saveProgress(WatchProgress(Provider.NICO, "legacy", "legacy"))
        c.refreshAuth()
        assertEquals("nico-id:101", c.store.scope.value)
        assertNull(c.store.progressOf(Provider.NICO, "legacy"))
        c.store.saveProgress(WatchProgress(Provider.NICO, "new", "new"))
        name = "renamed"
        c.refreshAuth()
        assertEquals("new", c.store.progressOf(Provider.NICO, "new")?.title)
        c.store.importLegacy("nico:old-name")
        assertNotNull(c.store.progressOf(Provider.NICO, "legacy"))
        assertTrue(c.store.data.value.accounts.containsKey("nico:old-name"))
    }

    @Test fun commentCacheAndStoryboardDoNotCrossAccounts() {
        val cache = CommentCache(temp.newFolder())
        cache.write("sm1", listOf(record(1)), scope = "nico-id:1")
        assertNull(cache.read("sm1", scope = "nico-id:2"))
        assertEquals(1, cache.read("sm1", scope = "nico-id:1")?.comments?.size)
        val board = StoryboardStore(temp.newFolder())
        board.write("sm1", Storyboard(1, 1, 1000, 1, 1, listOf(byteArrayOf(1))), scope = "nico-id:1")
        assertNull(board.read("sm1", scope = "nico-id:2"))
        assertNotNull(board.read("sm1", scope = "nico-id:1"))
    }

    @Test fun largeCommentBodiesRespectMemoryBudgetAndRetainedSeenSet() = runBlocking {
        val loader = CommentLoader(this, catalog { _, _ -> (1..30_010).map { record(it.toLong()).copy(body = "字".repeat(2048)) } }, source)
        try {
            loader.loadNormal()
            (field(loader, "normalJob").get(loader) as Job).join()
            val list = loader.comments.value
            assertTrue(list.size < 30_000)
            assertTrue(list.sumOf { app.nicotv.firetv.comments.CommentLimits.bytes(it) } <= app.nicotv.firetv.comments.CommentLimits.BYTES)
            assertTrue(list.all { it.body.length <= 1024 })
            assertEquals(list.size, (field(loader, "seen").get(loader) as Set<*>).size)
        } finally { loader.cancel() }
    }

    @Test fun repeatedMiniChecksCannotPostponeFullSeriesValidation() {
        val ep = SeriesEpisode(VideoSummary(Provider.NICO, "sm1", "one"), 1)
        val original = SeriesSnapshot(Provider.NICO, "1", "series", listOf(ep), 1, false, 1000)
        val renewed = original.copy(fetchedAt = 86_400_001)
        assertEquals(SeriesRefreshPlan.Full, planSeriesRefresh(renewed, 1, "sm1", now = 86_401_001))
    }

    @Test fun seriesAfterFivePagesHasUsableContinuationAndMalformedPageFails() = runBlocking {
        val p = provider()
        stub(p) { request ->
            val page = request.url.queryParameter("page")!!.toInt()
            val count = if (page <= 5) 100 else 50
            val items = (1..count).joinToString(",") { i ->
                val n = (page - 1) * 100 + i
                """{"video":{"id":"sm$n","title":"v$n"},"meta":{"order":$n}}"""
            }
            200 to """{"meta":{"status":200},"data":{"detail":{"title":"series"},"totalCount":550,"items":[$items]}}"""
        }
        val initial = (p.series("1", true) as LoadResult.Ok).value
        assertEquals(500, initial.episodes.size)
        assertEquals(6, initial.nextPage)
        val tail = (p.seriesPage("1", 6) as LoadResult.Ok).value
        assertEquals(50, tail.episodes.size)
        assertEquals("sm501", tail.episodes.first().video.id)
        assertNull(tail.nextPage)
        assertEquals(6, tail.firstPage)
    }

    @Test fun queueCarriesListIdentityAndCanLoadForwardAndBackwardPages() = runBlocking {
        val source = app.nicotv.firetv.ui.QueueSource.Mylist(Provider.DEMO, "250")
        val cat = object : CatalogProvider by DemoProvider() {
            override suspend fun mylist(id: String, page: Int): LoadResult<MylistPage> {
                val end = minOf(page * 100, 250)
                val videos = ((page - 1) * 100 + 1..end).map { VideoSummary(Provider.DEMO, "v$it", "video $it") }
                return LoadResult.Ok(MylistPage(id, "fixture", videos, 250, page < 3))
            }
        }
        val pages = (1..3).map { (cat.queuePage(source, it) as LoadResult.Ok).value }
        assertEquals(250, pages.sumOf { it.videos.size })
        assertEquals(listOf(2, 3, null), pages.map { it.nextPage })
        assertEquals("v101", (cat.queuePage(source, 2) as LoadResult.Ok).value.videos.first().id)
        val c = container()
        val state = AppState(c, this) {}
        state.playList(pages.first().videos, 99, "fixture", source, 1, 2)
        val route = state.current.route as Route.Player
        assertEquals(source, route.source)
        assertEquals(2, route.nextPage)
    }

    @Test fun existingSeriesLoopPreferenceStillMatchesPlaybackSource() {
        val c = container()
        c.setDataSource(Provider.DEMO)
        c.store.setListLoop("series:s1", true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val app = AppState(c, scope) {}
            val episodes = (1..2).map { SeriesEpisode(VideoSummary(Provider.DEMO, "demo$it", "Episode $it"), it) }
            app.playSeries(SeriesSnapshot(Provider.DEMO, "s1", "series", episodes, 2, false, 0), 0)
            val route = app.current.route as Route.Player
            assertTrue(route.source!!.key in c.store.account().loopLists)
        } finally { scope.cancel() }
    }

    @Test fun providerCloseReleasesOwnedLowPriorityThreads() = runBlocking {
        val before = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "nicotv-low" }
        repeat(6) {
            val p = provider()
            p.lowPriority { Unit }
            p.close()
        }
        delay(150)
        assertTrue(Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "nicotv-low" } <= before)
    }

    @Test fun oversizedMetadataIsNotRetainedAndDiskBudgetIsImmediate() {
        val memory = MetaCache(null)
        memory.write("large", "x".repeat(2_000_001))
        assertNull(memory.read("large"))
        val disk = MetaCache(temp.newFolder(), maxBytes = 100)
        repeat(12) { disk.write("key$it", (0..500).joinToString { n -> "value${n * (it + 1)}" }) }
        assertTrue(disk.totalBytes() <= 100)
    }

    @Test fun burstSavesKeepLatestSnapshot() = runBlocking {
        val dir = temp.newFolder()
        val store = LocalStore(context(dir)).also { isolatedStore(it) }
        repeat(1000) { store.saveProgress(WatchProgress(Provider.NICO, "same", "v$it", positionMs = it.toLong())) }
        assertTrue(store.flush())
        val restored = LocalStore(context(dir)).also { isolatedStore(it) }
        assertEquals(999L, restored.progressOf(Provider.NICO, "same")?.positionMs)
    }

}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class FixtureCookies : CookieManager() {
    var value: String? = "user_session=fixture-account-a"
    override fun setAcceptCookie(accept: Boolean) {}
    override fun acceptCookie() = true
    override fun setAcceptThirdPartyCookies(webview: WebView, accept: Boolean) {}
    override fun acceptThirdPartyCookies(webview: WebView) = false
    override fun setCookie(url: String, value: String) {}
    override fun setCookie(url: String, value: String, callback: ValueCallback<Boolean>?) {}
    override fun getCookie(url: String) = value
    override fun removeSessionCookie() {}
    override fun removeSessionCookies(callback: ValueCallback<Boolean>?) {}
    override fun removeAllCookie() {}
    override fun removeAllCookies(callback: ValueCallback<Boolean>?) { value = null; callback?.onReceiveValue(true) }
    override fun hasCookies() = value != null
    override fun removeExpiredCookie() {}
    override fun flush() {}
}
