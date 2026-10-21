package app.nicotv.firetv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nicotv.firetv.AppContainer
import app.nicotv.firetv.data.Entitlement
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.SeriesSnapshot
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class TopDest(val label: String, val icon: String) {
    HOME("ホーム", "⌂"),
    RANKING("ランキング", "♛"),
    SEARCH("検索", "⌕"),
    HISTORY("視聴履歴", "◷"),
    LIBRARY("ライブラリ", "☰"),
    SETTINGS("設定", "⚙"),
}

/** 再生キューの項目。同じ動画の意図的な重複を項目IDで区別する (SER-06)。 */
data class QueueEntry(val entryId: Long, val video: VideoSummary, val origin: String)

sealed interface Route {
    data class Top(val dest: TopDest) : Route
    data class Detail(val video: VideoSummary) : Route
    data class Series(val provider: Provider, val id: String, val title: String) : Route
    data class Search(val query: String, val tag: Boolean) : Route
    data class Player(
        val queue: List<QueueEntry>,
        val index: Int,
        val startMs: Long,
        /** false なら「この話だけ見る」 */
        val continuous: Boolean,
        val seriesId: String?,
        val seriesTitle: String?,
        /** 単体で始めた動画がシリーズに属していれば、再生中にシリーズのキューへつなぐ */
        val discoverSeries: Boolean = false,
        /** 属するシリーズが分かっている場合（調べる手間を省く）。話の一覧は再生が始まってから取る */
        val knownSeries: app.nicotv.firetv.data.SeriesRef? = null,
        val source: QueueSource? = null,
        val firstPage: Int = 1,
        val nextPage: Int? = null,
    ) : Route
    data object Login : Route
    data class Owner(val owner: app.nicotv.firetv.data.OwnerRef) : Route
    data class Mylist(val provider: Provider, val id: String, val name: String) : Route
}

class Entry(val route: Route) {
    val focus = FocusMemory()
    val memory = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(key: String, init: () -> T): T = memory.getOrPut(key, init) as T
}

data class ErrorInfo(val title: String, val message: String, val officialUrl: String? = null)

class AppState(val c: AppContainer, private val scope: CoroutineScope, val exitApp: () -> Unit) {
    val stack = mutableStateListOf(Entry(Route.Top(TopDest.HOME)))
    val current: Entry get() = stack.last()

    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<ErrorInfo?>(null)
    private var busyJob: Job? = null
    private val entryIds = AtomicLong(1)

    fun navigate(route: Route) { stack.add(Entry(route)) }

    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    fun switchTop(dest: TopDest) {
        val top = stack.first().route as? Route.Top
        if (stack.size == 1 && top?.dest == dest) return
        stack.clear()
        stack.add(Entry(Route.Top(dest)))
    }

    fun newEntry(v: VideoSummary, origin: String) = QueueEntry(entryIds.getAndIncrement(), v, origin)

    fun cancelBusy() { busyJob?.cancel(); busy = null }

    private fun runBusy(label: String, block: suspend () -> Unit) {
        busyJob?.cancel()
        busy = label
        busyJob = scope.launch {
            try { block() } finally { busy = null }
        }
    }

    // ---------- 再生の入口 ----------

    /** 単体で再生。シリーズに属する場合も自動次話はしない */
    fun playSingle(v: VideoSummary, startMs: Long = 0) {
        navigate(Route.Player(listOf(newEntry(v, "単体")), 0, startMs, continuous = false, seriesId = null, seriesTitle = null))
    }

    /** 一覧（マイリスト・投稿動画など）の指定位置から、その順で連続再生 */
    fun playList(videos: List<VideoSummary>, index: Int, title: String, source: QueueSource? = null, firstPage: Int = 1, nextPage: Int? = null) {
        if (videos.isEmpty()) return
        val q = videos.map { newEntry(it, "一覧") }
        navigate(Route.Player(q, index.coerceIn(0, q.lastIndex), 0, continuous = true, seriesId = null, seriesTitle = title, source = source, firstPage = firstPage, nextPage = nextPage))
    }

    /** 動画の投稿者のページを開く */
    fun openOwner(v: VideoSummary) {
        val id = v.ownerId ?: return
        navigate(Route.Owner(app.nicotv.firetv.data.OwnerRef(v.provider, id, v.ownerName, v.ownerIconUrl)))
    }

    /** シリーズの指定話から連続再生 */
    fun playSeries(s: SeriesSnapshot, index: Int, startMs: Long = 0, continuous: Boolean = true) {
        if (s.episodes.isEmpty()) return
        val q = s.episodes.map { newEntry(it.video, "シリーズ") }
        navigate(Route.Player(q, index.coerceIn(0, q.lastIndex), startMs, continuous, s.id, s.title, source = QueueSource.Series(s.provider, s.id), firstPage = s.firstPage, nextPage = s.nextPage))
    }

