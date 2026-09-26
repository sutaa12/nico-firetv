package app.nicotv.firetv.data.local

import android.content.Context
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.VideoSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class CommentMode(val label: String) { OFF("オフ"), NORMAL("通常"), BOOST("増量") }

/** 取得量 (03 §3)。倍率は通常取得件数 B に対する目標。絶対件数はアプリ側の安全上限の範囲内。 */
enum class BoostLevel(val label: String, val multiplier: Int?, val absolute: Int?) {
    X3("増量（通常の約3倍）", 3, null),
    X5("多め（通常の約5倍）", 5, null),
    X10("大量（通常の約10倍）", 10, null),
    ABS3K("詳細：3,000件", null, 3_000),
    ABS10K("詳細：10,000件", null, 10_000),
    ABS30K("詳細：30,000件（上限）", null, 30_000),
}

/**
 * 同時描画数 (取得量とは別)。目安 fps は Fire TV Cube 第3世代 (AFTGAZL) で、弾幕優先・負荷試験用データ
 * (30,000件、最大約3,100件が同時に画面内) を使って 2026-09-25 に計測した値。実際の動画のコメント密度で変わる。
 * 利用者の判断基準は「10fps 以上なら可」（テレビ側のフレーム補間があるため）。
 */
enum class Density(val label: String, val maxOnScreen: Int, val fpsHint: String) {
    LOW("30件", 30, "60fps"),
    STANDARD("60件", 60, "60fps"),
    HIGH("120件", 120, "60fps"),
    MASS("250件", 250, "60fps"),
    P500("500件", 500, "約60fps"),
    P1000("1,000件", 1_000, "約55fps"),
    P2000("2,000件", 2_000, "約48fps"),
    P3000("3,000件", 3_000, "約44fps"),
    P5000("5,000件", 5_000, "約42fps"),
    UNLIMITED("上限なし", Int.MAX_VALUE, "約40fps〜（約3,100件同時で計測）"),
    ;

    val fullLabel: String get() = "$label（目安 $fpsHint）"
}

/** 高評価コメントの優先表示。ニコるがこの数以上なら、混雑していても必ず表示する */
enum class NicoruPriority(val label: String, val threshold: Int?) {
    OFF("オフ", null), N3("ニコる3以上", 3), N10("ニコる10以上", 10)
}

enum class CollisionPolicy(val label: String) { READABLE("読みやすさ優先"), DANMAKU("弾幕優先") }

@Serializable
data class Settings(
    val dataSource: Provider = Provider.NICO,
    val commentMode: CommentMode = CommentMode.NORMAL,
    val boostLevel: BoostLevel = BoostLevel.X5,
    val boostConsented: Boolean = false,
    val density: Density = Density.HIGH,
    val collision: CollisionPolicy = CollisionPolicy.READABLE,
    val nicoruPriority: NicoruPriority = NicoruPriority.N3,
    /** スコアが大きく負のコメント (NG 共有「中」相当: -4800 以下) を隠す */
    val hideLowScore: Boolean = true,
    /** 設定の版。保存ファイルに無ければ 1。初期値を変えたときに既存の設定を一度だけ移行する */
    val version: Int = 1,
    val commentScale: Float = 1.0f,
    val commentOpacity: Float = 0.9f,
    val showFixedComments: Boolean = true,
    /** 古いコメント（投稿日時が古い順位のもの）ほど薄く描く。最低 15% */
    val fadeOldComments: Boolean = false,
    /** 字幕用に下端を空ける割合 */
    val bottomMarginRatio: Float = 0f,
    val autoNext: Boolean = true,
    val nextCountdownSec: Int = 5,
    /** 続きから再開するときの巻き戻し秒 (既定 0) */
    val resumeRewindSec: Int = 0,
    /** シーク中に、選んでいる位置の縮小画像を出す（ログイン時のみ。公式の会員向け機能） */
    val seekThumbnails: Boolean = true,
    /** 回線の速さに合わせて画質を下げず、提供されている最高画質（プレミアム会員は1080pなど）で再生する */
    val highestQuality: Boolean = true,
    /** 再生速度。選んだ速さを次の動画・次回にも使う */
    val playbackSpeed: Float = 1.0f,
    val reduceMotion: Boolean = false,
    /** 表示中のサムネイルの画質を動画情報から調べる（通信が増え、再生数に数えられる可能性がある） */
    val probeQuality: Boolean = false,
    val saveSearchHistory: Boolean = true,
    val saveWatchHistory: Boolean = true,
    /** 取得したコメントを動画ごとに圧縮して保存し、次回はそこから展開する */
    val cacheComments: Boolean = true,
)

