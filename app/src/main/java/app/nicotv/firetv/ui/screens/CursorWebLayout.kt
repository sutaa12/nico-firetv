package app.nicotv.firetv.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * WebView の上に疑似マウスカーソルを重ねるレイアウト。
 * 公式ログインページなど、方向キーでボタンを選べない Web ページをリモコンで操作するために使う。
 *
 * - 方向キー: カーソル移動（押し続けると加速）
 * - 決定: カーソル位置をタップ（押している間はタッチ中なので長押しもできる）
 * - 画面の端でさらに押す: ページをスクロール
 * - 早送り・巻戻し: 大きくスクロール
 * - 戻る: 扱わない（キーボードを閉じる／前のページ／画面を閉じる は呼び出し側）
 */
@SuppressLint("ViewConstructor")
class CursorWebLayout(context: Context, val webView: WebView) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var cx = -1f
    private var cy = -1f
    private var downTime = 0L
    private var pressed = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val pressFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x663FA9F5; style = Paint.Style.FILL }

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cx < 0) { cx = w / 2f; cy = h / 2f }
        cx = cx.coerceIn(0f, w - 1f); cy = cy.coerceIn(0f, h - 1f)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (cx < 0) return
        val s = density
        if (pressed) canvas.drawCircle(cx, cy, 16 * s, pressFill)
        // 矢印カーソル（先端がクリック位置）
        val p = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy + 22 * s)
            lineTo(cx + 6 * s, cy + 17 * s)
            lineTo(cx + 10 * s, cy + 26 * s)
            lineTo(cx + 14 * s, cy + 24 * s)
            lineTo(cx + 10 * s, cy + 15 * s)
            lineTo(cx + 17 * s, cy + 15 * s)
            close()
        }
        canvas.drawPath(p, fill)
        canvas.drawPath(p, stroke)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) move(event.keyCode, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // 文字入力中の Enter は Web ページ側（送信など）へ渡す
                if (event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && webView.hasFocus() && isEditingText()) {
                    return super.dispatchKeyEvent(event)
                }
                if (down && event.repeatCount == 0) touch(MotionEvent.ACTION_DOWN)
                else if (!down) touch(MotionEvent.ACTION_UP)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) webView.scrollBy(0, height * 3 / 4); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) webView.scrollBy(0, -height * 3 / 4); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private var editing = false
    private fun isEditingText() = editing

    private fun move(code: Int, repeat: Int) {
        // 押し続けると加速する
        val step = density * when {
            repeat > 25 -> 36f
            repeat > 12 -> 22f
            repeat > 4 -> 12f
            else -> 6f
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = 2f
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cx -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> cx += step
            KeyEvent.KEYCODE_DPAD_UP -> cy -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> cy += step
        }
        // 端を越えた分はページのスクロールに回す
        val scrollStep = (step * 3).toInt()
        if (cy < margin) { cy = margin; webView.scrollBy(0, -scrollStep) }
        if (cy > h - margin) { cy = h - margin; webView.scrollBy(0, scrollStep) }
        if (cx < margin) { cx = margin; webView.scrollBy(-scrollStep, 0) }
        if (cx > w - margin) { cx = w - margin; webView.scrollBy(scrollStep, 0) }
        hover()
        invalidate()
    }

    /** ホバー表示のあるページのためにマウス移動も伝える */
    private fun hover() {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        webView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun touch(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) { downTime = now; pressed = true } else pressed = false
        if (action == MotionEvent.ACTION_UP && downTime == 0L) return
        val ev = MotionEvent.obtain(downTime, now, action, cx, cy, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(ev)
        ev.recycle()
        if (action == MotionEvent.ACTION_UP) {
            downTime = 0L
            // タップ後に文字入力欄が選ばれたかを調べ、キーボードを出しやすくする
            webView.requestFocus()
            webView.evaluateJavascript(
                "(function(){var e=document.activeElement;return !!e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable);})()",
            ) { r -> editing = r == "true" }
        }
        invalidate()
    }
}
