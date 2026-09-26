package app.nicotv.firetv.ui.player

import androidx.compose.foundation.background
import app.nicotv.firetv.comments.TimePoint
import app.nicotv.firetv.comments.timeMachineOptions
import app.nicotv.firetv.ui.parsePostedMs
import app.nicotv.firetv.ui.formatDate
import app.nicotv.firetv.data.Provider
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import app.nicotv.firetv.comments.CommentLoadState
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.FailKind
import app.nicotv.firetv.data.local.BoostLevel
import app.nicotv.firetv.data.local.CollisionPolicy
import app.nicotv.firetv.data.local.CommentMode
import app.nicotv.firetv.data.local.Density
import app.nicotv.firetv.data.local.NgKind
import app.nicotv.firetv.data.local.NgRule
import app.nicotv.firetv.data.local.NicoruPriority
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.FocusSurface
import app.nicotv.firetv.ui.MenuDialog
import app.nicotv.firetv.ui.MenuItem
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.QrCode
import app.nicotv.firetv.ui.QueueEntry
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvDialog
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.formatTime
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 操作バー。上段が時間軸、下段が 前の話／再生・停止／次の話／コメント／その他 (02 §4)。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ControlsOverlay(
    title: String,
    position: () -> Long,
    duration: Long,
    isPlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    commentSummary: String,
    playPauseFocus: FocusRequester,
    timelineFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onNoPrev: () -> Unit,
    onNoNext: () -> Unit,
    onComment: () -> Unit,
    /** 0: オフ、1: この動画、2: リスト全体 */
    loopMode: Int,
    onToggleLoop: () -> Unit,
    onMore: () -> Unit,
    onTimelineSeek: (Long) -> Unit,
) {
    var pos by remember { mutableLongStateOf(position()) }
    LaunchedEffect(Unit) { while (true) { pos = position(); delay(250) } }
    var timelineFocused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Menu) { onMore(); true } else false
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontSize = TvText.heading, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 時間軸: 左右でシーク候補へ、決定でも候補を開く
            Box(
                Modifier.fillMaxWidth()
                    .focusRequester(timelineFocus)
                    .onFocusChanged { timelineFocused = it.isFocused }
                    .onKeyEvent {
                        if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (it.key) {
                            Key.DirectionLeft -> { onTimelineSeek(-10_000); true }
                            Key.DirectionRight -> { onTimelineSeek(10_000); true }
                            Key.DirectionCenter, Key.Enter -> { onTimelineSeek(0); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .border(if (timelineFocused) 2.dp else 0.dp, if (timelineFocused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(8.dp),
            ) {
                TimeBar(pos, null, duration, timelineFocused)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatTime(pos)} / ${formatTime(duration)}", fontSize = TvText.small, color = Palette.textDim)
                Spacer(Modifier.width(20.dp))
                Text(commentSummary, fontSize = TvText.small, color = Palette.textDim)
                Spacer(Modifier.weight(1f))
                if (timelineFocused) Text("左右でシーク", fontSize = TvText.small, color = Palette.textDim)
            }
            // 前後の話が無い場合も配置を詰めず、理由を表示する無効状態にする
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("前の話", if (hasPrev) onPrev else onNoPrev, dim = !hasPrev, icon = "⏮")
                TvButton(if (isPlaying) "一時停止" else "再生", onPlayPause, Modifier.focusRequester(playPauseFocus), primary = true, icon = if (isPlaying) "⏸" else "▶")
                TvButton("次の話", if (hasNext) onNext else onNoNext, dim = !hasNext, icon = "⏭")
                // 決定で オフ → この動画 → リスト全体 と切り替える。動画のループは動画ごと、リストのループはシリーズ・一覧ごとに記憶する
                TvButton(
                    when (loopMode) { 1 -> "ループ：1本"; 2 -> "ループ：リスト"; else -> "ループ" }, onToggleLoop,
                    icon = when (loopMode) { 1 -> "🔂"; 2 -> "🔁"; else -> "☐" }, primary = loopMode != 0,
                )
                TvButton("コメント", onComment, icon = "💬")
                TvButton("その他", onMore, icon = "≡")
            }
        }
    }
}

