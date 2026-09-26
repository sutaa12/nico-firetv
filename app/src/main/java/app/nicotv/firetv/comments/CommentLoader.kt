package app.nicotv.firetv.comments

import app.nicotv.firetv.data.CatalogProvider
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.CommentSourceInfo
import app.nicotv.firetv.data.FailKind
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.ThreadTarget
import app.nicotv.firetv.data.local.BoostLevel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 画面に出す取得状況。取得件数・重複除外・停止理由を分けて持つ (03 §12)。 */
data class CommentLoadState(
    val normalCount: Int = 0,
    val pastAdded: Int = 0,
    val duplicatesRemoved: Int = 0,
    val loadingNormal: Boolean = false,
    val loadingPast: Boolean = false,
    val pastPages: Int = 0,
    val target: Int? = null,
    val status: String = "",
    val normalError: String? = null,
    /** 端末のキャッシュから展開した件数 */
    val fromCache: Int = 0,
    /** 重複を除いた、いま手元にある件数 */
    val total: Int = 0,
)

/**
 * 1 動画ぶんのコメント取得。通常コメントを先に出し、過去ログは直列・予算付きで追加する (03 §4)。
 * 動画を切り替えたら新しいインスタンスを作り、古い取得は cancel で止める。
 */
class CommentLoader(
    private val scope: CoroutineScope,
    private val catalog: CatalogProvider,
    private val source: CommentSourceInfo,
    /** 取得済みコメントの圧縮保存先。null なら保存しない */
    private val cache: CommentCache? = null,
    /** キャッシュの書き込み用（画面を閉じても書き終えるよう、画面とは別の寿命） */
    private val ioScope: CoroutineScope? = null,
    private val accountScope: String = "guest",
) {
    /** キャッシュ以降に新しく手に入ったコメントがあるか */
    @Volatile private var dirty = false
    private var cacheOrigin = System.currentTimeMillis()
    /** 動画内時刻順に並べた重複なしのコメント */
    private val _comments = MutableStateFlow<List<CommentRecord>>(emptyList())
    val comments: StateFlow<List<CommentRecord>> = _comments.asStateFlow()

    private val _state = MutableStateFlow(CommentLoadState())
    val state: StateFlow<CommentLoadState> = _state.asStateFlow()

    private val seen = HashSet<String>()
    private var normalJob: Job? = null
    private var pastJob: Job? = null

    fun loadNormal() {
        if (normalJob != null) return
        _state.update { it.copy(loadingNormal = true, status = "通常コメントを取得中") }
        normalJob = scope.launch {
            // 1) 端末のキャッシュがあれば先に展開してすぐ表示する
            if (cache != null) {
                val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.read(source.videoId, scope = accountScope) }
                if (cached != null && cached.comments.isNotEmpty()) {
                    cacheOrigin = cached.savedAt
                    merge(cached.comments)
                    Log.i(TAG, "cache video=${source.videoId} restored=${cached.comments.size} bytes=${cached.bytes}")
                    _state.update { it.copy(fromCache = cached.comments.size, status = "保存済みのコメント${cached.comments.size}件を展開。新しいコメントを取得中") }
                }
            }
            // 2) 最新のコメントを取得して足す（キャッシュ以降の新しい投稿）
            when (val r = catalog.fetchComments(source, null)) {
                is LoadResult.Ok -> {
                    val added = merge(r.value.comments)
                    if (added.first > 0) dirty = true
                    Log.i(TAG, "normal video=${source.videoId} got=${r.value.comments.size} added=${added.first} threads=${r.value.comments.groupingBy { it.threadId + "/" + it.fork }.eachCount()}")
                    val got = r.value.comments.size
                    _state.update {
                        it.copy(
                            loadingNormal = false, normalCount = got, duplicatesRemoved = it.duplicatesRemoved + added.second,
                            status = if (it.fromCache > 0) "保存済み${it.fromCache}件＋新しいコメント${added.first}件" else "通常コメント ${got}件",
                        )
                    }
                }
                is LoadResult.Failed -> _state.update {
                    it.copy(loadingNormal = false, normalError = r.kind.label, status = "コメントを取得できません：${r.kind.label}")
                }
            }
        }
    }

    /**
     * 過去ログで増量する。目標件数・終端・権限不足・制限・予算・取消のいずれかで止める。
     * 不足分を複製や架空データで補わない。
     *
     * メインのスレッドごとに「そのスレッドで取得済みのいちばん古い投稿日時」を起点にして、順番にさかのぼる。
     * 通常取得と同じ「今」から始めると最初の1回が重複だけになり、また投稿者コメント（投稿時の日時）や
     * 別スレッドの古い日時へ一気に飛ぶと、途中の大量の過去ログを取りこぼすため。
     */
    fun startBoost(level: BoostLevel) {
        atJob?.cancel()
        if (pastJob?.isActive == true) return
        pastJob = scope.launch {
            normalJob?.join()
            val base = _state.value.normalCount
            val target = when {
                level.absolute != null -> level.absolute
                base == 0 -> null
                else -> base * (level.multiplier ?: 3)
            }?.coerceAtMost(SAFETY_CAP)
            if (target == null) {
                _state.update { it.copy(status = "通常コメントが0件のため倍率で計算できません。詳細（件数指定）を選んでください") }
                return@launch
            }
            // キャッシュだけで目標に届いていれば、通信せずに終える
            if (_state.value.total >= target) {
                _state.update { it.copy(target = target, status = "保存済みのコメントで目標に到達（${it.total}件）") }
                saveCache()
                return@launch
            }
            // 取得回数の予算は目標件数と「1回で返ってくる件数」から決める。1回の件数は動画によって違う
            // （実機で 250件の動画を確認）ので、取得しながら見直す。無制限にはしない
            var perPage = 250
            fun budget(done: Int) = (done + (target - _state.value.total + perPage - 1) / perPage + 2).coerceIn(5, MAX_PAGES)
            var maxPages = budget(0)
            _state.update { it.copy(loadingPast = true, target = target, status = "過去のコメントを取得中") }

            // さかのぼる対象はメインのスレッド。投稿者コメントとかんたんコメントは通常取得で足りる
            val mains = source.targets.filter { it.fork == "main" }.ifEmpty { source.targets.filter { it.fork != "owner" } }
            // 起点は「今」。通常取得は最新順ではなく動画全体に散らばった抜粋を返すため（実機で確認: 番号1〜10,546から2,650件）、
            // 通常取得のいちばん古い日時を起点にすると投稿時まで飛んで何も取れない。重なりは重複除外で吸収する
            val nowSec = System.currentTimeMillis() / 1000
            val cursors = HashMap<ThreadTarget, Long>()
            for (t in mains) cursors[t] = nowSec
            val alive = ArrayDeque(mains)
            val emptyStreak = HashMap<ThreadTarget, Int>()
            var pages = 0
            var rateWaits = 0
            var stopReason = if (mains.isEmpty()) "過去ログを取得できるスレッドがありません" else "目標に到達"
            while (isActive && alive.isNotEmpty()) {
                if (_state.value.total >= target) { stopReason = "目標に到達"; break }
                if (pages >= maxPages) { stopReason = "取得回数の上限（${maxPages}回）に到達"; break }
                val t = alive.removeFirst()
                val whenSec = cursors.getValue(t)
                val r = catalog.fetchComments(source, whenSec, t)
                if (r is LoadResult.Failed) {
                    // 取得回数の制限（1分に60回）を超えた場合は、60秒待ってから再開する（2回まで）
                    if (r.kind == FailKind.RATE_LIMITED && rateWaits < 2) {
                        rateWaits++
                        _state.update { it.copy(status = "取得回数の制限に達したため60秒待っています") }
                        delay(RATE_LIMIT_WAIT_MS + (0..1_000).random())
                        alive.addFirst(t)
                        continue
                    }
                    Log.i(TAG, "past thread=${t.id}/${t.fork} when=$whenSec failed=${r.kind} ${r.message}")
                    stopReason = when (r.kind) {
                        FailKind.LOGIN_REQUIRED -> "過去ログの取得にはログインが必要です"
                        FailKind.NO_ENTITLEMENT -> "このアカウントでは過去ログを取得できません"
                        else -> "過去ログを取得できません（${r.kind.label}：${r.message}）"
                    }
                    // 認証・権限の問題は全スレッド共通なので止める。それ以外はこのスレッドだけ外す
                    if (r.kind == FailKind.LOGIN_REQUIRED || r.kind == FailKind.NO_ENTITLEMENT) break
                    continue
                }
                pages++
                val batch = (r as LoadResult.Ok).value.comments.filter { it.threadId == t.id && it.fork == t.fork }
                val (added, dup) = merge(batch)
                if (added > 0) dirty = true
                if (batch.size > perPage) perPage = batch.size
                maxPages = budget(pages)
                // 途中でアプリが終了しても失わないよう、20回ごとに途中経過を保存する
                if (pages % 20 == 0) saveCache()
                val oldest = batch.mapNotNull { it.postedAt }.minOrNull()?.let { it / 1000 }
                Log.i(TAG, "past thread=${t.id}/${t.fork} when=$whenSec got=${batch.size} added=$added dup=$dup oldest=$oldest")
                _state.update { it.copy(pastAdded = it.pastAdded + added, duplicatesRemoved = it.duplicatesRemoved + dup, pastPages = pages, status = "過去のコメントを取得中（${pages}回目）") }
                val moved = oldest != null && oldest < whenSec
                emptyStreak[t] = if (added == 0) (emptyStreak[t] ?: 0) + 1 else 0
                // 空の応答・起点が進まない・新しいコメントが5回続けて無い場合は、このスレッドの終端とみなす
                // （通常取得やキャッシュと重なる区間では、重複だけの応答が何回か続くことがある）
                if (batch.isEmpty() || !moved || (emptyStreak[t] ?: 0) >= 5) {
                    if (alive.isEmpty()) stopReason = "取得できる過去ログの終端"
                } else {
                    cursors[t] = oldest!!
                    alive.addLast(t)
                }
                delay(REQUEST_INTERVAL_MS)
            }
            val s = _state.value
            Log.i(TAG, "boost done normal=${s.normalCount} past=${s.pastAdded} dup=${s.duplicatesRemoved} pages=$pages reason=$stopReason")
            _state.update {
                it.copy(
                    loadingPast = false,
                    status = (if (s.fromCache > 0) "保存済み${s.fromCache}件＋" else "通常${s.normalCount}件＋") + "過去${s.pastAdded}件を取得。$stopReason",
                )
            }
            saveCache()
        }
    }

    private var atJob: Job? = null

    /**
     * 過去の時点のコメントを見るための取得（プレミアム会員の過去ログ）。
     * [whenSec] より前に投稿されたコメントが手元に [wanted] 件あれば通信しない。
     * 足りなければ、その時点からスレッドごとに最大 [pagesPerThread] 回さかのぼって足す（重複は除く）。
     * 取れたコメントは通常のコメントと同じく保存されるので、次に同じ時点を選ぶときは通信しない。
     */
    fun loadAt(whenSec: Long, label: String, wanted: Int = 1_000, pagesPerThread: Int = 4) {
        atJob?.cancel()
        atJob = scope.launch {
            normalJob?.join()
            val limitMs = whenSec * 1000
            val have = _comments.value.count { !it.isOwner && (it.postedAt ?: Long.MAX_VALUE) <= limitMs }
            if (have >= wanted) {
                _state.update { it.copy(status = "${label}のコメント ${have}件（保存済み）") }
                return@launch
            }
            // 増量の取得と同時に走らせない（取得回数の制限に当たりやすくなるため）
            pastJob?.cancel()
            _state.update { it.copy(loadingPast = true, status = "${label}のコメントを取得中") }
            val mains = source.targets.filter { it.fork == "main" }.ifEmpty { source.targets.filter { it.fork != "owner" } }
            var pages = 0
            var added = 0
            for (t in mains) {
                var cursor = whenSec
                var n = 0
                while (n < pagesPerThread && isActive) {
                    val r = catalog.fetchComments(source, cursor, t)
                    if (r !is LoadResult.Ok) {
                        Log.i(TAG, "at thread=${t.id}/${t.fork} when=$cursor failed=${(r as LoadResult.Failed).kind}")
                        break
                    }
                    pages++; n++
                    val batch = r.value.comments.filter { it.threadId == t.id && it.fork == t.fork }
                    val (a2, _) = merge(batch)
                    added += a2
                    if (a2 > 0) dirty = true
                    val oldest = batch.mapNotNull { it.postedAt }.minOrNull()?.let { it / 1000 }
                    Log.i(TAG, "at thread=${t.id}/${t.fork} when=$cursor got=${batch.size} added=$a2")
                    // 空・起点が進まない場合は、このスレッドはここまで
                    if (batch.isEmpty() || oldest == null || oldest >= cursor) break
                    cursor = oldest
                    delay(REQUEST_INTERVAL_MS)
                }
            }
            val now = _comments.value.count { !it.isOwner && (it.postedAt ?: Long.MAX_VALUE) <= limitMs }
            _state.update { it.copy(loadingPast = false, pastAdded = it.pastAdded + added, status = "${label}のコメント ${now}件（${pages}回取得）") }
            saveCache()
        }
    }

    fun stopBoost() {
        pastJob?.cancel()
        atJob?.cancel()
        _state.update { it.copy(loadingPast = false, status = "追加取得を中止。取得済み${it.total}件を表示") }
    }

    /** 過去分を描画対象から外す (通常表示へ戻したとき)。取得済みデータは破棄しない */
    fun normalOnly(list: List<CommentRecord>) = list.filterNot { it.fromPastLog }

    private fun merge(incoming: List<CommentRecord>): Pair<Int, Int> = synchronized(seen) {
        val old = _comments.value.associateBy { it.dedupeKey }
        val merged = LinkedHashMap(old)
        var duplicates = 0
        val incomingKeys = HashSet<String>()
        for (c in incoming) {
            if (c.videoId != source.videoId) continue
            val previous = merged[c.dedupeKey]
            if (previous != null) duplicates++
            merged[c.dedupeKey] = c.copy(fromPastLog = c.fromPastLog && (previous?.fromPastLog ?: true))
            incomingKeys += c.dedupeKey
        }
        // 通常分と投稿者を優先し、日時指定で新たに要求した過去分を古い過去分より残す。
        val prioritized = merged.values.sortedWith(compareByDescending<CommentRecord> { it.isOwner }
            .thenBy { it.fromPastLog }.thenByDescending { it.dedupeKey in incomingKeys })
        val retained = CommentLimits.bounded(prioritized).sortedWith(compareBy<CommentRecord> { it.vposMs }
            .thenBy { it.threadId }.thenBy { it.fork }.thenBy { it.no })
        seen.clear(); retained.mapTo(seen) { it.dedupeKey }
        val added = retained.count { it.dedupeKey !in old }
        if (retained != _comments.value) dirty = true
        _comments.value = retained
        _state.update { it.copy(total = retained.size) }
        added to duplicates
    }

    fun cancel() {
        normalJob?.cancel()
        pastJob?.cancel()
        atJob?.cancel()
        saveCache()
    }

    /** 保存せずに止める（取り直しのとき） */
    fun discard() {
        dirty = false
        normalJob?.cancel()
        pastJob?.cancel()
        atJob?.cancel()
    }

    /** 途中経過を保存する（ホームへ移ったときなど。取得は止めない） */
    fun checkpoint() = saveCache()

    /** 新しく手に入ったコメントがあれば、全件を圧縮して保存する（画面とは別の寿命で書き終える） */
    private fun saveCache() {
        val c = cache ?: return
        val io = ioScope ?: return
        if (!dirty) return
        dirty = false
        val snapshot = _comments.value
        c.enqueue(io, source.videoId, snapshot, accountScope, cacheOrigin)
    }

    companion object {
        const val TAG = "NicoTVComments"

        /** アプリ側の仮上限。サービスの公式制限ではない (03 §3) */
        const val SAFETY_CAP = CommentLimits.COUNT
        /** 1回の増量操作での取得回数の上限。アプリ側の仮上限で、サービスの公式制限ではない */
        const val MAX_PAGES = 150
        /** 取得回数の制限は1分に60回（コメント増量の開発者資料より）。これを超えないよう1秒間隔を保つ */
        const val REQUEST_INTERVAL_MS = 1_000L
        const val RATE_LIMIT_WAIT_MS = 60_000L
    }
}
