package app.nicotv.firetv.data

/**
 * 保存済みのシリーズを、小さな確認（1件だけの取得で総数と先頭の話を見る）の結果と比べて、
 * どこまで取り直すかを決める。シリーズは後ろに話が足されるのがふつうなので、増えた分だけを取る。
 */
sealed interface SeriesRefreshPlan {
    /** 変わっていない。保存済みをそのまま使う */
    data object UpToDate : SeriesRefreshPlan

    /** 後ろに話が増えた。[fromPage]（1始まり、[pageSize]件ずつ）から後だけ取り直して差し替える */
    data class Append(val fromPage: Int, val pageSize: Int) : SeriesRefreshPlan

    /** 先頭が変わった・減ったなど。全部取り直す */
    data object Full : SeriesRefreshPlan
}

fun planSeriesRefresh(cached: SeriesSnapshot, checkTotal: Int?, checkFirstId: String?, pageSize: Int = 100, now: Long = cached.fetchedAt): SeriesRefreshPlan {
    if (now - cached.fullCheckedAt >= 24L * 60 * 60 * 1000) return SeriesRefreshPlan.Full
    val have = cached.episodes.size
    if (checkTotal == null || cached.hasMore || have == 0) return SeriesRefreshPlan.Full
    if (checkFirstId != cached.episodes.first().video.id) return SeriesRefreshPlan.Full
    return when {
        checkTotal == have && cached.totalCount == checkTotal -> SeriesRefreshPlan.UpToDate
        checkTotal > have -> SeriesRefreshPlan.Append(fromPage = have / pageSize + 1, pageSize = pageSize)
        else -> SeriesRefreshPlan.Full
    }
}

/** 取り直したページで、保存済みの該当位置から後ろを差し替える */
fun mergeSeriesTail(cached: SeriesSnapshot, fromPage: Int, pageSize: Int, tail: List<SeriesEpisode>, total: Int?, now: Long): SeriesSnapshot {
    val keep = cached.episodes.sortedBy { it.order }.take((fromPage - 1) * pageSize)
    val seen = keep.mapTo(HashSet()) { it.video.id }
    val merged = keep + tail.filter { seen.add(it.video.id) }
    // 足りない分が取れなかったら「一部のみ」とする（最終話扱いしない）
    return cached.copy(episodes = merged.sortedBy { it.order }, totalCount = total, hasMore = total != null && merged.size < total, fetchedAt = now)
}
