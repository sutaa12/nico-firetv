package app.nicotv.firetv.ui.screens

import app.nicotv.firetv.data.local.WatchProgress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.nicotv.firetv.AuthState
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.data.nico.OfficialHistoryItem
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.CardExtra
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.TopDest
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.VideoCard
import app.nicotv.firetv.ui.formatTime
import app.nicotv.firetv.ui.remembered
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class HistoryState {
    val items = mutableStateListOf<OfficialHistoryItem>()
    var cursor by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(true)
    var total by mutableStateOf<Int?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var job: Job? = null
}

/** 一覧の1行。公式の履歴とこの端末の記録のどちらか（両方あれば両方） */
private class HistoryRow(val video: VideoSummary, val at: Long, val official: OfficialHistoryItem?, val local: WatchProgress?)

/**
 * アカウントの視聴履歴（ニコニコ側に記録された履歴・読込のみ）に、このアプリで見た動画（この端末の記録）を
 * 日時順に混ぜて出す。アプリでの再生が公式の履歴に載らない場合でも、見た話がここに残る。
 * 再生はこの端末に記録した位置から、シリーズの話ならシリーズとして続ける。
 */
@Composable
fun AccountHistoryScreen(app: AppState, entry: Entry) {
    val auth by app.c.auth.collectAsState()
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val scope = rememberCoroutineScope()
    val mem = entry.focus
    val st = entry.keep("history") { HistoryState() }
    val grid = entry.keep("grid") { LazyGridState() }
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }
    val demo = data.settings.dataSource == Provider.DEMO

    fun loadMore(reset: Boolean = false) {
        if (reset) { st.job?.cancel(); st.items.clear(); st.cursor = null; st.hasMore = true; st.error = null; st.loading = false; st.loaded = false }
        if (!st.hasMore || st.loading) return
        val cursor = st.cursor
        st.loading = true
        st.job = scope.launch {
            try {
                when (val r = app.c.nico.officialHistory(cursor)) {
                    is LoadResult.Ok -> {
                        val known = st.items.map { it.video.id }.toHashSet()
                        st.items.addAll(r.value.items.filter { known.add(it.video.id) })
                        st.total = r.value.totalCount
                        st.cursor = r.value.nextCursor
                        st.hasMore = r.value.nextCursor != null
                        st.error = null
                    }
                    is LoadResult.Failed -> st.error = "${r.kind.label}（${r.message}）"
                }
            } finally {
                st.loading = false
                st.loaded = true
            }
        }
    }

    val loggedIn = auth is AuthState.LoggedIn && !demo
    LaunchedEffect(loggedIn) { if (loggedIn && !st.loaded && !st.loading) loadMore() }
    // 公式の履歴を読んだ範囲（いちばん古い日時）までは、この端末の記録も混ぜる。すべて読んだら全部混ぜる
    val rows = remember(st.items.size, st.hasMore, account.progress) {
        val byId = LinkedHashMap<String, HistoryRow>()
        for (h in st.items) {
            val lp = account.progress[key(h.video.provider, h.video.id)]
            byId[h.video.id] = HistoryRow(h.video, maxOf(h.lastViewedAt ?: 0L, lp?.updatedAt ?: 0L), h, lp)
        }
        val oldest = st.items.mapNotNull { it.lastViewedAt }.minOrNull()
        for (p in account.progress.values) {
            if (p.provider != Provider.NICO || byId.containsKey(p.videoId)) continue
            if (st.hasMore && oldest != null && p.updatedAt < oldest) continue
            byId[p.videoId] = HistoryRow(p.toSummary(), p.updatedAt, null, p)
        }
        byId.values.sortedByDescending { it.at }
    }
    RestoreFocus(mem, rows.firstOrNull()?.let { "h:" + it.video.id } ?: "refresh", trigger = rows.isNotEmpty())

    Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 24.dp, top = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("視聴履歴", fontSize = TvText.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(
                if (loggedIn) "アカウントの履歴" + (st.total?.let { "（${it}件）" } ?: "") + "＋このアプリで見た動画" else "",
                fontSize = TvText.small, color = Palette.textDim,
            )
            Spacer(Modifier.weight(1f))
            if (loggedIn) TvButton("更新", { loadMore(reset = true) }, Modifier.remembered(mem, "refresh"))
        }
        Spacer(Modifier.width(8.dp))
        when {
            demo -> StatusBox("デモモードではアカウントの履歴はありません。この端末の履歴は［ライブラリ］で見られます。")
            !loggedIn -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                Text("アカウントの視聴履歴を見るにはログインが必要です。", fontSize = TvText.body)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("公式画面でログイン", { app.navigate(Route.Login) }, Modifier.remembered(mem, "refresh"), primary = true)
                    TvButton("この端末の履歴（ライブラリ）", { app.switchTop(TopDest.LIBRARY) })
                }
            }
            st.error != null && rows.isEmpty() -> StatusBox("視聴履歴を${st.error}", isError = true, onRetry = { loadMore(reset = true) })
            st.loaded && rows.isEmpty() -> StatusBox("視聴履歴はありません。")
            rows.isEmpty() -> StatusBox("読み込み中…")
            else -> LazyVerticalGrid(
                GridCells.Fixed(4), state = grid,
                contentPadding = PaddingValues(6.dp, 12.dp, 6.dp, 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(rows, key = { _, r -> r.video.id }) { i, r ->
                    val v = r.video
                    val lp = r.local
                    // 再生位置はこの端末の記録を優先し（最後に見た位置）、無ければ公式が返した位置を使う
                    val posMs = lp?.takeIf { it.isInProgress }?.positionMs ?: r.official?.positionSec?.takeIf { it > 0 }?.let { it * 1000L }
                    VideoCard(
                        v, width = null,
                        onClick = { app.navigate(Route.Detail(v)) },
                        // この端末の記録があれば、その位置から（シリーズの話はシリーズとして）続ける
                        onPlay = { if (lp != null && lp.seriesId != null) app.resume(lp) else app.playAuto(v, posMs ?: 0) },
                        onMenu = { menuFor = v },
                        modifier = Modifier.remembered(mem, "h:" + v.id).onFocusChanged {
                            if (it.isFocused && i >= rows.size - 8) loadMore()
                        },
                        extra = CardExtra(
                            progress = if (posMs != null && v.durationSec != null && v.durationSec > 0) posMs / (v.durationSec * 1000f)
                            else if (lp?.isFinished == true) 1f else null,
                            subtitle = listOfNotNull(
                                fmt.format(Date(r.at)) + " 視聴",
                                lp?.seriesTitle,
                                when {
                                    posMs != null -> formatTime(posMs) + "まで"
                                    lp?.isFinished == true -> "最後まで"
                                    else -> null
                                },
                                if (r.official == null) "この端末" else null,
                            ).joinToString("  "),
                        ),
                    )
                }
                if (st.loading) item(span = { GridItemSpan(4) }) { Text("読み込み中…", fontSize = TvText.small, color = Palette.textDim) }
                st.error?.let { e -> item(span = { GridItemSpan(4) }) { StatusBox("続きを$e", isError = true, onRetry = { loadMore() }) } }
            }
        }
    }
    menuFor?.let { v -> VideoMenu(app, v) { menuFor = null } }
}
