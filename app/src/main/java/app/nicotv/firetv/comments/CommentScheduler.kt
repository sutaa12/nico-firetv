package app.nicotv.firetv.comments

import app.nicotv.firetv.data.CommentPosition
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.CommentSize

class ActiveComment(
    val record: CommentRecord,
    val fontPx: Float,
    /** いちばん長い行の幅 */
    val width: Float,
    /** 上端の y (px) */
    val y: Float,
    val lane: Int,
    val laneSpan: Int,
    /** 改行で分けた各行（1行のコメントは1つ） */
    val lines: List<String> = listOf(record.body),
    /** 行の間隔 (px) */
    val lineHeight: Float = fontPx * CommentScheduler.LINE_HEIGHT,
) {
    val position get() = record.position
}

data class SchedulerConfig(
    val maxOnScreen: Int = 60,
    val danmaku: Boolean = false,
    val scale: Float = 1f,
    val showFixed: Boolean = true,
    val bottomMarginRatio: Float = 0f,
    /** ニコるがこの数以上のコメントは、混雑していても必ず表示する。null なら優先しない */
    val priorityNicoru: Int? = 3,
)

/**
 * 動画内時刻を唯一の基準にしてコメントの出現と配置を決める (03 §6)。
 * 壁時計は使わない。毎フレーム全件を走査せず、時刻順リスト上のポインタと二分探索で対象を絞る。
 * 描画 (Compose) とは分離しているので単体試験できる。
 */
class CommentScheduler(private val measure: (String, Float) -> Float) {
    private var comments: List<CommentRecord> = emptyList()
    private var pointer = 0
    private var lastPos = -1L
    private val active = ArrayList<ActiveComment>()
    private var screenW = 0f
    private var screenH = 0f
    var config = SchedulerConfig()
        private set

    /** 同時描画数の制限で出さなかった件数 (表示用) */
    var skippedByDensity = 0
        private set

    /** 高評価・投稿者コメントとして優先表示した件数 (表示用) */
    var priorityShown = 0
        private set

    val activeComments: List<ActiveComment> get() = active

    fun setConfig(c: SchedulerConfig) {
        if (c == config) return
        val layoutChanged = c.scale != config.scale || c.bottomMarginRatio != config.bottomMarginRatio || c.showFixed != config.showFixed
        config = c
        if (layoutChanged) rebuild(lastPos)
    }

    /**
     * コメント集合を差し替える。既に過ぎた時刻のコメントは今の画面へまとめて流さず、
     * 次に見直したとき (シーク時) に使う。
     */
    fun setComments(list: List<CommentRecord>) {
        if (list === comments) return
        comments = list
        val keep = active.map { it.record.dedupeKey }.toHashSet()
        val allowed = list.asSequence().map { it.dedupeKey }.filter { it in keep }.toHashSet()
        // NG などで消えたものは表示中からも除く
        active.removeAll { it.record.dedupeKey !in allowed }
        pointer = upperBound(lastPos)
        // 取得した分をすぐ画面に反映する: 表示期間が「今」を含む新着コメントは、
        // 本来いるはずの途中の位置から流す（期間を過ぎたものをまとめて流すことはしない）
        if (lastPos >= 0 && screenW > 0f) {
            var i = lowerBound(lastPos - NAKA_MS)
            while (i < pointer) {
                val c = comments[i]
                val dur = if (c.position == CommentPosition.NAKA) NAKA_MS else FIXED_MS
                if (lastPos - c.vposMs < dur && considered.add(c.dedupeKey)) place(c, lastPos)
                i++
            }
        }
    }

    /** 今の表示期間ですでに配置を判断したコメント（途中から足すときに二重に置かない） */
    private val considered = HashSet<String>()

    fun update(posMs: Long, w: Float, h: Float) {
        if (w != screenW || h != screenH) {
            screenW = w; screenH = h
            rebuild(posMs)
            return
        }
        val jumped = lastPos < 0 || posMs < lastPos - 150 || posMs > lastPos + 1_500
        if (jumped) {
            rebuild(posMs)
            return
        }
        expire(posMs)
        while (pointer < comments.size && comments[pointer].vposMs <= posMs) {
            considered.add(comments[pointer].dedupeKey)
            place(comments[pointer], posMs)
            pointer++
        }
        // 覚えておく量を抑える（表示期間を過ぎたものは不要）
        if (considered.size > 20_000) {
            considered.clear()
            // 画面にあるものは判断済みのまま残す（途中から足すときの二重配置を防ぐ）
            active.forEach { considered.add(it.record.dedupeKey) }
        }
        lastPos = posMs
    }

