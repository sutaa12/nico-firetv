package app.nicotv.firetv.data.local

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 更新の少ない情報（動画の詳しい情報・シリーズ・動画が属するシリーズ・一覧の応答など）を
 * キーごとに圧縮して端末に保存する。何日で古いとみなすかは使う側が決める。
 *
 * 設定や履歴のファイル（nicotv-store.json）とは分ける。あちらは保存のたびに全体を書き直すため、
 * 大きなデータを入れると再生中の定期保存が重くなる。
 * [dir] が null ならメモリだけに置く（単体試験用）。
 */
class MetaCache(private val dir: File?, private val maxBytes: Long = 64L * 1024 * 1024) {

    class Entry(val text: String, val savedAt: Long)

    /** メモリには合計約4MB（文字数で200万字）まで置く。ランキング1ページは10万字ほどある */
    private var memoryChars = 0L
    private val memory = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val diskLock = Any()

    private fun remember(key: String, e: Entry) = synchronized(memory) {
        memory.remove(key)?.let { memoryChars -= it.text.length }
        if (e.text.length <= 2_000_000) {
            memory[key] = e
            memoryChars += e.text.length
        }
        val it = memory.entries.iterator()
        while ((memoryChars > 2_000_000 || memory.size > 1024) && it.hasNext()) {
            memoryChars -= it.next().value.text.length
            it.remove()
        }
    }

    private fun fileOf(key: String): File? {
        val d = dir ?: return null
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "$h.gz")
    }

    /** 保存した時刻から [maxAgeMs] 以内なら返す。古いものも使いたいときは Long.MAX_VALUE を渡す */
    fun read(key: String, maxAgeMs: Long = Long.MAX_VALUE, now: Long = System.currentTimeMillis()): Entry? {
        val e = synchronized(memory) { memory[key] } ?: readFile(key)?.also { remember(key, it) } ?: return null
        return e.takeIf { now - it.savedAt <= maxAgeMs }
    }

    fun write(key: String, text: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(diskLock) {
        if (text.length > 4_000_000) return@synchronized false
        val e = Entry(text, now)
        remember(key, e)
        val f = fileOf(key) ?: return@synchronized true
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            f.parentFile?.mkdirs()
            GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { w ->
                w.write(key.replace('\n', ' ')); w.write("\n"); w.write(now.toString()); w.write("\n"); w.write(text)
            }
            check(tmp.renameTo(f))
            trim()
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    fun remove(key: String) {
        synchronized(memory) { memory.remove(key)?.let { memoryChars -= it.text.length } }
        fileOf(key)?.delete()
    }

    fun clearMemory() { synchronized(memory) { memory.clear(); memoryChars = 0 } }

    fun clear() = synchronized(diskLock) {
        clearMemory()
        dir?.listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L

    private fun readFile(key: String): Entry? {
        val f = fileOf(key)?.takeIf { it.exists() } ?: return null
        return try {
            GZIPInputStream(f.inputStream()).bufferedReader().use { r ->
                if (r.readLine() != key.replace('\n', ' ')) return null
                val at = r.readLine()?.toLongOrNull() ?: return null
                val body = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = r.read(buffer)
                    if (count < 0) break
                    require(body.length + count <= 4_000_000)
                    body.append(buffer, 0, count)
                }
                Entry(body.toString(), at)
            }.also { f.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) {
            f.delete()
            null
        }
    }

    /** 上限を超えたら、最後に使ったのが古い順に消す */
    private fun trim() {
        val files = dir?.listFiles { x -> x.name.endsWith(".gz") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (f in files) {
            if (total <= maxBytes && count <= 5000) break
            count--
            total -= f.length()
            f.delete()
        }
    }
}
