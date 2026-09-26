package app.nicotv.firetv.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.nicotv.firetv.AuthState
import app.nicotv.firetv.data.CapState
import app.nicotv.firetv.data.Capabilities
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.local.BoostLevel
import app.nicotv.firetv.data.local.CollisionPolicy
import app.nicotv.firetv.data.local.CommentMode
import app.nicotv.firetv.data.local.Density
import app.nicotv.firetv.data.local.NicoruPriority
import app.nicotv.firetv.data.local.Settings
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.Chip
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.FocusSurface
import app.nicotv.firetv.ui.MenuDialog
import app.nicotv.firetv.ui.MenuItem
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.TvDialog
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.remembered
import kotlinx.coroutines.launch

enum class SettingsSection(val label: String) {
    ACCOUNT("アカウント"), SOURCE("データの取得元"), PLAYBACK("再生"), COMMENT("コメント"), NG("NG（非表示）"),
    STORAGE("保存とデータ"), DISPLAY("表示"), STATUS("対応状況"), DEVICE("端末情報"), HELP("操作ヘルプ"),
}

@Composable
fun SettingsScreen(app: AppState, entry: Entry) {
    var section by remember { mutableStateOf(entry.keep("section") { SettingsSection.ACCOUNT }) }
    val mem = entry.focus
    RestoreFocus(mem, "sec:" + section.name)
    Row(Modifier.fillMaxSize().padding(start = 28.dp, top = 22.dp)) {
        Column(Modifier.width(210.dp).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("設定", fontSize = TvText.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            SettingsSection.entries.forEach { s ->
                FocusSurface(
                    onClick = { section = s; entry.memory["section"] = s },
                    modifier = Modifier.fillMaxWidth().height(38.dp).remembered(mem, "sec:" + s.name),
                    containerColor = if (s == section) Palette.accent.copy(alpha = 0.25f) else Palette.bg,
                    scale = 1.02f,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.label, fontSize = TvText.small, fontWeight = if (s == section) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().focusGroup(),
            contentPadding = PaddingValues(end = 32.dp, top = 6.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (section) {
                SettingsSection.ACCOUNT -> item { AccountSection(app) }
                SettingsSection.SOURCE -> item { SourceSection(app) }
                SettingsSection.PLAYBACK -> item { PlaybackSection(app) }
                SettingsSection.COMMENT -> item { CommentDefaultsSection(app) }
                SettingsSection.NG -> item { NgSection(app) }
                SettingsSection.STORAGE -> item { StorageSection(app) }
                SettingsSection.DISPLAY -> item { DisplaySection(app) }
                SettingsSection.STATUS -> statusSection()
                SettingsSection.DEVICE -> item { DeviceSection() }
                SettingsSection.HELP -> item { HelpSection() }
            }
        }
    }
}

@Composable
private fun Label(title: String, note: String? = null) {
    Column {
        Text(title, fontSize = TvText.body, fontWeight = FontWeight.Bold)
        note?.let { Text(it, fontSize = TvText.small, color = Palette.textDim) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, note: String?, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(title, note)
        // 選択肢が多い場合は折り返す
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { o -> Chip(label(o), o == selected, { onSelect(o) }) }
        }
    }
}

@Composable
private fun settingsState(app: AppState): Settings = app.c.store.data.collectAsState().value.settings

@Composable
private fun AccountSection(app: AppState) {
    val auth by app.c.auth.collectAsState()
    var importScope by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf(false) }
    val s = settingsState(app)
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Label("アカウント")
        if (s.dataSource == Provider.DEMO) Text("現在はデモモードです。ニコニコに接続するには［データの取得元］を切り替えてください。", fontSize = TvText.small, color = Palette.warn)
        Text(
            when (val a = auth) {
                is AuthState.LoggedIn -> "ログイン中：${a.nickname}" + if (a.premium) "（プレミアム会員）" else "（一般会員）"
                is AuthState.Checking -> "確認中…"
                is AuthState.Expired -> "ログインを確認できません：${a.message}"
                AuthState.Guest -> "ゲスト（ログインしていません）"
            },
            fontSize = TvText.body,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("公式画面でログイン", { app.navigate(Route.Login) }, primary = auth !is AuthState.LoggedIn)
            TvButton("状態を再確認", { scope.launch { app.c.refreshAuth() } })
            if (auth is AuthState.LoggedIn || auth is AuthState.Expired) TvButton("ログアウト", { app.c.logout {} })
        }
        if (auth is AuthState.LoggedIn && s.dataSource == Provider.NICO) {
            app.c.store.legacyScopes().forEach { old ->
                TvButton("旧形式の履歴を引き継ぐ：${old.removePrefix("nico:")}", { importScope = old })
            }
            if (imported) Text("引き継ぎました。元の履歴も端末に残しています。", fontSize = TvText.small)
        }
        importScope?.let { old ->
            TvDialog(onDismiss = { importScope = null }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("この履歴はご本人のものですか？", fontSize = TvText.heading)
                    Text("以前は表示名で保存していたため、同じ名前の別アカウントを区別できません。自分の履歴であると確認できる場合だけ引き継いでください。", fontSize = TvText.small)
                    TvButton("自分の履歴として引き継ぐ", { app.c.store.importLegacy(old); imported = true; importScope = null })
                    TvButton("戻る", { importScope = null })
                }
            }
        }
        Text(
            "ログインはニコニコ公式のログイン画面をアプリ内で表示して行います。パスワードや二段階認証はその公式画面に直接入力し、このアプリは保存も送信もしません。" +
                "ログイン後の Cookie はこのアプリ専用の領域に保存されます。ログアウトで消去します。\n" +
                "※ この連携方法は実機未検証です。ログインできても、有料作品の再生は作品ごとの権利に従います。",
            fontSize = TvText.small, color = Palette.textDim,
        )
    }
}

@Composable
private fun SourceSection(app: AppState) {
    val s = settingsState(app)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ChoiceRow(
            "データの取得元", "デモはネット上のテスト用サンプル動画と疑似コメントで、リモコン操作や連続再生を確かめるためのものです。",
            listOf(Provider.NICO, Provider.DEMO), s.dataSource, { if (it == Provider.NICO) "ニコニコ" else "デモ" },
        ) { app.c.setDataSource(it) }
        Text("切り替えると、履歴や設定はそれぞれの取得元・アカウントごとに分けて保存されます。", fontSize = TvText.small, color = Palette.textDim)
    }
}

