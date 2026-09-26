package app.nicotv.firetv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import app.nicotv.firetv.AuthState
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.CardExtra
import app.nicotv.firetv.ui.Chip
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.MenuDialog
import app.nicotv.firetv.ui.MenuItem
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.VideoCard
import app.nicotv.firetv.ui.formatTime
import app.nicotv.firetv.ui.remembered
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LibTab(val label: String) {
    HISTORY("この端末の視聴履歴"), MYLISTS("マイリスト"), SERIES("シリーズ"), FAVORITES("お気に入り")
}

@Composable
fun LibraryScreen(app: AppState, entry: Entry) {
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val auth by app.c.auth.collectAsState()
    var tab by remember { mutableStateOf(entry.keep("tab") { LibTab.HISTORY }) }
    val mem = entry.focus
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }

    RestoreFocus(mem, "tab:" + tab.name)

    Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 24.dp, top = 22.dp)) {
        Text("ライブラリ", fontSize = TvText.title, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LibTab.entries.forEach { t ->
                Chip(t.label, t == tab, { tab = t; entry.memory["tab"] = t }, Modifier.remembered(mem, "tab:" + t.name))
            }
            if (tab == LibTab.HISTORY && account.progress.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                TvButton("消去", { confirmClear = true }, Modifier.remembered(mem, "clear"))
            }
        }
        Text(
            when (tab) {
                LibTab.HISTORY -> "この端末で再生した記録です。アカウントの履歴は左メニューの［視聴履歴］にあります。"
                LibTab.MYLISTS -> "アカウントのマイリストと「あとで見る」です（非公開のものも含む）。選ぶと中身を見て、そこから連続再生できます。"
                LibTab.SERIES -> "固定したシリーズと、途中まで見たシリーズです（この端末のみ）。"
                LibTab.FAVORITES -> "この端末だけのお気に入りです。公式のマイリストとは別です。"
            },
            fontSize = TvText.small, color = Palette.textDim, modifier = Modifier.padding(bottom = 4.dp),
        )
        val grid = entry.keep("grid-" + tab.name) { LazyGridState() }
        when (tab) {
            LibTab.HISTORY -> {
                val list = account.progress.values.sortedByDescending { it.updatedAt }
                if (list.isEmpty()) StatusBox("まだ視聴履歴はありません。") else
                    LazyVerticalGrid(GridCells.Fixed(4), state = grid, contentPadding = PaddingValues(6.dp, 10.dp, 6.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(list, key = { key(it.provider, it.videoId) }) { p ->
                            val v = p.toSummary()
                            VideoCard(
                                v, width = null,
                                onClick = { app.navigate(Route.Detail(v)) },
                                onPlay = { app.resume(p) },
                                onMenu = { menuFor = v },
                                modifier = Modifier.remembered(mem, "h:" + p.videoId),
                                extra = CardExtra(
                                    progress = p.durationSec?.takeIf { it > 0 }?.let { p.positionMs / (it * 1000f) },
                                    watched = p.isWatched,
                                    subtitle = fmt.format(Date(p.updatedAt)) + "  " + if (p.isInProgress) formatTime(p.positionMs) + "まで" else "",
                                ),
                            )
                        }
                    }
            }
            LibTab.MYLISTS -> {
                if (auth !is app.nicotv.firetv.AuthState.LoggedIn) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Text("自分のマイリストを見るにはログインが必要です。", fontSize = TvText.body)
                        TvButton("公式画面でログイン", { app.navigate(Route.Login) }, Modifier.remembered(mem, "login"), primary = true)
                    }
                } else {
                    val (mylists, mScope) = rememberLoadable(entry, "my-mylists") { app.c.nico.myMylists() }
                    when (val r = mylists.result) {
                        null -> StatusBox("マイリストを読み込み中…")
                        is LoadResult.Failed -> StatusBox("マイリストを${r.kind.label}（${r.message}）", isError = true, onRetry = { mylists.load(mScope, force = true) })
                        is LoadResult.Ok -> LazyVerticalGrid(GridCells.Fixed(4), state = grid, contentPadding = PaddingValues(6.dp, 10.dp, 6.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            items(r.value, key = { it.id }) { m ->
                                CollectionCard(m.name, m.thumbnailUrl, m.itemsCount?.let { "${it}件" }, Modifier.remembered(mem, "my:" + m.id)) {
                                    app.navigate(Route.Mylist(m.provider, m.id, m.name))
                                }
                            }
                        }
                    }
                }
            }
            LibTab.SERIES -> {
                val list = account.series.values.filter { it.pinned || it.lastVideoId != null }.sortedByDescending { it.updatedAt }
                if (list.isEmpty()) StatusBox("固定・視聴したシリーズはまだありません。シリーズ画面の「ホームに固定」で追加できます。") else
                    LazyVerticalGrid(GridCells.Fixed(4), state = grid, contentPadding = PaddingValues(6.dp, 10.dp, 6.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(list, key = { key(it.provider, it.seriesId) }) { s ->
                            SeriesCard(s.copy(title = (if (s.pinned) "📌 " else "") + s.title), Modifier.remembered(mem, "s:" + s.seriesId)) {
                                app.navigate(Route.Series(s.provider, s.seriesId, s.title))
                            }
                        }
                    }
            }
            LibTab.FAVORITES -> {
                if (account.favorites.isEmpty()) StatusBox("お気に入りはありません。動画でメニューキー（または決定の長押し）→「お気に入りに追加」。") else
                    LazyVerticalGrid(GridCells.Fixed(4), state = grid, contentPadding = PaddingValues(6.dp, 10.dp, 6.dp, 40.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(account.favorites, key = { key(it.provider, it.videoId) }) { f ->
                            val v = VideoSummary(f.provider, f.videoId, f.title, f.thumbnailUrl)
                            VideoCard(v, width = null, onClick = { app.navigate(Route.Detail(v)) }, onPlay = { app.playAuto(v) }, onMenu = { menuFor = v }, modifier = Modifier.remembered(mem, "f:" + f.videoId))
                        }
                    }
            }
        }
    }

    menuFor?.let { v -> VideoMenu(app, v) { menuFor = null } }
    if (confirmClear) {
        // 不可逆操作の確認。既定のフォーカスは取消 (TV-15)
        MenuDialog(
            "この端末の視聴履歴（${account.progress.size}件）を消しますか？\n公式の視聴履歴は変わりません。固定したシリーズは残ります。",
            listOf(
                MenuItem("取消") {},
                MenuItem("消す", "元に戻せません") { app.c.store.clearHistory() },
            ),
            onDismiss = { confirmClear = false },
        )
    }
}
