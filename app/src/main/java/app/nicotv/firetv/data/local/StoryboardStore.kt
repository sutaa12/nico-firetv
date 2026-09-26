package app.nicotv.firetv.data.local

import app.nicotv.firetv.data.Storyboard
import java.io.File

/**
 * シーク用の縮小画像（ストーリーボード）を動画ごとに端末へ保存する。
 * 画像は後から変わらないので、同じ動画をもう一度見るときは通信しない。
 * 合計150MBを超えたら使っていない順に消し、30日たったものは取り直す。
 */
class StoryboardStore(private val dir: File, private val maxBytes: Long = 150L * 1024 * 1024, private val maxAgeMs: Long = 30L * 86_400_000L) {

    private fun dirOf(videoId: String, scope: String) = File(dir, cacheScope(scope) + "-" + videoId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    @Synchronized fun read(videoId: String, now: Long = System.currentTimeMillis(), scope: String = "guest"): Storyboard? {
        val d = dirOf(videoId, scope)
        val info = File(d, "board.txt").takeIf { it.exists() } ?: return null
        return try {
            val v = info.readText().trim().split(' ').map { it.toLong() }
            val (columns, rows, interval, w, h) = v
            val count = v[5].toInt()
            require(count in 1..60 && columns in 1..100 && rows in 1..100 && interval > 0 && w in 1..4096 && h in 1..4096)
            require((0 until count).sumOf { File(d, "$it.jpg").length() } <= 24L * 1024 * 1024)
            val savedAt = v[6]
            if (now - savedAt > maxAgeMs) { d.deleteRecursively(); return null }
            val sheets = (0 until count).map { File(d, "$it.jpg").readBytes() }
            d.setLastModified(now) // 最近使ったものとして残す
            Storyboard(columns.toInt(), rows.toInt(), interval, w.toInt(), h.toInt(), sheets)
        } catch (_: Exception) {
            d.deleteRecursively() // 壊れていたら捨てて取り直す
            null
        }
    }

    @Synchronized fun write(videoId: String, b: Storyboard, now: Long = System.currentTimeMillis(), scope: String = "guest") {
        try {
            require(b.sheets.size <= 60 && b.sheets.sumOf { it.size.toLong() } <= 24L * 1024 * 1024)
            val d = dirOf(videoId, scope)
            d.deleteRecursively()
            d.mkdirs()
            b.sheets.forEachIndexed { i, bytes -> File(d, "$i.jpg").writeBytes(bytes) }
            // 画像を書き終えてから目録を書く（途中で止まったものは読まない）
            File(d, "board.txt").writeText("${b.columns} ${b.rows} ${b.intervalMs} ${b.thumbWidth} ${b.thumbHeight} ${b.sheets.size} $now")
            trim()
        } catch (_: Exception) {
        }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L } ?: 0L

    @Synchronized fun clear() { dir.deleteRecursively() }

    private fun trim() {
        val dirs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: return
        var total = dirs.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
        for (d in dirs) {
            if (total <= maxBytes) break
            total -= d.listFiles()?.sumOf { it.length() } ?: 0L
            d.deleteRecursively()
        }
    }
}