@Composable
private fun PlaybackSection(app: AppState) {
    val s = settingsState(app)
    val st = app.c.store
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ChoiceRow("シリーズの自動次話", "本編が最後まで終わったあとで次の話へ進みます。エンディングは切りません。", listOf(true, false), s.autoNext, { if (it) "オン" else "オフ" }) { v -> st.updateSettings { it.copy(autoNext = v) } }
        ChoiceRow("次の話までの秒数", null, listOf(3, 5, 10, 15), s.nextCountdownSec, { "${it}秒" }) { v -> st.updateSettings { it.copy(nextCountdownSec = v) } }
        ChoiceRow(
            "画質",
            "「最高画質を優先」は回線の速さで画質を下げず、提供されている最高画質（プレミアム会員は1080pなど）で再生します。回線が遅いと止まりやすくなります。",
            listOf(true, false), s.highestQuality, { if (it) "最高画質を優先" else "回線に合わせる" },
        ) { v -> st.updateSettings { it.copy(highestQuality = v) } }
        ChoiceRow("再生速度", "再生中に変えた速さも、ここに保存されます。", listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f), s.playbackSpeed, { "${it}倍" }) { v -> st.updateSettings { it.copy(playbackSpeed = v) } }
        ChoiceRow(
            "シーク中の縮小画像",
            "左右で位置を選ぶとき、その付近の映像を小さく表示します。ログインしていて、公式が縮小画像を用意している動画だけです。画像は再生が始まって少ししてから取り、動画ごとに端末へ保存します（合計150MBまで）。",
            listOf(true, false), s.seekThumbnails, { if (it) "表示する" else "表示しない" },
        ) { v -> st.updateSettings { it.copy(seekThumbnails = v) } }
        ChoiceRow("続きから再開するときの巻き戻し", "保存された位置そのものは変わりません。", listOf(0, 3, 5), s.resumeRewindSec, { if (it == 0) "なし" else "${it}秒" }) { v -> st.updateSettings { it.copy(resumeRewindSec = v) } }
    }
}

