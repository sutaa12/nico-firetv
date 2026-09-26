package app.nicotv.firetv

import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.SeriesEpisode
import app.nicotv.firetv.data.SeriesRefreshPlan
import app.nicotv.firetv.data.SeriesSnapshot
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.MetaCache
import app.nicotv.firetv.data.mergeSeriesTail
import app.nicotv.firetv.data.planSeriesRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MetaCacheTest {
    @Test fun diskCacheSurvivesRestartAndHonorsAge() {
        val dir = Files.createTempDirectory("meta").toFile()
        MetaCache(dir).write("series:1", "{\"a\":1}", now = 1_000)
        val fresh = MetaCache(dir) // 再起動を想定（メモリは空）
        assertEquals("{\"a\":1}", fresh.read("series:1", maxAgeMs = 500, now = 1_400)?.text)
        assertNull(fresh.read("series:1", maxAgeMs = 500, now = 2_000)) // 古すぎる
        assertEquals(1_000L, fresh.read("series:1", now = 99_999)?.savedAt) // 古くても使うとき
        assertNull(fresh.read("series:2"))
        dir.deleteRecursively()
    }

    private fun ep(i: Int) = SeriesEpisode(VideoSummary(Provider.NICO, "sm$i", "第${i}話"), i)
    private fun snap(n: Int, total: Int? = n, hasMore: Boolean = false) =
        SeriesSnapshot(Provider.NICO, "s1", "シリーズ", (1..n).map(::ep), total, hasMore, 0)

    @Test fun seriesRefreshPlan() {
        // 変わっていない
        assertEquals(SeriesRefreshPlan.UpToDate, planSeriesRefresh(snap(12), 12, "sm1"))
        // 後ろに2話増えた → 1ページ目（100件ずつ）から取り直す
        assertEquals(SeriesRefreshPlan.Append(1, 100), planSeriesRefresh(snap(12), 14, "sm1"))
        // 長いシリーズで増えた → 増えた位置のページだけ
        assertEquals(SeriesRefreshPlan.Append(3, 100), planSeriesRefresh(snap(250), 251, "sm1"))
        // 先頭が変わった・減った・前回が途中まで → 全部
        assertEquals(SeriesRefreshPlan.Full, planSeriesRefresh(snap(12), 12, "sm99"))
        assertEquals(SeriesRefreshPlan.Full, planSeriesRefresh(snap(12), 10, "sm1"))
        assertEquals(SeriesRefreshPlan.Full, planSeriesRefresh(snap(100, 300, hasMore = true), 300, "sm1"))
    }

    @Test fun storyboardCellMatchesOfficialLayout() {
        // sm9 と同じ形: 120×90、10×10 マス、2秒間隔、2枚
        val b = app.nicotv.firetv.data.Storyboard(10, 10, 2_000, 120, 90, listOf(ByteArray(1), ByteArray(1)))
        val C = app.nicotv.firetv.data.Storyboard::Cell
        assertEquals(C(0, 0, 0), b.cellAt(1_999))
        assertEquals(C(0, 120, 0), b.cellAt(2_000))
        assertEquals(C(0, 1_080, 810), b.cellAt(199_999))
        assertEquals(C(1, 0, 0), b.cellAt(200_000))
        assertEquals(C(1, 1_080, 810), b.cellAt(399_999))
        assertEquals(C(1, 1_080, 810), b.cellAt(500_000)) // 最後より後ろは最後のマス
    }

    @Test fun mergeKeepsHeadAndReplacesTail() {
        val cached = snap(250)
        val tail = (201..252).map(::ep)
        val merged = mergeSeriesTail(cached, fromPage = 3, pageSize = 100, tail = tail, total = 252, now = 5)
        assertEquals(252, merged.episodes.size)
        assertEquals((1..252).map { "sm$it" }, merged.episodes.map { it.video.id })
        assertFalse(merged.hasMore)
        assertEquals(5L, merged.fetchedAt)
        // 足りない分が取れなかったときは「一部のみ」
        assertTrue(mergeSeriesTail(cached, 3, 100, (201..220).map(::ep), 252, 5).hasMore)
    }
}