@Serializable
data class WatchProgress(
    val provider: Provider,
    val videoId: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val durationSec: Int? = null,
    val positionMs: Long = 0,
    val updatedAt: Long = 0,
    /** 自然終了したか (終端へのシークは含めない) */
    val naturallyEnded: Boolean = false,
    /** 実際に再生した区間 [start,end] ms の配列。シークで跳んだ区間は含めない (HIS-03) */
    val watchedRanges: List<List<Long>> = emptyList(),
    /** 手動の視聴済み設定。null は未設定 */
    val manualWatched: Boolean? = null,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val ownerName: String? = null,
    val ownerIconUrl: String? = null,
    val ownerId: String? = null,
) {
    val watchedMs: Long get() = watchedRanges.sumOf { (it[1] - it[0]).coerceAtLeast(0) }

    /** 自然終了かつ実視聴区間が本編長の90%以上 (04 §7 の初期設計値) */
    val isWatched: Boolean
        get() = manualWatched ?: (naturallyEnded && durationSec != null && durationSec > 0 && watchedMs >= durationSec * 1000L * 0.9)

    /** 最後の10秒以内で止めた（エンディングの最後まで見たのとほぼ同じ） */
    val nearEnd: Boolean get() = durationSec != null && durationSec > 0 && positionMs > 0 &&
        positionMs >= maxOf(durationSec * 1000L - 10_000, durationSec * 900L)

    /** 見終わった（自然終了、または最後の10秒以内で止めた）。続きは次の話から */
    val isFinished: Boolean get() = naturallyEnded || nearEnd

    val isInProgress: Boolean get() = !isFinished && positionMs > 5_000

    fun toSummary() = VideoSummary(provider, videoId, title, thumbnailUrl, durationSec, ownerName = ownerName, ownerIconUrl = ownerIconUrl, ownerId = ownerId)
}

@Serializable
data class SeriesProgress(
    val provider: Provider,
    val seriesId: String,
    val title: String,
    val lastVideoId: String? = null,
    val updatedAt: Long = 0,
    val pinned: Boolean = false,
    val thumbnailUrl: String? = null,
)

@Serializable
data class NgRule(val kind: NgKind, val value: String)

@Serializable
enum class NgKind(val label: String) { WORD("ワード"), USER("ユーザー"), COMMAND("コマンド") }

@Serializable
data class LocalFavorite(val provider: Provider, val videoId: String, val title: String, val thumbnailUrl: String? = null, val addedAt: Long = 0)

@Serializable
data class AccountData(
    val progress: Map<String, WatchProgress> = emptyMap(),
    val series: Map<String, SeriesProgress> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
    val ng: List<NgRule> = emptyList(),
    val favorites: List<LocalFavorite> = emptyList(),
    val lastRankingGenre: String? = null,
    val lastRankingTerm: String? = null,
    /** 最後の再生セッション (ホームの「続き」の起点) */
    val lastSessionVideoKey: String? = null,
    /** ループ再生をオンにした動画 (provider:id) */
    val loopVideos: Set<String> = emptySet(),
    /** リスト全体をループするシリーズ・一覧（"series:ID" または "list:一覧名"） */
    val loopLists: Set<String> = emptySet(),
)

@Serializable
data class StoreData(
    val settings: Settings = Settings(),
    val accounts: Map<String, AccountData> = emptyMap(),
    /** 動画ごとの最高画質 (provider:id → 1080p など)。アカウントに関係しない公開情報 */
    val qualityCache: Map<String, String> = emptyMap(),
)

fun key(provider: Provider, id: String) = "${provider.name}:$id"

/**
 * 端末内の履歴・設定。アカウント区画ごとに分離し、別ユーザーの内容を表示しない (HIS-10)。
 * 公式の履歴やマイリストとは別物として扱う。
 */
