package app.nicotv.firetv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import app.nicotv.firetv.comments.TimePoint
import app.nicotv.firetv.comments.timeMachineOptions
import app.nicotv.firetv.ui.parsePostedMs
import app.nicotv.firetv.ui.formatDate
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import app.nicotv.firetv.DebugOverrides
import app.nicotv.firetv.comments.CommentLoadState
import app.nicotv.firetv.comments.CommentLoader
import app.nicotv.firetv.comments.SchedulerConfig
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.FailKind
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.PlaybackDescriptor
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.local.CollisionPolicy
import app.nicotv.firetv.data.local.CommentMode
import app.nicotv.firetv.data.local.NgKind
import app.nicotv.firetv.data.local.WatchProgress
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.data.local.mergeRange
import app.nicotv.firetv.ui.QueueSource
import app.nicotv.firetv.ui.queuePage
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.formatTime
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.border
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 再生画面の入力状態 (02 §4)。状態ごとに同じキーの意味を固定する。 */
enum class PlayerMode { VIDEO_ONLY, CONTROLS, SEEK, PANEL, NEXT_PROMPT, ENDED, ERROR, LOADING }

/** NG 共有「中」相当のしきい値 */
const val LOW_SCORE_THRESHOLD = -4800

data class DrawStats(val active: Int = 0, val skipped: Int = 0, val priority: Int = 0)

enum class PanelKind { COMMENT, MORE, EPISODES, QUALITY, SPEED, COMMENT_LIST, SLEEP, TIME_MACHINE }