@Composable
private fun PanelRow(label: String, value: String? = null, modifier: Modifier = Modifier, note: String? = null, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), containerColor = Color(0x22FFFFFF), focusedColor = Color(0xFF3A3A48), scale = 1.02f) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = TvText.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                value?.let { Text(it, fontSize = TvText.small, color = Palette.accent, maxLines = 1) }
            }
            note?.let { Text(it, fontSize = 13.sp, color = Palette.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun <T> List<T>.after(v: T): T = this[(indexOf(v) + 1) % size]

/** 右側のサイドパネル。開いている間は背面の動画へ入力を送らない (02 §8)。 */
@OptIn(ExperimentalComposeUiApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerPanel(
    kind: PanelKind,
    app: AppState,
    exo: ExoPlayer,
    queue: List<QueueEntry>,
    index: Int,
    commentMode: CommentMode,
    loadState: CommentLoadState,
    drawStats: DrawStats,
    ngHidden: Int,
    comments: List<CommentRecord>,
    continuous: Boolean,
    stopAtEnd: Boolean,
    sleepAt: Long?,
    onCommentMode: (CommentMode) -> Unit,
    onBoostLevel: (BoostLevel) -> Unit,
    onStopBoost: () -> Unit,
    onRedownload: () -> Unit,
    onNavigate: (PanelKind) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onContinuous: (Boolean) -> Unit,
    onStopAtEnd: (Boolean) -> Unit,
    onSleep: (Long?) -> Unit,
    timeMachine: app.nicotv.firetv.comments.TimePoint?,
    onTimeMachine: (app.nicotv.firetv.comments.TimePoint?) -> Unit,
    onClose: () -> Unit,
) {
    val first = remember(kind) { FocusRequester() }
    val store = app.c.store
    val settings = store.data.collectAsState().value.settings
    LaunchedEffect(kind) { awaitFrame(); runCatching { first.requestFocus() } }

    Column(
        Modifier.width(400.dp).fillMaxHeight()
            .background(Color(0xF0141419))
            .padding(horizontal = 18.dp, vertical = 20.dp)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .onPreviewKeyEvent {
                // 同じメニューキーでパネルを閉じる
                if (it.type == KeyEventType.KeyDown && it.key == Key.Menu) { onClose(); true } else false
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (kind) {
            PanelKind.COMMENT -> {
                PanelTitle("コメント")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommentMode.entries.forEach { m ->
                        TvButton(
                            (if (m == commentMode) "✓ " else "") + m.label, { onCommentMode(m) },
                            if (m == commentMode) Modifier.focusRequester(first) else Modifier,
                            primary = m == commentMode,
                        )
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    item { PanelRow("取得量", settings.boostLevel.label.replace("詳細：", ""), note = "増量時に過去ログをどこまで取るか") { onBoostLevel(BoostLevel.entries.after(settings.boostLevel)) } }
                    item {
                        PanelRow(
                            "画面の密度", settings.density.fullLabel,
                            note = if (settings.density.maxOnScreen > 120 && settings.collision == CollisionPolicy.READABLE) "読みやすさ優先では約100件で頭打ち。多く出すには「混雑時」を弾幕優先に"
                            else "同時に表示する上限（取得量とは別）。決定で切替",
                        ) { store.updateSettings { it.copy(density = Density.entries.after(it.density)) } }
                    }
                    item { PanelRow("文字の大きさ", "${(settings.commentScale * 100).toInt()}%") { store.updateSettings { it.copy(commentScale = listOf(0.8f, 1.0f, 1.2f, 1.4f).after(it.commentScale)) } } }
                    item { PanelRow("不透明度", "${(settings.commentOpacity * 100).toInt()}%") { store.updateSettings { it.copy(commentOpacity = listOf(0.5f, 0.7f, 0.9f, 1.0f).after(it.commentOpacity)) } } }
                    item { PanelRow("古いコメントを薄く", if (settings.fadeOldComments) "オン" else "オフ", note = "投稿が古いものほど薄く（最低15%）。新しいコメントが目立つ") { store.updateSettings { it.copy(fadeOldComments = !it.fadeOldComments) } } }
                    item {
                        PanelRow(
                            "コメントの時点",
                            timeMachine?.let { "${it.label}（${formatDate(it.epochSec * 1000)}）" } ?: "現在",
                            note = "過去ログ（プレミアム会員）で、その時点までに投稿されたコメントだけを流す",
                        ) { onNavigate(PanelKind.TIME_MACHINE) }
                    }
                    item { PanelRow("上・下固定コメント", if (settings.showFixedComments) "表示" else "非表示") { store.updateSettings { it.copy(showFixedComments = !it.showFixedComments) } } }
                    item { PanelRow("混雑時", settings.collision.label, note = "弾幕優先は重なりを許して多く出す") { store.updateSettings { it.copy(collision = CollisionPolicy.entries.after(it.collision)) } } }
                    item { PanelRow("高評価コメント", settings.nicoruPriority.label, note = "ニコるの多いコメントは混雑していても必ず表示") { store.updateSettings { it.copy(nicoruPriority = NicoruPriority.entries.after(it.nicoruPriority)) } } }
                    item { PanelRow("低評価コメント", if (settings.hideLowScore) "隠す" else "表示", note = "NG共有「中」相当（スコア -4800 以下）") { store.updateSettings { it.copy(hideLowScore = !it.hideLowScore) } } }
                    item { PanelRow("下端の余白（字幕よけ）", if (settings.bottomMarginRatio == 0f) "なし" else "${(settings.bottomMarginRatio * 100).toInt()}%") { store.updateSettings { it.copy(bottomMarginRatio = listOf(0f, 0.1f, 0.2f).after(it.bottomMarginRatio)) } } }
                    if (loadState.loadingPast) item { PanelRow("過去コメントの取得を中止", note = "取れた分は保存します。動画は止まりません") { onStopBoost() } }
                    item { PanelRow("コメントを取り直す", note = "保存したコメントを消して、最初から取得し直す") { onRedownload() } }
                    item { PanelRow("コメント一覧・NG登録") { onNavigate(PanelKind.COMMENT_LIST) } }
                    item {
                        // 取得件数・重複除外・NG・現在描画数を区別して示す (03 §12)
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            StatLine("通常コメント", "${loadState.normalCount}件")
                            StatLine("過去コメント（追加）", "${loadState.pastAdded}件" + if (loadState.pastPages > 0) "（${loadState.pastPages}回取得）" else "")
                            loadState.target?.let { StatLine("目標件数", "${it}件") }
                            StatLine("重複として除外", "${loadState.duplicatesRemoved}件")
                            StatLine("NGで非表示", "${ngHidden}件")
                            StatLine("いま画面に表示", "${drawStats.active}件")
                            StatLine("高評価・投稿者を優先表示（累計）", "${drawStats.priority}件")
                            StatLine("混雑で省略（累計）", "${drawStats.skipped}件")
                            if (loadState.status.isNotBlank()) Text(loadState.status, fontSize = 13.sp, color = Palette.warn)
                        }
                    }
                }
            }
            PanelKind.MORE -> {
                PanelTitle("その他")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PanelRow("話一覧・キュー", "${index + 1}/${queue.size}", Modifier.focusRequester(first)) { onNavigate(PanelKind.EPISODES) } }
                    item { PanelRow("コメント一覧・NG登録") { onNavigate(PanelKind.COMMENT_LIST) } }
                    item { PanelRow("画質", exo.videoFormat?.height?.let { "${it}p" } ?: "自動") { onNavigate(PanelKind.QUALITY) } }
                    item { PanelRow("再生速度", "${exo.playbackParameters.speed}倍") { onNavigate(PanelKind.SPEED) } }
                    item { PanelRow("自動で次の話へ", if (continuous) "オン" else "オフ", note = if (queue.size <= 1) "単体再生中" else null) { onContinuous(!continuous) } }
                    item { PanelRow("スリープ", sleepLabel(sleepAt, stopAtEnd)) { onNavigate(PanelKind.SLEEP) } }
                    item { PanelRow("閉じる") { onClose() } }
                    val v = queue[index].video
                    if (v.provider.name == "NICO") item {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            QrCode("https://www.nicovideo.jp/watch/${v.id}", size = 110.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("公式ページ（共有・通報・大百科など）をスマホで開く", fontSize = 13.sp, color = Palette.textDim)
                        }
                    }
                }
            }
            PanelKind.EPISODES -> {
                PanelTitle("話一覧・キュー")
                val data by store.data.collectAsState()
                val acc = store.account(data)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(queue, key = { _, q -> q.entryId }) { i, q ->
                        val p = acc.progress[key(q.video.provider, q.video.id)]
                        PanelRow(
                            "${i + 1}. ${q.video.title}",
                            when {
                                i == index -> "再生中"
                                p?.isWatched == true -> "視聴済み"
                                p?.isInProgress == true -> formatTime(p.positionMs) + "まで"
                                else -> null
                            },
                            if (i == index) Modifier.focusRequester(first) else Modifier,
                            note = if (q.video.isPaid) "有料・会員限定" else null,
                        ) { if (i == index) onClose() else onSelectEpisode(i) }
                    }
                }
            }
            PanelKind.QUALITY -> {
                PanelTitle("画質")
                Text("実際の画質：" + (exo.videoFormat?.let { "${it.height}p" } ?: "不明"), fontSize = TvText.small, color = Palette.textDim)
                val groups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val overridden = exo.trackSelectionParameters.overrides.isNotEmpty()
                PanelRow(
                    if (settings.highestQuality) "自動（最高画質を優先）" else "自動（回線に合わせる）", if (!overridden) "✓" else null, Modifier.focusRequester(first),
                    note = "決定で自動に戻す。優先の切り替えは下の項目",
                ) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                    onClose()
                }
                PanelRow(
                    "最高画質を優先", if (settings.highestQuality) "オン" else "オフ",
                    note = "オンは回線が遅くても画質を下げない（止まりやすくなる）。オフは回線に合わせて自動で上下する",
                ) { store.updateSettings { it.copy(highestQuality = !it.highestQuality) } }
                // 同じ解像度でビットレート違いが複数ある場合は、その解像度の最高ビットレートを1つだけ出す
                val choices = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
                    .groupBy { (g, t) -> g.getTrackFormat(t).height }
                    .mapValues { (_, v) -> v.maxBy { (g, t) -> g.getTrackFormat(t).bitrate } }
                    .toSortedMap(compareByDescending { it })
                choices.forEach { (height, pair) ->
                    val (g, t) = pair
                    PanelRow("${height}p", if (overridden && g.isTrackSelected(t)) "✓" else null) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build()
                        onClose()
                    }
                }
                if (groups.isEmpty()) Text("選べる画質はありません。", fontSize = TvText.small, color = Palette.textDim)
            }
            PanelKind.SPEED -> {
                PanelTitle("再生速度")
                val cur = exo.playbackParameters.speed
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { s ->
                    PanelRow("${s}倍", if (s == cur) "✓" else null, if (s == cur) Modifier.focusRequester(first) else Modifier) {
                        exo.setPlaybackSpeed(s)
                        store.updateSettings { it.copy(playbackSpeed = s) }
                        onClose()
                    }
                }
                Text("選んだ速さは次の動画や次回の再生にも使います。音の高さは変わりません。コメントも動画の時刻に合わせて同じ速さで流れます。", fontSize = 13.sp, color = Palette.textDim)
            }
            PanelKind.SLEEP -> {
                PanelTitle("スリープ")
                PanelRow("オフ", if (sleepAt == null && !stopAtEnd) "✓" else null, Modifier.focusRequester(first)) { onSleep(null); onStopAtEnd(false); onClose() }
                listOf(30, 60, 90).forEach { m ->
                    PanelRow("${m}分後に停止") { onSleep(System.currentTimeMillis() + m * 60_000L); onClose() }
                }
                PanelRow("この話の終わりで停止", if (stopAtEnd) "✓" else null) { onStopAtEnd(true); onClose() }
            }
            PanelKind.COMMENT_LIST -> CommentListPanel(app, exo, comments, first, onSeekTo)
            PanelKind.TIME_MACHINE -> {
                PanelTitle("コメントの時点")
                val v = queue.getOrNull(index)?.video
                // デモ動画は確認用に、ログインなしでも選べる
                val loggedIn = app.c.auth.collectAsState().value is app.nicotv.firetv.AuthState.LoggedIn || v?.provider == Provider.DEMO
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    item { PanelRow("現在（最新）", if (timeMachine == null) "✓" else null, if (timeMachine == null) Modifier.focusRequester(first) else Modifier) { onTimeMachine(null) } }
                    if (loggedIn) {
                        val opts = timeMachineOptions(parsePostedMs(v?.registeredAt), System.currentTimeMillis())
                        items(opts.size) { i ->
                            val o = opts[i]
                            val on = timeMachine?.epochSec == o.epochSec
                            PanelRow(o.label, formatDate(o.epochSec * 1000) + if (on) "  ✓" else "", if (on) Modifier.focusRequester(first) else Modifier) { onTimeMachine(o) }
                        }
                    }
                    item {
                        Text(
                            if (loggedIn) "選んだ時点までに投稿されたコメントだけを流します。手元に足りなければ、その時点から過去ログをさかのぼって取ります（取れた分は保存）。投稿者コメントは常に出ます。"
                            else "過去の時点のコメントを見るにはログインが必要です（過去ログはプレミアム会員の機能）。",
                            fontSize = 13.sp, color = Palette.textDim,
                        )
                    }
                }
            }
        }
    }
}

