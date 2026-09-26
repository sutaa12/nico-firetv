package app.nicotv.firetv

import app.nicotv.firetv.comments.CommentCache
import app.nicotv.firetv.data.CommentPosition
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.CommentSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.random.Random

class CommentCacheTest {
    private val phrases = listOf("ｗｗｗ", "草", "8888888", "きたああああ", "🫱👩🫲お前やりますねぇスギ", "神回", "ここすき", "うおおおおおお")

    private fun sample(n: Int): List<CommentRecord> {
        val rnd = Random(1)
        return (0 until n).map { i ->
            CommentRecord(
                videoId = "so41603987", threadId = if (i % 5 == 0) "1672812965" else "1672812963", fork = if (i == 0) "owner" else "main",
                no = i + 1L, commentId = null, vposMs = rnd.nextLong(0, 1_420_000), postedAt = 1_700_000_000_000L + i * 60_000L,
                body = phrases[rnd.nextInt(phrases.size)] + if (i % 7 == 0) " $i" else "", userId = if (i % 3 == 0) null else "u${rnd.nextInt(3000)}",
                position = CommentPosition.entries[i % 3], size = CommentSize.entries[i % 3], color = if (i % 4 == 0) 0xFFFF0000 else 0xFFFFFFFF,
                isOwner = i == 0, fromPastLog = i % 2 == 0, nicoru = i % 11, score = if (i % 13 == 0) -5000 else 0,
            )
        }
    }

    @Test fun roundTripKeepsEveryField() {
        val input = sample(5_000)
        val out = ByteArrayOutputStream()
        CommentCache.encode(out, input, savedAt = 123L)
        val back = CommentCache.decode(ByteArrayInputStream(out.toByteArray()), "so41603987")
        assertEquals(123L, back.savedAt)
        // 並び順はスレッド・番号順になるので、キーで比べる
        assertEquals(input.associateBy { it.dedupeKey }, back.comments.associateBy { it.dedupeKey })
    }

    @Test fun compressesWell() {
        val input = sample(30_000)
        val out = ByteArrayOutputStream()
        CommentCache.encode(out, input, savedAt = 0L)
        val perComment = out.size().toDouble() / input.size
        // 1件あたり十数バイト程度に収まる (素の JSON だと 1件 200バイト以上)
        assertTrue("1件あたり ${"%.1f".format(perComment)} バイト", perComment < 20)
    }

    @Test fun rejectsOtherVideoAndExpired() {
        val dir = Files.createTempDirectory("cc").toFile()
        val cache = CommentCache(dir, maxAgeMs = 1_000L)
        cache.write("so41603987", sample(100), now = 10_000L)
        assertEquals(100, cache.read("so41603987", now = 10_500L)?.comments?.size)
        assertNull("30日などの期限を過ぎたら使わない", cache.read("so41603987", now = 20_000L))
        assertNull(cache.read("sm1", now = 10_500L))
    }
}
