package app.nicotv.firetv.data

enum class SearchSort(val label: String) { POPULAR("再生数が多い順"), NEWEST("投稿が新しい順"), COMMENTS("コメントが多い順") }
enum class SearchTarget(val label: String) { KEYWORD("キーワード"), TAG("タグ完全一致") }

data class SearchPage(val items: List<VideoSummary>, val totalCount: Long?, val nextOffset: Int?)

/** 画面から取得元固有のJSONを隠す窓口 (06 §2 Catalog Providers)。 */
interface CatalogProvider {
    val provider: Provider
    val rankingGenres: List<RankingGenre>

    /** 公式のジャンル一覧を取る。取れなければ [rankingGenres] */
    suspend fun loadRankingGenres(): List<RankingGenre> = rankingGenres
    val rankingTerms: List<RankingTerm>

    /** [force] は保存済みを使わず取り直す（［更新］ボタン） */
    suspend fun ranking(genre: RankingGenre, term: RankingTerm, page: Int, force: Boolean = false): LoadResult<RankingSnapshot>
    suspend fun search(query: String, target: SearchTarget, sort: SearchSort, offset: Int): LoadResult<SearchPage>
    suspend fun videoDetail(id: String, force: Boolean = false): LoadResult<VideoDetail>
    /** シリーズ。保存済みがあれば小さな確認だけで済ませ、増えた話だけを取る。[force] は確認を省かず必ず取り直す */
    suspend fun series(id: String, force: Boolean = false): LoadResult<SeriesSnapshot>

    /** 大きなシリーズの続き。ページの終端を最終話と取り違えないためカーソルを返す。 */
    suspend fun seriesPage(id: String, page: Int): LoadResult<SeriesSnapshot> =
        if (page == 1) series(id) else LoadResult.Failed(FailKind.NOT_FOUND, "続きのページはありません")

    /** 通信せずに、端末に保存済みのシリーズを返す（画面をすぐ出すため） */
    fun cachedSeries(id: String): Cached<SeriesSnapshot>? = null

    /** 通信せずに、端末に保存済みの動画の詳しい情報を返す */
    fun cachedDetail(id: String): Cached<VideoDetail>? = null

    /** 急がない取得（シリーズの確認など）。再生開始などの目の前の操作が終わるのを待ち、最低の優先度で行う */
    suspend fun <T> lowPriority(block: suspend () -> T): T = block()

    /** 投稿者のプロフィール・投稿動画・シリーズ・マイリスト */
    suspend fun ownerProfile(owner: OwnerRef): LoadResult<OwnerProfile>
    suspend fun ownerVideos(owner: OwnerRef, sort: UploadSort, page: Int): LoadResult<VideoPage>
    suspend fun ownerSeries(owner: OwnerRef): LoadResult<List<SeriesSummary>>
    suspend fun ownerMylists(owner: OwnerRef): LoadResult<List<MylistSummary>>
    suspend fun mylist(id: String, page: Int): LoadResult<MylistPage>

    /** 動画が属するシリーズ。属していない・取得できない場合は null */
    suspend fun seriesOfVideo(videoId: String): SeriesRef?
    suspend fun preparePlayback(id: String): LoadResult<PlaybackDescriptor>

    /** シーク用の縮小画像を取る。対応していない・取れない場合は null */
    suspend fun storyboard(source: StoryboardSource): Storyboard? = null

    /**
     * コメント取得。[whenEpochSec] が null なら通常取得、指定すればその時刻以前の過去ログ。
     */
    suspend fun fetchComments(source: CommentSourceInfo, whenEpochSec: Long?, only: ThreadTarget? = null): LoadResult<CommentBatch>
}
