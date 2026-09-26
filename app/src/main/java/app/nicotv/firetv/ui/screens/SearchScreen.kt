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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.SearchSort
import app.nicotv.firetv.data.SearchTarget
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.CardExtra
import app.nicotv.firetv.ui.Chip
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.FocusSurface
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.VideoCard
import app.nicotv.firetv.ui.formatCount
import app.nicotv.firetv.ui.remembered
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/** ID・公式URLの認識 (SRC-03)。未知のURLは開かない。 */
sealed interface OpenTarget {
    data class Video(val id: String) : OpenTarget
    data class Series(val id: String) : OpenTarget
}

fun detectOpenTarget(q: String): OpenTarget? {
    val s = q.trim()
    Regex("^(sm|so|nm)\\d+$").find(s)?.let { return OpenTarget.Video(it.value) }
    Regex("^https?://(www\\.|sp\\.)?nicovideo\\.jp/watch/((sm|so|nm)\\d+)").find(s)?.let { return OpenTarget.Video(it.groupValues[2]) }
    Regex("^https?://nico\\.ms/((sm|so|nm)\\d+)").find(s)?.let { return OpenTarget.Video(it.groupValues[1]) }
    Regex("^https?://(www\\.)?nicovideo\\.jp/(user/\\d+/)?series/(\\d+)").find(s)?.let { return OpenTarget.Series(it.groupValues[3]) }
    return null
}

private class SearchResults {
    val items = mutableStateListOf<VideoSummary>()
    var total by mutableStateOf<Long?>(null)
    var nextOffset by mutableStateOf<Int?>(0)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var job: Job? = null
}

