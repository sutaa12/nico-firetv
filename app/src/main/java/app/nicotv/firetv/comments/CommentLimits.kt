package app.nicotv.firetv.comments

import app.nicotv.firetv.data.CommentRecord

/** 全取得経路とディスク復元で共有する保持上限。描画用の切り詰めだけに頼らない。 */
object CommentLimits {
    const val COUNT = 30_000
    const val BYTES = 16L * 1024 * 1024

    fun bytes(c: CommentRecord): Long = 256L + 2L * (c.body.length + (c.userId?.length ?: 0) + c.threadId.length + c.videoId.length + c.fork.length)

    fun bounded(comments: Iterable<CommentRecord>): List<CommentRecord> {
        val out = ArrayList<CommentRecord>()
        var used = 0L
        for (original in comments) {
            val c = original.copy(body = original.body.take(1024), userId = original.userId?.take(256), threadId = original.threadId.take(256), fork = original.fork.take(32))
            val size = bytes(c)
            if (out.size >= COUNT) break
            if (used + size > BYTES) continue
            out += c
            used += size
        }
        return out
    }
}
