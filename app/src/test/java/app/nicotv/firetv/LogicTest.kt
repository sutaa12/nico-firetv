package app.nicotv.firetv

import app.nicotv.firetv.comments.CommentScheduler
import app.nicotv.firetv.comments.SchedulerConfig
import app.nicotv.firetv.data.CommentPosition
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.CommentSize
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.local.WatchProgress
import app.nicotv.firetv.data.local.mergeRange
import app.nicotv.firetv.data.nico.CommentCommands
import app.nicotv.firetv.data.nico.seriesFromWatchPage
import app.nicotv.firetv.ui.screens.OpenTarget
import app.nicotv.firetv.ui.screens.continueItems
import app.nicotv.firetv.ui.screens.detectOpenTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun c(no: Long, vpos: Long, body: String = "c$no", past: Boolean = false, pos: CommentPosition = CommentPosition.NAKA, nicoru: Int = 0) = CommentRecord(
        videoId = "sm1", threadId = "t", fork = "main", no = no, commentId = null, vposMs = vpos, postedAt = null,
        body = body, userId = null, position = pos, size = CommentSize.MEDIUM, color = 0xFFFFFFFF, isOwner = false, fromPastLog = past,
        nicoru = nicoru,
    )

    private fun scheduler() = CommentScheduler { text, px -> text.length * px }

    @Test fun multiLineCommentKeepsLines() {
        val s = scheduler()
        s.setComments(listOf(c(1, 0, body = "( ˃ᯅ˂ )\n　 /⌒ヽ⊂⌒﹨\n ⊂ ノヽ_ω_ 人 ⊃")))
        s.update(100, 1920f, 1080f)
        val a = s.activeComments.single()
        assertEquals(3, a.lines.size)
        assertEquals(" ⊂ ノヽ_ω_ 人 ⊃", a.lines[2])
        assertTrue(a.laneSpan >= 3)
    }

    @Test fun manyLinesShrinkUnlessEnder() {
        val body = (1..5).joinToString("\n") { "行$it" }
        val s = scheduler()
        s.setComments(listOf(c(1, 0, body = body), c(2, 0, body = body).copy(ender = true)))
        s.update(100, 1920f, 1080f)
        val (resized, ender) = s.activeComments.sortedBy { it.record.no }
        val base = s.fontPxOf(CommentSize.MEDIUM)
        assertEquals(base * 13.1f / 25.4f, resized.fontPx, 0.01f)
        assertEquals(base, ender.fontPx, 0.01f)
    }

    @Test fun longFixedCommentFitsScreenWidth() {
        val s = scheduler()
        val long = "あ".repeat(60)
        s.setComments(listOf(c(1, 0, body = long, pos = CommentPosition.UE), c(2, 0, body = long, pos = CommentPosition.SHITA).copy(full = true)))
        s.update(100, 1920f, 1080f)
        val (ue, shitaFull) = s.activeComments.sortedBy { it.record.no }
        assertEquals(1920f * 1530f / 1920f, ue.width, 0.5f)  // full なし: 4:3 相当の幅
        assertEquals(1920f, shitaFull.width, 0.5f)            // full: 全幅
        // 流しコメントは縮めない（流れて全体が見えるため）
        val n = scheduler(); n.setComments(listOf(c(3, 0, body = long))); n.update(100, 1920f, 1080f)
        assertEquals(n.fontPxOf(CommentSize.MEDIUM), n.activeComments.single().fontPx, 0.01f)
    }

    @Test fun ageFadeGoesFromFullToFifteenPercentByRank() {
        val list = (1..11L).map { c(it, 0).copy(postedAt = it * 1000) } + c(99, 0).copy(isOwner = true, postedAt = 1)
        val f = app.nicotv.firetv.comments.AgeFade(list)
        assertEquals(1f, f.alpha(list[10]), 1e-4f)   // いちばん新しい
        assertEquals(0.15f, f.alpha(list[0]), 1e-4f) // いちばん古い
        assertEquals(0.575f, f.alpha(list[5]), 1e-4f) // 真ん中
        assertEquals(1f, f.alpha(list[11]), 1e-4f)   // 投稿者コメントは薄くしない
        assertEquals(1f, f.alpha(c(50, 0)), 1e-4f)   // 投稿日時が無いものもそのまま
    }

    @Test fun commentsAppearAtVposAndExpire() {
        val s = scheduler()
        s.setComments(listOf(c(1, 1_000), c(2, 2_000)))
        s.update(0, 1920f, 1080f)
        assertEquals(0, s.activeComments.size)
        s.update(500, 1920f, 1080f); s.update(1_000, 1920f, 1080f)
        assertEquals(1, s.activeComments.size)
        for (t in 1_100L..5_100L step 100) s.update(t, 1920f, 1080f)
        // 1本目は 1000+4000=5000ms で消え、2本目は残る
        assertEquals(listOf(2L), s.activeComments.map { it.record.no })
    }

    @Test fun pausedPositionDoesNotMoveComments() {
        val s = scheduler()
        s.setComments(listOf(c(1, 1_000)))
        s.update(1_000, 1920f, 1080f); s.update(1_500, 1920f, 1080f)
        val x1 = s.nakaX(s.activeComments.first(), 1_500)
        s.update(1_500, 1920f, 1080f)
        assertEquals(x1, s.nakaX(s.activeComments.first(), 1_500), 0.001f)
    }

    @Test fun seekRebuildsCommentsStillOnScreen() {
        val s = scheduler()
        s.setComments(listOf(c(1, 10_000), c(2, 12_500), c(3, 30_000)))
        s.update(0, 1920f, 1080f)
        s.update(13_000, 1920f, 1080f) // シーク
        assertEquals(setOf(1L, 2L), s.activeComments.map { it.record.no }.toSet())
    }

    @Test fun lateArrivalsJoinMidFlightButExpiredOnesAreNotDumped() {
        val s = scheduler()
        s.setComments(listOf(c(1, 1_000)))
        for (t in 0L..20_000L step 250) s.update(t, 1920f, 1080f)
        // 過去ログが届いた: 18秒のものはまだ表示期間内なので途中の位置から出す。15秒のものは期間を過ぎているので出さない (03 §6)
        s.setComments(listOf(c(1, 1_000), c(4, 15_000, past = true), c(5, 18_000, past = true), c(6, 25_000, past = true)))
        val shown = s.activeComments.map { it.record.no }
        assertTrue("表示期間内の新着はすぐ出る", 5L in shown)
        assertFalse("期間を過ぎたものは出さない", 4L in shown)
        val x5 = s.nakaX(s.activeComments.first { it.record.no == 5L }, 20_000)
        assertTrue("途中の位置（右端ではない）から流れる", x5 < 1920f * 0.8f)
        // 同じ一覧をもう一度受け取っても二重に置かない
        s.setComments(listOf(c(1, 1_000), c(4, 15_000, past = true), c(5, 18_000, past = true), c(6, 25_000, past = true), c(7, 30_000)))
        assertEquals(1, s.activeComments.count { it.record.no == 5L })
        for (t in 20_250L..25_000L step 250) s.update(t, 1920f, 1080f)
        assertTrue(s.activeComments.any { it.record.no == 6L })
    }

    @Test fun densityCapIsRespected() {
        val s = scheduler()
        s.setConfig(SchedulerConfig(maxOnScreen = 30, danmaku = true))
        s.setComments((1..500L).map { c(it, 1_000) })
        s.update(0, 1920f, 1080f); s.update(1_000, 1920f, 1080f)
        assertTrue(s.activeComments.size <= 30)
        assertTrue(s.skippedByDensity > 0)
    }

    @Test fun highlyRatedCommentIsShownEvenWhenScreenIsFull() {
        val s = scheduler()
        s.setConfig(SchedulerConfig(maxOnScreen = 30, danmaku = false, priorityNicoru = 3))
        // 普通のコメントで上限まで埋め、そのあとに高評価コメントと普通のコメントを流す
        s.setComments((1..200L).map { c(it, 1_000, past = true) } + listOf(c(900, 1_000, nicoru = 12), c(901, 1_000)))
        s.update(0, 1920f, 1080f); s.update(1_000, 1920f, 1080f)
        val shown = s.activeComments.map { it.record.no }
        assertTrue("高評価コメントは必ず表示", 900L in shown)
        assertFalse("普通のコメントは混雑で省略", 901L in shown)
        assertEquals(1, s.priorityShown)
    }

    @Test fun priorityCanBeTurnedOff() {
        val s = scheduler()
        s.setConfig(SchedulerConfig(maxOnScreen = 30, danmaku = false, priorityNicoru = null))
        s.setComments((1..200L).map { c(it, 1_000) } + listOf(c(900, 1_000, nicoru = 50)))
        s.update(0, 1920f, 1080f); s.update(1_000, 1920f, 1080f)
        assertFalse(s.activeComments.any { it.record.no == 900L })
    }

    @Test fun fixedCommentsDoNotOverlapInReadableMode() {
        val s = scheduler()
        s.setComments((1..5L).map { c(it, 1_000, pos = CommentPosition.UE) })
        s.update(0, 1920f, 1080f); s.update(1_000, 1920f, 1080f)
        val lanes = s.activeComments.map { it.lane }
        assertEquals(lanes.size, lanes.toSet().size)
    }

    @Test fun dedupeKeyKeepsSameTextDifferentPosts() {
        val a = c(1, 1_000, body = "ｗｗｗ")
        val b = c(2, 1_000, body = "ｗｗｗ")
        assertTrue(a.dedupeKey != b.dedupeKey)
        assertEquals(a.dedupeKey, a.copy(fromPastLog = true).dedupeKey)
    }

    @Test fun mergeRangeJoinsOverlaps() {
        var r = mergeRange(emptyList(), 0, 5_000)
        r = mergeRange(r, 4_800, 10_000)
        r = mergeRange(r, 60_000, 61_000)
        assertEquals(listOf(listOf(0L, 10_000L), listOf(60_000L, 61_000L)), r)
    }

    @Test fun seekToEndIsNotWatched() {
        val p = WatchProgress(Provider.NICO, "sm1", "t", durationSec = 600, positionMs = 600_000, naturallyEnded = true,
            watchedRanges = listOf(listOf(0L, 30_000L), listOf(590_000L, 600_000L)))
        assertFalse(p.isWatched)
        assertTrue(p.copy(watchedRanges = listOf(listOf(0L, 560_000L))).isWatched)
    }

    @Test fun continueRowUsesLatestEpisodePerSeries() {
        val a = WatchProgress(Provider.NICO, "sm1", "1話", positionMs = 60_000, updatedAt = 1, seriesId = "s")
        val b = WatchProgress(Provider.NICO, "sm2", "2話", positionMs = 90_000, updatedAt = 2, seriesId = "s")
        val list = continueItems(mapOf("a" to a, "b" to b), null)
        assertEquals(listOf("sm2"), list.map { it.videoId })
    }

    @Test fun openTargetParsing() {
        assertEquals(OpenTarget.Video("sm9"), detectOpenTarget("sm9"))
        assertEquals(OpenTarget.Video("so123"), detectOpenTarget("https://www.nicovideo.jp/watch/so123?ref=x"))
        assertEquals(OpenTarget.Series("210"), detectOpenTarget("https://www.nicovideo.jp/user/1/series/210"))
        assertNull(detectOpenTarget("https://example.com/watch/sm9"))
    }

    @Test fun seriesFromWatchPageParsesEscapedServerResponse() {
        // 実際の視聴ページと同じエスケープ形式 (&quot; と JSON の \\u エスケープ)
        val html = "<meta name=\"server-response\" content=\"{&quot;meta&quot;:{&quot;status&quot;:200},&quot;data&quot;:{&quot;response&quot;:" +
            "{&quot;series&quot;:{&quot;id&quot;:567981,&quot;title&quot;:&quot;\\u7121\\u8077\\u8ee2\\u751f &amp; test&quot;}}}}\">"
        val ref = seriesFromWatchPage(html)
        assertEquals("567981", ref?.id)
        assertEquals("無職転生 & test", ref?.title)
        assertNull(seriesFromWatchPage("<meta name=\"server-response\" content=\"{&quot;data&quot;:{&quot;response&quot;:{&quot;series&quot;:null}}}\">"))
        assertNull(seriesFromWatchPage("<html></html>"))
    }

    @Test fun commandParsing() {
        assertEquals(CommentPosition.UE, CommentCommands.position(listOf("184", "ue", "red")))
        assertEquals(0xFFFF0000, CommentCommands.color(listOf("red")))
        assertEquals(0xFF123456, CommentCommands.color(listOf("#123456")))
        assertEquals(0xFFFFFFFF, CommentCommands.color(listOf("<script>")))
    }
}
