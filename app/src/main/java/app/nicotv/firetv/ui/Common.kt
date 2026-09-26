package app.nicotv.firetv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import app.nicotv.firetv.data.VideoSummary
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.android.awaitFrame

/** サムネイルの画質表示。label は既知の最高画質、probe は（設定でオンなら）調べる */
class QualityLookup(val label: (VideoSummary) -> String?, val probe: suspend (VideoSummary) -> Unit)
val LocalQuality = androidx.compose.runtime.staticCompositionLocalOf<QualityLookup?> { null }

/** 「動きを減らす」設定。フォーカス時の拡大を止める */
val LocalReduceMotion = androidx.compose.runtime.staticCompositionLocalOf { false }

object Palette {
    val bg = Color(0xFF111114)
    val surface = Color(0xFF1E1E24)
    val surfaceHigh = Color(0xFF2A2A33)
    val focus = Color(0xFFFFFFFF)
    val accent = Color(0xFF3FA9F5)
    val text = Color(0xFFF2F2F5)
    val textDim = Color(0xFFB4B4C0)
    val warn = Color(0xFFFFB74D)
    val bad = Color(0xFFEF5350)
    val ok = Color(0xFF66BB6A)
}

@Composable
fun NicoTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            surface = Palette.surface,
            background = Palette.bg,
            onSurface = Palette.text,
            onBackground = Palette.text,
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Palette.text, content = content)
    }
}

// ---------- 文字サイズ ----------
// Fire TV の 1080p は論理 960×540dp。仕様の初期案 (本文20〜24sp) では一覧に収まらないため、
// 本文18sp・補足15sp から実機で調整する (02 §9 は検証用の案)
object TvText {
    val title = 26.sp
    val heading = 21.sp
    val body = 18.sp
    val small = 15.sp
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatCount(n: Long?): String = when {
    n == null -> "—"
    n >= 100_000_000 -> "%.1f億".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f万".format(n / 10_000.0)
    else -> "%,d".format(n)
}

/**
 * 画面ごとのフォーカス記憶。戻ったときに同じ部品へ戻す (02 §8)。
 * BackStackEntry に持たせて画面の寿命より長く保持する。
 */
class FocusMemory {
    var lastKey: String? = null
    private val requesters = HashMap<String, FocusRequester>()
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    /** ページを置き換えたとき、表示されなくなった動画の参照を解放する。 */
    fun forgetPrefix(prefix: String) {
        requesters.keys.removeAll { it.startsWith(prefix) }
        if (lastKey?.startsWith(prefix) == true) lastKey = null
    }

    /** 記憶した部品、なければ fallback にフォーカスする */
    fun restore(fallbackKey: String?): Boolean {
        val keys = listOfNotNull(lastKey, fallbackKey)
        for (k in keys) {
            val r = requesters[k] ?: continue
            if (runCatching { r.requestFocus() }.isSuccess) return true
        }
        return false
    }
}

fun Modifier.remembered(mem: FocusMemory, key: String): Modifier =
    this.focusRequester(mem.requester(key)).onFocusChanged { if (it.isFocused) mem.lastKey = key }

/** 最初の描画後にフォーカスを戻す */
@Composable
fun RestoreFocus(mem: FocusMemory, fallbackKey: String?, trigger: Any? = Unit) {
    LaunchedEffect(trigger) {
        repeat(4) {
            awaitFrame()
            if (mem.restore(fallbackKey)) return@LaunchedEffect
        }
    }
}

fun Modifier.onKey(key: Key, action: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.key == key && it.type == KeyEventType.KeyDown) { action(); true } else false
}

// ---------- 部品 ----------

@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    containerColor: Color = Palette.surface,
    focusedColor: Color = Palette.surfaceHigh,
    scale: Float = 1.04f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = focusedColor,
            contentColor = Palette.text,
            focusedContentColor = Palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (LocalReduceMotion.current) 1f else scale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Palette.focus), shape = shape),
        ),
    ) { content() }
}

