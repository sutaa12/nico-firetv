package app.nicotv.firetv.ui.player

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import app.nicotv.firetv.DebugOverrides
import app.nicotv.firetv.R
import app.nicotv.firetv.comments.AgeFade
import app.nicotv.firetv.comments.CommentScheduler
import app.nicotv.firetv.comments.SchedulerConfig
import app.nicotv.firetv.data.CommentPosition
import app.nicotv.firetv.data.CommentRecord
import kotlinx.coroutines.android.awaitFrame

/**
 * コメント専用の軽量な文字描画 (03 §7)。
 * 1行の短い文字列なので、汎用のテキスト組版ではなく Android の drawText（グリフキャッシュ）を直接使う。
 * 文字は BIZ UDPゴシック Bold、絵文字は同梱の Noto Color Emoji（Unicode 18 まで）で描く。
 * Fire OS 7 (Android 9) 標準の絵文字フォントは古く、🫱🫲 など新しい絵文字が表示できないため。
 * Android 9 には独自フォントの代替フォント指定がないので、文字列を書記素ごとに分けて描き分ける。
 */
private class CommentPainter(typeface: Typeface, emojiTypeface: Typeface?) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; style = Paint.Style.FILL }
    /** 薄くしたコメントの本体用。縁取りの上を「置き換え」で塗るので、縁取りが透けて中抜けに見えない */
    val fillReplace = Paint(fill).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    val emoji: Paint? = emojiTypeface?.let { tf -> Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = tf } }

    /** 文字サイズ 1px あたりの ascent（上端から基準線まで） */
    private val ascentPerPx: Float = Paint(fill).apply { textSize = 100f }.fontMetrics.let { -it.ascent / 100f }

    /** 描き分けの単位。emoji=true は絵文字フォントで描く */
    class Run(val text: String, val emoji: Boolean, val offset: Float)
    class Shaped(val runs: List<Run>, val width: Float) {
        val plain: Boolean get() = runs.size == 1 && !runs[0].emoji
    }

    /** 同じ本文・大きさは一度だけ分割・計測する */
    private val shapes = object : LinkedHashMap<String, Shaped>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Shaped>?) = size > 4_000
    }

    fun shape(text: String, px: Float): Shaped = shapes.getOrPut("${px.toInt()}|$text") {
        fill.textSize = px
        val e = emoji
        if (e == null || !mayContainEmoji(text)) return@getOrPut Shaped(listOf(Run(text, false, 0f)), fill.measureText(text))
        e.textSize = px * EMOJI_SCALE
        // 書記素（見た目の1文字。ZWJ でつながった絵文字も1つ）ごとに、絵文字かどうかで描き分ける
        val parts = ArrayList<Pair<String, Boolean>>()
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            val g = text.substring(start, end)
            val isEmoji = isEmojiCluster(g) && e.hasGlyph(g)
            if (parts.isNotEmpty() && parts.last().second == isEmoji) parts[parts.lastIndex] = (parts.last().first + g) to isEmoji
            else parts += g to isEmoji
            start = end
            end = it.next()
        }
        var x = 0f
        val runs = parts.map { (t, isEmoji) ->
            val r = Run(t, isEmoji, x)
            x += if (isEmoji) e.measureText(t) else fill.measureText(t)
            r
        }
        Shaped(runs, x)
    }

    fun width(text: String, px: Float): Float = shape(text, px).width

    fun baseline(topY: Float, px: Float) = topY + ascentPerPx * px * 1.05f

    companion object {
        /** 絵文字は文字より大きく見えるので少し小さく描く */
        const val EMOJI_SCALE = 0.92f

        fun mayContainEmoji(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                if (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0xFE0F || cp == 0x200D || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF) return true
                i += Character.charCount(cp)
            }
            return false
        }

        fun isEmojiCluster(g: String): Boolean {
            val cp = g.codePointAt(0)
            // 記号類は、絵文字表示の指定 (U+FE0F) がある場合だけ絵文字にする（↑ や ☆ などは文字のまま）
            return cp >= 0x1F000 || g.contains('\uFE0F') || g.contains('\u200D') || ((cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF) && g.length > 1)
        }
    }
}

/**
 * 映像の上に重ねるコメント層。位置はプレイヤーが報告するメディア時刻だけを使う (03 §6)。
 * 一時停止中は時刻が進まないので、文字もその場で止まる。
 */
