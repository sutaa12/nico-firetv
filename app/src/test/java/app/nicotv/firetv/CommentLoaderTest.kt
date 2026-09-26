package app.nicotv.firetv

import app.nicotv.firetv.comments.CommentLoader
import app.nicotv.firetv.data.CatalogProvider
import app.nicotv.firetv.data.CommentBatch
import app.nicotv.firetv.data.CommentPosition
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.CommentSize
import app.nicotv.firetv.data.CommentSourceInfo
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.PlaybackDescriptor
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.RankingGenre
import app.nicotv.firetv.data.RankingSnapshot
import app.nicotv.firetv.data.RankingTerm
import app.nicotv.firetv.data.SearchPage
import app.nicotv.firetv.data.SearchSort
import app.nicotv.firetv.data.SearchTarget
import app.nicotv.firetv.data.SeriesRef
import app.nicotv.firetv.data.SeriesSnapshot
import app.nicotv.firetv.data.ThreadTarget
import app.nicotv.firetv.data.VideoDetail
import app.nicotv.firetv.data.local.BoostLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実際のコメントサーバーと同じ振る舞い（通常取得 = 各スレッドの新しい順1,000件、when = その時刻より前の新しい順1,000件）
 * をまねた取得元で、増量が途中で止まらないことを確かめる。公式アニメ（so41603987）と同じく、
 * メインスレッドが2本（動画用・チャンネル用）と、投稿時の日時を持つ投稿者コメントがある構成。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommentLoaderTest {
    private val now = System.currentTimeMillis()
    private val main = ThreadTarget("100", "main")
    private val community = ThreadTarget("101", "main")
    private val owner = ThreadTarget("100", "owner")

    private fun thread(t: ThreadTarget, count: Int, spanDays: Long): List<CommentRecord> = (0 until count).map { i ->
        CommentRecord(
            videoId = "so1", threadId = t.id, fork = t.fork, no = i + 1L, commentId = null, vposMs = (i * 37L) % 1_400_000,
            postedAt = if (t.fork == "owner") now - 1_000L * 86_400_000L else now - spanDays * 86_400_000L * (count - i) / count,
            body = "c$i", userId = null, position = CommentPosition.NAKA, size = CommentSize.MEDIUM, color = 0xFFFFFFFF,
            isOwner = t.fork == "owner", fromPastLog = false,
        )
    }

    private val data = mapOf(
        main to thread(main, 50_000, 900),
        community to thread(community, 4_000, 900),
        owner to thread(owner, 30, 0),
    )

    private inner class FakeServer(val pageSize: Int = 1_000) : CatalogProvider {
        var requests = 0
        override val provider = Provider.NICO
        override val rankingGenres = emptyList<RankingGenre>()
        override val rankingTerms = emptyList<RankingTerm>()
        override suspend fun ranking(genre: RankingGenre, term: RankingTerm, page: Int, force: Boolean): LoadResult<RankingSnapshot> = error("unused")
        override suspend fun search(query: String, target: SearchTarget, sort: SearchSort, offset: Int): LoadResult<SearchPage> = error("unused")
        override suspend fun videoDetail(id: String, force: Boolean): LoadResult<VideoDetail> = error("unused")
        override suspend fun series(id: String, force: Boolean): LoadResult<SeriesSnapshot> = error("unused")
        override suspend fun seriesOfVideo(videoId: String): SeriesRef? = null
        override suspend fun ownerProfile(owner: app.nicotv.firetv.data.OwnerRef) = error("unused")
        override suspend fun ownerVideos(owner: app.nicotv.firetv.data.OwnerRef, sort: app.nicotv.firetv.data.UploadSort, page: Int) = error("unused")
        override suspend fun ownerSeries(owner: app.nicotv.firetv.data.OwnerRef) = error("unused")
        override suspend fun ownerMylists(owner: app.nicotv.firetv.data.OwnerRef) = error("unused")
        override suspend fun mylist(id: String, page: Int) = error("unused")
        override suspend fun preparePlayback(id: String): LoadResult<PlaybackDescriptor> = error("unused")
        override suspend fun fetchComments(source: CommentSourceInfo, whenEpochSec: Long?, only: ThreadTarget?): LoadResult<CommentBatch> {
            requests++
            val out = data.flatMap { (t, list) ->
                if (only != null && t != only) emptyList()
                // 通常取得は最新順ではなく、動画全体（投稿時〜今）に散らばった抜粋を返す（実機で確認した振る舞い）
                else if (whenEpochSec == null) list.filterIndexed { i, _ -> i % maxOf(1, list.size / pageSize) == 0 }.take(pageSize)
                else list.filter { it.postedAt!! < whenEpochSec * 1000 }.takeLast(pageSize)
            }
            return LoadResult.Ok(CommentBatch(out.map { it.copy(fromPastLog = whenEpochSec != null) }, null))
        }
    }

    @Test fun boostKeepsGoingPastTheNormalFetchAndOwnerComments() = runTest {
        val server = FakeServer()
        val source = CommentSourceInfo("s", "k", "{}", "so1", listOf(owner, main, community))
        val loader = CommentLoader(this, server, source)
        loader.loadNormal()
        advanceUntilIdle()
        assertEquals(2_030, loader.state.value.normalCount) // 1,000 + 1,000 + 投稿者30（最古の投稿も含む抜粋）
        loader.startBoost(BoostLevel.ABS10K)
        advanceUntilIdle()
        val s = loader.state.value
        assertTrue("目標の10,000件に届く (実際 ${s.total})", s.total >= 10_000)
        assertTrue("最初の1回で止まらない", s.pastPages >= 8)
        // 重複は通常取得（動画全体の抜粋）と重なった分だけ。それ以上に同じものを取り直さない
        assertTrue("重複は通常取得の件数以内 (${s.duplicatesRemoved})", s.duplicatesRemoved <= s.normalCount)
        assertTrue(s.status.contains("目標に到達"))
    }

    @Test fun smallThreadEndsWithoutStoppingTheOthers() = runTest {
        val server = FakeServer()
        val source = CommentSourceInfo("s", "k", "{}", "so1", listOf(owner, main, community))
        val loader = CommentLoader(this, server, source)
        loader.loadNormal()
        advanceUntilIdle()
        loader.startBoost(BoostLevel.ABS30K)
        advanceUntilIdle()
        // チャンネル側のスレッド(4,000件)を取り切ったあとも、動画側のスレッドを続けて取得する
        assertTrue(loader.comments.value.count { it.threadId == "101" } >= 3_990)
        assertTrue(loader.state.value.total >= 30_000)
    }

    @Test fun smallPagesStillReachTheTarget() = runTest {
        // 実機で 1回 250件しか返らない動画があった。予算を1,000件前提にすると途中で止まる
        val server = FakeServer(pageSize = 250)
        val source = CommentSourceInfo("s", "k", "{}", "so1", listOf(owner, main, community))
        val loader = CommentLoader(this, server, source)
        loader.loadNormal()
        advanceUntilIdle()
        loader.startBoost(BoostLevel.ABS30K)
        advanceUntilIdle()
        assertTrue("30,000件に届く (実際 ${loader.state.value.total})", loader.state.value.total >= 30_000)
        assertTrue(loader.state.value.status.contains("目標に到達"))
    }
}