private fun sleepLabel(at: Long?, stopAtEnd: Boolean) = when {
    stopAtEnd -> "この話の終わり"
    at != null -> "あと${((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)}分"
    else -> "オフ"
}

@Composable
private fun PanelTitle(t: String) = Text(t, fontSize = TvText.heading, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))

@Composable
private fun StatLine(k: String, v: String) {
    Row { Text(k, fontSize = 13.sp, color = Palette.textDim, modifier = Modifier.weight(1f)); Text(v, fontSize = 13.sp) }
}

/**
 * コメント一覧。開いた時点の位置付近で固定し、新着でフォーカスを動かさない (03 §9)。
 * 動く弾幕を狙わずに NG 登録できる。
 */
@Composable
private fun CommentListPanel(app: AppState, exo: ExoPlayer, all: List<CommentRecord>, first: FocusRequester, onSeekTo: (Long) -> Unit) {
    val anchor = remember { exo.currentPosition }
    val list = remember { all.filter { it.vposMs in (anchor - 15_000)..(anchor + 15_000) }.take(300) }
    val startIdx = remember { list.indexOfFirst { it.vposMs >= anchor }.coerceAtLeast(0) }
    var target by remember { mutableStateOf<CommentRecord?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy/M/d", Locale.JAPAN) }
    PanelTitle("コメント一覧")
    Text("${formatTime(anchor)} 付近（前後15秒・${list.size}件）", fontSize = 13.sp, color = Palette.textDim)
    if (list.isEmpty()) {
        Text("この付近にコメントはありません。", fontSize = TvText.small, color = Palette.textDim)
        PanelRow("戻る", modifier = Modifier.focusRequester(first)) { onSeekTo(anchor) }
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (startIdx - 1).coerceAtLeast(0))
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(list, key = { _, cm -> cm.dedupeKey }) { i, cm ->
            PanelRow(
                cm.body,
                formatTime(cm.vposMs),
                if (i == startIdx) Modifier.focusRequester(first) else Modifier,
                note = listOfNotNull(cm.postedAt?.let { "投稿 " + fmt.format(Date(it)) }, if (cm.fromPastLog) "過去ログ" else null, if (cm.isOwner) "投稿者コメント" else null).joinToString("・"),
            ) { target = cm }
        }
    }
    target?.let { cm ->
        MenuDialog(
            cm.body,
            buildList {
                add(MenuItem("この時刻へ移動", formatTime(cm.vposMs)) { onSeekTo(cm.vposMs) })
                add(MenuItem("NGワードに登録", "「${cm.body.take(20)}」を含むコメントを隠す（このアプリのみ）") { app.c.store.addNg(NgRule(NgKind.WORD, cm.body.take(40))) })
                cm.userId?.let { u -> add(MenuItem("このユーザーをNG", "匿名IDで判定（このアプリのみ）") { app.c.store.addNg(NgRule(NgKind.USER, u)) }) }
                add(MenuItem("取消") {})
            },
            onDismiss = { target = null },
        )
    }
}

