package app.nicotv.firetv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.nicotv.firetv.data.Entitlement
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.SeriesRef
import app.nicotv.firetv.data.VideoDetail
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.local.key
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.Chip
import app.nicotv.firetv.ui.Entry
import app.nicotv.firetv.ui.FocusableText
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.RestoreFocus
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.StatusBox
import app.nicotv.firetv.ui.Thumb
import app.nicotv.firetv.ui.TvButton
import app.nicotv.firetv.ui.TvText
import app.nicotv.firetv.ui.formatCount
import app.nicotv.firetv.ui.formatPosted
import app.nicotv.firetv.ui.formatTime
import app.nicotv.firetv.ui.remembered

/**
 * 動画詳細。サムネイルを押したらすぐ開き、一覧で分かっている情報（タイトル・サムネイル・投稿者・長さ・件数）を先に出す。
 * 説明・タグ・視聴条件（動画情報）とシリーズ（視聴ページ）は裏で同時に取得して、届いたところから埋める。
 * ボタンの位置は変えないので、読み込みが終わってもフォーカスが動かない。
 */
@Composable
fun DetailScreen(app: AppState, entry: Entry, route: Route.Detail) {
    val v0 = route.video
    val catalog = if (v0.provider == app.c.demo.provider) app.c.demo else app.c.nico
    // 保存済みの動画情報があればすぐ出す。説明・タグ・シリーズはほとんど変わらないので、
    // 保存して12時間以内なら通信しない（視聴できるかは再生を始めるときに必ず確かめる。「権限を再確認」で取り直せる）
    val cached = remember(v0.id) { catalog.cachedDetail(v0.id) }
    val skipCache = entry.keep("skipCache") { java.util.concurrent.atomic.AtomicBoolean(false) }
    val (detail, scope) = rememberLoadable(entry, "detail") {
        if (!skipCache.get() && cached != null && cached.age() < 12 * 3_600_000L) {
            android.util.Log.i("NicoTVDetail", "${v0.id} detail cache")
            return@rememberLoadable LoadResult.Ok(cached.value)
        }
        val t0 = System.currentTimeMillis()
        catalog.videoDetail(v0.id, force = skipCache.get()).also { android.util.Log.i("NicoTVDetail", "${v0.id} detail ${System.currentTimeMillis() - t0}ms") }
    }
    val (seriesLookup, _) = rememberLoadable(entry, "series") {
        val t0 = System.currentTimeMillis()
        LoadResult.Ok(catalog.seriesOfVideo(v0.id)).also { android.util.Log.i("NicoTVDetail", "${v0.id} series ${System.currentTimeMillis() - t0}ms") }
    }
    val data by app.c.store.data.collectAsState()
    val account = app.c.store.account(data)
    val progress = account.progress[key(v0.provider, v0.id)]
    val mem = entry.focus
    var showMenu by remember { mutableStateOf(false) }
    fun reload() { skipCache.set(true); detail.load(scope, force = true) }
    val det = (detail.result as? LoadResult.Ok<VideoDetail>)?.value ?: cached?.value
    val failed = detail.result as? LoadResult.Failed
    // 動画情報が届くまでは一覧の情報で表示する
    val v: VideoSummary = det?.summary?.let { s ->
        s.copy(
            thumbnailUrl = s.thumbnailUrl ?: v0.thumbnailUrl, ownerName = s.ownerName ?: v0.ownerName,
            ownerIconUrl = s.ownerIconUrl ?: v0.ownerIconUrl, ownerId = s.ownerId ?: v0.ownerId,
        )
    } ?: v0
    val series: SeriesRef? = det?.series ?: (seriesLookup.result as? LoadResult.Ok<SeriesRef?>)?.value
    val seriesId = series?.id
    val ent = det?.entitlement

    RestoreFocus(mem, "primary")

    LazyColumn(
        Modifier.fillMaxSize(),
        state = entry.keep("list") { LazyListState() },
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.width(320.dp)) {
                    Thumb(v.thumbnailUrl, Modifier.size(320.dp, 180.dp).clip(RoundedCornerShape(10.dp)), label = "DEMO")
                    Spacer(Modifier.height(10.dp))
                    EntitlementBadge(det, loading = detail.loading || detail.result == null)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(v.title, fontSize = TvText.title, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            v.ownerName,
                            formatPosted(v.registeredAt),
                            v.durationSec?.let { formatTime(it * 1000L) },
                            v.viewCount?.let { "再生 " + formatCount(it) },
                            v.commentCount?.let { "コメント " + formatCount(it) },
                        ).joinToString("  ・  "),
                        fontSize = TvText.small, color = Palette.textDim,
                    )
                    Text(
                        series?.let { "シリーズ：${it.title}" } ?: if (seriesLookup.result == null) "シリーズを確認中…" else " ",
                        fontSize = TvText.small, color = if (series != null) Palette.accent else Palette.textDim,
                    )
                    progress?.let { p ->
                        Text(
                            when {
                                p.isWatched -> "視聴済み" + if (p.manualWatched != null) "（手動設定）" else ""
                                p.isInProgress -> "前回 ${formatTime(p.positionMs)} まで視聴"
                                p.naturallyEnded -> "最後まで再生（視聴区間 ${p.watchedMs * 100 / ((p.durationSec ?: 1) * 1000L).coerceAtLeast(1)}%）"
                                else -> "未視聴"
                            },
                            fontSize = TvText.small, color = Palette.textDim,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 主ボタンは最初から押せる（権利の確認は再生を始めるときに行う）。
                    // 動画情報で「視聴できない」と分かったら、同じ位置のボタンの内容だけを変える (TV-04)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (label, icon, action) = when {
                            ent == Entitlement.LOGIN_REQUIRED -> Triple("ログインして見る", null, { app.navigate(Route.Login) })
                            ent != null && !ent.playable && ent != Entitlement.UNKNOWN ->
                                Triple("視聴条件を確認", null, { app.entitlementHelp(v, ent, det.entitlementReason) })
                            progress?.isInProgress == true -> Triple("続きから  ${formatTime(progress.positionMs)}", "▶", { app.resume(progress) })
                            else -> Triple(if (seriesId != null) "続けて再生" else "再生", "▶", { app.play(v, series, 0) })
                        }
                        TvButton(label, action, Modifier.remembered(mem, "primary"), primary = true, icon = icon, compact = true)
                        when {
                            ent != null && !ent.playable && ent != Entitlement.UNKNOWN ->
                                TvButton("権限を再確認", { reload() }, Modifier.remembered(mem, "second"), compact = true)
                            progress?.isInProgress == true ->
                                TvButton("最初から", { app.play(v, series, 0) }, Modifier.remembered(mem, "second"), compact = true)
                        }
                        if (v.ownerId != null) TvButton("投稿者", { app.openOwner(v) }, Modifier.remembered(mem, "owner"), compact = true)
                        TvButton("その他", { showMenu = true }, Modifier.remembered(mem, "more"), compact = true)
                        if (seriesId != null) TvButton("シリーズ", { app.navigate(Route.Series(v.provider, seriesId, series.title)) }, Modifier.remembered(mem, "series"), compact = true)
                    }
                }
            }
        }
        if (failed != null) {
            item {
                StatusBox("動画の詳しい情報を${failed.kind.label}（${failed.message}）。再生は試せます。", isError = true, onRetry = { reload() })
            }
        }
        if (det != null && det.tags.isNotEmpty()) {
            item {
                Column {
                    Text("タグ", fontSize = TvText.body, fontWeight = FontWeight.Bold)
                    LazyRow(
                        state = entry.keep("tags") { LazyListState() },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
                    ) {
                        items(det.tags, key = { it }) { t ->
                            Chip(t, false, { app.navigate(Route.Search(t, tag = true)) }, Modifier.remembered(mem, "tag:$t"))
                        }
                    }
                }
            }
        }
        item {
            Column {
                Text("説明", fontSize = TvText.body, fontWeight = FontWeight.Bold)
                when {
                    det != null -> FocusableText(det.description.ifBlank { "（説明はありません）" }, Modifier.fillMaxWidth())
                    failed == null -> Text("読み込み中…", fontSize = TvText.small, color = Palette.textDim, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
    if (showMenu) VideoMenu(app, v, onDismiss = { showMenu = false })
}

@Composable
private fun EntitlementBadge(d: VideoDetail?, loading: Boolean) {
    val ent = d?.entitlement
    val color = when {
        ent == null -> Palette.textDim
        ent.playable -> Palette.ok
        ent == Entitlement.UNKNOWN -> Palette.textDim
        else -> Palette.warn
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (d == null) {
            Text(if (loading) "視聴条件を確認中…" else "視聴条件：不明", fontSize = TvText.small, color = Palette.textDim)
            return@Column
        }
        Text(d.billing.label, fontSize = TvText.small, color = Palette.text)
        Text("視聴：" + d.entitlement.label, fontSize = TvText.small, color = color, fontWeight = FontWeight.Bold)
        if (!d.entitlement.playable) d.entitlementReason?.let { Text(it, fontSize = 13.sp, color = Palette.textDim, maxLines = 2) }
    }
}