class LocalStore(context: Context) {
    private val file = File(context.filesDir, "nicotv-store.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _data = MutableStateFlow(load())
    val data: StateFlow<StoreData> = _data.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()
    private val pendingWrites = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init { io.launch { for (ignored in pendingWrites) flush() } }

    /** 現在のアカウント区画 ("guest" / "nico:<userId>" / "demo") */
    private val _scope = MutableStateFlow("guest")
    val scope: StateFlow<String> = _scope.asStateFlow()

    fun setScope(scope: String) { _scope.value = scope }

    private fun load(): StoreData = try {
        if (file.exists()) migrate(json.decodeFromString(StoreData.serializer(), file.readText())) else StoreData()
    } catch (_: Exception) {
        // 壊れたファイルは消さずに退避する
        file.renameTo(File(file.parentFile, "nicotv-store.broken-${System.currentTimeMillis()}.json"))
        StoreData()
    }

    /**
     * 版1 → 版2: コメントを多く出す方向へ初期値を変えたため、控えめな既定値のままの人だけ引き上げる。
     * 利用者が自分で選んだ値（控えめ・大量など）はそのまま残す。
     */
    private fun migrate(d: StoreData): StoreData {
        var s = d.settings
        if (s.version >= 2) return d
        if (s.density == Density.STANDARD) s = s.copy(density = Density.HIGH)
        if (s.boostLevel == BoostLevel.X3) s = s.copy(boostLevel = BoostLevel.X5)
        return d.copy(settings = s.copy(version = 2))
    }

    private fun persist() { pendingWrites.trySend(Unit) }

    /** 保留は一件にまとめ、常に最新状態を保存する。失敗時も前回の正常ファイルを残す。 */
    suspend fun flush(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                val text = json.encodeToString(StoreData.serializer(), _data.value)
                val tmp = File(file.parentFile, file.name + ".tmp")
                java.io.FileOutputStream(tmp).use { out -> out.write(text.toByteArray()); out.fd.sync() }
                check(tmp.renameTo(file)) { "保存ファイルを置き換えられません" }
                _saveError.value = null
                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _saveError.value = "履歴・設定を保存できませんでした。空き容量を確認し、保存を再試行してください。"
                false
            }
        }
    }

    fun retrySave() = persist()
    fun close() { pendingWrites.close(); io.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    /** 表示名で保存していた区画は推測で割り当てず、本人の選択でコピーする。元データも残す。 */
    fun legacyScopes(): List<String> = _data.value.accounts.keys.filter { it.startsWith("nico:") }.sorted()

    fun importLegacy(oldScope: String) {
        if (!_scope.value.startsWith("nico-id:")) return
        val old = _data.value.accounts[oldScope] ?: return
        if (!oldScope.startsWith("nico:")) return
        updateAccount { current -> current.copy(
            progress = old.progress + current.progress, series = old.series + current.series,
            favorites = (current.favorites + old.favorites).distinctBy { key(it.provider, it.videoId) },
            ng = (current.ng + old.ng).distinct(),
            searchHistory = (current.searchHistory + old.searchHistory).distinct().take(30),
            loopVideos = current.loopVideos + old.loopVideos, loopLists = current.loopLists + old.loopLists,
            lastSessionVideoKey = current.lastSessionVideoKey ?: old.lastSessionVideoKey,
        ) }
    }

    val settings: Settings get() = _data.value.settings
    fun account(d: StoreData = _data.value): AccountData = d.accounts[_scope.value] ?: AccountData()

    fun updateSettings(f: (Settings) -> Settings) {
        _data.update { it.copy(settings = f(it.settings)) }
        persist()
    }

    fun updateAccount(f: (AccountData) -> AccountData) {
        val s = _scope.value
        _data.update { d -> d.copy(accounts = d.accounts + (s to f(d.accounts[s] ?: AccountData()))) }
        persist()
    }

    // ---- 視聴位置 ----

    fun progressOf(provider: Provider, id: String): WatchProgress? = account().progress[key(provider, id)]

    fun saveProgress(p: WatchProgress, markSession: Boolean = true) {
        if (!settings.saveWatchHistory) return
        updateAccount { a ->
            var series = a.series
            if (p.seriesId != null) {
                val k = key(p.provider, p.seriesId)
                val old = series[k] ?: SeriesProgress(p.provider, p.seriesId, p.seriesTitle ?: "シリーズ")
                series = series + (k to old.copy(lastVideoId = p.videoId, updatedAt = p.updatedAt, thumbnailUrl = old.thumbnailUrl ?: p.thumbnailUrl))
            }
            a.copy(
                progress = a.progress + (key(p.provider, p.videoId) to p),
                series = series,
                lastSessionVideoKey = if (markSession) key(p.provider, p.videoId) else a.lastSessionVideoKey,
            )
        }
    }

    fun setManualWatched(provider: Provider, id: String, title: String, watched: Boolean?) = updateAccount { a ->
        val k = key(provider, id)
        val old = a.progress[k] ?: WatchProgress(provider, id, title)
        a.copy(progress = a.progress + (k to old.copy(manualWatched = watched)))
    }

    fun removeProgress(provider: Provider, id: String) = updateAccount { a ->
        val k = key(provider, id)
        a.copy(progress = a.progress - k, lastSessionVideoKey = a.lastSessionVideoKey.takeUnless { it == k })
    }

    fun clearHistory() = updateAccount { it.copy(progress = emptyMap(), lastSessionVideoKey = null, series = it.series.filterValues { s -> s.pinned }.mapValues { (_, s) -> s.copy(lastVideoId = null) }) }

    // ---- シリーズ固定 ----

    fun setSeriesPinned(provider: Provider, id: String, title: String, thumb: String?, pinned: Boolean) = updateAccount { a ->
        val k = key(provider, id)
        val old = a.series[k] ?: SeriesProgress(provider, id, title, thumbnailUrl = thumb)
        a.copy(series = a.series + (k to old.copy(pinned = pinned, title = title, thumbnailUrl = old.thumbnailUrl ?: thumb)))
    }

    // ---- 端末内お気に入り ----

    fun toggleFavorite(v: VideoSummary) = updateAccount { a ->
        if (a.favorites.any { it.provider == v.provider && it.videoId == v.id }) a.copy(favorites = a.favorites.filterNot { it.provider == v.provider && it.videoId == v.id })
        else a.copy(favorites = listOf(LocalFavorite(v.provider, v.id, v.title, v.thumbnailUrl, System.currentTimeMillis())) + a.favorites)
    }

    // ---- 検索履歴 ----

    fun addSearch(q: String) {
        if (!settings.saveSearchHistory || q.isBlank()) return
        updateAccount { a -> a.copy(searchHistory = (listOf(q) + a.searchHistory.filterNot { it == q }).take(30)) }
    }

    fun removeSearch(q: String) = updateAccount { a -> a.copy(searchHistory = a.searchHistory.filterNot { it == q }) }
    fun clearSearch() = updateAccount { it.copy(searchHistory = emptyList()) }

    // ---- NG ----

    fun addNg(rule: NgRule) = updateAccount { a -> if (rule in a.ng) a else a.copy(ng = a.ng + rule) }
    fun removeNg(rule: NgRule) = updateAccount { a -> a.copy(ng = a.ng - rule) }

    fun setListLoop(listKey: String, on: Boolean) = updateAccount { a ->
        a.copy(loopLists = if (on) a.loopLists + listKey else a.loopLists - listKey)
    }

    fun setLoop(provider: Provider, id: String, on: Boolean) = updateAccount { a ->
        val k = key(provider, id)
        a.copy(loopVideos = if (on) a.loopVideos + k else a.loopVideos - k)
    }

    /** 以前この保存ファイルに入れていた画質を取り出して消す（今は専用のファイルに保存する） */
    fun takeLegacyQualities(): Map<String, String> {
        val old = _data.value.qualityCache
        if (old.isEmpty()) return old
        _data.update { it.copy(qualityCache = emptyMap()) }
        persist()
        return old
    }

    fun setRankingPref(genre: String, term: String) = updateAccount { it.copy(lastRankingGenre = genre, lastRankingTerm = term) }

    fun clearAll() {
        _data.value = StoreData(settings = Settings(dataSource = settings.dataSource))
        persist()
    }
}

/** 区間を追加して重なりをまとめる。 */
fun mergeRange(ranges: List<List<Long>>, start: Long, end: Long): List<List<Long>> {
    if (end <= start) return ranges
    val all = (ranges + listOf(listOf(start, end))).sortedBy { it[0] }
    val out = ArrayList<List<Long>>()
    for (r in all) {
        val last = out.lastOrNull()
        if (last != null && r[0] <= last[1] + 500) out[out.size - 1] = listOf(last[0], maxOf(last[1], r[1]))
        else out += r
    }
    return out
}