@Composable
fun NextPrompt(nextTitle: String, seconds: Int, focus: FocusRequester, onNow: () -> Unit, onStop: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomEnd).padding(36.dp).width(440.dp)
                .background(Color(0xEE17171D), RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${seconds}秒後に次の話", fontSize = TvText.small, color = Palette.textDim)
            Text(nextTitle, fontSize = TvText.body, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("今すぐ次へ", onNow, Modifier.focusRequester(focus), primary = true, icon = "⏭")
                TvButton("止める", onStop)
            }
        }
    }
}

@Composable
fun EndedOverlay(hasNext: Boolean, nextTitle: String?, focus: FocusRequester, onNext: () -> Unit, onReplay: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(620.dp)) {
            Text(if (hasNext) "再生が終わりました" else "最後まで再生しました", fontSize = TvText.title, fontWeight = FontWeight.Bold)
            if (hasNext && nextTitle != null) Text("次：$nextTitle", fontSize = TvText.body, color = Palette.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasNext) {
                    TvButton("次の話を再生", onNext, Modifier.focusRequester(focus), primary = true, icon = "⏭")
                    TvButton("もう一度", onReplay)
                } else {
                    TvButton("もう一度", onReplay, Modifier.focusRequester(focus), primary = true)
                }
                TvButton("戻る", onBack)
            }
        }
    }
}