    /** シーク・画面サイズ変更時。移動先で表示期間内のコメントも含めて組み直す */
    private fun rebuild(posMs: Long) {
        active.clear()
        considered.clear()
        resetLanes()
        if (posMs < 0 || screenW <= 0f) { lastPos = posMs; pointer = upperBound(posMs); return }
        var i = lowerBound(posMs - NAKA_MS)
        while (i < comments.size && comments[i].vposMs <= posMs) {
            val c = comments[i]
            val dur = if (c.position == CommentPosition.NAKA) NAKA_MS else FIXED_MS
            if (posMs - c.vposMs < dur) { considered.add(c.dedupeKey); place(c, posMs) }
            i++
        }
        pointer = i
        lastPos = posMs
    }

    private fun expire(posMs: Long) {
        active.removeAll { a ->
            val dur = if (a.position == CommentPosition.NAKA) NAKA_MS else FIXED_MS
            posMs - a.record.vposMs >= dur || posMs < a.record.vposMs
        }
    }

    private fun lowerBound(v: Long): Int {
        var lo = 0; var hi = comments.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (comments[m].vposMs < v) lo = m + 1 else hi = m }
        return lo
    }

    private fun upperBound(v: Long): Int {
        var lo = 0; var hi = comments.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (comments[m].vposMs <= v) lo = m + 1 else hi = m }
        return lo
    }

    val baseFontPx: Float get() = screenH / 15f * config.scale
    val laneHeight: Float get() = baseFontPx * 1.2f

    private fun usableHeight() = screenH * (1f - config.bottomMarginRatio)
    private fun laneCount() = maxOf(1, (usableHeight() / laneHeight).toInt())

    /**
     * 文字の大きさ。公式（HTML5 プレイヤー）の画面あたりの行数（big 8.4、medium 13.1、small 21 行）の比に合わせる。
     * 参考: xpadev-net/niconicomments の html5LineCounts
     */
    fun fontPxOf(size: CommentSize) = when (size) {
        CommentSize.SMALL -> baseFontPx * (13.1f / 21f)
        CommentSize.MEDIUM -> baseFontPx
        CommentSize.BIG -> baseFontPx * (13.1f / 8.4f)
    }

    /**
     * 公式と同じく、行数が多いコメント（big 3行・medium 5行・small 7行以上）は文字を縮める（「ender」があれば縮めない）。
     * 縮めた後の行数は big 16、medium 25.4、small 38 行相当
     */
    private fun lineResize(size: CommentSize, lines: Int): Float = when (size) {
        CommentSize.BIG -> if (lines >= 3) 8.4f / 16f else 1f
        CommentSize.MEDIUM -> if (lines >= 5) 13.1f / 25.4f else 1f
        CommentSize.SMALL -> if (lines >= 7) 21f / 38f else 1f
    }

    /** 流しコメントの x 座標。W から -width まで NAKA_MS かけて移動する */
    fun nakaX(a: ActiveComment, posMs: Long): Float {
        val t = (posMs - a.record.vposMs).coerceAtLeast(0).toFloat() / NAKA_MS
        return screenW - (screenW + a.width) * t
    }

    private fun place(c: CommentRecord, posMs: Long) {
        if (c.body.isBlank()) return
        if (c.position != CommentPosition.NAKA && !config.showFixed) return
        val max = config.maxOnScreen
        // 高評価（ニコる）と投稿者コメントは、上限や混雑に関係なく必ず出す
        val priority = c.isOwner || (config.priorityNicoru?.let { c.nicoru >= it } == true)
        // 混雑時は通常取得を優先し、過去分は残りの余裕に入れる (03 §7)
        if (!priority && (active.size >= max || (c.fromPastLog && active.size >= max * 0.8))) { skippedByDensity++; return }
        val body = if (c.body.length > MAX_CHARS) c.body.take(MAX_CHARS) + "…" else c.body
        // 改行はそのまま行に分ける（コメントアートなど）。行末の空行は除く
        val lines = body.split("\r\n", "\n", "\r").dropLastWhile { it.isEmpty() }.ifEmpty { listOf(body) }
        var font = fontPxOf(c.size) * (if (c.ender) 1f else lineResize(c.size, lines.size))
        var width = lines.maxOf { measure(it, font) }
        // 上・下固定は画面の幅に収まるよう縮める（公式と同じ。「full」なら 16:9 の全幅、無ければ 4:3 相当の幅）
        if (c.position != CommentPosition.NAKA) {
            val range = if (c.full) screenW else screenW * FIXED_RANGE
            if (width > range && width > 0f) { val k = range / width; font *= k; width *= k }
        }
        // 高さが画面に入りきらない長いコメントは、はみ出さないよう全体を縮める
        val maxH = usableHeight()
        val h0 = lines.size * font * LINE_HEIGHT
        if (h0 > maxH && h0 > 0f) { val k = maxH / h0; font *= k; width *= k }
        val span = maxOf(1, kotlin.math.ceil(lines.size * font * LINE_HEIGHT / laneHeight).toInt())
        val lanes = laneCount()
        val lane = findLane(c, width, span, lanes, posMs)
            ?: if (config.danmaku || priority) leastCrowdedLane(c, span, lanes, posMs) else null
        if (lane == null) { skippedByDensity++; return }
        if (priority) priorityShown++
        val y = when (c.position) {
            CommentPosition.SHITA -> usableHeight() - (lane + span) * laneHeight
            else -> lane * laneHeight
        }
        val placed = ActiveComment(c.copy(body = body), font, width, y, lane, span, lines, font * LINE_HEIGHT)
        active += placed
        for (l in lane until minOf(lane + span, lanes)) {
            when (c.position) {
                CommentPosition.NAKA -> nakaLast[l] = placed
                CommentPosition.UE -> ueUntil[l] = c.vposMs + FIXED_MS
                CommentPosition.SHITA -> shitaUntil[l] = c.vposMs + FIXED_MS
            }
        }
    }

    // 段ごとに「最後に置いたコメント」だけを覚える。配置の判定が画面内の件数に比例しなくなり、
    // 数千件を同時に出しても配置計算で止まらない
    private var nakaLast = arrayOfNulls<ActiveComment>(0)
    private var ueUntil = LongArray(0)
    private var shitaUntil = LongArray(0)

    private fun resetLanes() {
        val n = laneCount()
        nakaLast = arrayOfNulls(n)
        ueUntil = LongArray(n) { Long.MIN_VALUE }
        shitaUntil = LongArray(n) { Long.MIN_VALUE }
    }

    private fun laneFree(c: CommentRecord, l: Int, width: Float, posMs: Long): Boolean = when (c.position) {
        CommentPosition.NAKA -> {
            val a = nakaLast[l]
            a == null || posMs - a.record.vposMs >= NAKA_MS || nakaFits(a, width, c.vposMs, posMs)
        }
        CommentPosition.UE -> posMs >= ueUntil[l]
        CommentPosition.SHITA -> posMs >= shitaUntil[l]
    }

    /** 段の混み具合。流しコメントは直前のコメントの末尾がまだ右にあるほど重なりやすい */
    private fun laneCost(c: CommentRecord, l: Int, posMs: Long): Float = when (c.position) {
        CommentPosition.NAKA -> nakaLast[l]?.takeIf { posMs - it.record.vposMs < NAKA_MS }?.let { (nakaX(it, posMs) + it.width).coerceAtLeast(0f) } ?: 0f
        CommentPosition.UE -> (ueUntil[l] - posMs).coerceAtLeast(0).toFloat()
        CommentPosition.SHITA -> (shitaUntil[l] - posMs).coerceAtLeast(0).toFloat()
    }

    private fun findLane(c: CommentRecord, width: Float, span: Int, lanes: Int, posMs: Long): Int? {
        if (span > lanes || nakaLast.size != lanes) { if (nakaLast.size != lanes) resetLanes(); if (span > lanes) return null }
        for (lane in 0..(lanes - span)) {
            var ok = true
            for (l in lane until lane + span) if (!laneFree(c, l, width, posMs)) { ok = false; break }
            if (ok) return lane
        }
        return null
    }

    /** 空きが無いときに、重なりが最も少ない段を選ぶ (弾幕優先・優先コメント用) */
    private fun leastCrowdedLane(c: CommentRecord, span: Int, lanes: Int, posMs: Long): Int? {
        if (span > lanes) return null
        var best = 0
        var bestCost = Float.MAX_VALUE
        for (lane in 0..(lanes - span)) {
            var cost = 0f
            for (l in lane until lane + span) cost += laneCost(c, l, posMs)
            if (cost < bestCost) { bestCost = cost; best = lane }
        }
        return best
    }

    /** 先行コメント a の後ろに、幅 w の新コメントを流しても追い越さないか */
    private fun nakaFits(a: ActiveComment, w: Float, vpos: Long, posMs: Long): Boolean {
        // 先行コメントの末尾が画面右端に入りきっている
        val tail = nakaX(a, posMs) + a.width
        if (tail > screenW - baseFontPx * 0.3f) return false
        // 先行コメントが消える時刻に、新コメントの先頭がまだ画面左端に届いていない
        val remain = (a.record.vposMs + NAKA_MS - vpos).coerceAtLeast(0).toFloat() / NAKA_MS
        val headX = screenW - (screenW + w) * remain
        return headX >= 0f
    }

    companion object {
        const val NAKA_MS = 4_000L
        const val FIXED_MS = 3_000L
        /** 1件の上限。コメントアート（改行の多い長文）を途中で切らない長さにする */
        const val MAX_CHARS = 1_024
        /** 行の高さ（文字の大きさに対する比） */
        const val LINE_HEIGHT = 1.2f
        /** 「full」の無い固定コメントが使える幅（公式の 1920 中 1530 に合わせる） */
        const val FIXED_RANGE = 1530f / 1920f
    }
}