data class PlayerError(val kind: FailKind?, val message: String, val canSkip: Boolean)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(app: AppState, entry: Entry, route: Route.Player) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = app.c
    val store = c.store
    val accountScope = remember { store.scope.value }
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackGate = remember { PlaybackGate(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var foreground by remember { mutableStateOf(playbackGate.foreground) }

    val storeData by store.data.collectAsState()
    val settings = DebugOverrides.apply(storeData.settings)
    val ng = store.account(storeData).ng
    val loopVideos = store.account(storeData).loopVideos

    // ---- プレイヤー本体 (1画面につき1つ。二重音声を防ぐ) ----
    val exo = remember {
        val ds = OkHttpDataSource.Factory(c.nico.http)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { it.setPlaybackSpeed(c.store.settings.playbackSpeed) }
    }
    fun playRequested() { if (playbackGate.requestPlay()) exo.play() }

    // 最高画質を優先する設定。回線の推定で低い画質から始めず、提供されている最高の画質を選ぶ
    val highest = storeData.settings.highestQuality
    LaunchedEffect(highest) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setForceHighestSupportedBitrate(highest).build()
    }
    val session = remember { runCatching { MediaSession.Builder(ctx, exo).setId("nicotv-${System.nanoTime()}").build() }.getOrNull() }

    var index by remember { mutableIntStateOf(entry.keep("index") { route.index }) }
    // キューとシリーズは再生中にシリーズへつなぐと変わるため状態で持つ (画面を離れても BackStackEntry に残す)
    var queue by remember { mutableStateOf(entry.keep("queue") { route.queue }) }
    var queueSource by remember { mutableStateOf(entry.memory["queueSource"] as? QueueSource ?: route.source) }
    var firstPage by remember { mutableIntStateOf(entry.memory["firstPage"] as? Int ?: route.firstPage) }
    var nextPage by remember { mutableStateOf(if (entry.memory.containsKey("nextPage")) (entry.memory["nextPage"] as? Int)?.takeIf { it > 0 } else route.nextPage) }
    var paging by remember { mutableStateOf(false) }
    var pageRetry by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var seriesId by remember { mutableStateOf(entry.memory["seriesId"] as String? ?: route.seriesId) }
    var seriesTitle by remember { mutableStateOf(entry.memory["seriesTitle"] as String? ?: route.seriesTitle) }
    val current = queue[index.coerceIn(0, queue.lastIndex)]
    val video = current.video
    // 動画ごとのループ再生。オンの動画は終わると最初から繰り返し、次の話へは進まない
    val loopOn = key(video.provider, video.id) in loopVideos
    LaunchedEffect(loopOn) { exo.repeatMode = if (loopOn) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    // リスト全体のループ（シリーズ・マイリストなど2本以上のとき）。最後の話が終わったら最初の話へ戻る
    fun listKeyNow(): String? = queueSource?.key ?: seriesId?.let { "series:$it" } ?: seriesTitle?.let { "list:$it" }
    fun listLoopNow(): Boolean = queue.size > 1 && listKeyNow()?.let { it in store.account().loopLists } == true
    val listLoopOn = queue.size > 1 && listKeyNow()?.let { it in store.account(storeData).loopLists } == true
    /** 次に再生する項目。リストループ中は最後の次が最初 */
    fun nextIndex(): Int? = when {
        index + 1 < queue.size -> index + 1
        nextPage != null || (listLoopNow() && firstPage > 1) -> queue.size
        listLoopNow() -> 0
        else -> null
    }

    var mode by remember { mutableStateOf(PlayerMode.LOADING) }
    var modeBeforePanel by remember { mutableStateOf(PlayerMode.VIDEO_ONLY) }
    var panel by remember { mutableStateOf(PanelKind.MORE) }
    var error by remember { mutableStateOf<PlayerError?>(null) }
    var descriptor by remember { mutableStateOf<PlaybackDescriptor?>(null) }
    /** シーク用の縮小画像（取れた動画だけ） */
    var storyboard by remember { mutableStateOf<app.nicotv.firetv.data.Storyboard?>(null) }
    var storyboardLoading by remember { mutableStateOf(false) }
    /** 「コメントの時点」。null は現在。動画を切り替えたら現在に戻す */
    var timeMachine by remember { mutableStateOf<app.nicotv.firetv.comments.TimePoint?>(null) }
    /** シークを始めたら、待たずにすぐ縮小画像を取りに行くための合図 */
    var storyboardKick by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Unit>?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var generation by remember { mutableIntStateOf(0) }
    // リストループにしているシリーズ・一覧は、自動で次の話へ進む
    var continuous by remember { mutableStateOf((route.continuous && settings.autoNext) || listLoopNow()) }
    var stopAtEndOfEpisode by remember { mutableStateOf(false) }
    var sleepAt by remember { mutableStateOf<Long?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // シーク候補
    var seekOrigin by remember { mutableLongStateOf(0L) }
    var seekCandidate by remember { mutableLongStateOf(0L) }
    var seekWasPlaying by remember { mutableStateOf(false) }
    var seekStepSec by remember { mutableIntStateOf(10) }
    var seekReturnMode by remember { mutableStateOf(PlayerMode.VIDEO_ONLY) }

    // 自動次話カウントダウン
    var countdown by remember { mutableIntStateOf(0) }

    // コメント
    var commentMode by remember { mutableStateOf(settings.commentMode) }
    var loader by remember { mutableStateOf<CommentLoader?>(null) }
    val emptyComments = remember { MutableStateFlow<List<CommentRecord>>(emptyList()) }
    val emptyState = remember { MutableStateFlow(CommentLoadState()) }
    val rawComments by (loader?.comments ?: emptyComments).collectAsState()
    val loadState by (loader?.state ?: emptyState).collectAsState()
    var drawStats by remember { mutableStateOf(DrawStats()) }
    var askBoostConsent by remember { mutableStateOf(false) }

    // 実視聴区間の記録 (HIS-03)
    var ranges by remember { mutableStateOf<List<List<Long>>>(emptyList()) }
    var lastSample by remember { mutableLongStateOf(-1L) }
    var endedHandledGen by remember { mutableIntStateOf(-1) }

    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    val promptFocus = remember { FocusRequester() }
    var swallowEnterUp by remember { mutableStateOf(false) }

    val hideLowScore = settings.hideLowScore
    val filteredComments = remember(rawComments, ng, commentMode, hideLowScore, timeMachine) {
        if (commentMode == CommentMode.OFF) emptyList() else {
            val tm = timeMachine
            val base = when {
                // 過去の時点: その時点までに投稿されたコメントだけ（投稿者コメントは常に出す）
                tm != null -> { val lim = tm.epochSec * 1000; rawComments.filter { it.isOwner || (it.postedAt ?: Long.MAX_VALUE) <= lim } }
                commentMode == CommentMode.NORMAL -> rawComments.filterNot { it.fromPastLog }
                else -> rawComments
            }
            val words = ng.filter { it.kind == NgKind.WORD }.map { it.value }
            val users = ng.filter { it.kind == NgKind.USER }.map { it.value }.toHashSet()
            base.filter { cm ->
                // NG 共有「中」相当: スコアが -4800 以下のコメントを隠す
                !(hideLowScore && cm.score <= LOW_SCORE_THRESHOLD) &&
                    cm.userId !in users && words.none { w -> cm.body.contains(w) }
            }
        }
    }
    val ngHidden = (if (commentMode == CommentMode.NORMAL) rawComments.count { !it.fromPastLog } else rawComments.size) - filteredComments.size

    fun currentProgress(ended: Boolean = false): WatchProgress {
        // 一度だけ作るリスナーや定期処理からも呼ばれるため、合成時の値ではなく今の項目を読む
        val video = queue[index].video
        val old = store.progressOf(video.provider, video.id)
        val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val pos = if (ended && dur != null) dur else exo.currentPosition
        return WatchProgress(
            provider = video.provider,
            videoId = video.id,
            title = video.title,
            thumbnailUrl = video.thumbnailUrl ?: old?.thumbnailUrl,
            durationSec = dur?.let { (it / 1000).toInt() } ?: video.durationSec ?: old?.durationSec,
            positionMs = pos,
            updatedAt = System.currentTimeMillis(),
            naturallyEnded = ended || (old?.naturallyEnded == true && pos >= (dur ?: Long.MAX_VALUE) - 5_000),
            watchedRanges = ranges,
            manualWatched = old?.manualWatched,
            seriesId = seriesId ?: old?.seriesId,
            seriesTitle = seriesTitle ?: old?.seriesTitle,
            ownerName = video.ownerName ?: old?.ownerName,
            ownerIconUrl = video.ownerIconUrl ?: old?.ownerIconUrl,
            ownerId = video.ownerId ?: old?.ownerId,
        )
    }

    fun saveProgress(ended: Boolean = false) {
        if (DebugOverrides.active || store.scope.value != accountScope) return
        if (descriptor == null || exo.playbackState == Player.STATE_IDLE) return
        store.saveProgress(currentProgress(ended))
    }

    fun startComments(d: PlaybackDescriptor) {
        loader?.cancel()
        loader = null
        val src = d.commentSource ?: return
        if (commentMode == CommentMode.OFF) return
        val v = queue[index].video
        val l = CommentLoader(scope, if (v.provider == Provider.DEMO) c.demo else c.nico, src, cache = if (store.settings.cacheComments) c.commentCache else null, ioScope = c.ioScope, accountScope = accountScope)
        loader = l
        l.loadNormal()
        if (commentMode == CommentMode.BOOST) l.startBoost(DebugOverrides.apply(store.settings).boostLevel)
    }

    var loadJob by remember { mutableStateOf<Job?>(null) }

    /**
     * シーク用の縮小画像を用意する。保存済みならそれを使い、無ければ再生が始まって3秒後に最低の優先度で取る。
     * ログインしていて、動画が対応している場合だけ（descriptor.storyboard がある場合）
     */
    fun loadStoryboard(v: app.nicotv.firetv.data.VideoSummary, d: PlaybackDescriptor, gen: Int) {
        if (!store.settings.seekThumbnails || d.storyboard == null) return
        val cat = if (v.provider == Provider.DEMO) c.demo else c.nico
        scope.launch {
            val saved = withContext(Dispatchers.IO) { c.storyboards.read(v.id, scope = accountScope) }
            if (saved != null) { if (gen == generation) storyboard = saved; return@launch }
            val src = d.storyboard ?: return@launch
            if (gen == generation) storyboardLoading = true
            try {
                // 再生開始の通信を先に通すため3秒待つ。ただしその前にシークを始めたらすぐ取る
                val kick = kotlinx.coroutines.CompletableDeferred<Unit>().also { storyboardKick = it }
                kotlinx.coroutines.withTimeoutOrNull(3_000) { kick.await() }
                if (gen != generation) return@launch
                val b = cat.lowPriority { cat.storyboard(src) } ?: return@launch
                withContext(Dispatchers.IO) { c.storyboards.write(v.id, b, scope = accountScope) }
                // シーク中ならその場で画像に切り替わる（状態として持っているので、表示中の画面がすぐ描き直される）
                if (gen == generation) storyboard = b
            } finally {
                if (gen == generation) storyboardLoading = false
            }
        }
    }

    fun loadItem(i: Int, startMs: Long) {
        pageRetry = null
        if (i != index) saveProgress()
        loadJob?.cancel()
        loader?.cancel(); loader = null
        exo.stop()
        exo.clearMediaItems()
        playbackGate.requestPlay()
        generation++
        val gen = generation
        index = i
        entry.memory["index"] = i
        descriptor = null
        storyboard = null
        storyboardLoading = false
        timeMachine = null
        error = null
        ranges = store.progressOf(queue[i].video.provider, queue[i].video.id)?.watchedRanges ?: emptyList()
        lastSample = -1
        mode = PlayerMode.LOADING
        val v = queue[i].video
        val cat = if (v.provider == Provider.DEMO) c.demo else c.nico
        loadJob = scope.launch {
            // 権利確認と配信情報の取得は再生直前に正規の手順で行う (04 §3)
            val t0 = System.currentTimeMillis()
            val prepared = cat.preparePlayback(v.id)
            android.util.Log.i("NicoTVPlay", "${v.id} prepare ${System.currentTimeMillis() - t0}ms ok=${prepared is LoadResult.Ok}")
            when (val r = prepared) {
                is LoadResult.Failed -> if (gen == generation) {
                    error = PlayerError(r.kind, r.message, canSkip = i + 1 < queue.size)
                    mode = PlayerMode.ERROR
                }
                is LoadResult.Ok -> if (gen == generation) {
                    val d = r.value
                    descriptor = d
                    val item = MediaItem.Builder().setUri(d.uri).apply { if (d.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
                    exo.setMediaItem(item, startMs)
                    exo.prepare()
                    exo.playWhenReady = playbackGate.canPlay && store.scope.value == accountScope
                    mode = PlayerMode.VIDEO_ONLY
                    startComments(d)
                    loadStoryboard(v, d, gen)
                }
            }
        }
    }

    fun loadQueuePage(page: Int, fromEnd: Boolean = false) {
        val source = queueSource ?: return
        if (paging || !foreground) return
        saveProgress()
        exo.pause()
        paging = true
        pageRetry = page to fromEnd
        val cat = if (source.provider == Provider.DEMO) c.demo else c.nico
        scope.launch {
            try {
                when (val r = cat.queuePage(source, page)) {
                    is LoadResult.Failed -> {
                        error = PlayerError(r.kind, r.message, canSkip = false)
                        mode = PlayerMode.ERROR
                    }
                    is LoadResult.Ok -> {
                        if (r.value.videos.isEmpty()) {
                            // 空でも続きがあるページは、次の操作で先へ進める。
                            nextPage = r.value.nextPage
                            notice = "このページに再生できる動画はありません"
                            mode = PlayerMode.ENDED
                        } else {
                            // 切替前の動画の位置を保存した後で、現在ページを入れ替える。
                            descriptor = null
                            queue = r.value.videos.map { app.newEntry(it, "一覧") }
                            firstPage = page
                            nextPage = r.value.nextPage
                            index = if (fromEnd) queue.lastIndex else 0
                            entry.memory["queue"] = queue
                            entry.memory["firstPage"] = firstPage
                            nextPage?.let { entry.memory["nextPage"] = it } ?: run { entry.memory["nextPage"] = -1 }
                            pageRetry = null
                            loadItem(index, 0)
                        }
                    }
                }
            } finally { paging = false }
        }
    }

    fun goNext() {
        if (!foreground) return
        when {
            index + 1 < queue.size -> loadItem(index + 1, 0)
            nextPage != null -> loadQueuePage(nextPage!!)
            listLoopNow() && firstPage > 1 -> loadQueuePage(1)
            listLoopNow() -> loadItem(0, 0)
        }
    }
    fun goPrev() {
        if (index > 0) loadItem(index - 1, 0) else if (firstPage > 1) loadQueuePage(firstPage - 1, fromEnd = true)
    }

    fun togglePlay() {
        if (exo.playbackState == Player.STATE_ENDED) { exo.seekTo(0); playRequested(); return }
        if (exo.playWhenReady) { playbackGate.pause(); exo.pause(); saveProgress() } else playRequested()
    }

    fun showControls(focusTimeline: Boolean = false) {
        mode = PlayerMode.CONTROLS
        lastInput = System.currentTimeMillis()
        scope.launch {
            awaitFrame()
            runCatching { (if (focusTimeline) timelineFocus else playPauseFocus).requestFocus() }
        }
    }

    fun hideControls() {
        mode = PlayerMode.VIDEO_ONLY
        scope.launch { awaitFrame(); runCatching { rootFocus.requestFocus() } }
    }

    fun openPanel(k: PanelKind) {
        if (mode != PlayerMode.PANEL) modeBeforePanel = if (mode == PlayerMode.CONTROLS) PlayerMode.CONTROLS else PlayerMode.VIDEO_ONLY
        panel = k
        mode = PlayerMode.PANEL
    }

    fun closePanel() {
        if (modeBeforePanel == PlayerMode.CONTROLS) showControls() else hideControls()
    }

    fun enterSeek(delta: Long) {
        if (descriptor == null) return
        seekReturnMode = if (mode == PlayerMode.CONTROLS) PlayerMode.CONTROLS else PlayerMode.VIDEO_ONLY
        seekOrigin = exo.currentPosition
        seekWasPlaying = exo.isPlaying
        exo.pause() // 候補を選ぶ間は映像とコメントを止める (02 §5)
        seekCandidate = (seekOrigin + delta).coerceIn(0, exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        mode = PlayerMode.SEEK
        storyboardKick?.complete(Unit)
        scope.launch { awaitFrame(); runCatching { rootFocus.requestFocus() } }
    }

    fun commitSeek(forcePlay: Boolean = false) {
        saveProgress()
        exo.seekTo(seekCandidate)
        lastSample = -1
        if (seekWasPlaying || forcePlay) playRequested()
        if (seekReturnMode == PlayerMode.CONTROLS) showControls(focusTimeline = true) else hideControls()
    }

    fun cancelSeek() {
        if (seekWasPlaying) playRequested()
        if (seekReturnMode == PlayerMode.CONTROLS) showControls(focusTimeline = true) else hideControls()
    }

    fun exitPlayer() {
        saveProgress()
        app.back()
    }

    /** 保存したコメントを消して、最初から取り直す */
    fun redownloadComments() {
        val d = descriptor ?: return
        loader?.discard()
        loader = null
        val v = queue[index].video
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.commentCache.delete(v.id, scope = accountScope) }
            notice = "保存したコメントを消して取り直します"
            startComments(d)
        }
    }

    fun setCommentMode(m: CommentMode) {
        if (m == CommentMode.BOOST && !store.settings.boostConsented) { askBoostConsent = true; return }
        commentMode = m
        store.updateSettings { it.copy(commentMode = m) }
        val d = descriptor ?: return
        when (m) {
            CommentMode.OFF -> { loader?.cancel(); loader = null }
            CommentMode.NORMAL -> { if (loader == null) startComments(d) else loader?.stopBoost() }
            CommentMode.BOOST -> { if (loader == null) startComments(d) else loader?.startBoost(store.settings.boostLevel) }
        }
    }

    // ---- 初回読込・破棄 ----
    LaunchedEffect(Unit) {
        loadItem(index, route.startMs)
        awaitFrame()
        runCatching { rootFocus.requestFocus() }
    }

    // 単体で始めた動画がシリーズに属していれば、シリーズの正式順のキューへ差し替える。
    // 再生中の動画はそのままで、話一覧・前後の話・自動次話がシリーズ単位で使えるようになる。
    // シリーズの確認は急がないので、再生が始まってから最低の優先度で行う（再生開始の通信と取り合わない）
    LaunchedEffect(Unit) {
        snapshotFlow { descriptor }.filterNotNull().first()
        delay(1_500)
        val v0 = queue[index].video
        val cat = if (v0.provider == Provider.DEMO) c.demo else c.nico
        if (route.discoverSeries && entry.memory["seriesChecked"] != true && queue.size == 1) {
            entry.memory["seriesChecked"] = true
            val v = queue.first().video
            val t0 = System.currentTimeMillis()
            // 属するシリーズの判定は、それ自体が急がない取得として行われる（取得中なら結果を待つだけ）
            val ref = route.knownSeries ?: cat.seriesOfVideo(v.id)
            val snap = ref?.let { r -> cat.lowPriority { (cat.series(r.id) as? LoadResult.Ok)?.value } }
            android.util.Log.i("NicoTVPlay", "${v.id} series discovery ${System.currentTimeMillis() - t0}ms found=${snap != null}")
            snap ?: return@LaunchedEffect
            val idx = snap.episodes.indexOfFirst { it.video.id == v.id }
            if (idx < 0 || queue.size != 1 || queue.first().video.id != v.id) return@LaunchedEffect
            val newQueue = snap.episodes.mapIndexed { i, e -> if (i == idx) queue.first() else app.newEntry(e.video, "シリーズ") }
            queueSource = QueueSource.Series(snap.provider, snap.id); entry.memory["queueSource"] = queueSource!!
            firstPage = snap.firstPage; entry.memory["firstPage"] = firstPage
            nextPage = snap.nextPage; entry.memory["nextPage"] = nextPage ?: -1
            queue = newQueue; entry.memory["queue"] = newQueue
            index = idx; entry.memory["index"] = idx
            seriesId = snap.id; entry.memory["seriesId"] = snap.id
            seriesTitle = snap.title; entry.memory["seriesTitle"] = snap.title
            notice = "シリーズ「${snap.title}」の${idx + 1}本目として再生中（話一覧は その他 から）"
        } else if (seriesId != null && entry.memory["seriesRefreshed"] != true) {
            // 保存済みのシリーズで始めたときは、裏で新しい話が増えていないか確かめ、増えていれば後ろに足す
            entry.memory["seriesRefreshed"] = true
            val id = seriesId ?: return@LaunchedEffect
            val snap = cat.lowPriority { (cat.series(id) as? LoadResult.Ok)?.value } ?: return@LaunchedEffect
            val currentId = queue[index].video.id
            val currentIndex = snap.episodes.indexOfFirst { it.video.id == currentId }
            if (currentIndex >= 0 && seriesId == id && !paging) {
                val playingEntry = queue[index]
                queue = snap.episodes.mapIndexed { i, e -> if (i == currentIndex) playingEntry else app.newEntry(e.video, "シリーズ") }
                index = currentIndex
                firstPage = snap.firstPage; nextPage = snap.nextPage
                entry.memory["queue"] = queue; entry.memory["index"] = index
                entry.memory["firstPage"] = firstPage; entry.memory["nextPage"] = nextPage ?: -1
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                if (ready && !playbackGate.foreground) exo.pause()
                else if (ready) playbackGate.requestPlay()
                else playbackGate.pause()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && endedHandledGen != generation) {
                    // 自然終了は現在の項目に対して一度だけ処理する (04 §4)
                    endedHandledGen = generation
                    saveProgress(ended = true)
                    val hasNext = nextIndex() != null
                    if (foreground && playbackGate.canPlay && continuous && !stopAtEndOfEpisode && hasNext) {
                        countdown = store.settings.nextCountdownSec
                        mode = PlayerMode.NEXT_PROMPT
                    } else {
                        stopAtEndOfEpisode = false
                        mode = PlayerMode.ENDED
                    }
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { lastSample = -1; saveProgress() }
                // ループで最初に戻ったときは、最後まで見たものとして記録する
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && exo.repeatMode == Player.REPEAT_MODE_ONE) {
                    saveProgress(ended = true)
                    lastSample = -1
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                saveProgress()
                val kind = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> FailKind.NETWORK
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> FailKind.NO_ENTITLEMENT
                    PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> FailKind.UNSUPPORTED
                    else -> null
                }
                error = PlayerError(kind, "${e.errorCodeName}", canSkip = index + 1 < queue.size)
                mode = PlayerMode.ERROR
            }
        }
        exo.addListener(listener)
        onDispose {
            saveProgress()
            loader?.cancel()
            exo.removeListener(listener)
            session?.release()
            exo.release()
        }
    }

    // OS ホームや画面消灯では止めて保存する。戻っても勝手に再生を再開しない (VID-11)
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP || ev == Lifecycle.Event.ON_PAUSE) {
                playbackGate.hideScreen()
                foreground = false
                seekWasPlaying = false
                exo.pause()
                if (mode == PlayerMode.NEXT_PROMPT) mode = PlayerMode.ENDED
                saveProgress()
                // 取得途中のコメントも保存しておく（バックグラウンドで終了されても失わない）
                loader?.stopBoost()
                loader?.checkpoint()
            } else if (ev == Lifecycle.Event.ON_RESUME) {
                playbackGate.resumeScreen()
                foreground = true
                if (playbackGate.canPlay && descriptor != null) exo.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ---- 定期処理: 実視聴区間・5秒ごとの保存・操作バー自動非表示・スリープ ----
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(500)
            tick++
            if (exo.isPlaying) {
                val pos = exo.currentPosition
                val speed = exo.playbackParameters.speed
                if (lastSample >= 0 && pos > lastSample && pos - lastSample <= (1_500 * speed).toLong()) {
                    ranges = mergeRange(ranges, lastSample, pos)
                }
                lastSample = pos
                if (tick % 10 == 0) saveProgress()
            }
            if (mode == PlayerMode.CONTROLS && exo.isPlaying && System.currentTimeMillis() - lastInput > 5_000) hideControls()
            sleepAt?.let {
                if (System.currentTimeMillis() >= it) {
                    sleepAt = null; exo.pause(); saveProgress()
                    notice = "スリープタイマーで停止しました"
                }
            }
        }
    }

    // 次話カウントダウン。パネルなどが開いている間は止まる
    LaunchedEffect(mode, countdown, foreground) {
        if (foreground && mode == PlayerMode.NEXT_PROMPT) {
            awaitFrame(); runCatching { promptFocus.requestFocus() }
            if (countdown > 0) { delay(1_000); countdown-- } else goNext()
        }
        if (mode == PlayerMode.ENDED || mode == PlayerMode.ERROR) { awaitFrame(); runCatching { promptFocus.requestFocus() } }
    }

    LaunchedEffect(notice) { if (notice != null) { delay(4_000); notice = null } }

    // ---- 戻る: 一回で一段だけ戻る (02 §6) ----
    fun handleBack() {
        when (mode) {
            PlayerMode.PANEL -> if (panel in listOf(PanelKind.EPISODES, PanelKind.QUALITY, PanelKind.SPEED, PanelKind.COMMENT_LIST, PanelKind.SLEEP)) panel = PanelKind.MORE else closePanel()
            PlayerMode.SEEK -> cancelSeek()
            PlayerMode.CONTROLS -> hideControls()
            PlayerMode.NEXT_PROMPT -> { mode = PlayerMode.ENDED }
            PlayerMode.LOADING -> { loadJob?.cancel(); exitPlayer() }
            else -> exitPlayer()
        }
    }
    BackHandler { handleBack() }

    val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((video.durationSec ?: 0) * 1000L)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                lastInput = System.currentTimeMillis()
                val code = ev.nativeKeyEvent.keyCode
                val isEnter = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER
                // 画面状態を切り替えた決定キーの「離す」を次の部品へ渡さない (一操作一回)
                if (ev.type == KeyEventType.KeyUp && isEnter && swallowEnterUp) { swallowEnterUp = false; return@onPreviewKeyEvent true }
                // 戻るはフォーカス移動 (Exit) より先にここで処理する。長押しのリピートでは複数階層を閉じない
                if (ev.key == Key.Back) {
                    if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) handleBack()
                    return@onPreviewKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.MediaPause -> {
                        playbackGate.pause(); seekWasPlaying = false
                        exo.pause(); saveProgress()
                        true
                    }
                    Key.MediaPlay -> {
                        when (mode) {
                            PlayerMode.SEEK -> commitSeek(forcePlay = true)
                            PlayerMode.NEXT_PROMPT -> goNext()
                            else -> { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0); playRequested() }
                        }
                        true
                    }
                    Key.MediaPlayPause -> {
                        when (mode) {
                            PlayerMode.SEEK -> commitSeek(forcePlay = true)
                            PlayerMode.VIDEO_ONLY, PlayerMode.CONTROLS, PlayerMode.PANEL -> togglePlay()
                            else -> {}
                        }
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { ev ->
                // ここに届くのはフォーカスが画面全体 (映像のみ・シーク候補) のとき
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                val accel = if (repeat > 20) 6 else if (repeat > 8) 3 else 1
                when (mode) {
                    PlayerMode.VIDEO_ONLY -> when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { swallowEnterUp = true; showControls(); true }
                        Key.DirectionLeft, Key.MediaRewind -> { enterSeek(-seekStepSec * 1000L); true }
                        Key.DirectionRight, Key.MediaFastForward -> { enterSeek(seekStepSec * 1000L); true }
                        Key.DirectionUp -> { openPanel(PanelKind.COMMENT); true }
                        Key.DirectionDown -> { showControls(focusTimeline = true); true }
                        Key.Menu -> { openPanel(PanelKind.MORE); true }
                        else -> false
                    }
                    PlayerMode.SEEK -> {
                        val max = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        when (ev.key) {
                            Key.DirectionLeft, Key.MediaRewind -> { seekCandidate = (seekCandidate - seekStepSec * 1000L * accel).coerceIn(0, max); true }
                            Key.DirectionRight, Key.MediaFastForward -> { seekCandidate = (seekCandidate + seekStepSec * 1000L * accel).coerceIn(0, max); true }
                            Key.DirectionUp -> { seekStepSec = when (seekStepSec) { 10 -> 30; 30 -> 60; else -> 60 }; true }
                            Key.DirectionDown -> { seekStepSec = when (seekStepSec) { 60 -> 30; 30 -> 10; else -> 10 }; true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { swallowEnterUp = true; commitSeek(); true }
                            Key.Menu -> { cancelSeek(); openPanel(PanelKind.MORE); true }
                            else -> true
                        }
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        // 右にコメント一覧を出すときは、映像を縦横比のまま左へ縮める
        val showCommentList = settings.commentListSide && commentMode != CommentMode.OFF && descriptor != null
        Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    player = exo
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // 縦横比維持が既定 (VID-08)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(false)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 終了・次話案内・エラー中は止まったコメントが案内に重ならないよう隠す
        val commentsVisible = mode !in listOf(PlayerMode.NEXT_PROMPT, PlayerMode.ENDED, PlayerMode.ERROR, PlayerMode.LOADING)
        if (commentMode != CommentMode.OFF && descriptor != null && commentsVisible) {
            CommentLayer(
                comments = filteredComments,
                config = SchedulerConfig(
                    maxOnScreen = DebugOverrides.maxOnScreen ?: settings.density.maxOnScreen,
                    danmaku = settings.collision == CollisionPolicy.DANMAKU,
                    scale = settings.commentScale,
                    showFixed = settings.showFixedComments,
                    bottomMarginRatio = settings.bottomMarginRatio,
                    priorityNicoru = settings.nicoruPriority.threshold,
                ),
                // 操作バーやパネルを開いている間はコメントを薄くして、操作部を読みやすくする
                opacity = settings.commentOpacity * if (mode == PlayerMode.CONTROLS || mode == PlayerMode.PANEL || mode == PlayerMode.SEEK) 0.3f else 1f,
                positionMs = { exo.currentPosition },
                fadeOld = settings.fadeOldComments,
                onStats = { a, s, pr -> drawStats = DrawStats(a, s, pr) },
            )
        }
        }
        if (showCommentList) {
            val w = settings.commentListWidth.coerceIn(0.15f, 0.45f)
            CommentSidePanel(
                comments = filteredComments,
                positionMs = { exo.currentPosition },
                modifier = Modifier.fillMaxHeight().weight(w / (1f - w)),
            )
        }
        }

        val title = buildString {
            seriesTitle?.let { append(it).append("  ") }
            if (queue.size > 1) append("${index + 1}/${queue.size}  ")
            append(video.title)
        }

        when (mode) {
            PlayerMode.LOADING -> CenterMessage("読み込み中：${video.title}", "権利と配信情報を確認しています。戻るで取消。")
            PlayerMode.SEEK -> SeekOverlay(seekOrigin, seekCandidate, duration, seekStepSec, storyboard, storyboardLoading)
            PlayerMode.CONTROLS -> ControlsOverlay(
                title = title,
                position = { exo.currentPosition },
                duration = duration,
                isPlaying = isPlaying,
                hasPrev = index > 0 || firstPage > 1,
                hasNext = nextIndex() != null,
                commentSummary = commentSummary(commentMode, loadState),
                playPauseFocus = playPauseFocus,
                timelineFocus = timelineFocus,
                onPlayPause = { togglePlay() },
                onPrev = { goPrev() },
                onNext = { goNext() },
                onNoPrev = { notice = "前の話はありません" },
                onNoNext = { notice = if (queue.size == 1) "単体で再生しています（シリーズの連続再生ではありません）" else "これが最後の話です" },
                onComment = { openPanel(PanelKind.COMMENT) },
                onMore = { openPanel(PanelKind.MORE) },
                loopMode = if (loopOn) 1 else if (listLoopOn) 2 else 0,
                // 決定のたびに オフ → この動画 → リスト全体（2本以上のとき）→ オフ と切り替える
                onToggleLoop = {
                    val lk = listKeyNow()
                    val multi = queue.size > 1 && lk != null
                    when {
                        loopOn -> {
                            store.setLoop(video.provider, video.id, false)
                            if (multi) {
                                store.setListLoop(lk!!, true)
                                continuous = true
                                notice = "リスト全体をループします（最後まで行くと最初に戻ります。自動再生もオン）"
                            } else notice = "ループ再生をオフにしました"
                        }
                        listLoopOn -> { lk?.let { store.setListLoop(it, false) }; notice = "ループ再生をオフにしました" }
                        else -> { store.setLoop(video.provider, video.id, true); notice = "この動画をループ再生します" + if (multi) "（もう一度押すとリスト全体）" else "" }
                    }
                },
                onTimelineSeek = { d -> enterSeek(d) },
            )
            PlayerMode.PANEL -> Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                PlayerPanel(
                    kind = panel,
                    app = app,
                    exo = exo,
                    queue = queue,
                    index = index,
                    commentMode = commentMode,
                    loadState = loadState,
                    drawStats = drawStats,
                    ngHidden = ngHidden,
                    comments = rawComments,
                    continuous = continuous,
                    stopAtEnd = stopAtEndOfEpisode,
                    sleepAt = sleepAt,
                    onCommentMode = { setCommentMode(it) },
                    onBoostLevel = { lv -> store.updateSettings { it.copy(boostLevel = lv) }; if (commentMode == CommentMode.BOOST) { loader?.stopBoost(); loader?.startBoost(lv) } },
                    onStopBoost = { loader?.stopBoost() },
                    onRedownload = { redownloadComments() },
                    onNavigate = { panel = it },
                    onSelectEpisode = { i -> loadItem(i, 0) },
                    onSeekTo = { ms -> exo.seekTo(ms); lastSample = -1; closePanel() },
                    onContinuous = { continuous = it },
                    onStopAtEnd = { stopAtEndOfEpisode = it },
                    onSleep = { sleepAt = it },
                    timeMachine = timeMachine,
                    onTimeMachine = { tp ->
                        if (tp != null && commentMode == CommentMode.OFF) setCommentMode(CommentMode.NORMAL)
                        timeMachine = tp
                        if (tp != null) {
                            loader?.loadAt(tp.epochSec, tp.label)
                            notice = "${tp.label}（${formatDate(tp.epochSec * 1000)}）までのコメントを表示中"
                        } else { loader?.stopBoost(); notice = "現在のコメントに戻しました" }
                        panel = PanelKind.COMMENT
                    },
                    onClose = { closePanel() },
                )
            }
            PlayerMode.NEXT_PROMPT -> NextPrompt(
                nextTitle = nextIndex()?.let { queue.getOrNull(it) }?.video?.let { (if (index + 1 >= queue.size) "（最初に戻る）" else "") + it.title } ?: "",
                seconds = countdown,
                focus = promptFocus,
                onNow = { goNext() },
                onStop = { mode = PlayerMode.ENDED },
            )
            PlayerMode.ENDED -> EndedOverlay(
                hasNext = nextIndex() != null,
                nextTitle = nextIndex()?.let { queue.getOrNull(it) }?.video?.title,
                focus = promptFocus,
                onNext = { goNext() },
                onReplay = { exo.seekTo(0); playRequested(); endedHandledGen = -1; hideControls() },
                onBack = { exitPlayer() },
            )
            PlayerMode.ERROR -> ErrorOverlay(
                title = video.title,
                err = error,
                officialUrl = if (video.provider == Provider.NICO) "https://www.nicovideo.jp/watch/${video.id}" else null,
                focus = promptFocus,
                onRetry = { val retry = pageRetry; if (retry != null) loadQueuePage(retry.first, retry.second) else loadItem(index, store.progressOf(video.provider, video.id)?.takeIf { it.isInProgress }?.positionMs ?: 0) },
                onSkip = { goNext() },
                onLogin = { app.navigate(Route.Login) },
                onBack = { exitPlayer() },
            )
            PlayerMode.VIDEO_ONLY -> {
                if (!isPlaying && descriptor != null && exo.playbackState == Player.STATE_READY) PausedBadge(title)
            }
        }

        notice?.let {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 24.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(it, fontSize = TvText.body)
            }
        }
    }

    if (askBoostConsent) {
        BoostConsentDialog(
            onAccept = {
                askBoostConsent = false
                store.updateSettings { it.copy(boostConsented = true) }
                setCommentMode(CommentMode.BOOST)
            },
            onCancel = { askBoostConsent = false },
        )
    }
}


