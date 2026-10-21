package app.nicotv.firetv

import app.nicotv.firetv.data.nico.between
import app.nicotv.firetv.data.nico.serverResponseOf
import app.nicotv.firetv.data.nico.seriesFromWatchPage
import app.nicotv.firetv.data.nico.splitBetween
import app.nicotv.firetv.data.nico.unescapeHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 視聴ページの埋め込みデータを、正規表現を使わずに切り出す処理 (2026-09-27 メモリ不足の修正) */
class WatchPageParseTest {
    @Test fun unescapesEntities() {
        assertEquals("\"a\" & <b> 'c' / あ", unescapeHtml("&quot;a&quot; &amp; &lt;b&gt; &#39;c&#39; &#x2F; &#12354;"))
        // 知らない参照・閉じていない & はそのまま
        assertEquals("&nbsp; & x &;", unescapeHtml("&nbsp; & x &;"))
        assertEquals("b", unescapeHtml("a&quot;b&quot;c", 7, 8))
    }

    @Test(timeout = 5000) fun ampersandsWithoutEntitiesRemainLinear() {
        val input = "&".repeat(1_000_000)
        assertEquals(input, unescapeHtml(input))
        assertEquals(input + ";", unescapeHtml(input + ";"))
    }

    @Test fun entityOutsideRequestedRangeIsNotRead() {
        assertEquals("&amp", unescapeHtml("&amp;", end = 4))
        assertEquals("&#x110000; &#-1;", unescapeHtml("&#x110000; &#-1;"))
        assertEquals("&quot;", unescapeHtml("&amp;quot;"))
    }

    @Test fun absentOrMalformedServerResponseReturnsNull() {
        assertNull(serverResponseOf("<html></html>"))
        assertNull(serverResponseOf("<meta name=\"server-response\" content=\"{broken\">"))
        assertNull(serverResponseOf("<meta name=\"server-response\" content=\""))
    }

    @Test fun largeServerResponseIsParsed() {
        // 実際の視聴ページと同じく、数 MB の属性値に JSON が HTML エスケープされて入っている
        val filler = "x".repeat(2_000_000)
        val json = "{&quot;data&quot;:{&quot;response&quot;:{&quot;pad&quot;:&quot;$filler&quot;,&quot;series&quot;:{&quot;id&quot;:123,&quot;title&quot;:&quot;S &amp; T&quot;}}}}"
        val html = "<html><meta name=\"other\" content=\"x\"><meta name=\"server-response\" content=\"$json\"></html>"
        assertNotNull(serverResponseOf(html))
        val ref = seriesFromWatchPage(html)!!
        assertEquals("123", ref.id)
        assertEquals("S & T", ref.title)
    }

    @Test fun splitsItems() {
        val xml = "<dc:creator>ch &amp; 名</dc:creator><item>a</item><item>b</item><item>broken"
        assertEquals("ch &amp; 名", between(xml, "<dc:creator>", "</dc:creator>"))
        assertEquals(listOf("a", "b"), splitBetween(xml, "<item>", "</item>").toList())
    }
}