@Composable
private fun CommentDefaultsSection(app: AppState) {
    val s = settingsState(app)
    val st = app.c.store
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("再生中は、決定 → 「コメント」、または上キーでも変更できます。", fontSize = TvText.small, color = Palette.textDim)
        ChoiceRow("表示", "「増量」は実際に投稿された過去のコメントを取得して重ねます。同じコメントの複製はしません。", CommentMode.entries, s.commentMode, { it.label }) { v -> st.updateSettings { it.copy(commentMode = v, boostConsented = it.boostConsented || v == CommentMode.BOOST) } }
        ChoiceRow("増量の取得量", "目標件数です。取得できる過去ログが少なければそこで止まります。", BoostLevel.entries, s.boostLevel, { it.label.substringBefore("（").replace("詳細：", "") }) { v -> st.updateSettings { it.copy(boostLevel = v) } }
        ChoiceRow(
            "画面の密度（同時に表示する上限）",
            "取得量とは別の設定です。目安 fps は Fire TV Cube 第3世代での計測値（弾幕優先）。「読みやすさ優先」では重ならない範囲（約100件）で頭打ちになります。",
            Density.entries, s.density, { it.label + "・" + it.fpsHint },
        ) { v -> st.updateSettings { it.copy(density = v) } }
        ChoiceRow("混雑時", "弾幕優先は重なりを許して、より多く表示します。", CollisionPolicy.entries, s.collision, { it.label }) { v -> st.updateSettings { it.copy(collision = v) } }
        ChoiceRow("高評価コメント", "ニコるの多いコメントと投稿者コメントは、混雑していても必ず表示します。", NicoruPriority.entries, s.nicoruPriority, { it.label }) { v -> st.updateSettings { it.copy(nicoruPriority = v) } }
        ChoiceRow("低評価コメント", "NG共有「中」相当（スコア -4800 以下）を隠します。", listOf(true, false), s.hideLowScore, { if (it) "隠す" else "表示" }) { v -> st.updateSettings { it.copy(hideLowScore = v) } }
        ChoiceRow("文字の大きさ", null, listOf(0.8f, 1.0f, 1.2f, 1.4f), s.commentScale, { "${(it * 100).toInt()}%" }) { v -> st.updateSettings { it.copy(commentScale = v) } }
        ChoiceRow("不透明度", null, listOf(0.5f, 0.7f, 0.9f, 1.0f), s.commentOpacity, { "${(it * 100).toInt()}%" }) { v -> st.updateSettings { it.copy(commentOpacity = v) } }
        ChoiceRow("古いコメントを薄く", "取得したコメントを投稿日時の順に並べ、古いものほど薄く描きます（いちばん古いもので15%）。投稿者コメントはそのままです。", listOf(false, true), s.fadeOldComments, { if (it) "オン" else "オフ" }) { v -> st.updateSettings { it.copy(fadeOldComments = v) } }
        ChoiceRow("上・下固定コメント", null, listOf(true, false), s.showFixedComments, { if (it) "表示" else "非表示" }) { v -> st.updateSettings { it.copy(showFixedComments = v) } }
        ChoiceRow("下端の余白（字幕よけ）", null, listOf(0f, 0.1f, 0.2f), s.bottomMarginRatio, { if (it == 0f) "なし" else "${(it * 100).toInt()}%" }) { v -> st.updateSettings { it.copy(bottomMarginRatio = v) } }
    }
}

@Composable
private fun NgSection(app: AppState) {
    val data by app.c.store.data.collectAsState()
    val ng = app.c.store.account(data).ng
    var target by remember { mutableStateOf<app.nicotv.firetv.data.local.NgRule?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("NG（このアプリのみ）", "登録は再生中の「その他 › コメント一覧」から、コメントを選んで行います。公式のNG設定とは同期しません。")
        if (ng.isEmpty()) Text("登録はありません。", fontSize = TvText.body, color = Palette.textDim)
        ng.forEach { r ->
            FocusSurface(onClick = { target = r }, modifier = Modifier.fillMaxWidth(), scale = 1.01f) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(r.kind.label, fontSize = TvText.small, color = Palette.textDim, modifier = Modifier.width(100.dp))
                    Text(r.value, fontSize = TvText.body)
                }
            }
        }
    }
    target?.let { r ->
        MenuDialog("「${r.value}」", listOf(MenuItem("取消") {}, MenuItem("NGを解除") { app.c.store.removeNg(r) }), onDismiss = { target = null })
    }
}