fun commentSummary(mode: CommentMode, s: CommentLoadState): String = when {
    mode == CommentMode.OFF -> "コメント：オフ"
    s.normalError != null -> "コメント：${s.normalError}"
    s.loadingNormal -> "コメント：取得中"
    mode == CommentMode.BOOST -> "コメント：通常${s.normalCount}＋過去${s.pastAdded}" + if (s.loadingPast) "（取得中）" else ""
    else -> "コメント：${s.normalCount}件"
}

@Composable
private fun PausedBadge(title: String) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(horizontal = 40.dp, vertical = 24.dp),
        ) {
            Text("一時停止中", fontSize = TvText.heading, fontWeight = FontWeight.Bold)
            Text(title, fontSize = TvText.small, color = Palette.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("決定：操作バー　再生キー：再開　戻る：終了", fontSize = TvText.small, color = Palette.textDim)
        }
    }
}

@Composable
fun CenterMessage(title: String, sub: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(640.dp)) {
            Text(title, fontSize = TvText.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = TvText.small, color = Palette.textDim)
        }
    }
}

@Composable
private fun SeekOverlay(
    origin: Long, candidate: Long, duration: Long, stepSec: Int,
    storyboard: app.nicotv.firetv.data.Storyboard?, storyboardLoading: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(horizontal = 40.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val diff = candidate - origin
            val frac = if (duration > 0) (candidate.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val preview = storyboard != null || storyboardLoading
            // 縮小画像があるときは、画像の真下に時刻を出す（画像と一緒に左右へ動き、画面からはみ出さない）
            if (preview) SeekPreview(storyboard, candidate, frac) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatTime(candidate), fontSize = TvText.title, fontWeight = FontWeight.Bold)
                    if (duration > 0) Text(" / " + formatTime(duration), fontSize = TvText.small, color = Palette.textDim)
                }
                Text((if (diff >= 0) "+" else "−") + formatTime(kotlin.math.abs(diff)), fontSize = TvText.body, color = Palette.accent)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (!preview) {
                    Text(formatTime(candidate), fontSize = TvText.title, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(16.dp))
                    Text((if (diff >= 0) "+" else "−") + formatTime(kotlin.math.abs(diff)), fontSize = TvText.body, color = Palette.accent)
                }
                Spacer(Modifier.weight(1f))
                Text("刻み ${stepSec}秒（上下で変更）", fontSize = TvText.small, color = Palette.textDim)
            }
            TimeBar(position = origin, candidate = candidate, duration = duration, focused = true)
            Text("左右：時刻を選ぶ　決定：移動　戻る：取消（元の位置のまま）", fontSize = TvText.small, color = Palette.textDim)
        }
    }
}