/** 押せるボタン。dim=true は「理由を見る」ための選択可能な無効表示 (02 §8)。 */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    dim: Boolean = false,
    icon: String? = null,
    /** 横に多く並べる行では左右の余白を詰める */
    compact: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        containerColor = if (primary) Palette.accent.copy(alpha = 0.35f) else Palette.surfaceHigh,
        focusedColor = if (primary) Palette.accent else Color(0xFF45455A),
        scale = 1.06f,
    ) {
        Box(Modifier.padding(horizontal = if (compact) 14.dp else 22.dp).height(48.dp), contentAlignment = Alignment.Center) {
            Text(
                (if (icon != null) "$icon  " else "") + label,
                fontSize = TvText.body,
                fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
                color = if (dim) Palette.textDim else Palette.text,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = if (selected) Palette.accent.copy(alpha = 0.45f) else Palette.surface,
        focusedColor = if (selected) Palette.accent else Palette.surfaceHigh,
        scale = 1.05f,
    ) {
        Box(Modifier.padding(horizontal = 16.dp).height(40.dp), contentAlignment = Alignment.Center) {
            Text((if (selected) "✓ " else "") + label, fontSize = TvText.small, maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, note: String? = null) {
    Row(modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = TvText.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        if (note != null) {
            Spacer(Modifier.width(12.dp))
            Text(note, fontSize = TvText.small, color = Palette.textDim)
        }
    }
}

@Composable
fun Thumb(url: String?, modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier.background(Color(0xFF2B2B35)), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(label ?: "▶", fontSize = 28.sp, color = Palette.textDim)
        }
    }
}

data class CardExtra(
    val progress: Float? = null,
    val watched: Boolean = false,
    val badge: String? = null,
    val subtitle: String? = null,
)

@Composable
fun VideoCard(
    video: VideoSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** null なら親の幅いっぱい (グリッド用) */
    width: Dp? = 200.dp,
    extra: CardExtra = CardExtra(),
    onMenu: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        onLongClick = onMenu,
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Menu -> { onMenu?.invoke(); onMenu != null }
                    Key.MediaPlayPause, Key.MediaPlay -> { onPlay?.invoke(); onPlay != null }
                    else -> false
                }
            },
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))) {
                Thumb(video.thumbnailUrl, Modifier.fillMaxSize(), label = if (video.provider.name == "DEMO") "DEMO" else null)
                video.rank?.let {
                    Box(Modifier.align(Alignment.TopStart).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("${it}位", fontSize = TvText.small, fontWeight = FontWeight.Bold, color = if (it <= 3) Palette.warn else Palette.text)
                    }
                }
                val lookup = LocalQuality.current
                val quality = lookup?.label?.invoke(video)
                if (lookup != null && quality == null) LaunchedEffect(video.id) { lookup.probe(video) }
                Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 最高画質をごく小さく（例: 1080p）
                    if (quality != null) {
                        Box(Modifier.background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            Text(quality, fontSize = 10.sp, color = Color(0xFFB8E0FF))
                        }
                        Spacer(Modifier.width(3.dp))
                    }
                    video.durationSec?.let {
                        Box(Modifier.background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(formatTime(it * 1000L), fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
                val badge = extra.badge ?: if (video.isPaid) "有料/会員" else null
                if (badge != null || extra.watched) {
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(if (extra.watched) Palette.ok.copy(alpha = 0.9f) else Palette.warn.copy(alpha = 0.9f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(if (extra.watched) "視聴済み" else badge!!, fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                // 投稿者（アイコン＋名前）を小さく左下に重ねる
                if (video.ownerName != null) {
                    Row(
                        Modifier.align(Alignment.BottomStart)
                            .padding(start = 6.dp, bottom = if (extra.progress != null) 9.dp else 6.dp, end = 96.dp)
                            .background(Color(0xB3000000), RoundedCornerShape(10.dp))
                            .padding(start = 2.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (video.ownerIconUrl != null) {
                            AsyncImage(
                                model = video.ownerIconUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF555560)),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(video.ownerName, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                extra.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(5.dp).background(Palette.bad))
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    video.title,
                    fontSize = TvText.small,
                    maxLines = 2,
                    lineHeight = 20.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(42.dp),
                    color = Palette.text,
                )
                Text(
                    extra.subtitle ?: listOfNotNull(
                        video.viewCount?.let { "再生 " + formatCount(it) },
                        video.commentCount?.let { "コメ " + formatCount(it) },
                    ).joinToString("  "),
                    fontSize = 13.sp,
                    color = if (focused) Palette.text else Palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 投稿日時をごく小さく
                formatPosted(video.registeredAt)?.let {
                    Text("投稿 $it", fontSize = 10.sp, color = Palette.textDim, maxLines = 1)
                }
            }
        }
    }
}

private val isoIn = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
private val postedOut = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.JAPAN).apply {
    timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
}
private val postedCache = HashMap<String, String?>()

/** 投稿日時 (ISO 8601) を「2026/09/24 19:00」（日本時間）にする。読めなければ null */
fun formatPosted(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return postedCache.getOrPut(iso) { runCatching { isoIn.parse(iso)?.let { postedOut.format(it) } }.getOrNull() }
}

/** 投稿日時 (ISO 8601) をミリ秒にする。読めなければ null */
fun parsePostedMs(iso: String?): Long? = if (iso.isNullOrBlank()) null else runCatching { synchronized(isoIn) { isoIn.parse(iso)?.time } }.getOrNull()

/** ミリ秒の時刻を「2026/09/24」（日本時間）にする */
fun formatDate(ms: Long): String = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.JAPAN).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo") }.format(java.util.Date(ms))

/** 読込中・失敗・空の状態表示 (02 §10)。失敗は再試行を選べる。 */
@Composable
fun StatusBox(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onRetry: (() -> Unit)? = null,
    retryRequester: FocusRequester? = null,
) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, fontSize = TvText.body, color = if (isError) Palette.warn else Palette.textDim)
        if (onRetry != null) {
            TvButton("再試行", onRetry, modifier = if (retryRequester != null) Modifier.focusRequester(retryRequester) else Modifier)
        }
    }
}

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 180.dp) {
    val bmp = remember(content) {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height) b.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        b.asImageBitmap()
    }
    Image(bmp, contentDescription = "QRコード", modifier = modifier.size(size).background(Color.White).padding(8.dp))
}

