package app.nicotv.firetv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.nicotv.firetv.data.CatalogProvider
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.MylistSummary
import app.nicotv.firetv.data.OwnerProfile
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.SeriesSummary
import app.nicotv.firetv.data.UploadSort
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.QueueSource
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.CardExtra
import app.nicotv.firetv.ui.Chip
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.FocusSurface
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.Thumb
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.VideoCard
import app.nicotv.firetv.ui.formatCount
import app.nicotv.firetv.ui.remembered
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class OwnerTab(val label: String) { VIDEOS("投稿動画"), SERIES("シリーズ"), MYLISTS("マイリスト") }

private class VideoListState {
    val items = mutableStateListOf<VideoSummary>()
    var page by mutableStateOf(1)
    var firstPage by mutableStateOf(1)
    var attemptedPage by mutableStateOf(1)
    var hasNext by mutableStateOf(true)
    var total by mutableStateOf<Int?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var job: Job? = null
}

/** 投稿者（ユーザー・チャンネル）のページ。投稿動画・シリーズ・マイリストを見て再生できる */
@Composable
fun OwnerScreen(app: AppState, entry: Entry, route: Route.Owner) {
    val owner = route.owner
    val catalog: CatalogProvider = if (owner.provider == Provider.DEMO) app.c.demo else app.c.nico
    val (profile, _) = rememberLoadable(entry, "profile") { catalog.ownerProfile(owner) }
    val (series, sScope) = rememberLoadable(entry, "series") { catalog.ownerSeries(owner) }
    val (mylists, mScope) = rememberLoadable(entry, "mylists") { catalog.ownerMylists(owner) }
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val mem = entry.focus
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(entry.keep("tab") { OwnerTab.VIDEOS }) }
    var sort by remember { mutableStateOf(entry.keep("sort") { UploadSort.NEWEST }) }
    val videos = entry.keep("videos-${sort.name}") { VideoListState() }
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }
    val p = (profile.result as? LoadResult.Ok<OwnerProfile>)?.value
    val name = p?.ref?.name ?: owner.name ?: "投稿者"
    val icon = p?.ref?.iconUrl ?: owner.iconUrl

    fun loadMore(page: Int = videos.page) {
        if (videos.loading) return
        videos.loading = true
        videos.error = null
        videos.attemptedPage = page
        videos.job = scope.launch {
            try {
                when (val r = catalog.ownerVideos(owner, sort, page)) {
                    is LoadResult.Ok -> {
                        mem.forgetPrefix("v:")
                        videos.items.clear()
                        videos.items.addAll(r.value.items.distinctBy { it.id })
                        videos.firstPage = page
                        videos.total = r.value.totalCount
                        videos.hasNext = r.value.hasNext && r.value.items.isNotEmpty()
                        videos.page = page + 1
                        videos.error = null
                    }
                    is LoadResult.Failed -> {
                        videos.error = "${r.kind.label}（${r.message}）"
                    }
                }
            } finally {
                videos.loading = false
            }
        }
    }

    LaunchedEffect(sort) { if (videos.items.isEmpty() && videos.error == null) loadMore() }
    RestoreFocus(mem, "tab:" + tab.name)
    RestoreFocus(mem, "playAll", trigger = videos.firstPage)

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 28.dp, top = 22.dp)) {
        // 見出し: アイコン・名前・フォロワー数
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Palette.surfaceHigh)) {
                if (icon != null) AsyncImage(model = icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = TvText.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (owner.isChannel) "チャンネル" else "ユーザー",
                        p?.followerCount?.let { "フォロワー " + formatCount(it) },
                        videos.total?.let { "投稿動画 ${it}本" },
                    ).joinToString("  ・  "),
                    fontSize = TvText.small, color = Palette.textDim,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OwnerTab.entries.filter { !(owner.isChannel && it == OwnerTab.MYLISTS) }.forEach { t ->
                Chip(t.label, t == tab, { tab = t; entry.memory["tab"] = t }, Modifier.remembered(mem, "tab:" + t.name))
            }
            if (tab == OwnerTab.VIDEOS && !owner.isChannel) {
                Spacer(Modifier.width(14.dp))
                UploadSort.entries.forEach { s ->
                    Chip(s.label, s == sort, { sort = s; entry.memory["sort"] = s }, Modifier.remembered(mem, "sort:" + s.name))
                }
            }
        }
        val grid = entry.keep("grid-" + tab.name + sort.name) { LazyGridState() }
        when (tab) {
            OwnerTab.VIDEOS -> {
                if (videos.items.isEmpty()) {
                    when {
                        videos.error != null -> StatusBox(
                            "投稿動画を${videos.error}" + if (owner.isChannel) "（チャンネルの動画一覧はログインが必要です）" else "",
                            isError = true, onRetry = { videos.error = null; videos.hasNext = true; loadMore() },
                        )
                        videos.loading || videos.hasNext -> StatusBox("読み込み中…")
                        else -> StatusBox("投稿動画はありません。")
                    }
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(4), state = grid,
                        contentPadding = PaddingValues(6.dp, 8.dp, 6.dp, 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        item(span = { GridItemSpan(4) }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvButton("この順で連続再生", { app.playList(videos.items.toList(), 0, "$name の投稿動画", QueueSource.Uploads(owner, sort), videos.firstPage, videos.page.takeIf { videos.hasNext }) }, Modifier.remembered(mem, "playAll"), icon = "▶")
                            }
                        }
                        itemsIndexed(videos.items, key = { _, v -> v.id }) { i, v ->
                            VideoCard(
                                v, width = null,
                                onClick = { app.navigate(Route.Detail(v)) },
                                onPlay = { app.playAuto(v) },
                                onMenu = { menuFor = v },
                                modifier = Modifier.remembered(mem, "v:" + v.id),
                                extra = CardExtra(watched = account.progress[key(v.provider, v.id)]?.isWatched == true),
                            )
                        }
                        item(span = { GridItemSpan(4) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                videos.error?.let { StatusBox(it, isError = true, onRetry = { loadMore(videos.attemptedPage) }) }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (videos.firstPage > 1) TvButton("前のページ", { loadMore(videos.firstPage - 1) })
                                    if (videos.hasNext) TvButton(if (videos.loading) "読み込み中…" else "次のページ", { loadMore() })
                                }
                            }
                        }
                    }
                }
            }
            OwnerTab.SERIES -> when (val r = series.result) {
                null -> StatusBox("読み込み中…")
                is LoadResult.Failed -> if (owner.isChannel) StatusBox(
                    "チャンネルのシリーズ一覧は、公開されている取得方法がないため表示できません。動画を開くと、その動画のシリーズを表示できます。",
                ) else StatusBox("シリーズを${r.kind.label}（${r.message}）", isError = true, onRetry = { series.load(sScope, force = true) })
                is LoadResult.Ok -> if (r.value.isEmpty()) StatusBox("シリーズはありません。") else LazyVerticalGrid(
                    GridCells.Fixed(4), state = grid,
                    contentPadding = PaddingValues(6.dp, 8.dp, 6.dp, 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(r.value, key = { it.id }) { s ->
                        CollectionCard(s.title, s.thumbnailUrl, s.itemsCount?.let { "${it}本" }, Modifier.remembered(mem, "s:" + s.id)) {
                            app.navigate(Route.Series(s.provider, s.id, s.title))
                        }
                    }
                }
            }
            OwnerTab.MYLISTS -> when (val r = mylists.result) {
                null -> StatusBox("読み込み中…")
                is LoadResult.Failed -> StatusBox("マイリストを${r.kind.label}（${r.message}）", isError = true, onRetry = { mylists.load(mScope, force = true) })
                is LoadResult.Ok -> if (r.value.isEmpty()) StatusBox("公開マイリストはありません。") else LazyVerticalGrid(
                    GridCells.Fixed(4), state = grid,
                    contentPadding = PaddingValues(6.dp, 8.dp, 6.dp, 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(r.value, key = { it.id }) { m ->
                        CollectionCard(m.name, m.thumbnailUrl, m.itemsCount?.let { "${it}件" }, Modifier.remembered(mem, "m:" + m.id)) {
                            app.navigate(Route.Mylist(m.provider, m.id, m.name))
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { v -> VideoMenu(app, v) { menuFor = null } }
}

/** シリーズ・マイリストのカード */
@Composable
fun CollectionCard(title: String, thumb: String?, count: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))) {
                Thumb(thumb, Modifier.fillMaxSize(), label = "一覧")
                count?.let {
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).background(androidx.compose.ui.graphics.Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(it, fontSize = TvText.small, color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
            Text(title, fontSize = TvText.small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp).height(40.dp))
        }
    }
}

/** マイリストの中身。この順で連続再生できる (LIB-04) */
@Composable
fun MylistScreen(app: AppState, entry: Entry, route: Route.Mylist) {
    val catalog: CatalogProvider = if (route.provider == Provider.DEMO) app.c.demo else app.c.nico
    val st = entry.keep("list") { VideoListState() }
    val scope = rememberCoroutineScope()
    val mem = entry.focus
    val grid = entry.keep("grid") { LazyGridState() }
    var title by remember { mutableStateOf(entry.keep("title") { route.name }) }
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)

    fun loadMore(page: Int = st.page) {
        if (st.loading) return
        st.loading = true
        st.error = null
        st.attemptedPage = page
        st.job = scope.launch {
            try {
                when (val r = catalog.mylist(route.id, page)) {
                    is LoadResult.Ok -> {
                        title = r.value.name; entry.memory["title"] = r.value.name
                        mem.forgetPrefix("v:")
                        st.items.clear()
                        st.items.addAll(r.value.items.distinctBy { it.id })
                        st.firstPage = page
                        st.total = r.value.totalCount
                        st.hasNext = r.value.hasNext
                        st.page = page + 1
                    }
                    is LoadResult.Failed -> { st.error = "${r.kind.label}（${r.message}）" }
                }
            } finally { st.loading = false }
        }
    }
    LaunchedEffect(Unit) { if (st.items.isEmpty() && st.error == null) loadMore() }
    RestoreFocus(mem, "playAll", trigger = st.firstPage to st.items.isNotEmpty())

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 28.dp, top = 22.dp)) {
        Text(title, fontSize = TvText.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("マイリスト" + (st.total?.let { "  ・  ${it}件" } ?: ""), fontSize = TvText.small, color = Palette.textDim)
        Spacer(Modifier.height(10.dp))
        when {
            st.items.isEmpty() && st.error != null -> StatusBox("マイリストを${st.error}", isError = true, onRetry = { st.error = null; st.hasNext = true; loadMore() })
            st.items.isEmpty() -> StatusBox(if (st.loading || st.hasNext) "読み込み中…" else "動画はありません。")
            else -> LazyVerticalGrid(
                GridCells.Fixed(4), state = grid,
                contentPadding = PaddingValues(6.dp, 8.dp, 6.dp, 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(span = { GridItemSpan(4) }) {
                    TvButton("このページから連続再生", { app.playList(st.items.toList(), 0, title, QueueSource.Mylist(route.provider, route.id), st.firstPage, st.page.takeIf { st.hasNext }) }, Modifier.remembered(mem, "playAll"), primary = true, icon = "▶")
                }
                itemsIndexed(st.items, key = { _, v -> v.id }) { i, v ->
                    VideoCard(
                        v, width = null,
                        onClick = { app.navigate(Route.Detail(v)) },
                        onPlay = { app.playList(st.items.toList(), i, title, QueueSource.Mylist(route.provider, route.id), st.firstPage, st.page.takeIf { st.hasNext }) },
                        onMenu = { menuFor = v },
                        modifier = Modifier.remembered(mem, "v:" + v.id),
                        extra = CardExtra(watched = account.progress[key(v.provider, v.id)]?.isWatched == true),
                    )
                }
                item(span = { GridItemSpan(4) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        st.error?.let { StatusBox(it, isError = true, onRetry = { loadMore(st.attemptedPage) }) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (st.firstPage > 1) TvButton("前のページ", { loadMore(st.firstPage - 1) })
                            if (st.hasNext) TvButton(if (st.loading) "読み込み中…" else "次のページ", { loadMore() })
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { v -> VideoMenu(app, v) { menuFor = null } }
}