@Composable
fun SearchScreen(app: AppState, entry: Entry, initialQuery: String?, tagMode: Boolean) {
    val catalog = app.c.catalog
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val scope = rememberCoroutineScope()
    val mem = entry.focus
    var query by remember { mutableStateOf(entry.keep("q") { initialQuery ?: "" }) }
    var target by remember { mutableStateOf(entry.keep("target") { if (tagMode) SearchTarget.TAG else SearchTarget.KEYWORD }) }
    var sort by remember { mutableStateOf(entry.keep("sort") { SearchSort.POPULAR }) }
    var editing by remember { mutableStateOf(false) }
    val results = entry.keep("results") { SearchResults() }
    val gridState = entry.keep("grid") { LazyGridState() }
    val fieldRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var menuFor by remember { mutableStateOf<VideoSummary?>(null) }

    fun loadMore() {
        val off = results.nextOffset ?: return
        if (results.loading || query.isBlank()) return
        results.loading = true
        results.job = scope.launch {
            try {
                when (val r = catalog.search(query.trim(), target, sort, off)) {
                    is LoadResult.Ok -> {
                        val known = results.items.map { it.id }.toHashSet()
                        results.items.addAll(r.value.items.filter { known.add(it.id) })
                        results.total = r.value.totalCount
                        results.nextOffset = r.value.nextOffset
                        results.error = null
                    }
                    is LoadResult.Failed -> results.error = "${r.kind.label}（${r.message}）"
                }
            } finally {
                results.loading = false
            }
        }
    }

    fun runSearch() {
        entry.memory["q"] = query; entry.memory["target"] = target; entry.memory["sort"] = sort
        results.job?.cancel()
        results.items.clear(); results.total = null; results.nextOffset = 0; results.error = null; results.loading = false
        if (query.isBlank()) return
        app.c.store.addSearch(query.trim())
        loadMore()
    }

    // タグから来た場合はすぐ検索する
    LaunchedEffect(Unit) {
        if (initialQuery != null && results.items.isEmpty() && results.total == null) runSearch()
    }
    RestoreFocus(mem, if (results.items.isNotEmpty()) "r:" + results.items.first().id else "field")

    LaunchedEffect(editing) {
        if (editing) {
            entry.memory["wasEditing"] = true
            awaitFrame(); runCatching { fieldRequester.requestFocus() }; keyboard?.show()
        } else if (entry.memory["wasEditing"] == true) {
            // 入力を閉じたら入力欄へフォーカスを戻す (フォーカスの迷子を防ぐ)
            entry.memory["wasEditing"] = false
            awaitFrame(); runCatching { mem.requester("field").requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 24.dp, top = 22.dp)) {
        Text("検索", fontSize = TvText.title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // テキスト欄は決定で編集開始。方向キーで通過しただけではキーボードを出さない
            Box(Modifier.weight(1f)) {
                if (editing) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = TvText.body),
                        cursorBrush = SolidColor(Palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { editing = false; keyboard?.hide(); runSearch() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldRequester)
                            .onFocusChanged { if (!it.isFocused && editing) editing = false }
                            .background(Palette.surfaceHigh, RoundedCornerShape(24.dp))
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    FocusSurface(
                        onClick = { editing = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp).remembered(mem, "field"),
                        containerColor = Palette.surfaceHigh,
                        shape = RoundedCornerShape(24.dp),
                        scale = 1.02f,
                    ) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                            Text(
                                query.ifBlank { "決定で入力（キーワード・タグ・動画ID・URL）" },
                                fontSize = TvText.body,
                                color = if (query.isBlank()) Palette.textDim else Palette.text,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            TvButton("検索", { runSearch() }, Modifier.remembered(mem, "go"), primary = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchTarget.entries.forEach { t -> Chip(t.label, t == target, { target = t; runSearch() }, Modifier.remembered(mem, "t:" + t.name)) }
            Spacer(Modifier.width(12.dp))
            SearchSort.entries.forEach { s -> Chip(s.label, s == sort, { sort = s; runSearch() }, Modifier.remembered(mem, "s:" + s.name)) }
        }
        detectOpenTarget(query)?.let { t ->
            Spacer(Modifier.height(10.dp))
            when (t) {
                is OpenTarget.Video -> TvButton("動画 ${t.id} を開く", {
                    app.navigate(Route.Detail(VideoSummary(Provider.NICO, t.id, t.id)))
                }, Modifier.remembered(mem, "open"))
                is OpenTarget.Series -> TvButton("シリーズ ${t.id} を開く", {
                    app.navigate(Route.Series(Provider.NICO, t.id, "シリーズ"))
                }, Modifier.remembered(mem, "open"))
            }
        }
        if (results.items.isEmpty() && results.total == null && !results.loading && account.searchHistory.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("検索履歴", fontSize = TvText.heading, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                TvButton("履歴をすべて消す", { app.c.store.clearSearch() }, Modifier.remembered(mem, "clearHist"))
            }
            val hRow = entry.keep("hist") { LazyListState() }
            LazyRow(state = hRow, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
                items(account.searchHistory, key = { it }) { h ->
                    Chip(h, false, { query = h; runSearch() }, Modifier.remembered(mem, "h:$h"))
                }
            }
            Text("検索履歴を保存しない設定は［設定 › 保存とデータ］にあります。", fontSize = TvText.small, color = Palette.textDim)
        }
        results.error?.let { StatusBox("検索結果を$it", isError = true, onRetry = { loadMore() }) }
        if (results.total != null || results.items.isNotEmpty()) {
            Text(
                "${formatCount(results.total)}件" + if (catalog.provider == Provider.NICO) "（スナップショット検索：定期更新のため最新ではありません）" else "",
                fontSize = TvText.small, color = Palette.textDim, modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (results.total == 0L) StatusBox("該当する動画はありませんでした")
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = gridState,
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp, start = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(results.items, key = { _, v -> v.id }) { i, v ->
                VideoCard(
                    v,
                    onClick = { app.navigate(Route.Detail(v)) },
                    onPlay = { app.playAuto(v) },
                    onMenu = { menuFor = v },
                    width = null,
                    modifier = Modifier.remembered(mem, "r:" + v.id).onFocusChanged {
                        // 末尾付近にフォーカスしたら次のページを読む (SRC-10)
                        if (it.isFocused && i >= results.items.size - 8) loadMore()
                    },
                    extra = CardExtra(watched = account.progress[key(v.provider, v.id)]?.isWatched == true),
                )
            }
            if (results.loading) item(span = { GridItemSpan(4) }) { Text("読み込み中…", fontSize = TvText.small, color = Palette.textDim) }
        }
    }
    menuFor?.let { v -> VideoMenu(app, v) { menuFor = null } }
}