@Composable
fun CommentLayer(
    comments: List<CommentRecord>,
    config: SchedulerConfig,
    opacity: Float,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
    /** 古いコメントほど薄く描く（最低 15%） */
    fadeOld: Boolean = false,
    onStats: (active: Int, skipped: Int, priority: Int) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val painter = remember {
        val tf = runCatching { ResourcesCompat.getFont(context, R.font.biz_udp_gothic_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val emojiTf = runCatching { Typeface.createFromAsset(context.assets, "NotoColorEmoji.ttf") }.getOrNull()
        CommentPainter(tf, emojiTf)
    }
    val scheduler = remember(painter) { CommentScheduler { text, px -> painter.width(text, px) } }
    var frame by remember { mutableLongStateOf(0L) }
    val perf = remember { PerfMeter() }
    val pos by rememberUpdatedState(positionMs)
    val stats by rememberUpdatedState(onStats)

    val fade = remember(comments, fadeOld) { if (fadeOld) AgeFade(comments) else null }

    scheduler.setConfig(config)
    scheduler.setComments(comments)

    LaunchedEffect(scheduler) {
        var n = 0
        while (true) {
            awaitFrame()
            frame++
            if (++n % 30 == 0) stats(scheduler.activeComments.size, scheduler.skippedByDensity, scheduler.priorityShown)
        }
    }

    // 不透明度は層全体にまとめて掛ける。文字ごとに半透明にすると縁取りが透けて中抜けに見えるため
    Canvas(
        modifier.fillMaxSize().graphicsLayer {
            alpha = opacity
            // 不透明度 100% のときは合成層を使わない（全画面の中間バッファは毎フレームの負荷になる）
            // 古いコメントを薄くするときは、本体を「置き換え」で塗るため必ず合成層に描く（下の映像やUIを消さないように）
            compositingStrategy = if (fade == null && (opacity >= 0.999f || DebugOverrides.noOffscreen)) CompositingStrategy.Auto else CompositingStrategy.Offscreen
        },
    ) {
        @Suppress("UNUSED_EXPRESSION") frame // フレームごとに再描画
        val t0 = System.nanoTime()
        val p = pos()
        scheduler.update(p, size.width, size.height)
        // 非常に混雑しているときは、負荷の高いぼかし影を省いて縁取りだけにする (03 §7)
        val lightweight = scheduler.activeComments.size > 150 || DebugOverrides.noHalo
        val canvas = drawContext.canvas.nativeCanvas
        val fillP = painter.fill
        val strokeP = painter.stroke
        val replaceP = painter.fillReplace
        for (a in scheduler.activeComments) {
            val x = when (a.position) {
                CommentPosition.NAKA -> scheduler.nakaX(a, p)
                else -> (size.width - a.width) / 2f
            }
            if (x > size.width || x + a.width < 0f) continue
            val edge = if (a.record.color == 0xFF000000) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            val k = fade?.alpha(a.record) ?: 1f
            val faded = k < 0.995f
            // 1) 縁取り。混雑していなければ同じ描画でぼかした暗い影（ハロー）も付ける
            strokeP.textSize = a.fontPx
            strokeP.strokeWidth = a.fontPx * 0.08f
            strokeP.color = withAlpha(edge, k)
            if (lightweight) strokeP.clearShadowLayer()
            else strokeP.setShadowLayer(a.fontPx * 0.22f, 0f, a.fontPx * 0.04f, (edge and 0x00FFFFFF) or ((0xD0 * k).toInt() shl 24))
            val bodyP = if (faded) replaceP else fillP
            bodyP.textSize = a.fontPx
            bodyP.color = withAlpha(a.record.color.toInt(), k)
            // 改行のあるコメントは行ごとに描く。固定コメントは行ごとに中央へ（公式と同じく各行を中央寄せ）
            for ((li, line) in a.lines.withIndex()) {
                if (line.isEmpty()) continue
                val baseY = painter.baseline(a.y + li * a.lineHeight, a.fontPx)
                val shaped = painter.shape(line, a.fontPx)
                val lx = if (a.position == CommentPosition.NAKA) x else (size.width - shaped.width) / 2f
                if (shaped.plain) {
                    canvas.drawText(line, lx, baseY, strokeP)
                    // 2) 本体。縁取りの内側を完全に隠す（薄くするときは縁取りの上を置き換えて塗る）
                    canvas.drawText(line, lx, baseY, bodyP)
                } else {
                    // 絵文字を含む場合は、文字の部分だけ縁取りし、絵文字はカラー絵文字フォントでそのまま描く
                    for (r in shaped.runs) if (!r.emoji) canvas.drawText(r.text, lx + r.offset, baseY, strokeP)
                    for (r in shaped.runs) {
                        if (r.emoji) painter.emoji?.let { ep ->
                            ep.textSize = a.fontPx * CommentPainter.EMOJI_SCALE
                            ep.alpha = (255 * k).toInt()
                            canvas.drawText(r.text, lx + r.offset, baseY, ep)
                        }
                        else canvas.drawText(r.text, lx + r.offset, baseY, bodyP)
                    }
                }
            }
        }
        if (DebugOverrides.perfLog) perf.record(System.nanoTime() - t0, scheduler.activeComments.size, p)
    }
}

private fun withAlpha(argb: Int, k: Float): Int =
    if (k >= 0.995f) argb else (argb and 0x00FFFFFF) or ((((argb ushr 24) and 0xFF) * k).toInt() shl 24)

/** 負荷試験用: コメント層の1フレームあたりの処理時間（UIスレッドでの配置計算＋描画命令の記録）を5秒ごとにログへ出す */
private class PerfMeter {
    private val samples = ArrayList<Long>(512)
    private var maxActive = 0
    private var last = System.currentTimeMillis()

    fun record(nanos: Long, active: Int, posMs: Long) {
        samples += nanos
        if (active > maxActive) maxActive = active
        val now = System.currentTimeMillis()
        if (now - last < 5_000) return
        val sorted = samples.sorted()
        fun pct(q: Double) = sorted[((sorted.size - 1) * q).toInt()] / 1e6
        android.util.Log.i(
            "NicoTVPerf",
            "pos=%.1fs frames=%d fps=%.1f comment_ms p50=%.2f p95=%.2f max=%.2f activeMax=%d".format(
                posMs / 1000.0, sorted.size, sorted.size * 1000.0 / (now - last), pct(0.5), pct(0.95), sorted.last() / 1e6, maxActive,
            ),
        )
        samples.clear(); maxActive = 0; last = now
    }
}