/** 視聴不可・通信失敗などの説明 (TV-15)。原因を区別し、スキップは明示操作に限る (SER-08)。 */
@Composable
fun ErrorOverlay(
    title: String,
    err: PlayerError?,
    officialUrl: String?,
    focus: FocusRequester,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val kind = err?.kind
    val headline = when (kind) {
        FailKind.NO_ENTITLEMENT -> "このアカウントでは視聴できません"
        FailKind.LOGIN_REQUIRED -> "ログインが必要です"
        FailKind.NOT_FOUND -> "削除または非公開の動画です"
        FailKind.UNSUPPORTED -> "この動画の再生方式には未対応です"
        FailKind.RATE_LIMITED -> "取得が制限されています"
        FailKind.NETWORK -> "読み込めませんでした（通信エラー）"
        FailKind.UNKNOWN_FORMAT -> "想定外の応答でした"
        null -> "再生できませんでした"
    }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.width(820.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(headline, fontSize = TvText.title, fontWeight = FontWeight.Bold)
                Text(title, fontSize = TvText.body, color = Palette.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                err?.message?.let { Text("詳細：$it", fontSize = TvText.small, color = Palette.textDim) }
                if (kind == FailKind.NO_ENTITLEMENT) Text("購入・加入は公式ページで行えます。アプリは自動で購入しません。手続き後に「再試行」で権限を再確認します。", fontSize = TvText.small, color = Palette.textDim)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (kind == FailKind.LOGIN_REQUIRED) TvButton("ログイン", onLogin, Modifier.focusRequester(focus), primary = true)
                    TvButton("再試行", onRetry, if (kind != FailKind.LOGIN_REQUIRED) Modifier.focusRequester(focus) else Modifier, primary = kind != FailKind.LOGIN_REQUIRED)
                    if (err?.canSkip == true) TvButton("この話を飛ばす", onSkip)
                    TvButton("戻る", onBack)
                }
            }
            if (officialUrl != null && kind != FailKind.NETWORK) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCode(officialUrl, size = 160.dp)
                    Text("公式ページ", fontSize = TvText.small, color = Palette.textDim)
                }
            }
        }
    }
}

