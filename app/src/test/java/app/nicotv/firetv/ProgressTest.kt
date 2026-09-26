package app.nicotv.firetv

import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.local.WatchProgress
import app.nicotv.firetv.ui.screens.continueItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    private fun p(id: String, pos: Long, dur: Int = 1420, series: String? = "s1", at: Long, ended: Boolean = false) =
        WatchProgress(Provider.NICO, id, id, durationSec = dur, positionMs = pos, updatedAt = at, naturallyEnded = ended, seriesId = series)

    @Test fun stoppingInLastTenSecondsCountsAsFinished() {
        val nearEnd = p("so1", 1_419_000, at = 1) // 23:39 / 23:40
        assertTrue(nearEnd.isFinished)
        assertFalse(nearEnd.isInProgress)
        assertTrue(p("so2", 600_000, at = 1).isInProgress)
    }

    @Test fun continueShowsLatestEpisodeOfEachSeriesEvenIfOnlyOpened() {
        val items = continueItems(
            mapOf(
                "a" to p("ep1", 1_419_000, at = 1),  // 1話は最後まで
                "b" to p("ep2", 0, at = 2),          // 2話は開いただけ → 2話を最初から
                "c" to p("x", 3_000, series = null, at = 3), // 単体で3秒だけ → 出さない
                "d" to p("y", 60_000, series = null, at = 4), // 単体の途中 → 出す
            ),
            lastKey = null,
        )
        assertEquals(listOf("y", "ep2"), items.map { it.videoId })
    }
}