    /**
     * 動画をすぐ再生する。シリーズの話一覧は再生が始まってから裏で調べ、見つかればシリーズのキューへつなぐ。
     * [knownSeries] が分かっていれば、属するシリーズを調べる手間を省く
     */
    fun playAuto(v: VideoSummary, startMs: Long = 0, knownSeries: app.nicotv.firetv.data.SeriesRef? = null) {
        navigate(Route.Player(listOf(newEntry(v, "単体")), 0, startMs, continuous = true, seriesId = null, seriesTitle = null, discoverSeries = true, knownSeries = knownSeries))
    }

    private fun catalogOf(v: VideoSummary) = if (v.provider == Provider.DEMO) c.demo else c.nico

    /** 保存済みのシリーズに、この動画が入っていればその位置 */
    private fun cachedSeriesWith(v: VideoSummary, seriesId: String): Pair<SeriesSnapshot, Int>? {
        val snap = catalogOf(v).cachedSeries(seriesId)?.value ?: return null
        val idx = snap.episodes.indexOfFirst { it.video.id == v.id }
        return if (idx >= 0) snap to idx else null
    }

    /**
     * 動画を再生する。通信を待たずにすぐ再生画面へ移る。
     * シリーズを保存済みならそのキューで始め、無ければ再生が始まってから裏でシリーズを調べる
     */
    fun play(v: VideoSummary, series: app.nicotv.firetv.data.SeriesRef?, startMs: Long = 0) {
        if (series != null) cachedSeriesWith(v, series.id)?.let { (snap, idx) -> playSeries(snap, idx, startMs); return }
        playAuto(v, startMs, series)
    }

    /** ホームの「続き」。途中ならその位置、自然終了済みならシリーズの次話 (04 §8) */
    fun resume(p: WatchProgress) {
        val rewind = c.store.settings.resumeRewindSec * 1000L
        val pos = if (p.isInProgress) (p.positionMs - rewind).coerceAtLeast(0) else 0
        val v = p.toSummary()
        if (p.seriesId == null) { playAuto(v, pos); return }
        val ref = app.nicotv.firetv.data.SeriesRef(p.seriesId, p.seriesTitle ?: "シリーズ")
        // 見終わっていない話は、その話の続き（開いただけなら最初）からすぐ再生する（シリーズは保存済みを使うか、再生が始まってから調べる）
        if (!p.isFinished) { play(v, ref, pos); return }
        // 見終わった話の次は、保存済みのシリーズで分かればすぐ再生する
        cachedSeriesWith(v, p.seriesId)?.let { (snap, idx) ->
            if (idx + 1 < snap.episodes.size) { playSeries(snap, idx + 1, 0); return }
        }
        runBusy("続きを準備中") {
            when (val r = catalogOf(v).series(p.seriesId)) {
                is LoadResult.Ok -> {
                    val eps = r.value.episodes
                    val idx = eps.indexOfFirst { it.video.id == p.videoId }
                    when {
                        idx < 0 -> playAuto(v, 0, ref)
                        idx + 1 < eps.size -> playSeries(r.value, idx + 1, 0)
                        r.value.hasMore -> error = ErrorInfo("続きを取得できません", "シリーズの後続を取得できていません。シリーズ画面で再試行してください。")
                        else -> navigate(Route.Series(p.provider, p.seriesId, r.value.title))
                    }
                }
                is LoadResult.Failed -> playAuto(v, 0, ref)
            }
        }
    }

    /** 動画が属するシリーズの画面を開く。分からなければ調べる */
    fun openSeriesOf(v: VideoSummary) {
        runBusy("シリーズを確認中") {
            val cat = if (v.provider == Provider.DEMO) c.demo else c.nico
            val ref = cat.seriesOfVideo(v.id)
            if (ref != null) navigate(Route.Series(v.provider, ref.id, ref.title))
            else error = ErrorInfo("シリーズが見つかりません", "この動画はシリーズに登録されていないか、シリーズ情報を取得できませんでした。")
        }
    }

    fun entitlementHelp(v: VideoSummary, ent: Entitlement, reason: String?) {
        if (ent == Entitlement.SENSITIVE) {
            error = ErrorInfo(
                title = ent.label,
                message = (reason ?: app.nicotv.firetv.data.nico.NicoProvider.SENSITIVE_HELP) +
                    "\n\n設定を変えたあと、詳細画面の「権限を再確認」を選んでください。",
                officialUrl = "https://www.nicovideo.jp/watch/${v.id}",
            )
            return
        }
        error = ErrorInfo(
            title = ent.label,
            message = buildString {
                append("この動画はこのアプリのアカウントではまだ再生できません。")
                if (reason != null) append("\n理由: $reason")
                append("\n\n購入・加入は公式ページで行ってください。アプリは自動で購入しません。")
                append("\n公式で手続きしたあと、詳細画面の「権限を再確認」を選んでください。")
            },
            officialUrl = if (v.provider == Provider.NICO) "https://www.nicovideo.jp/watch/${v.id}" else null,
        )
    }
}
