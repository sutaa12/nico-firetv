package app.nicotv.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.nicotv.firetv.AuthState
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.ui.player.PlayerScreen
import app.nicotv.firetv.ui.screens.AccountHistoryScreen
import app.nicotv.firetv.ui.screens.DetailScreen
import app.nicotv.firetv.ui.screens.HomeScreen
import app.nicotv.firetv.ui.screens.LibraryScreen
import app.nicotv.firetv.ui.screens.LoginScreen
import app.nicotv.firetv.ui.screens.MylistScreen
import app.nicotv.firetv.ui.screens.OwnerScreen
import app.nicotv.firetv.ui.screens.RankingScreen
import app.nicotv.firetv.ui.screens.SearchScreen
import app.nicotv.firetv.ui.screens.SeriesScreen
import app.nicotv.firetv.ui.screens.SettingsScreen
import kotlinx.coroutines.android.awaitFrame

@Composable
fun NicoTvApp(app: AppState) {
    val entry = app.current
    val route = entry.route
    val navRequesters = remember { TopDest.entries.associateWith { FocusRequester() } }
    var navHasFocus by remember { mutableStateOf(false) }
    val settings by app.c.store.data.collectAsState()
    val saveError by app.c.store.saveError.collectAsState()

    val qualityLookup = remember { QualityLookup(label = { app.c.qualityOf(it) }, probe = { app.c.probeQuality(it) }) }
    androidx.compose.runtime.CompositionLocalProvider(LocalReduceMotion provides settings.settings.reduceMotion, LocalQuality provides qualityLookup) {
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        when (route) {
            is Route.Top -> {
                // 戻る: コンテンツ → 左ナビ → (ホーム以外なら) ホーム → OS へ終了 (02 §6)
                BackHandler {
                    when {
                        !navHasFocus -> runCatching { navRequesters.getValue(route.dest).requestFocus() }
                        route.dest != TopDest.HOME -> app.switchTop(TopDest.HOME)
                        else -> app.exitApp()
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    NavRail(
                        selected = route.dest,
                        requesters = navRequesters,
                        onFocusChange = { navHasFocus = it },
                        onSelect = { app.switchTop(it) },
                        onRight = { entry.focus.restore(null) },
                        demo = settings.settings.dataSource == Provider.DEMO,
                        auth = app.c.auth.collectAsState().value,
                    )
                    // focusRestorer は Compose 1.7 で遅延リストと組み合わせるとクラッシュするため使わず、
                    // 右キーで画面ごとの FocusMemory から戻す
                    Box(Modifier.weight(1f).fillMaxHeight().focusGroup()) {
                        key(entry) {
                            when (route.dest) {
                                TopDest.HOME -> HomeScreen(app, entry)
                                TopDest.RANKING -> RankingScreen(app, entry)
                                TopDest.SEARCH -> SearchScreen(app, entry, initialQuery = null, tagMode = false)
                                TopDest.LIBRARY -> LibraryScreen(app, entry)
                                TopDest.HISTORY -> AccountHistoryScreen(app, entry)
                                TopDest.SETTINGS -> SettingsScreen(app, entry)
                            }
                        }
                    }
                }
            }
            else -> {
                key(entry) {
                    when (route) {
                        is Route.Detail -> { BackHandler { app.back() }; DetailScreen(app, entry, route) }
                        is Route.Series -> { BackHandler { app.back() }; SeriesScreen(app, entry, route) }
                        is Route.Search -> { BackHandler { app.back() }; Box(Modifier.padding(start = 24.dp)) { SearchScreen(app, entry, route.query, route.tag) } }
                        is Route.Player -> PlayerScreen(app, entry, route)
                        is Route.Login -> LoginScreen(app)
                        is Route.Owner -> { BackHandler { app.back() }; OwnerScreen(app, entry, route) }
                        is Route.Mylist -> { BackHandler { app.back() }; MylistScreen(app, entry, route) }
                        is Route.Top -> Unit
                    }
                }
            }
        }

        app.busy?.let { label ->
            TvDialog(onDismiss = { app.cancelBusy() }, width = 520.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label + "…", fontSize = TvText.heading)
                    Text("戻るキーで取り消せます", fontSize = TvText.small, color = Palette.textDim)
                }
            }
        }
        app.error?.let { err -> ErrorDialog(err) { app.error = null } }
        saveError?.let { message ->
            Column(Modifier.align(Alignment.BottomCenter).background(Palette.surfaceHigh).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, fontSize = TvText.small, color = Palette.warn)
                TvButton("保存を再試行", { app.c.store.retrySave() })
            }
        }
    }
    }
}

@Composable
fun ErrorDialog(err: ErrorInfo, onClose: () -> Unit) {
    val closeReq = remember { FocusRequester() }
    TvDialog(onDismiss = onClose, width = 800.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(err.title, fontSize = TvText.title, fontWeight = FontWeight.Bold)
                Text(err.message, fontSize = TvText.body, color = Palette.textDim)
                Spacer(Modifier.height(8.dp))
                TvButton("閉じる", onClose, Modifier.focusRequester(closeReq), primary = true)
            }
            err.officialUrl?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrCode(it)
                    Text("スマホで公式ページを開く", fontSize = TvText.small, color = Palette.textDim)
                }
            }
        }
    }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { closeReq.requestFocus() } }
}

@Composable
private fun NavRail(
    selected: TopDest,
    requesters: Map<TopDest, FocusRequester>,
    onFocusChange: (Boolean) -> Unit,
    onSelect: (TopDest) -> Unit,
    onRight: () -> Boolean,
    demo: Boolean,
    auth: AuthState,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 262.dp else 72.dp, label = "nav")
    // 左ナビに入ったときは、位置的に近い項目ではなく現在の項目を選ぶ
    LaunchedEffect(expanded) {
        if (expanded) runCatching { requesters.getValue(selected).requestFocus() }
    }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(if (expanded) Color(0xFF191920) else Color(0xFF141418))
            .onFocusChanged { expanded = it.hasFocus; onFocusChange(it.hasFocus) }
            .onPreviewKeyEvent {
                // 右でコンテンツ側の前回の部品へ戻る
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && onRight()
            }
            .focusGroup()
            .padding(vertical = 20.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (expanded) "NicoTV" else "N",
            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.accent,
            modifier = Modifier.padding(start = 14.dp, bottom = 12.dp),
        )
        TopDest.entries.forEach { d ->
            val isSel = d == selected
            FocusSurface(
                onClick = { onSelect(d) },
                modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(requesters.getValue(d)),
                containerColor = if (isSel) Palette.accent.copy(alpha = 0.25f) else Color.Transparent,
                focusedColor = Palette.surfaceHigh,
                scale = 1.03f,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.icon, fontSize = 20.sp, color = if (isSel) Palette.accent else Palette.text, modifier = Modifier.width(28.dp))
                    if (expanded) {
                        Spacer(Modifier.width(12.dp))
                        Text(d.label, fontSize = TvText.body, maxLines = 1, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (expanded) {
            Text(
                when {
                    demo -> "デモモード"
                    auth is AuthState.LoggedIn -> auth.nickname + (if (auth.premium) "（プレミアム）" else "")
                    auth is AuthState.Expired -> "ログイン切れ"
                    else -> "ゲスト"
                },
                fontSize = TvText.small, color = Palette.textDim, modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
