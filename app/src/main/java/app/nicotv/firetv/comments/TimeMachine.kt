package app.nicotv.firetv.comments

/**
 * 「コメントの時点」の選択肢（プレミアム会員の過去ログで、その時点までのコメントだけを見る）。
 * 投稿からの経過（1日後・1週間後など）と、今からさかのぼった時点（1年前など）を、投稿日と今の間にあるものだけ出す。
 * 近すぎる時点（3日以内）は1つにまとめる。古い順。
 */
data class TimePoint(val label: String, val epochSec: Long)

fun timeMachineOptions(registeredMs: Long?, nowMs: Long): List<TimePoint> {
    val day = 86_400_000L
    val list = ArrayList<Pair<String, Long>>()
    if (registeredMs != null) {
        listOf("投稿1日後" to 1L, "投稿1週間後" to 7L, "投稿1か月後" to 30L, "投稿半年後" to 182L, "投稿1年後" to 365L).forEach { (l, d) ->
            val t = registeredMs + d * day
            if (t < nowMs - day) list += l to t
        }
    }
    listOf("1年前" to 365L, "3年前" to 3 * 365L, "5年前" to 5 * 365L, "10年前" to 10 * 365L).forEach { (l, d) ->
        val t = nowMs - d * day
        if (registeredMs == null || t > registeredMs + day) list += l to t
    }
    val out = ArrayList<TimePoint>()
    for ((l, t) in list.sortedBy { it.second }) {
        if (out.isNotEmpty() && t - out.last().epochSec * 1000 < 3 * day) continue
        out += TimePoint(l, t / 1000)
    }
    return out
}
