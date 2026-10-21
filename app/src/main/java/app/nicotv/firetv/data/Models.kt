package app.nicotv.firetv.data

import kotlinx.serialization.Serializable

/** 取得元。デモとニコニコのIDを同じ文字列だけで混ぜない (06 §3 ContentKey)。 */
enum class Provider { NICO, DEMO }

/** 取得結果。空集合・部分取得・認証必要・権限不足・未対応・通信失敗を区別する (06 §3)。 */
sealed interface LoadResult<out T> {
    data class Ok<T>(val value: T, val partial: Boolean = false) : LoadResult<T>
    data class Failed(val kind: FailKind, val message: String) : LoadResult<Nothing>
}

enum class FailKind(val label: String) {
    NETWORK("読み込めませんでした"),
    LOGIN_REQUIRED("ログインが必要です"),
    NO_ENTITLEMENT("このアカウントでは視聴できません"),
    RATE_LIMITED("取得が制限されています。しばらく待ってください"),
    NOT_FOUND("削除または非公開です"),
    UNSUPPORTED("この機能は未対応です"),
    UNKNOWN_FORMAT("想定外の応答形式です"),
}

inline fun <T, R> LoadResult<T>.map(f: (T) -> R): LoadResult<R> = when (this) {
    is LoadResult.Ok -> LoadResult.Ok(f(value), partial)
    is LoadResult.Failed -> this
}

fun <T> LoadResult<T>.getOrNull(): T? = (this as? LoadResult.Ok)?.value

@Serializable
data class VideoSummary(
    val provider: Provider,
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    /** 秒。不明なら null (0 と区別する)。 */
    val durationSec: Int? = null,
    val viewCount: Long? = null,
    val commentCount: Long? = null,
    val ownerName: String? = null,
    /** 投稿者（ユーザー・チャンネル）のアイコン */
    val ownerIconUrl: String? = null,
    /** 投稿者の ID（ユーザーは数字、チャンネルは ch から始まる） */
    val ownerId: String? = null,
    val registeredAt: String? = null,
    /** 公式ランキングの順位。提供されない場合は null。 */
    val rank: Int? = null,
    val isPaid: Boolean = false,
    /** 最高画質（例: 1080p）。分かっている場合だけ */
    val maxQuality: String? = null,
)

/** 視聴権限の状態 (05 §2)。未確認を視聴可能として扱わない。 */
enum class Entitlement(val label: String, val playable: Boolean) {
    UNKNOWN("未確認", false),
    AVAILABLE("視聴可能", true),
    LOGIN_REQUIRED("ログインが必要", false),
    PURCHASE_REQUIRED("購入・加入が必要", false),
    EXPIRED("視聴期限切れ", false),
    REGION("地域等の制限", false),
    DELETED("公開終了・削除", false),
    UNSUPPORTED_FORMAT("再生方式が未対応", false),
    /** センシティブな作品（年齢制限）。アカウントの表示設定がオフ */
    SENSITIVE("センシティブ（年齢制限）", false),
}

enum class BillingKind(val label: String) {
    FREE("無料"), PREMIUM("プレミアム限定"), CHANNEL_MEMBER("チャンネル会員限定"), PPV("有料（購入・レンタル）"), UNKNOWN("不明")
}

/** 端末に保存した値と、その保存時刻 */
data class Cached<T>(val value: T, val savedAt: Long) {
    fun age(now: Long = System.currentTimeMillis()) = now - savedAt
}

@Serializable
data class SeriesRef(val id: String, val title: String)

@Serializable
data class VideoDetail(
    val summary: VideoSummary,
    val description: String,
    val tags: List<String>,
    val entitlement: Entitlement,
    val billing: BillingKind,
    val entitlementReason: String?,
    val series: SeriesRef?,
    val channelName: String?,
)

@Serializable
data class SeriesEpisode(val video: VideoSummary, val order: Int)

/** シリーズの順序付きスナップショット。取得範囲を保持し、未取得を最終話扱いしない (04 §1)。 */
@Serializable
data class SeriesSnapshot(
    val provider: Provider,
    val id: String,
    val title: String,
    val episodes: List<SeriesEpisode>,
    val totalCount: Int?,
    val hasMore: Boolean,
    val fetchedAt: Long,
    val firstPage: Int = 1,
    val nextPage: Int? = if (hasMore) episodes.size / 100 + 1 else null,
    val fullCheckedAt: Long = fetchedAt,
)

data class RankingGenre(val id: String, val label: String)
data class RankingTerm(val id: String, val label: String)

data class RankingSnapshot(
    val genre: RankingGenre,
    val term: RankingTerm,
    val items: List<VideoSummary>,
    val fetchedAt: Long,
    val hasNext: Boolean,
)

