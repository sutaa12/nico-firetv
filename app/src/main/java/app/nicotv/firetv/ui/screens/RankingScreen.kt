package app.nicotv.firetv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.RankingSnapshot
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.CardExtra
import app.nicotv.firetv.ui.Chip
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.VideoCard
import app.nicotv.firetv.ui.remembered
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RankingScreen(app: AppState, entry: Entry) {
    val catalog = app.c.catalog
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val genres = catalog.rankingGenres
    val terms = catalog.rankingTerms
    var genre by remember { mutableStateOf(entry.keep("genre") { genres.find { it.id == account.lastRankingGenre } ?: genres.first() }) }
    var term by remember { mutableStateOf(entry.keep("term") { terms.find { it.id == account.lastRankingTerm } ?: terms.find { it.id == "24h" } ?: terms.first() }) }
    val scope = rememberCoroutineScope()
    val condKey = "rk-${catalog.provider}-${genre.id}-${term.id}"
    val forceNext = entry.keep("forceNext") { java.util.concurrent.atomic.AtomicBoolean(false) }
    val loadable = entry.keep(condKey) { Loadable { catalog.ranking(genre, term, 1, force = forceNext.getAndSet(false)) } }
    loadable.load(scope)
    val mem = entry.focus
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }
    val gridState = entry.keep("grid-$condKey") { LazyGridState() }
    val snapshot = (loadable.result as? LoadResult.Ok<RankingSnapshot>)?.value

    // 初回は前回の動画、なければ先頭 (TV-02)
    RestoreFocus(mem, snapshot?.items?.firstOrNull()?.let { "v:" + it.id } ?: "genre:${genre.id}", trigger = snapshot != null)

    fun select(g: app.nicotv.firetv.data.RankingGenre = genre, t: app.nicotv.firetv.data.RankingTerm = term) {
        genre = g; term = t
        entry.memory["genre"] = g; entry.memory["term"] = t
        app.c.store.setRankingPref(g.id, t.id)
    }

    Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 24.dp, top = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ランキング", fontSize = TvText.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            Text(
                snapshot?.let { "取得 " + SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(it.fetchedAt)) + "（公式の順位）" } ?: "",
                fontSize = TvText.small, color = Palette.textDim,
            )
            Spacer(Modifier.weight(1f))
            TvButton("更新", { forceNext.set(true); loadable.load(scope, force = true) }, Modifier.remembered(mem, "refresh"))
        }
        val gRow = entry.keep("genreRow") { LazyListState() }
        LazyRow(state = gRow, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
            items(genres, key = { it.id }) { g ->
                Chip(g.label, g == genre, { select(g = g) }, Modifier.remembered(mem, "genre:${g.id}"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            terms.forEach { t -> Chip(t.label, t == term, { select(t = t) }, Modifier.remembered(mem, "term:${t.id}")) }
        }
        when (val r = loadable.result) {
            null -> StatusBox("読み込み中…")
            is LoadResult.Failed -> StatusBox(
                "この区分のランキングを${r.kind.label}（${r.message}）。他の区分は選べます。",
                isError = true, onRetry = { loadable.load(scope, force = true) },
            )
            is LoadResult.Ok -> {
                if (r.value.items.isEmpty()) StatusBox("この区分には動画がありません（取得は成功）")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp, start = 6.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(r.value.items, key = { it.id }) { v ->
                        VideoCard(
                            v,
                            onClick = { app.navigate(Route.Detail(v)) },
                            onPlay = { app.playAuto(v) },
                            onMenu = { menuFor = v },
                            modifier = Modifier.remembered(mem, "v:" + v.id),
                            width = null,
                            extra = CardExtra(watched = account.progress[key(v.provider, v.id)]?.isWatched == true),
                        )
                    }
                    item(span = { GridItemSpan(4) }) {
                        Text("${r.value.items.size}件を表示", fontSize = TvText.small, color = Palette.textDim)
                    }
                }
            }
        }
    }
    menuFor?.let { v -> VideoMenu(app, v) { menuFor = null } }
}
