package app.nicotv.firetv.ui

import app.nicotv.firetv.data.*

/** 一覧の続きは動画を貯め続けず、必要なページへ入れ替えて再生する。 */
sealed interface QueueSource {
    val provider: Provider
    val key: String
    data class Series(override val provider: Provider, val id: String) : QueueSource { override val key = "series:$id" }
    data class Mylist(override val provider: Provider, val id: String) : QueueSource { override val key = "${provider.name}:mylist:$id" }
    data class Uploads(val owner: OwnerRef, val sort: UploadSort) : QueueSource {
        override val provider = owner.provider
        override val key = "${provider.name}:uploads:${owner.id}:${sort.name}"
    }
}

data class QueuePage(val videos: List<VideoSummary>, val page: Int, val nextPage: Int?)

suspend fun CatalogProvider.queuePage(source: QueueSource, page: Int): LoadResult<QueuePage> = when (source) {
    is QueueSource.Series -> seriesPage(source.id, page).map { QueuePage(it.episodes.map { e -> e.video }, page, it.nextPage) }
    is QueueSource.Mylist -> mylist(source.id, page).map { QueuePage(it.items, page, (page + 1).takeIf { _ -> it.hasNext }) }
    is QueueSource.Uploads -> ownerVideos(source.owner, source.sort, page).map { QueuePage(it.items, page, (page + 1).takeIf { _ -> it.hasNext }) }
}