@Composable
private fun StorageSection(app: AppState) {
    val s = settingsState(app)
    val st = app.c.store
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ChoiceRow("視聴履歴をこの端末に保存", "オフにしても、ニコニコ側の記録や公式の視聴履歴が無くなるわけではありません。", listOf(true, false), s.saveWatchHistory, { if (it) "保存する" else "保存しない" }) { v -> st.updateSettings { it.copy(saveWatchHistory = v) } }
        ChoiceRow("検索履歴を保存", null, listOf(true, false), s.saveSearchHistory, { if (it) "保存する" else "保存しない" }) { v -> st.updateSettings { it.copy(saveSearchHistory = v) } }
        var cacheInfo by remember { mutableStateOf<Pair<Int, Long>?>(null) }
        var metaBytes by remember { mutableStateOf<Long?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            cacheInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.c.commentCache.count() to app.c.commentCache.totalBytes() }
            metaBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.c.metaCache.totalBytes() + app.c.storyboards.totalBytes() }
        }
        ChoiceRow(
            "取得したコメントを保存",
            "動画ごとに圧縮して保存し、次に再生するときはすぐ展開します（不足分だけ追加で取得）。合計300MBを超えると古い順に消え、30日たったものは取り直します。" +
                (cacheInfo?.let { (n, b) -> "\n現在 ${n}本・${"%.1f".format(b / 1_048_576.0)}MB" } ?: ""),
            listOf(true, false), s.cacheComments, { if (it) "保存する" else "保存しない" },
        ) { v -> st.updateSettings { it.copy(cacheComments = v) } }
        Label(
            "動画情報・シリーズ・一覧の保存",
            "更新の少ない情報は端末に保存して、通信を減らしています。動画の説明・タグは12時間、シリーズは10分ごとに小さな確認で増えた話だけを取得、" +
                "ランキングは10分〜2時間、検索は30分、投稿者の情報は15分〜1日、画質は無期限で使い回します。合計64MBを超えると古い順に消えます。シーク用の縮小画像は別に150MBまで保存します。" +
                (metaBytes?.let { "\n現在 ${"%.1f".format(it / 1_048_576.0)}MB" } ?: ""),
        )
        Label("消去", "いずれもこの端末のデータだけが対象です。")
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("視聴履歴を消す", { confirm = "history" })
            TvButton("検索履歴を消す", { confirm = "search" })
            TvButton("コメントの保存を消す", { confirm = "comments" })
            TvButton("動画情報の保存を消す", { confirm = "meta" })
            TvButton("すべて初期化", { confirm = "all" })
        }
    }
    confirm?.let { c ->
        MenuDialog(
            when (c) {
                "history" -> "この端末の視聴履歴を消しますか？（公式の履歴は変わりません）"
                "search" -> "検索履歴を消しますか？"
                "comments" -> "保存したコメントをすべて消しますか？（次に再生するときに取り直します）"
                "meta" -> "保存した動画情報・シリーズ・一覧・シーク用の縮小画像を消しますか？（次に開くときに取り直します）"
                else -> "設定・履歴・NG・お気に入りをすべて初期化しますか？（ログイン状態は変わりません）"
            },
            listOf(
                MenuItem("取消") {},
                MenuItem("消す", "元に戻せません") {
                    when (c) {
                        "history" -> st.clearHistory()
                        "search" -> st.clearSearch()
                        "comments" -> app.c.ioScope.launch { app.c.commentCache.clear() }
                        "meta" -> app.c.ioScope.launch { app.c.metaCache.clear(); app.c.storyboards.clear() }
                        else -> st.clearAll()
                    }
                },
            ),
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun DisplaySection(app: AppState) {
    val s = settingsState(app)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ChoiceRow("動きを減らす", "フォーカス時の拡大などを抑えます。", listOf(false, true), s.reduceMotion, { if (it) "オン" else "オフ" }) { v -> app.c.store.updateSettings { it.copy(reduceMotion = v) } }
        ChoiceRow(
            "サムネイルの画質表示",
            "「見た動画だけ」は詳細や再生で一度開いた動画の最高画質を、通信を増やさずに表示します。" +
                "「表示中の動画を調べる」は画面に出たサムネイルごとに動画情報を取得します（ログインせずに取得しますが、通信が増え、再生数に数えられる可能性があります）。",
            listOf(false, true), s.probeQuality, { if (it) "表示中の動画を調べる" else "見た動画だけ" },
        ) { v -> app.c.store.updateSettings { it.copy(probeQuality = v) } }
    }
}

private fun LazyListScope.statusSection() {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("対応状況", "確認日 ${Capabilities.CHECKED_AT}。「実機未検証」は実装済みでも Fire TV とニコニコの実環境で動作確認していない項目です。")
        }
    }
    items(Capabilities.all) { c ->
        val color = when (c.state) {
            CapState.LOCAL_OK, CapState.VERIFIED_API -> Palette.ok
            CapState.UNVERIFIED -> Palette.warn
            CapState.OFFICIAL_ONLY -> Palette.accent
            CapState.NOT_IMPLEMENTED -> Palette.textDim
        }
        FocusSurface(onClick = {}, modifier = Modifier.fillMaxWidth(), scale = 1.01f) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, fontSize = TvText.small, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        c.state.label, fontSize = 13.sp, color = color, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(c.ids + "　" + c.note, fontSize = 13.sp, color = Palette.textDim)
            }
        }
    }
}