@Composable
fun BoostConsentDialog(onAccept: () -> Unit, onCancel: () -> Unit) {
    val f = remember { FocusRequester() }
    TvDialog(onDismiss = onCancel, width = 680.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("コメントの増量について", fontSize = TvText.heading, fontWeight = FontWeight.Bold)
            Text(
                "増量は、この動画に過去に実際に投稿されたコメント（過去ログ）を追加で取得し、元の時刻に重ねて表示します。同じコメントを複製して増やすことはしません。\n\n" +
                    "・取得は1秒に1回（サービスの上限の1分60回まで）、目標件数に応じて最大150回までにとどめます。30,000件だと2分ほどかかります\n" +
                    "・取得したコメントは端末に圧縮して保存し、次回はそこから表示します\n" +
                    "・過去ログの取得にはログインやプレミアム会員が必要な場合があります\n" +
                    "・件数が多いと端末の負荷が上がります。画面に同時に出す数は「画面の密度」で別に調整できます\n" +
                    "・取得中も動画はそのまま再生でき、いつでも中止できます",
                fontSize = TvText.small, color = Palette.textDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("増量を使う", onAccept, Modifier.focusRequester(f), primary = true)
                TvButton("取消", onCancel)
            }
        }
    }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { f.requestFocus() } }
}
