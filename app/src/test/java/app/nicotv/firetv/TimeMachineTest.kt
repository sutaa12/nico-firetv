package app.nicotv.firetv

import app.nicotv.firetv.comments.timeMachineOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeMachineTest {
    private val day = 86_400_000L

    @Test fun optionsLieBetweenUploadAndNow() {
        val now = 20_000 * day
        val up = now - 4 * 365 * day // 4年前に投稿
        val o = timeMachineOptions(up, now)
        // 「投稿1年後」と「3年前」は同じ日なので1つにまとまる
        assertEquals(listOf("投稿1日後", "投稿1週間後", "投稿1か月後", "投稿半年後", "投稿1年後", "1年前"), o.map { it.label })
        assertTrue(o.all { it.epochSec * 1000 in up..now })
        assertTrue(o.zipWithNext().all { (a, b) -> a.epochSec < b.epochSec }) // 古い順
    }

    @Test fun newVideoHasFewOptionsAndCloseOnesMerge() {
        val now = 20_000 * day
        assertEquals(listOf("投稿1日後"), timeMachineOptions(now - 5 * day, now).map { it.label })
        // 「投稿1年後」と「1年前」が3日以内なら1つにまとめる
        val o = timeMachineOptions(now - 2 * 365 * day - day, now).map { it.label }
        assertTrue("投稿1年後" in o && "1年前" !in o)
        // 投稿日が分からなければ、今からさかのぼる時点だけ
        assertEquals(listOf("10年前", "5年前", "3年前", "1年前"), timeMachineOptions(null, now).map { it.label })
    }
}
