package app.nicotv.firetv

import app.nicotv.firetv.data.nico.NicoProvider
import app.nicotv.firetv.data.nico.WebViewCookieJar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LowPriorityTest {
    /** 2026-09-26 の起動時クラッシュ（目印のキーが初期化時に null）の再発防止。入れ子でも止まらないこと */
    @Test fun lowPriorityRunsAndNests() = runBlocking {
        val nico = NicoProvider(WebViewCookieJar())
        assertEquals(3, nico.lowPriority { nico.lowPriority { 1 } + 2 })
    }
}
