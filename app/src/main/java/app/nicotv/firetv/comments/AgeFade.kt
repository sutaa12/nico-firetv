package app.nicotv.firetv.comments

import app.nicotv.firetv.data.CommentRecord

/**
 * 「古いコメントを薄く」の濃さ。取得済みコメントの投稿日時の順位で決める。
 * いちばん新しいものは 100%、古くなるほど少しずつ薄くなり、いちばん古いもので [min]（既定 15%）。
 *
 * 日時の差ではなく順位を使うのは、古い動画ではコメントが投稿直後に集中していて、
 * 日時の差で決めるとほとんどが最低の濃さになってしまうため。
 * 投稿者コメントと投稿日時の無いコメントは薄くしない。
 */
class AgeFade(comments: List<CommentRecord>, private val min: Float = 0.15f) {
    private val times: LongArray = LongArray(comments.size).let { a ->
        var n = 0
        for (c in comments) if (!c.isOwner) c.postedAt?.let { a[n++] = it }
        a.copyOf(n).also { it.sort() }
    }

    fun alpha(c: CommentRecord): Float {
        if (c.isOwner) return 1f
        val t = c.postedAt ?: return 1f
        if (times.size < 2) return 1f
        // この時刻より後に投稿されたコメントの割合（0 = いちばん新しい、1 に近い = いちばん古い）
        val newer = (times.size - upperBound(t)).toFloat() / (times.size - 1)
        return (1f - (1f - min) * newer.coerceIn(0f, 1f)).coerceIn(min, 1f)
    }

    private fun upperBound(t: Long): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