@Composable
fun TimeBar(position: Long, candidate: Long?, duration: Long, focused: Boolean) {
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(if (focused) 10.dp else 6.dp).background(Color(0x55FFFFFF), RoundedCornerShape(5.dp))) {
        Box(Modifier.fillMaxWidth(frac).height(if (focused) 10.dp else 6.dp).background(Palette.bad, RoundedCornerShape(5.dp)))
        if (candidate != null && duration > 0) {
            val cf = (candidate.toFloat() / duration).coerceIn(0f, 1f)
            Row(Modifier.fillMaxWidth()) {
                if (cf > 0f) Spacer(Modifier.fillMaxWidth(cf))
                Box(Modifier.width(4.dp).height(10.dp).background(Color.White))
            }
        }
    }
}

/**
 * シーク中の縮小画像と、その下の時刻。選んでいる位置に合わせて左右に動かし、画面の端でははみ出さないよう止める。
 * 画像の取得中は枠と「取得中」を出し、取れたらその場で画像に切り替わる。
 * シート（JPEG）は必要になったときに裏で展開し、最近使った3枚だけ覚える。
 * 元の画像は小さい（120×90 や 160×90）ので、テレビで見えるよう拡大して表示する。
 */
@Composable
private fun SeekPreview(board: app.nicotv.firetv.data.Storyboard?, timeMs: Long, frac: Float, info: @Composable () -> Unit) {
    val cell = board?.cellAt(timeMs)
    val sheets = remember(board) {
        object : LinkedHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, androidx.compose.ui.graphics.ImageBitmap>?) = size > 3
        }
    }
    var shown by remember(board) { mutableStateOf<Pair<androidx.compose.ui.graphics.ImageBitmap, app.nicotv.firetv.data.Storyboard.Cell>?>(null) }
    if (board != null && cell != null) LaunchedEffect(board, cell) {
        val img = sheets[cell.sheet] ?: withContext(Dispatchers.Default) {
            val bytes = board.sheets[cell.sheet]
            val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }?.also { sheets[cell.sheet] = it }
        // 展開中は直前の画像を出したままにする（ちらつかせない）
        if (img != null) shown = img to cell
    }
    val w = 288.dp
    val h = if (board != null) w * board.thumbHeight / board.thumbWidth.coerceAtLeast(1) else w * 9 / 16
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val x = (maxWidth * frac - w / 2).coerceIn(0.dp, (maxWidth - w).coerceAtLeast(0.dp))
        Column(Modifier.padding(start = x).width(w), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.width(w).height(h)
                    .background(Color.Black, RoundedCornerShape(6.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(6.dp))
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                val s = shown
                if (board != null && s != null) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawImage(
                            s.first,
                            srcOffset = androidx.compose.ui.unit.IntOffset(s.second.x, s.second.y),
                            srcSize = androidx.compose.ui.unit.IntSize(board.thumbWidth, board.thumbHeight),
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                        )
                    }
                } else {
                    Text(if (board == null) "縮小画像を取得中…" else "", fontSize = TvText.small, color = Palette.textDim)
                }
            }
            info()
        }
    }
}
