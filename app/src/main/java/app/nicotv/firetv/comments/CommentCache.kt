package app.nicotv.firetv.comments

import app.nicotv.firetv.data.CommentPosition
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.CommentSize
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.launch

/**
 * 取得したコメント（通常＋過去ログ）を動画ごとに圧縮して端末に保存する。
 *
 * 形式: 本文・ユーザー・スレッド・色を表にまとめて1回だけ書き（「ｗｗｗ」などの重複本文を1つにする）、
 * 数値は可変長で詰め、全体を Deflate の最大圧縮で圧縮する。
 *
 * 保存の方針 (03 §11 を利用者の要望で変更): 既定で保存する。合計の上限を超えたら使っていない順に消し、
 * 30日たったものは使わない（サイト側で削除されたコメントをいつまでも残さないため）。設定から消去・停止できる。
 */
class CommentCache(private val dir: File, private val maxBytes: Long = 300L * 1024 * 1024, private val maxAgeMs: Long = 30L * 86_400_000L) {

    data class Loaded(val comments: List<CommentRecord>, val savedAt: Long, val bytes: Long)

    private fun fileOf(videoId: String, scope: String): File {
        val safe = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "${app.nicotv.firetv.data.local.cacheScope(scope)}-$safe.nctc")
    }

    @Synchronized fun read(videoId: String, now: Long = System.currentTimeMillis(), scope: String = "guest"): Loaded? {
        val f = fileOf(videoId, scope)
        if (!f.exists()) return null
        return try {
            val loaded = f.inputStream().use { decode(it, videoId) }
            if (now - loaded.savedAt > maxAgeMs) { f.delete(); return null }
            f.setLastModified(now) // 最近使ったものとして残す
            loaded.copy(bytes = f.length())
        } catch (_: Exception) {
            f.delete() // 壊れたキャッシュは捨てて取り直す
            null
        }
    }

    @Synchronized fun write(videoId: String, comments: List<CommentRecord>, now: Long = System.currentTimeMillis(), scope: String = "guest", originSavedAt: Long? = null): Long {
        if (comments.isEmpty()) return 0
        dir.mkdirs()
        val f = fileOf(videoId, scope)
        val tmp = File(dir, f.name + ".tmp")
        // 追記で既存コメントの寿命を延長しない。期限を過ぎた分は再取得する。
        val existingTime = runCatching { f.inputStream().use { raw ->
            DataInputStream(InflaterInputStream(raw)).use { input ->
                require(input.readInt() == MAGIC && input.readUnsignedByte() == VERSION)
                input.readLong()
            }
        } }.getOrNull()
        val originalTime = listOfNotNull(existingTime, originSavedAt).minOrNull()
        if (originalTime != null && now - originalTime > maxAgeMs) { f.delete(); return 0 }
        try {
            tmp.outputStream().use { encode(it, CommentLimits.bounded(comments), originalTime ?: now) }
            check(tmp.renameTo(f)) { "コメントの保存を完了できません" }
        } finally { tmp.delete() }
        trim()
        return f.length()
    }

    fun totalBytes(): Long = dir.listFiles { x -> x.name.endsWith(".nctc") }?.sumOf { it.length() } ?: 0L

    fun count(): Int = dir.listFiles { x -> x.name.endsWith(".nctc") }?.size ?: 0

    @Synchronized fun clear() { invalidatePending(); dir.listFiles()?.forEach { it.delete() } }

    @Synchronized fun delete(videoId: String, scope: String = "guest") { invalidatePending(); fileOf(videoId, scope).delete() }

    /** 上限を超えたら、最後に使ったのが古い順に消す */
    private fun trim() {
        val files = dir.listFiles { x -> x.name.endsWith(".nctc") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (f in files) {
            if (total <= maxBytes && count <= 2000) break
            count--
            total -= f.length()
            f.delete()
        }
    }

    private data class Pending(val video: String, val comments: List<CommentRecord>, val account: String, val origin: Long, val epoch: Long)
    private val pendingLock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private var writing = false
    @Volatile private var cacheEpoch = 0L
    private fun invalidatePending() {
        cacheEpoch++
        synchronized(pendingLock) { pending.clear() }
    }

    /** 画面終了・定期保存の連打でも、保存待ちの大きな配列を積み上げない。 */
    fun enqueue(io: kotlinx.coroutines.CoroutineScope, videoId: String, comments: List<CommentRecord>, account: String, originSavedAt: Long) {
        synchronized(pendingLock) {
            val key = account + ":" + videoId
            pending.remove(key)
            pending[key] = Pending(videoId, comments, account, originSavedAt, cacheEpoch)
            while (pending.size > 2) pending.remove(pending.keys.first())
            if (writing) return
            writing = true
        }
        io.launchWriter()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWriter() = launch(kotlinx.coroutines.Dispatchers.IO) {
        while (true) {
            val item = synchronized(pendingLock) {
                if (pending.isEmpty()) { writing = false; null }
                else pending.remove(pending.keys.first())
            } ?: break
            synchronized(this@CommentCache) {
                if (item.epoch == cacheEpoch) runCatching { write(item.video, item.comments, scope = item.account, originSavedAt = item.origin) }
            }
        }
    }

    companion object {
        private const val MAGIC = 0x4E435443 // "NCTC"
        private const val VERSION = 1

        fun encode(out: OutputStream, comments: List<CommentRecord>, savedAt: Long) {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try { DataOutputStream(BufferedOutputStream(DeflaterOutputStream(out, deflater, 64 * 1024), 64 * 1024)).use { o ->
                val bodies = LinkedHashMap<String, Int>()
                val users = LinkedHashMap<String, Int>()
                val threads = LinkedHashMap<String, Int>()
                val colors = LinkedHashMap<Long, Int>()
                // 同じスレッドの番号順に並べると差分が小さくなり、よく縮む
                val sorted = CommentLimits.bounded(comments).sortedWith(compareBy<CommentRecord> { it.threadId }.thenBy { it.fork }.thenBy { it.no })
                for (c in sorted) {
                    bodies.getOrPut(c.body) { bodies.size }
                    c.userId?.let { users.getOrPut(it) { users.size } }
                    threads.getOrPut(c.threadId + "\u0000" + c.fork) { threads.size }
                    colors.getOrPut(c.color) { colors.size }
                }
                o.writeInt(MAGIC); o.writeByte(VERSION); o.writeLong(savedAt)
                o.writeUTF(sorted.first().videoId)
                writeTable(o, threads.keys); writeTable(o, bodies.keys); writeTable(o, users.keys)
                varint(o, colors.size.toLong()); colors.keys.forEach { o.writeInt(it.toInt()) }
                varint(o, sorted.size.toLong())
                var prevThread = -1
                var prevNo = 0L
                for (c in sorted) {
                    val t = threads.getValue(c.threadId + "\u0000" + c.fork)
                    varint(o, t.toLong())
                    if (t != prevThread) { prevNo = 0L; prevThread = t }
                    varint(o, zigzag(c.no - prevNo)); prevNo = c.no
                    varint(o, c.vposMs.coerceAtLeast(0))
                    varint(o, (c.postedAt ?: 0L) / 1000)
                    varint(o, bodies.getValue(c.body).toLong())
                    varint(o, c.userId?.let { users.getValue(it) + 1L } ?: 0L)
                    val flags = c.position.ordinal or (c.size.ordinal shl 2) or ((if (c.isOwner) 1 else 0) shl 4) or ((if (c.fromPastLog) 1 else 0) shl 5) or
                        ((if (c.full) 1 else 0) shl 6) or ((if (c.ender) 1 else 0) shl 7)
                    o.writeByte(flags)
                    varint(o, colors.getValue(c.color).toLong())
                    varint(o, c.nicoru.toLong().coerceAtLeast(0))
                    varint(o, zigzag(c.score.toLong()))
                }
            } } finally { deflater.end() }
        }

        fun decode(input: InputStream, expectVideoId: String? = null): Loaded {
            val inflated = object : java.io.FilterInputStream(InflaterInputStream(input)) {
                var consumed = 0L
                override fun read(): Int = super.read().also { if (it >= 0) check(++consumed <= 32L * 1024 * 1024) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also {
                    if (it > 0) { consumed += it; check(consumed <= 32L * 1024 * 1024) }
                }
            }
            DataInputStream(BufferedInputStream(inflated, 64 * 1024)).use { i ->
                require(i.readInt() == MAGIC) { "not a comment cache" }
                require(i.readUnsignedByte() == VERSION) { "unknown version" }
                val savedAt = i.readLong()
                val videoId = i.readUTF()
                if (expectVideoId != null) require(videoId == expectVideoId) { "other video" } // 他動画の混入防止
                val threads = readTable(i).map { it.split('\u0000', limit = 2) }
                val bodies = readTable(i)
                val users = readTable(i)
                val colors = List(readCount(i, CommentLimits.COUNT)) { i.readInt().toLong() and 0xFFFFFFFFL }
                val n = readCount(i, CommentLimits.COUNT)
                val out = ArrayList<CommentRecord>(n)
                var prevThread = -1
                var prevNo = 0L
                repeat(n) {
                    val t = readVarint(i).toInt()
                    if (t != prevThread) { prevNo = 0L; prevThread = t }
                    val no = prevNo + unzigzag(readVarint(i)); prevNo = no
                    val vpos = readVarint(i)
                    val posted = readVarint(i).takeIf { it > 0 }?.let { it * 1000 }
                    val body = bodies[readVarint(i).toInt()]
                    val u = readVarint(i).toInt()
                    val flags = i.readUnsignedByte()
                    val color = colors[readVarint(i).toInt()]
                    val nicoru = readVarint(i).toInt()
                    val score = unzigzag(readVarint(i)).toInt()
                    out += CommentRecord(
                        videoId = videoId, threadId = threads[t][0], fork = threads[t].getOrElse(1) { "main" }, no = no, commentId = null,
                        vposMs = vpos, postedAt = posted, body = body, userId = if (u == 0) null else users[u - 1],
                        position = CommentPosition.entries[flags and 3], size = CommentSize.entries[(flags shr 2) and 3],
                        color = color, isOwner = (flags shr 4) and 1 == 1, fromPastLog = (flags shr 5) and 1 == 1,
                        nicoru = nicoru, score = score,
                        full = (flags shr 6) and 1 == 1, ender = (flags shr 7) and 1 == 1,
                    )
                }
                return Loaded(CommentLimits.bounded(out), savedAt, 0)
            }
        }

        private fun writeTable(o: DataOutputStream, items: Collection<String>) {
            varint(o, items.size.toLong())
            for (s in items) { val b = s.toByteArray(Charsets.UTF_8); varint(o, b.size.toLong()); o.write(b) }
        }

        private fun readTable(i: DataInputStream): List<String> = List(readCount(i, CommentLimits.COUNT)) {
            val b = ByteArray(readCount(i, 4096)); i.readFully(b); String(b, Charsets.UTF_8)
        }

        private fun readCount(i: DataInputStream, max: Int): Int {
            val n = readVarint(i)
            require(n in 0..max.toLong()) { "invalid cache length" }
            return n.toInt()
        }

        private fun varint(o: DataOutputStream, v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { o.writeByte(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            o.writeByte(x.toInt())
        }

        private fun readVarint(i: DataInputStream): Long {
            var shift = 0
            var r = 0L
            while (true) {
                require(shift < 64) { "invalid varint" }
                val b = i.readUnsignedByte()
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
            }
        }

        private fun zigzag(v: Long) = (v shl 1) xor (v shr 63)
        private fun unzigzag(v: Long) = (v ushr 1) xor -(v and 1)
    }
}
