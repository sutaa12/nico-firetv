package app.nicotv.firetv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.SeriesSnapshot
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.FocusSurface
import app.nicotv.firetv.ui.MenuDialog
import app.nicotv.firetv.ui.MenuItem
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.Thumb
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.formatTime
import app.nicotv.firetv.ui.remembered
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SeriesScreen(app: AppState, entry: Entry, route: Route.Series) {
    val catalog = if (route.provider == app.c.demo.provider) app.c.demo else app.c.nico
    // 保存済みのシリーズがあればすぐ出し、裏で増えた話がないかを小さな確認で調べる
    val cachedSnap = remember(route.id) { catalog.cachedSeries(route.id)?.value }
    val forceNext = entry.keep("forceNext") { java.util.concurrent.atomic.AtomicBoolean(false) }
    val (series, scope) = rememberLoadable(entry, "series") { catalog.series(route.id, force = forceNext.getAndSet(false)) }
    var pageSnapshot by remember { mutableStateOf<SeriesSnapshot?>(null) }
    var pageLoading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    fun refresh() { pageSnapshot = null; forceNext.set(true); series.load(scope, force = true) }
    fun loadPage(page: Int) {
        if (pageLoading) return
        pageLoading = true; pageError = null
        scope.launch {
            try {
                when (val r = catalog.seriesPage(route.id, page)) {
                    is LoadResult.Ok -> pageSnapshot = r.value
                    is LoadResult.Failed -> pageError = "${r.kind.label}（${r.message}）"
                }
            } finally { pageLoading = false }
        }
    }
    val shown: LoadResult<SeriesSnapshot>? = pageSnapshot?.let { LoadResult.Ok(it) } ?: series.result.let { r -> if (r == null || (r is LoadResult.Failed && cachedSnap != null)) cachedSnap?.let { LoadResult.Ok(it) } ?: r else r }
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val mem = entry.focus
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    val snap = (shown as? LoadResult.Ok<SeriesSnapshot>)?.value
    val sp = account.series[key(route.provider, route.id)]

    RestoreFocus(mem, "continue", trigger = snap != null)

    when (val r = shown) {
        null -> StatusBox("シリーズを読み込み中…（戻るで取消）", Modifier.padding(48.dp))
        is LoadResult.Failed -> Column(Modifier.padding(48.dp)) {
            Text(route.title, fontSize = TvText.title, fontWeight = FontWeight.Bold)
            StatusBox("シリーズを${r.kind.label}（${r.message}）", isError = true, onRetry = { refresh() })
        }
        is LoadResult.Ok -> {
            val s = r.value
            val eps = s.episodes
            fun prog(i: Int) = account.progress[key(s.provider, eps[i].video.id)]
            val watchedCount = eps.indices.count { prog(it)?.isWatched == true }
            // 「続きから」は最後に意図して見ていた話 (04 §2)。視聴済みフラグだけで決めない
            val lastIdx = sp?.lastVideoId?.let { id -> eps.indexOfFirst { it.video.id == id } }?.takeIf { it >= 0 }
            // 最後に見ていた話を見終わっていれば次の話、途中・開いただけならその話から
            val continueIdx = lastIdx?.let { i -> if (prog(i)?.isFinished == true) (i + 1).takeIf { it < eps.size } else i }
            val firstUnwatched = eps.indices.firstOrNull { prog(it)?.isWatched != true }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = entry.keep("list") { LazyListState() },
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.title.ifBlank { route.title }, fontSize = TvText.title, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(
                            buildString {
                                append("${s.totalCount ?: eps.size}本")
                                append("  ・  視聴済み $watchedCount / ${eps.size}")
                                if (s.hasMore) append("  ・  一部のみ取得（${eps.size}本まで）")
                                append("  ・  確認 " + SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(s.fetchedAt)))
                                if (series.loading) append("  ・  新しい話を確認中…")
                            },
                            fontSize = TvText.small, color = Palette.textDim,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            if (continueIdx != null) {
                                val p = prog(continueIdx)
                                val label = "続きから：${continueIdx + 1}本目" + if (p?.isInProgress == true) " ${formatTime(p.positionMs)}" else ""
                                TvButton(label, { app.playSeries(s, continueIdx, if (p?.isInProgress == true) p.positionMs else 0) }, Modifier.remembered(mem, "continue"), primary = true, icon = "▶")
                                TvButton("このページから", { app.playSeries(s, 0) }, Modifier.remembered(mem, "fromStart"))
                            } else {
                                TvButton("このページから連続再生", { app.playSeries(s, 0) }, Modifier.remembered(mem, "continue"), primary = true, icon = "▶")
                            }
                            if (firstUnwatched != null && firstUnwatched != continueIdx && firstUnwatched != 0) {
                                TvButton("未視聴から", { app.playSeries(s, firstUnwatched) }, Modifier.remembered(mem, "unwatched"))
                            }
                            TvButton(
                                if (sp?.pinned == true) "固定解除" else "固定",
                                { app.c.store.setSeriesPinned(s.provider, s.id, s.title, eps.firstOrNull()?.video?.thumbnailUrl, sp?.pinned != true) },
                                Modifier.remembered(mem, "pin"),
                            )
                            TvButton("更新", { refresh() }, Modifier.remembered(mem, "refresh"))
                        }
                        Text("「固定」はホームに表示するための端末内の設定で、公式のフォローやマイリストとは別です。「未視聴から」は正式順で最初の未視聴の話から再生します。", fontSize = 13.sp, color = Palette.textDim)
                    }
                }
                itemsIndexed(eps, key = { i, e -> "${e.order}-${e.video.id}-$i" }) { i, e ->
                    val p = prog(i)
                    val isLast = i == lastIdx
                    FocusSurface(
                        onClick = { menuIndex = i },
                        modifier = Modifier.fillMaxWidth().remembered(mem, "ep:$i"),
                        scale = 1.01f,
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${e.order}", fontSize = TvText.body, color = Palette.textDim, modifier = Modifier.width(40.dp))
                            Thumb(e.video.thumbnailUrl, Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(6.dp)), label = "${i + 1}")
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.video.title, fontSize = TvText.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        e.video.durationSec?.let { formatTime(it * 1000L) },
                                        if (e.video.isPaid) "有料・会員限定" else null,
                                        if (isLast) "最後に見た話" else null,
                                    ).joinToString("  ・  "),
                                    fontSize = TvText.small, color = Palette.textDim,
                                )
                            }
                            Text(
                                when {
                                    p?.isWatched == true -> "視聴済み"
                                    p?.isInProgress == true -> formatTime(p.positionMs) + "まで"
                                    p?.isFinished == true -> "終端まで再生"
                                    else -> "未視聴"
                                },
                                fontSize = TvText.small,
                                color = if (p?.isWatched == true) Palette.ok else Palette.textDim,
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pageError?.let { Text(it, color = Palette.warn, fontSize = TvText.small) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (s.firstPage > 1) TvButton("前のページ", { loadPage(s.firstPage - 1) })
                            s.nextPage?.let { page -> TvButton(if (pageLoading) "読み込み中…" else "次のページ", { loadPage(page) }) }
                        }
                    }
                }
            }

            menuIndex?.let { i ->
                val e = eps[i]
                val p = prog(i)
                MenuDialog(
                    e.video.title,
                    buildList {
                        if (p?.isInProgress == true) add(MenuItem("続きから連続再生", formatTime(p.positionMs) + "から") { app.playSeries(s, i, p.positionMs) })
                        add(MenuItem("この話から連続再生", "以降を正式な順番で続けて再生") { app.playSeries(s, i, 0) })
                        add(MenuItem("この話だけ見る", "自動で次の話へ進まない") { app.playSeries(s, i, 0, continuous = false) })
                        add(MenuItem("詳細を見る") { app.navigate(Route.Detail(e.video)) })
                        add(MenuItem(if (p?.isWatched == true) "未視聴に戻す" else "視聴済みにする", "端末内の手動設定。再生履歴は作らない") {
                            app.c.store.setManualWatched(e.video.provider, e.video.id, e.video.title, p?.isWatched != true)
                        })
                    },
                    onDismiss = { menuIndex = null },
                )
            }
        }
    }
}