/** 確認・説明用ダイアログ。背面へ入力を漏らさない (02 §8)。 */
@Composable
fun TvDialog(onDismiss: () -> Unit, width: Dp = 640.dp, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .width(width)
                .background(Palette.surface, RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .padding(28.dp),
        ) { content() }
    }
}

/** 選択肢メニュー (追加操作・その他)。先頭の項目にフォーカスする。 */
data class MenuItem(val label: String, val note: String? = null, val action: () -> Unit)

@Composable
fun MenuDialog(title: String, items: List<MenuItem>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(onDismiss, width = 520.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = TvText.heading, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { i, item ->
                FocusSurface(
                    onClick = { onDismiss(); item.action() },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    containerColor = Palette.surfaceHigh,
                    focusedColor = Color(0xFF45455A),
                    scale = 1.02f,
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text(item.label, fontSize = TvText.body)
                        item.note?.let { Text(it, fontSize = TvText.small, color = Palette.textDim) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
}

/** フォーカスできる説明文 (長い説明を方向キーで読むため) */
@Composable
fun FocusableText(text: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(if (focused) 2.dp else 0.dp, if (focused) Palette.focus else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(text, fontSize = TvText.small, color = Palette.textDim, maxLines = if (focused) 30 else 4, overflow = TextOverflow.Ellipsis)
    }
}
