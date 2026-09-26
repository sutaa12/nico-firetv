package app.nicotv.firetv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.SeriesRef
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.SeriesProgress
import app.nicotv.firetv.data.local.WatchProgress
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.CardExtra
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.FocusSurface
import app.nicotv.firetv.ui.MenuDialog
import app.nicotv.firetv.ui.MenuItem
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.SectionTitle
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.Thumb
import app.nicotv.firetv.ui.TopDest
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.VideoCard
import app.nicotv.firetv.ui.formatTime
import app.nicotv.firetv.ui.remembered

/**
 * ホームの「続き」の候補。途中の動画と、シリーズごとに最後に見ていた話を新しい順に並べる。
 * シリーズは最後に見ていた話が、途中ならその位置から、見終わっていれば次の話から、開いただけならその話の最初から続ける。
 */
fun continueItems(progress: Map<String, WatchProgress>, lastKey: String?): List<WatchProgress> {
    val bySeries = HashMap<String, WatchProgress>()
    val singles = ArrayList<WatchProgress>()
    for (p in progress.values) {
        if (p.seriesId != null) {
            // シリーズは最後に意図して見ていた話だけを出す（途中かどうかに関わらず、いちばん新しいもの）
            val k = p.provider.name + p.seriesId
            val old = bySeries[k]
            if (old == null || old.updatedAt < p.updatedAt) bySeries[k] = p
        } else if (p.isInProgress) singles += p
    }
    return (bySeries.values + singles)
        .sortedWith(compareByDescending<WatchProgress> { key(it.provider, it.videoId) == lastKey }.thenByDescending { it.updatedAt })
        .take(15)
}