/** 再生に必要な期限付き情報。ログ・永続化・共有に入れない (06 §3 PlaybackDescriptor)。 */
data class PlaybackDescriptor(
    val uri: String,
    val isHls: Boolean,
    val qualityLabel: String?,
    val commentSource: CommentSourceInfo?,
    /** シーク用の縮小画像（ストーリーボード）を取るための情報。ログインしていて、動画が対応している場合だけ */
    val storyboard: StoryboardSource? = null,
)

/** ストーリーボードの取得に使う期限付きの情報。ログ・永続化に入れない */
class StoryboardSource(val videoId: String, val accessRightKey: String, val trackId: String)

/**
 * シーク用の縮小画像。1枚の JPEG（シート）に、[intervalMs] ごとの縮小画像が [columns]×[rows] マスで並ぶ。
 * 公式プレイヤーと同じ計算で、時刻からシートとマスの位置を求める。
 */
class Storyboard(
    val columns: Int,
    val rows: Int,
    val intervalMs: Long,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val sheets: List<ByteArray>,
) {
    data class Cell(val sheet: Int, val x: Int, val y: Int)

    fun cellAt(timeMs: Long): Cell? {
        if (columns <= 0 || rows <= 0 || intervalMs <= 0 || sheets.isEmpty()) return null
        val frame = (timeMs.coerceAtLeast(0) / intervalMs).toInt()
        val perSheet = columns * rows
        // 最後のマスより後ろ（動画の最後など）は、最後のマスを使う
        val f = frame.coerceAtMost(sheets.size * perSheet - 1)
        return Cell(f / perSheet, (f % columns) * thumbWidth, ((f / columns) % rows) * thumbHeight)
    }
}

/** コメント取得に必要な情報。動画・アカウント世代にひも付ける。 */
data class CommentSourceInfo(
    val server: String,
    val threadKey: String,
    val paramsJson: String,
    val videoId: String,
    /** 取得対象のスレッド (id, fork)。過去ログはスレッドごとに別々にさかのぼる */
    val targets: List<ThreadTarget> = emptyList(),
)

data class ThreadTarget(val id: String, val fork: String)

enum class CommentPosition { NAKA, UE, SHITA }
enum class CommentSize { SMALL, MEDIUM, BIG }

/**
 * コメント1件。投稿日時と動画内時刻を別に持つ (03 §1)。
 * 重複排除キーは provider+videoId+threadId+fork+no (03 §5)。
 */
data class CommentRecord(
    val videoId: String,
    val threadId: String,
    val fork: String,
    val no: Long,
    val commentId: String?,
    /** 動画内時刻 (ms) */
    val vposMs: Long,
    /** 投稿日時 (epoch ms)。不明なら null */
    val postedAt: Long?,
    val body: String,
    val userId: String?,
    val position: CommentPosition,
    val size: CommentSize,
    val color: Long,
    val isOwner: Boolean,
    /** 過去ログ取得で追加されたものか */
    val fromPastLog: Boolean,
    /** ニコる（評価）の数。不明なら 0 */
    val nicoru: Int = 0,
    /** サーバーが付けるスコア。大きく負のものは NG 共有で隠される対象 */
    val score: Int = 0,
    /** 「full」: 上・下固定コメントが 16:9 の全幅を使える（無ければ 4:3 相当の幅に収める） */
    val full: Boolean = false,
    /** 「ender」: 行数が多くても文字を縮めない */
    val ender: Boolean = false,
) {
    val dedupeKey: String get() = "$videoId|$threadId|$fork|$no"
}

data class CommentBatch(
    val comments: List<CommentRecord>,
    /** 取得できたスレッド側の総数 (表示用) */
    val serverTotal: Long?,
)

/** 投稿者（ユーザーまたはチャンネル） */
data class OwnerRef(val provider: Provider, val id: String, val name: String?, val iconUrl: String?) {
    val isChannel: Boolean get() = id.startsWith("ch")
}

data class OwnerProfile(val ref: OwnerRef, val description: String?, val followerCount: Long?, val videoCount: Int?)

data class VideoPage(val items: List<VideoSummary>, val totalCount: Int?, val hasNext: Boolean)

data class SeriesSummary(val provider: Provider, val id: String, val title: String, val thumbnailUrl: String?, val itemsCount: Int?)

data class MylistSummary(val provider: Provider, val id: String, val name: String, val itemsCount: Int?, val thumbnailUrl: String?)

data class MylistPage(val id: String, val name: String, val items: List<VideoSummary>, val totalCount: Int?, val hasNext: Boolean)

enum class UploadSort(val label: String) { NEWEST("新しい順"), OLDEST("古い順"), POPULAR("再生数が多い順"), COMMENTS("コメントが多い順") }