@Composable
private fun DeviceSection() {
    val ctx = LocalContext.current
    val conf = LocalConfiguration.current
    val density = LocalDensity.current
    val pkg = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
    val metrics = ctx.resources.displayMetrics
    val lines = listOf(
        "機種" to "${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）",
        "OS" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        "Fire OS 判定" to if (Build.MODEL.startsWith("AFT")) "Fire TV 端末" else "Fire TV 以外（エミュレータ等）",
        "画面" to "${metrics.widthPixels}×${metrics.heightPixels}px ／ 論理 ${conf.screenWidthDp}×${conf.screenHeightDp}dp（倍率 ${density.density}）",
        "アプリ" to "NicoTV $pkg",
        "空き容量" to "%.1f GB".format(ctx.filesDir.usableSpace / 1e9),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("端末情報", "不具合の報告時にこの画面を撮影してください。ログイン情報は表示しません。")
        lines.forEach { (k, v) ->
            FocusSurface(onClick = {}, modifier = Modifier.fillMaxWidth(), scale = 1.01f) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(k, fontSize = TvText.small, color = Palette.textDim, modifier = Modifier.width(140.dp))
                    Text(v, fontSize = TvText.small)
                }
            }
        }
    }
}

@Composable
private fun HelpSection() {
    val rows = listOf(
        "方向キー" to "項目を選ぶ。左端でさらに左を押すとメニュー",
        "決定" to "開く・実行する。再生中は操作バーを表示",
        "戻る" to "一つ前へ。再生中は パネル → 操作バー → 再生終了 の順",
        "再生中 左・右" to "シーク候補を表示（決定で移動、戻るで取消）",
        "再生中 上" to "コメント設定",
        "再生中 下" to "操作バー（時間軸）",
        "再生／一時停止キー" to "すぐに再生・停止（無くても決定で操作可能）",
        "早送り・巻戻しキー" to "シーク候補を表示",
        "メニューキー（≡）" to "一覧では追加操作、再生中は「その他」。決定の長押しでも同じ",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("操作ヘルプ", "付属リモコンの方向キー・決定・戻るだけで、すべての主要操作ができます。")
        rows.forEach { (k, v) ->
            FocusSurface(onClick = {}, modifier = Modifier.fillMaxWidth(), scale = 1.01f) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(k, fontSize = TvText.small, fontWeight = FontWeight.Bold, modifier = Modifier.width(200.dp))
                    Text(v, fontSize = TvText.small, color = Palette.textDim)
                }
            }
        }
    }
}