@Composable
fun HomeScreen(app: AppState, entry: Entry) {
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val provider = app.c.catalog
    val (ranking, rScope) = rememberLoadable(entry, "ranking-${provider.provider}") {
        provider.ranking(provider.rankingGenres.first(), provider.rankingTerms.first { it.id == "24h" }, 1)
    }
    val listState = entry.keep("list") { LazyListState() }
    val conts = continueItems(account.progress, account.lastSessionVideoKey)
    val pinned = account.series.values.filter { it.pinned }.sortedByDescending { it.updatedAt }
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }
    val mem = entry.focus

    val firstKey = when {
        conts.isNotEmpty() -> "cont:" + key(conts.first().provider, conts.first().videoId)
        else -> (ranking.result as? LoadResult.Ok)?.value?.items?.firstOrNull()?.let { "rank:" + it.id }
    }
    RestoreFocus(mem, firstKey, trigger = firstKey != null)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 36.dp, end = 24.dp, top = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                if (provider.provider == Provider.DEMO) "ホーム（デモモード）" else "ホーム",
                fontSize = TvText.title, fontWeight = FontWeight.Bold,
            )
        }
        if (conts.isNotEmpty()) {
            item(key = "cont") {
                Column {
                    SectionTitle("続きを見る", note = "決定ですぐ再開")
                    val rowState = entry.keep("row-cont") { LazyListState() }
                    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
                        items(conts, key = { key(it.provider, it.videoId) }) { p ->
                            val sub = when {
                                p.isInProgress -> formatTime(p.positionMs) + "から再開"
                                p.isFinished -> "次の話へ進む"
                                else -> "この話を最初から"
                            }
                            VideoCard(
                                video = p.toSummary(),
                                onClick = { app.resume(p) },
                                onPlay = { app.resume(p) },
                                onMenu = { menuFor = p.toSummary() },
                                modifier = Modifier.remembered(mem, "cont:" + key(p.provider, p.videoId)),
                                extra = CardExtra(
                                    progress = p.durationSec?.takeIf { it > 0 }?.let { p.positionMs / (it * 1000f) },
                                    subtitle = listOfNotNull(p.seriesTitle, sub).joinToString(" ・ "),
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (pinned.isNotEmpty()) {
            item(key = "pinned") {
                Column {
                    SectionTitle("固定したシリーズ")
                    val rowState = entry.keep("row-pinned") { LazyListState() }
                    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
                        items(pinned, key = { key(it.provider, it.seriesId) }) { s ->
                            SeriesCard(s, Modifier.remembered(mem, "series:" + s.seriesId)) {
                                app.navigate(Route.Series(s.provider, s.seriesId, s.title))
                            }
                        }
                    }
                }
            }
        }
        item(key = "ranking") {
            Column {
                SectionTitle("ランキング", note = "全ジャンル・24時間")
                when (val r = ranking.result) {
                    null -> StatusBox("ランキングを読み込み中…")
                    is LoadResult.Failed -> StatusBox("ランキングを${r.kind.label}（${r.message}）", isError = true, onRetry = { ranking.load(rScope, force = true) })
                    is LoadResult.Ok -> {
                        val rowState = entry.keep("row-rank") { LazyListState() }
                        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
                            items(r.value.items.take(20), key = { it.id }) { v ->
                                VideoCard(
                                    video = v,
                                    onClick = { app.navigate(Route.Detail(v)) },
                                    onPlay = { app.playAuto(v) },
                                    onMenu = { menuFor = v },
                                    modifier = Modifier.remembered(mem, "rank:" + v.id),
                                    extra = CardExtra(watched = account.progress[key(v.provider, v.id)]?.isWatched == true),
                                )
                            }
                            item(key = "more") {
                                FocusSurface(onClick = { app.switchTop(TopDest.RANKING) }, modifier = Modifier.width(160.dp).height(170.dp).remembered(mem, "rank-more")) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("ランキングを\nもっと見る ›", fontSize = TvText.body)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (account.favorites.isNotEmpty()) {
            item(key = "fav") {
                Column {
                    SectionTitle("お気に入り", note = "この端末のみ（公式マイリストとは別）")
                    val rowState = entry.keep("row-fav") { LazyListState() }
                    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
                        items(account.favorites, key = { key(it.provider, it.videoId) }) { f ->
                            val v = VideoSummary(f.provider, f.videoId, f.title, f.thumbnailUrl)
                            VideoCard(
                                video = v,
                                onClick = { app.navigate(Route.Detail(v)) },
                                onPlay = { app.playAuto(v) },
                                onMenu = { menuFor = v },
                                modifier = Modifier.remembered(mem, "fav:" + f.videoId),
                            )
                        }
                    }
                }
            }
        }
        if (conts.isEmpty() && pinned.isEmpty()) {
            item {
                Text(
                    "操作のヒント：方向キーで選び、決定で開きます。戻るで一つ前へ。左端でさらに左を押すとメニューが開きます。",
                    fontSize = TvText.small, color = Palette.textDim,
                )
            }
        }
    }

    menuFor?.let { v -> VideoMenu(app, v) { menuFor = null } }
}

@Composable
fun SeriesCard(s: SeriesProgress, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.width(200.dp)) {
        Column {
            Thumb(s.thumbnailUrl, Modifier.fillMaxWidth().height(112.dp), label = "シリーズ")
            Text(
                s.title, fontSize = TvText.small, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp).height(40.dp),
            )
        }
    }
}

/** 動画カードの追加操作 (02 §3)。メニューキー・長押し・詳細の「その他」から同じものを開く。 */
@Composable
fun VideoMenu(app: AppState, v: VideoSummary, onDismiss: () -> Unit) {
    val data by app.c.store.data.collectAsState()
    val acc = app.c.store.account(data)
    val p = acc.progress[key(v.provider, v.id)]
    val fav = acc.favorites.any { it.provider == v.provider && it.videoId == v.id }
    val items = buildList {
        add(MenuItem("詳細を見る") { app.navigate(Route.Detail(v)) })
        if (p?.isInProgress == true) add(MenuItem("続きから再生", formatTime(p.positionMs) + "から") { app.resume(p) })
        add(MenuItem("最初から再生", "シリーズに属していれば続きの話も連続再生") { app.play(v, p?.seriesId?.let { SeriesRef(it, p.seriesTitle ?: "シリーズ") }, 0) })
        add(MenuItem("この動画だけ再生", "自動で次の話へ進まない") { app.playSingle(v, 0) })
        if (p?.seriesId != null) add(MenuItem("シリーズを見る", p.seriesTitle) { app.navigate(Route.Series(v.provider, p.seriesId, p.seriesTitle ?: "シリーズ")) })
        else add(MenuItem("シリーズを見る", "この動画が属するシリーズの話一覧を開く") { app.openSeriesOf(v) })
        if (v.ownerId != null) add(MenuItem("投稿者のページ", listOfNotNull(v.ownerName, "投稿動画・シリーズ・マイリスト").joinToString(" ・ ")) { app.openOwner(v) })
        add(MenuItem(if (fav) "お気に入りから外す" else "お気に入りに追加", "この端末のみ") { app.c.store.toggleFavorite(v) })
        if (p != null) add(MenuItem(if (p.isWatched) "未視聴に戻す" else "視聴済みにする", "端末内の手動設定") { app.c.store.setManualWatched(v.provider, v.id, v.title, !p.isWatched) })
        if (p != null) add(MenuItem("この端末の履歴から削除", "公式の視聴履歴は変わりません") { app.c.store.removeProgress(v.provider, v.id) })
    }
    MenuDialog(v.title, items, onDismiss)
}
