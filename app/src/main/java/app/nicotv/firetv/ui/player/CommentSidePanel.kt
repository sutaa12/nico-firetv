package app.nicotv.firetv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.formatTime
import kotlinx.coroutines.delay

/**
 * 映像の右に出すコメント一覧（YTChat TV のチャット欄と同じ方式）。
 * 再生位置（動画内時刻）までのコメントを新しいものが下になるよう並べ、常に最新へ追従する。
 * 流れるコメントとは別に、NG などで隠したものを除いた同じ集合を使う。
 * 操作（NG 登録・時刻へ移動）は、既存の「コメント一覧・NG登録」パネルで行う
 */
@Composable
fun CommentSidePanel(
    comments: List<CommentRecord>,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pos by rememberUpdatedState(positionMs)
    val all by rememberUpdatedState(comments)
    var visible by remember { mutableStateOf<List<CommentRecord>>(emptyList()) }

    // 5 回／秒、再生位置までの直近 150 件を表示対象にする（comments は動画内時刻の順）
    LaunchedEffect(Unit) {
        while (true) {
            val list = all
            val idx = upperBound(list, pos())
            val from = (idx - 150).coerceAtLeast(0)
            val changed = visible.size != idx - from ||
                visible.firstOrNull()?.dedupeKey != list.getOrNull(idx - 1)?.dedupeKey ||
                visible.lastOrNull()?.dedupeKey != list.getOrNull(from)?.dedupeKey
            if (changed) visible = list.subList(from, idx).asReversed().toList()
            delay(200)
        }
    }
    // 常に最新（一番下）へ合わせる。一覧は表示中の項目の位置を保とうとするため、
    // 位置の条件を付けると、多くの新着やシークのあとで追従が外れる
    LaunchedEffect(visible) {
        if (visible.isNotEmpty() && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
            listState.scrollToItem(0)
        }
    }

    Column(modifier.background(Color(0xE6101014))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1A1A20)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("コメント", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("再生位置まで", fontSize = 11.sp, color = Palette.textDim)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) Text("まだコメントはありません", fontSize = 13.sp, color = Palette.textDim, modifier = Modifier.padding(12.dp))
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(visible, key = { it.dedupeKey }) { c -> CommentRow(c) }
            }
        }
    }
}

@Composable
private fun CommentRow(c: CommentRecord) {
    // 白以外の色コメントは左の点で示す（本文を色で描くと黒などが読めないため）
    val dot = c.color.toInt().let { if ((it and 0xFFFFFF) == 0xFFFFFF) null else Color(it or 0xFF000000.toInt()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp, end = 5.dp).size(6.dp).background(dot ?: Color.Transparent, CircleShape))
        val text = remember(c.dedupeKey) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color(0xFF777784), fontSize = 0.8.em)) { append(formatTime(c.vposMs)); append(" ") }
                if (c.isOwner) withStyle(SpanStyle(color = Color(0xFFFFD600), fontWeight = FontWeight.Bold)) { append("投稿者 ") }
                append(c.body.replace('\n', ' ').take(200))
                // ニコるの数は本文と見分けられるよう、ごく小さく淡い色で添える
                if (c.nicoru > 0) withStyle(SpanStyle(color = Color(0x99FF9EB5), fontSize = 0.55.em)) { append("  ♡${c.nicoru}") }
            }
        }
        Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = if (c.fromPastLog) Color(0xFFCFCFD8) else Color(0xFFF0F0F4), modifier = Modifier.weight(1f))
    }
}

/** 動画内時刻の順のリストで、time 以下の最後の位置の次 */
private fun upperBound(list: List<CommentRecord>, time: Long): Int {
    var lo = 0; var hi = list.size
    while (lo < hi) { val m = (lo + hi) ushr 1; if (list[m].vposMs <= time) lo = m + 1 else hi = m }
    return lo
}
