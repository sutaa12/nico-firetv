package app.nicotv.firetv.data.nico

import app.nicotv.firetv.data.BillingKind
import app.nicotv.firetv.data.CatalogProvider
import app.nicotv.firetv.data.CommentBatch
import app.nicotv.firetv.data.CommentPosition
import app.nicotv.firetv.data.CommentRecord
import app.nicotv.firetv.data.CommentSize
import app.nicotv.firetv.data.CommentSourceInfo
import app.nicotv.firetv.data.Entitlement
import app.nicotv.firetv.data.FailKind
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.PlaybackDescriptor
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.RankingGenre
import app.nicotv.firetv.data.RankingSnapshot
import app.nicotv.firetv.data.RankingTerm
import app.nicotv.firetv.data.SearchPage
import app.nicotv.firetv.data.SearchSort
import app.nicotv.firetv.data.SearchTarget
import app.nicotv.firetv.data.SeriesEpisode
import app.nicotv.firetv.data.SeriesRef
import app.nicotv.firetv.data.SeriesSnapshot
import app.nicotv.firetv.data.MylistPage
import app.nicotv.firetv.data.MylistSummary
import app.nicotv.firetv.data.OwnerProfile
import app.nicotv.firetv.data.OwnerRef
import app.nicotv.firetv.data.SeriesSummary
import app.nicotv.firetv.data.UploadSort
import app.nicotv.firetv.data.VideoPage
import app.nicotv.firetv.data.ThreadTarget
import app.nicotv.firetv.data.map
import app.nicotv.firetv.data.VideoDetail
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.Cached
import app.nicotv.firetv.data.SeriesRefreshPlan
import app.nicotv.firetv.data.mergeSeriesTail
import app.nicotv.firetv.data.planSeriesRefresh
import app.nicotv.firetv.data.Storyboard
import app.nicotv.firetv.data.StoryboardSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * ニコニコ接続アダプター。
 *
 * 接続確認の状況 (2026-09-25、開発用Macから):
 * - ランキング (nvapi ranking/genre)・シリーズ (nvapi v2/series)・スナップショット検索・動画情報 (watch v3_guest) は応答形式を確認済み
 * - HLS 取得 (access-rights/hls)・コメント (nvcomment threads)・過去ログ (when 指定)・ログイン後 API は未検証
 * 形式が想定外なら空一覧にせず UNKNOWN_FORMAT を返す (06 §3)。
 */
class NicoProvider(
    val cookieJar: WebViewCookieJar,
    /** 更新の少ない情報の保存先（シリーズ・動画情報・一覧の応答） */
    private val meta: app.nicotv.firetv.data.local.MetaCache = app.nicotv.firetv.data.local.MetaCache(null),
) : CatalogProvider {
    override val provider = Provider.NICO

    /**
     * センシティブな作品（年齢制限）を一覧に含めるか。オンのときは一覧 API に sensitiveContents=mask を付ける。
     * オフのときは何も付けない（従来どおり）。再生できるかはアカウントの表示設定（viewer.allowSensitiveContents）による
     */
    @Volatile var showSensitive: Boolean = false

    private fun sens(): String = if (showSensitive) "&sensitiveContents=mask" else ""

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            // 個別に UA を指定した要求 (視聴ページ) はそのまま使う
            val b = req.newBuilder()
            if (req.header("User-Agent") == null) b.header("User-Agent", USER_AGENT)
            if (req.url.host.endsWith("nicovideo.jp")) {
                b.header("X-Frontend-Id", "6").header("X-Frontend-Version", "0")
            }
            chain.proceed(b.build())
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // 現行区分はサービス側で変わりうる。失敗した区分だけを失敗表示にし、他は使える状態に保つ (RNK-08)
    override val rankingGenres = listOf(
        RankingGenre("all", "全ジャンル"),
        RankingGenre("entertainment", "エンタメ"),
        RankingGenre("anime", "アニメ"),
        RankingGenre("game", "ゲーム"),
        RankingGenre("music_sound", "音楽・サウンド"),
        RankingGenre("dance", "ダンス"),
        RankingGenre("radio", "ラジオ"),
        RankingGenre("animal", "動物"),
        RankingGenre("cooking", "料理"),
        RankingGenre("nature", "自然"),
        RankingGenre("traveling_outdoor", "旅行・アウトドア"),
        RankingGenre("vehicle", "乗り物"),
        RankingGenre("sports", "スポーツ"),
        RankingGenre("society_politics_news", "社会・政治・時事"),
        RankingGenre("technology_craft", "技術・工作"),
        RankingGenre("commentary_lecture", "解説・講座"),
        RankingGenre("other", "その他"),
    )
    /**
     * 公式のランキングのジャンル一覧（nvapi v2/genres）。サービス側で増減するので固定の一覧より優先する。
     * センシティブな作品を表示する設定のときは sensitiveContents=mask を付ける（年齢制限向けのジャンルがあれば含まれる）。
     * 取れなければ固定の一覧を使う
     */
    override suspend fun loadRankingGenres(): List<RankingGenre> {
        val url = "https://nvapi.nicovideo.jp/v2/genres" + (if (showSensitive) "?sensitiveContents=mask" else "")
        val r = cachedCall(url, DAY)
        if (r !is Http.Ok) return rankingGenres
        val list = r.body["data"]["genres"].arr.mapNotNull { g ->
            val key = g["key"].str ?: return@mapNotNull null
            RankingGenre(key, g["label"].str ?: key)
        }
        android.util.Log.i("NicoTVRanking", "genres sensitive=$showSensitive keys=${list.joinToString(",") { it.id }}")
        if (list.isEmpty()) return rankingGenres
        // 年齢制限のジャンル（r18）は、センシティブな作品を表示する設定のときだけ並べ、いちばん最後に置く
        // 公式の表示名は「例のソレ」。年齢制限のジャンルだと分かるよう R-18 を添える
        val adult = list.filter { it.id == R18_GENRE }.map { g -> g.copy(label = (g.label.takeIf { it != R18_GENRE } ?: "例のソレ") + "（R-18）") }
        val normal = list.filter { it.id != R18_GENRE }
        // 「全ジャンル」は先頭に固定する
        val all = normal.firstOrNull { it.id == "all" } ?: rankingGenres.first()
        return listOf(all) + normal.filter { it.id != "all" } + (if (showSensitive) adult else emptyList())
    }

    override val rankingTerms = listOf(
        RankingTerm("hour", "毎時"),
        RankingTerm("24h", "24時間"),
        RankingTerm("week", "週間"),
        RankingTerm("month", "月間"),
        RankingTerm("total", "全期間"),
    )

    // ---------- 通信の共通処理 ----------

    private sealed interface Http {
        /** [savedAt] は応答を得た時刻（保存済みを使ったときはその保存時刻） */
        data class Ok(val code: Int, val body: JsonElement, val savedAt: Long = System.currentTimeMillis()) : Http
        data class Err(val kind: FailKind, val message: String) : Http
    }

    private suspend fun call(request: Request): Http = withContext(ioFor()) {
        try {
            http.newCall(request).execute().use { res ->
                val text = res.body?.boundedText().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                when {
                    res.code == 429 -> Http.Err(FailKind.RATE_LIMITED, "429")
                    res.code == 401 -> Http.Err(FailKind.LOGIN_REQUIRED, "401")
                    parsed == null -> Http.Err(
                        if (res.isSuccessful) FailKind.UNKNOWN_FORMAT else FailKind.NETWORK,
                        "HTTP ${res.code}",
                    )
                    else -> Http.Ok(res.code, parsed)
                }
            }
        } catch (e: IOException) {
            Http.Err(FailKind.NETWORK, e.message ?: "通信エラー")
        }
    }

    private fun okhttp3.ResponseBody.boundedText(): String {
        val limit = 8L * 1024 * 1024
        if (contentLength() > limit) throw IOException("応答が大きすぎます")
        val source = source()
        source.request(limit + 1)
        if (source.buffer.size > limit) throw IOException("応答が大きすぎます")
        return source.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    private fun get(url: String) = Request.Builder().url(url).get().build()

    /**
     * 更新の少ない応答を保存して使い回す。[ttlMs] 以内なら通信しない。
     * 通信に失敗したときは、古くても保存済みの応答を使う（一覧が空になるより良い）。
     * [scoped] はログインの有無で内容が変わりうる応答（投稿者・マイリスト）で、保存を分ける。
     */
    private suspend fun cachedCall(url: String, ttlMs: Long, scoped: Boolean = false, force: Boolean = false): Http {
        val key = "http:" + (if (scoped) cookieJar.sessionKey() + "|" else "G|") + url
        fun parse(e: app.nicotv.firetv.data.local.MetaCache.Entry?) = e?.let { en -> runCatching { Http.Ok(200, json.parseToJsonElement(en.text), en.savedAt) }.getOrNull() }
        if (!force) parse(withContext(Dispatchers.IO) { meta.read(key, ttlMs) })?.let { return it }
        val r = call(get(url))
        when {
            r is Http.Ok && r.body["meta"]["status"].int == 200 -> withContext(Dispatchers.IO) { meta.write(key, r.body.toString()) }
            r is Http.Err && r.kind == FailKind.NETWORK -> parse(withContext(Dispatchers.IO) { meta.read(key) })?.let { return it }
        }
        return r
    }

    private fun post(url: String, body: JsonElement, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    private fun metaFailure(body: JsonElement, httpCode: Int): LoadResult.Failed {
        val code = body["meta"]["errorCode"].str ?: "HTTP $httpCode"
        val kind = when {
            httpCode == 404 || code.contains("NOT_FOUND") || code.contains("DELETED") -> FailKind.NOT_FOUND
            httpCode == 401 || code.contains("LOGIN") || code.contains("UNAUTHORIZED") -> FailKind.LOGIN_REQUIRED
            httpCode == 403 || code.contains("FORBIDDEN") -> FailKind.NO_ENTITLEMENT
            httpCode == 429 -> FailKind.RATE_LIMITED
            else -> FailKind.NETWORK
        }
        return LoadResult.Failed(kind, code)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- 一覧系 ----------

    private fun parseNvapiVideo(v: JsonElement, rank: Int? = null): VideoSummary? {
        val id = v["id"].str ?: return null
        val thumb = v["thumbnail"]
        return VideoSummary(
            provider = Provider.NICO,
            id = id,
            title = v["title"].str ?: id,
            thumbnailUrl = thumb["listingUrl"].str ?: thumb["middleUrl"].str ?: thumb["url"].str,
            durationSec = v["duration"].int,
            viewCount = v["count"]["view"].long,
            commentCount = v["count"]["comment"].long,
            ownerName = v["owner"]["name"].str,
            ownerIconUrl = v["owner"]["iconUrl"].str,
            ownerId = v["owner"]["id"].str,
            registeredAt = v["registeredAt"].str,
            rank = rank,
            isPaid = v["isPaymentRequired"].bool == true,
        )
    }

    override suspend fun ranking(genre: RankingGenre, term: RankingTerm, page: Int, force: Boolean): LoadResult<RankingSnapshot> {
        val url = "https://nvapi.nicovideo.jp/v1/ranking/genre/${genre.id}?term=${term.id}&page=$page" + sens()  // pageSize は指定不可 (1ページ100件)
        // 毎時は更新が早いので短く、それ以外は長めに使い回す
        val ttl = when (term.id) { "hour" -> 10 * MIN; "24h" -> 30 * MIN; else -> 2 * HOUR }
        return when (val r = cachedCall(url, ttl, force = force)) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val items = r.body["data"]["items"] as? kotlinx.serialization.json.JsonArray
                    ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "items がありません")
                // 年齢制限まわりの確認用（件数と、ぼかし指定の有無だけを記録する）
                if (showSensitive || genre.id == R18_GENRE) android.util.Log.i(
                    "NicoTVRanking",
                    "ranking ${genre.id}/${term.id} items=${items.size} masked=${items.count { it["requireSensitiveMasking"].bool == true }} keys=${(items.firstOrNull() as? JsonObject)?.keys}",
                )
                val offset = (page - 1) * 100
                val videos = items.mapIndexedNotNull { i, v -> parseNvapiVideo(v, rank = offset + i + 1) }
                LoadResult.Ok(
                    RankingSnapshot(
                        genre, term, videos, r.savedAt,
                        hasNext = r.body["data"]["hasNext"].bool ?: false,
                    ),
                )
            }
        }
    }

    override suspend fun search(query: String, target: SearchTarget, sort: SearchSort, offset: Int): LoadResult<SearchPage> {
        // 公式の「スナップショット検索API」。結果は定期更新のスナップショットであり最新ではない
        val targets = if (target == SearchTarget.TAG) "tagsExact" else "title,description,tags"
        val sortKey = when (sort) {
            SearchSort.POPULAR -> "-viewCounter"
            SearchSort.NEWEST -> "-startTime"
            SearchSort.COMMENTS -> "-commentCounter"
        }
        val limit = 40
        val url = "https://snapshot.search.nicovideo.jp/api/v2/snapshot/video/contents/search" +
            "?q=${enc(query)}&targets=$targets" +
            "&fields=contentId,title,viewCounter,commentCounter,lengthSeconds,thumbnailUrl,startTime,userId,channelId" +
            "&_sort=$sortKey&_offset=$offset&_limit=$limit&_context=nicotv-firetv"
        // スナップショット検索は1日1回程度の更新
        return when (val r = cachedCall(url, 30 * MIN)) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val data = r.body["data"] as? kotlinx.serialization.json.JsonArray
                    ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "data がありません")
                val total = r.body["meta"]["totalCount"].long
                val items = data.mapNotNull { v ->
                    val id = v["contentId"].str ?: return@mapNotNull null
                    VideoSummary(
                        provider = Provider.NICO, id = id, title = v["title"].str ?: id,
                        thumbnailUrl = v["thumbnailUrl"].str, durationSec = v["lengthSeconds"].int,
                        viewCount = v["viewCounter"].long, commentCount = v["commentCounter"].long,
                        registeredAt = v["startTime"].str,
                        ownerId = v["channelId"].long?.let { "ch$it" } ?: v["userId"].long?.toString(),
                    )
                }
                val next = offset + items.size
                // スナップショット検索は _offset の上限が 100,000 件
                LoadResult.Ok(SearchPage(items, total, next.takeIf { items.size == limit && (total == null || next < total) && next < 100_000 }))
            }
        }
    }

    private fun seriesKey(id: String) = "series:$id"

    override fun cachedSeries(id: String): Cached<SeriesSnapshot>? = meta.read(seriesKey(id))?.let { e ->
        runCatching { Cached(json.decodeFromString(SeriesSnapshot.serializer(), e.text), e.savedAt) }.getOrNull()
    }

    private fun saveSeries(s: SeriesSnapshot) {
        // 途中までしか取れていないものは保存しない（次回すべて取り直す）
        if (!s.hasMore) meta.write(seriesKey(s.id), json.encodeToString(SeriesSnapshot.serializer(), s))
    }

    /**
     * シリーズ。保存済みがあれば、1件だけの取得（約4KB）で総数と先頭の話を確かめ、
     * 変わっていなければそのまま、後ろに増えていれば増えた分のページだけを取る。
     * 確認して10分以内なら確認も省く。通信に失敗したら保存済みを使う。
     */
    override suspend fun series(id: String, force: Boolean): LoadResult<SeriesSnapshot> {
        val cached = withContext(Dispatchers.IO) { cachedSeries(id) }
        if (!force && cached != null && cached.age() < 10 * MIN) {
            android.util.Log.i("NicoTVCache", "series $id cache (${cached.value.episodes.size})")
            return LoadResult.Ok(cached.value)
        }
        if (!force && cached != null) {
            val check = call(get("https://nvapi.nicovideo.jp/v2/series/$id?page=1&pageSize=1"))
            if (check is Http.Err || (check as Http.Ok).body["meta"]["status"].int != 200) {
                android.util.Log.i("NicoTVCache", "series $id check failed, use cache")
                return LoadResult.Ok(cached.value)
            }
            val data = check.body["data"]
            if (data["items"] !is kotlinx.serialization.json.JsonArray) return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "series.items がありません")
            val total = data["totalCount"].int
            val firstId = data["items"].arr.firstOrNull()?.get("video")?.get("id").str
            val now = System.currentTimeMillis()
            when (val plan = planSeriesRefresh(cached.value, total, firstId, now = now)) {
                SeriesRefreshPlan.UpToDate -> {
                    val same = cached.value.copy(title = data["detail"]["title"].str ?: cached.value.title, fetchedAt = now)
                    withContext(Dispatchers.IO) { saveSeries(same) }
                    android.util.Log.i("NicoTVCache", "series $id unchanged (${same.episodes.size})")
                    return LoadResult.Ok(same)
                }
                is SeriesRefreshPlan.Append -> {
                    val tail = fetchSeriesPages(id, plan.fromPage, plan.pageSize)
                    if (tail is LoadResult.Ok) {
                        val (_, eps, t) = tail.value
                        val merged = mergeSeriesTail(cached.value, plan.fromPage, plan.pageSize, eps, t ?: total, now).copy(hasMore = tail.value.nextPage != null, nextPage = tail.value.nextPage)
                        withContext(Dispatchers.IO) { saveSeries(merged) }
                        android.util.Log.i("NicoTVCache", "series $id appended from page ${plan.fromPage} (${cached.value.episodes.size} -> ${merged.episodes.size})")
                        return LoadResult.Ok(merged, partial = merged.hasMore)
                    }
                    // 足りない分が取れなければ全部取り直す
                }
                SeriesRefreshPlan.Full -> Unit
            }
        }
        return when (val r = fetchSeriesPages(id, 1, 100)) {
            is LoadResult.Failed -> cached?.let { LoadResult.Ok(it.value) } ?: r
            is LoadResult.Ok -> {
                val (title, eps, total) = r.value
                val hasMore = r.value.nextPage != null
                val snap = SeriesSnapshot(Provider.NICO, id, title, eps.sortedBy { it.order }, total, hasMore, System.currentTimeMillis(), nextPage = r.value.nextPage)
                withContext(Dispatchers.IO) { saveSeries(snap) }
                android.util.Log.i("NicoTVCache", "series $id full (${eps.size})")
                LoadResult.Ok(snap, partial = hasMore || r.partial)
            }
        }
    }

    private data class SeriesPages(val title: String, val episodes: List<SeriesEpisode>, val total: Int?, val nextPage: Int?)

    override suspend fun seriesPage(id: String, page: Int): LoadResult<SeriesSnapshot> =
        when (val r = fetchSeriesPages(id, page, 100, maxPages = 1)) {
            is LoadResult.Failed -> r
            is LoadResult.Ok -> LoadResult.Ok(SeriesSnapshot(provider, id, r.value.title, r.value.episodes,
                r.value.total, r.value.nextPage != null, System.currentTimeMillis(), firstPage = page, nextPage = r.value.nextPage))
        }

    /** 初回は最大500件。その先は nextPage から読み、失敗したページも再試行できる。 */
    private suspend fun fetchSeriesPages(id: String, fromPage: Int, pageSize: Int, maxPages: Int = 5): LoadResult<SeriesPages> {
        val all = mutableListOf<SeriesEpisode>()
        var title = ""
        var total: Int? = null
        var page = fromPage
        while (page < fromPage + maxPages) {
            val r = call(get("https://nvapi.nicovideo.jp/v2/series/$id?page=$page&pageSize=$pageSize" + sens()))
            val fail = when {
                r is Http.Err -> LoadResult.Failed(r.kind, r.message)
                r is Http.Ok && r.body["meta"]["status"].int != 200 -> metaFailure(r.body, r.code)
                r is Http.Ok && r.body["data"]["items"] !is kotlinx.serialization.json.JsonArray -> LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "series.items がありません")
                else -> null
            }
            if (fail != null) {
                if (all.isEmpty()) return fail
                return LoadResult.Ok(SeriesPages(title, all, total, page), partial = true)
            }
            r as Http.Ok
            val data = r.body["data"]
            title = data["detail"]["title"].str ?: title
            total = data["totalCount"].int ?: total
            val items = data["items"].arr
            val base = (page - 1) * pageSize
            items.forEachIndexed { i, item ->
                val v = parseNvapiVideo(item["video"] ?: return@forEachIndexed) ?: return@forEachIndexed
                all += SeriesEpisode(v, item["meta"]["order"].int ?: (base + i + 1))
            }
            if (items.size < pageSize || (total != null && base + items.size >= total))
                return LoadResult.Ok(SeriesPages(title, all, total, null))
            page++
        }
        return LoadResult.Ok(SeriesPages(title, all, total, page), partial = true)
    }

    // ---------- 投稿者 ----------
    // ユーザー: v1/users/{id}・v2/users/{id}/videos・v1/users/{id}/series・v1/users/{id}/mylists・v2/mylists/{id} は
    // 2026-09-25 に未ログインで応答を確認。チャンネル: v1/channels/{ch}/videos・series はログインが必要（形式は実機で確認）

    private fun parseEssential(item: JsonElement): VideoSummary? = parseNvapiVideo(item["essential"] ?: item["video"] ?: item)

    override suspend fun ownerProfile(owner: OwnerRef): LoadResult<OwnerProfile> {
        if (owner.isChannel) {
            val icon = owner.iconUrl ?: "https://secure-dcdn.cdn.nimg.jp/comch/channel-icon/128x128/${owner.id}.jpg"
            val name = owner.name ?: (channelRss(owner.id, 1) as? LoadResult.Ok)?.value?.first
            return LoadResult.Ok(OwnerProfile(owner.copy(name = name, iconUrl = icon), null, null, null))
        }
        return when (val r = cachedCall("https://nvapi.nicovideo.jp/v1/users/${owner.id}", DAY, scoped = true)) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val u = r.body["data"]["user"]
                LoadResult.Ok(
                    OwnerProfile(
                        owner.copy(name = u["nickname"].str ?: owner.name, iconUrl = u["icons"]["large"].str ?: u["icons"]["small"].str ?: owner.iconUrl),
                        description = u["strippedDescription"].str,
                        followerCount = u["followerCount"].long,
                        videoCount = null,
                    ),
                )
            }
        }
    }

    override suspend fun ownerVideos(owner: OwnerRef, sort: UploadSort, page: Int): LoadResult<VideoPage> {
        val (key, order) = when (sort) {
            UploadSort.NEWEST -> "registeredAt" to "desc"
            UploadSort.OLDEST -> "registeredAt" to "asc"
            UploadSort.POPULAR -> "viewCount" to "desc"
            UploadSort.COMMENTS -> "commentCount" to "desc"
        }
        if (owner.isChannel) return channelRss(owner.id, page).map { (_, list) -> VideoPage(list, null, list.size >= 20) }
        val size = 50
        val url = if (owner.isChannel) "https://nvapi.nicovideo.jp/v1/channels/${owner.id}/videos?sortKey=$key&sortOrder=$order&pageSize=$size&page=$page"
        else "https://nvapi.nicovideo.jp/v2/users/${owner.id}/videos?sortKey=$key&sortOrder=$order&pageSize=$size&page=$page" + sens()
        return when (val r = cachedCall(url, 15 * MIN, scoped = true)) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message).also { android.util.Log.i("NicoTVOwner", "videos ${owner.id} failed ${r.kind} ${r.message} session=${cookieJar.hasSession()}") }
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code).also {
                    android.util.Log.i("NicoTVOwner", "videos ${owner.id} status=${r.body["meta"]["status"].int} ${it.message} session=${cookieJar.hasSession()}")
                }
                val data = r.body["data"]
                val items = (data["items"] ?: data["videos"]) as? kotlinx.serialization.json.JsonArray
                if (owner.isChannel) android.util.Log.i("NicoTVOwner", "channel videos keys data=${(data as? JsonObject)?.keys} item=${(items?.firstOrNull() as? JsonObject)?.keys}")
                if (items == null) return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "items がありません")
                val list = items.mapNotNull { parseEssential(it) }
                val total = (data["totalCount"] ?: data["total"]).int
                LoadResult.Ok(VideoPage(list, total, hasNext = (data["hasNext"].bool) ?: (list.size >= size && (total == null || page * size < total))))
            }
        }
    }

    /**
     * チャンネルの動画一覧。nvapi の v1/channels/{ch}/videos はログインしていても 403（2026-09-25 実機）のため、
     * 公開の RSS（ch.nicovideo.jp/{ch}/video?rss=2.0、1ページ20件・新しい順）を使う。
     * 動画 ID はサムネイル URL の番号（so + 番号）。2026-09-25 に so46839434 で対応を確認。
     */
    private suspend fun channelRss(ch: String, page: Int): LoadResult<Pair<String?, List<VideoSummary>>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://ch.nicovideo.jp/$ch/video?rss=2.0&page=$page").header("User-Agent", DESKTOP_UA).get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext LoadResult.Failed(if (res.code == 404) FailKind.NOT_FOUND else FailKind.NETWORK, "HTTP ${res.code}")
                val xml = res.body?.boundedText().orEmpty()
                val name = between(xml, "<dc:creator>", "</dc:creator>")?.let { unescapeHtml(it) }
                // 文書全体に正規表現を当てない（ネイティブで大きく作業領域を使うため）。項目ごとに切り出す
                val items = splitBetween(xml, "<item>", "</item>").mapNotNull { item ->
                    val thumb = Regex("<img[^>]*src=\"([^\"]+)\"").find(item)?.groupValues?.get(1) ?: return@mapNotNull null
                    val num = Regex("/thumbnails/(\\d+)/").find(thumb)?.groupValues?.get(1) ?: return@mapNotNull null
                    val title = Regex("<title>([^<]*)</title>").find(item)?.groupValues?.get(1)?.let { unescapeHtml(it) } ?: "so$num"
                    val len = Regex("nico-info-length\">([0-9:]+)<").find(item)?.groupValues?.get(1)
                    val date = Regex("nico-info-date\">(\\d{4})年(\\d{2})月(\\d{2})日 (\\d{2})[：:](\\d{2})[：:](\\d{2})").find(item)?.groupValues
                    VideoSummary(
                        provider = Provider.NICO, id = "so$num", title = title, thumbnailUrl = thumb,
                        durationSec = len?.split(":")?.mapNotNull { it.toIntOrNull() }?.fold(0) { acc, v -> acc * 60 + v },
                        ownerName = name, ownerId = ch,
                        ownerIconUrl = "https://secure-dcdn.cdn.nimg.jp/comch/channel-icon/128x128/$ch.jpg",
                        registeredAt = date?.let { "${it[1]}-${it[2]}-${it[3]}T${it[4]}:${it[5]}:${it[6]}+09:00" },
                    )
                }.toList()
                android.util.Log.i("NicoTVOwner", "channel rss $ch page=$page items=${items.size}")
                LoadResult.Ok(name to items)
            }
        } catch (e: IOException) {
            LoadResult.Failed(FailKind.NETWORK, e.message ?: "通信エラー")
        }
    }

    override suspend fun ownerSeries(owner: OwnerRef): LoadResult<List<SeriesSummary>> {
        val url = if (owner.isChannel) "https://nvapi.nicovideo.jp/v1/channels/${owner.id}/series?page=1&pageSize=100"
        else "https://nvapi.nicovideo.jp/v1/users/${owner.id}/series?page=1&pageSize=100"
        return when (val r = cachedCall(url, 6 * HOUR, scoped = true)) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message).also { android.util.Log.i("NicoTVOwner", "series ${owner.id} failed ${r.kind} ${r.message} session=${cookieJar.hasSession()}") }
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code).also {
                    android.util.Log.i("NicoTVOwner", "series ${owner.id} status=${r.body["meta"]["status"].int} ${it.message} session=${cookieJar.hasSession()}")
                }
                val data = r.body["data"]
                val items = (data["items"] ?: data["series"]) as? kotlinx.serialization.json.JsonArray
                if (owner.isChannel) android.util.Log.i("NicoTVOwner", "channel series keys data=${(data as? JsonObject)?.keys} item=${(items?.firstOrNull() as? JsonObject)?.keys}")
                if (items == null) return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "items がありません")
                LoadResult.Ok(items.mapNotNull { s ->
                    val id = s["id"].str ?: return@mapNotNull null
                    SeriesSummary(Provider.NICO, id, s["title"].str ?: "シリーズ", s["thumbnailUrl"].str, s["itemsCount"].int)
                })
            }
        }
    }

    override suspend fun ownerMylists(owner: OwnerRef): LoadResult<List<MylistSummary>> {
        if (owner.isChannel) return LoadResult.Ok(emptyList())
        return when (val r = cachedCall("https://nvapi.nicovideo.jp/v1/users/${owner.id}/mylists?sampleItemCount=1", 6 * HOUR, scoped = true)) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val items = r.body["data"]["mylists"] as? kotlinx.serialization.json.JsonArray
                    ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "mylists がありません")
                LoadResult.Ok(items.mapNotNull { m ->
                    val id = m["id"].str ?: return@mapNotNull null
                    val thumb = m["sampleItems"].arr.firstOrNull()?.let { parseEssential(it)?.thumbnailUrl }
                    MylistSummary(Provider.NICO, id, m["name"].str ?: "マイリスト", m["itemsCount"].int, thumb)
                })
            }
        }
    }

    /**
     * 自分のマイリスト一覧（ログイン中のみ。非公開のものも含む）。先頭に「あとで見る」を付ける。
     * 自分のマイリストの ID は「me:番号」、あとで見るは「watch-later」として [mylist] に渡す。
     * 形式は niconicolibs/niconico.py・grayjay-plugin-niconico の実装で確認（v1/users/me/mylists、v1/users/me/watch-later）
     */
    suspend fun myMylists(): LoadResult<List<MylistSummary>> {
        if (!cookieJar.hasSession()) return LoadResult.Failed(FailKind.LOGIN_REQUIRED, "ログインが必要です")
        return when (val r = call(get("https://nvapi.nicovideo.jp/v1/users/me/mylists?sampleItemCount=1"))) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code).also {
                    android.util.Log.i("NicoTVMylist", "me status=${r.body["meta"]["status"].int} ${it.message}")
                }
                val items = r.body["data"]["mylists"] as? kotlinx.serialization.json.JsonArray
                    ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "mylists がありません")
                android.util.Log.i("NicoTVMylist", "me mylists=${items.size}")
                val mine = items.mapNotNull { m ->
                    val id = m["id"].str ?: return@mapNotNull null
                    val thumb = m["sampleItems"].arr.firstOrNull()?.let { parseEssential(it)?.thumbnailUrl }
                    MylistSummary(Provider.NICO, "me:$id", (m["name"].str ?: "マイリスト") + if (m["isPublic"].bool == false) "（非公開）" else "", m["itemsCount"].int, thumb)
                }
                LoadResult.Ok(listOf(MylistSummary(Provider.NICO, WATCH_LATER, "あとで見る", null, null)) + mine)
            }
        }
    }

    override suspend fun mylist(id: String, page: Int): LoadResult<MylistPage> {
        if (id == WATCH_LATER) return watchLater(page)
        if (id.startsWith("me:")) return ownMylist(id, page)
        return when (val r = cachedCall("https://nvapi.nicovideo.jp/v2/mylists/$id?pageSize=100&page=$page" + sens(), 30 * MIN, scoped = true)) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val m = r.body["data"]["mylist"] ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "mylist がありません")
                val list = m["items"].arr.mapNotNull { parseEssential(it) }
                LoadResult.Ok(MylistPage(id, m["name"].str ?: "マイリスト", list, m["totalItemCount"].int, m["hasNext"].bool ?: false))
            }
        }
    }

    /** 自分のマイリストの中身（変わりやすいので保存しない） */
    private suspend fun ownMylist(id: String, page: Int): LoadResult<MylistPage> {
        val real = id.removePrefix("me:")
        return when (val r = call(get("https://nvapi.nicovideo.jp/v1/users/me/mylists/$real?pageSize=100&page=$page" + sens()))) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val m = r.body["data"]["mylist"] ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "mylist がありません")
                val list = m["items"].arr.mapNotNull { parseEssential(it) }
                android.util.Log.i("NicoTVMylist", "me mylist page=$page items=${list.size} keys=${(m as? JsonObject)?.keys}")
                LoadResult.Ok(MylistPage(id, m["name"].str ?: "マイリスト", list, m["totalItemCount"].int ?: m["totalCount"].int, m["hasNext"].bool ?: false))
            }
        }
    }

    /** あとで見る（新しく追加した順） */
    private suspend fun watchLater(page: Int): LoadResult<MylistPage> {
        if (!cookieJar.hasSession()) return LoadResult.Failed(FailKind.LOGIN_REQUIRED, "ログインが必要です")
        return when (val r = call(get("https://nvapi.nicovideo.jp/v1/users/me/watch-later?sortKey=addedAt&sortOrder=desc&pageSize=100&page=$page" + sens()))) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val w = r.body["data"]["watchLater"] ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "watchLater がありません")
                val list = w["items"].arr.mapNotNull { parseEssential(it) }
                android.util.Log.i("NicoTVMylist", "watch-later page=$page items=${list.size}")
                LoadResult.Ok(MylistPage(WATCH_LATER, "あとで見る", list, w["totalCount"].int, w["hasNext"].bool ?: false))
            }
        }
    }

    // ---------- 動画が属するシリーズ ----------

    private fun seriesOfKey(videoId: String) = "seriesOf:$videoId"

    @kotlinx.serialization.Serializable
    private data class SeriesOfEntry(val ref: SeriesRef? = null)

    /** 保存済みの「動画が属するシリーズ」。属している場合は30日、属していない場合は後から登録されることがあるので3日使う */
    private fun cachedSeriesOf(videoId: String): SeriesOfEntry? {
        val e = meta.read(seriesOfKey(videoId)) ?: return null
        val v = runCatching { json.decodeFromString(SeriesOfEntry.serializer(), e.text) }.getOrNull() ?: return null
        val ttl = if (v.ref != null) 30 * DAY else 3 * DAY
        return v.takeIf { System.currentTimeMillis() - e.savedAt <= ttl }
    }

    private fun rememberSeriesOf(videoId: String, ref: SeriesRef?) {
        meta.write(seriesOfKey(videoId), json.encodeToString(SeriesOfEntry.serializer(), SeriesOfEntry(ref)))
    }

    /**
     * watch API (v3_guest) は series を返さないことがあるため、公式の視聴ページに埋め込まれた
     * 同じ形式のデータ (server-response) から series を読む。2026-09-25 に通常動画・チャンネル動画で確認。
     * 結果は端末に保存する。視聴ページは大きいので、取得は最低の優先度で行う（再生開始などを先に通す）。
     */
    override suspend fun seriesOfVideo(videoId: String): SeriesRef? {
        withContext(Dispatchers.IO) { cachedSeriesOf(videoId) }?.let { return it.ref }
        synchronized(watchCache) { pruneWatchCache(); watchCache[watchKey(videoId)] }?.data?.get("data")?.get("series")?.let(::seriesRefOf)?.let { ref ->
            withContext(Dispatchers.IO) { rememberSeriesOf(videoId, ref) }
            return ref
        }
        // 視聴ページは大きいので、同じ動画を取得中ならその結果を待つ。画面を離れても取得は最後まで行い、結果を保存する
        // （詳細画面で取得中に再生を押しても、再生中のシリーズ確認で同じページを取り直さない）
        val session = cookieJar.sessionKey()
        val cacheKey = session + ":" + videoId
        val job = synchronized(pageInflight) {
            pageInflight[cacheKey]?.takeIf { it.isActive } ?: appScope.async {
                try { lowPriority { seriesFromPage(videoId) } } finally { synchronized(pageInflight) { pageInflight.remove(cacheKey) } }
            }.also { pageInflight[cacheKey] = it }
        }
        val result = job.await()
        return result.takeIf { cookieJar.sessionKey() == session }
    }

    private val pageInflight = HashMap<String, kotlinx.coroutines.Deferred<SeriesRef?>>()

    private suspend fun seriesFromPage(videoId: String): SeriesRef? {
        // 待っている間に詳細の取得で分かっていればそれを使う
        synchronized(watchCache) { pruneWatchCache(); watchCache[watchKey(videoId)] }?.data?.get("data")?.get("series")?.let(::seriesRefOf)?.let { return it }
        // 通信に失敗したときは「シリーズなし」と記憶せず、次回また調べる
        var cacheable = true
        val t0 = System.currentTimeMillis()
        val ref = withContext(ioFor()) {
            try {
                // Android の UA だとスマホ版ページへ転送され、埋め込みデータが無いため PC 版として取得する
                val req = Request.Builder().url("https://www.nicovideo.jp/watch/$videoId").header("User-Agent", DESKTOP_UA).get().build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) { cacheable = false; return@use null }
                    val html = res.body?.boundedText() ?: return@use null
                    // 埋め込みデータは一度だけ切り出して解析する。画質の情報も入っているので、ついでに覚える
                    val root = serverResponseOf(html)
                    root?.let(::qualityFromServerResponse)?.let { q -> onQualityKnown?.invoke(videoId, q) }
                    root?.let(::seriesFromServerResponse)
                }
            } catch (_: IOException) {
                cacheable = false
                null
            }
        }
        android.util.Log.i("NicoTVCache", "seriesOf $videoId page ${System.currentTimeMillis() - t0}ms found=${ref != null}")
        if (cacheable) withContext(Dispatchers.IO) { rememberSeriesOf(videoId, ref) }
        return ref
    }

    // ---------- 動画詳細・再生 ----------

    private data class WatchCache(val data: JsonElement, val at: Long, val chars: Int)

    private val watchCache = LinkedHashMap<String, WatchCache>(16, 0.75f, true)

    private fun watchKey(id: String) = cookieJar.sessionKey() + ":" + id
    private fun pruneWatchCache() {
        val now = System.currentTimeMillis()
        watchCache.entries.removeAll { now - it.value.at >= 60_000 }
        while (watchCache.size > 8 || watchCache.values.sumOf { it.chars.toLong() } > 2_000_000) watchCache.remove(watchCache.keys.first())
    }
    private fun rememberWatch(key: String, root: JsonElement) = synchronized(watchCache) {
        val chars = root.toString().length
        if (chars <= 500_000) watchCache[key] = WatchCache(root, System.currentTimeMillis(), chars)
        pruneWatchCache()
    }

    fun invalidateSession() {
        http.dispatcher.cancelAll()
        synchronized(watchInflight) { watchInflight.values.forEach { it.cancel() }; watchInflight.clear() }
        synchronized(pageInflight) { pageInflight.values.forEach { it.cancel() }; pageInflight.clear() }
        synchronized(watchCache) { watchCache.clear() }
    }

    fun close() {
        invalidateSession()
        appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (lowDispatcherDelegate.isInitialized()) lowDispatcher.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun actionTrackId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..10).map { chars.random() }.joinToString("") + "_" + System.currentTimeMillis()
    }

    /** 動画情報を取得したときに最高画質を知らせる（サムネイルの画質表示用） */
    var onQualityKnown: ((String, String) -> Unit)? = null

    private fun maxQualityOf(root: JsonElement?): String? =
        root["data"]["media"]["domand"]["videos"].arr.maxByOrNull { it["qualityLevel"].int ?: -1 }?.get("label").str

    /** Cookie を送らない（アカウントの視聴履歴に残さない）動画情報の取得用 */
    private val guestHttp: OkHttpClient by lazy { http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build() }
    private val probeGate = kotlinx.coroutines.sync.Semaphore(2)

    /** 目の前の操作のための取得（再生開始・詳細）が進行中の数。急がない取得はこの間待つ */
    private val foreground = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun <T> foregroundCall(block: suspend () -> T): T {
        foreground.incrementAndGet()
        try { return block() } finally { foreground.decrementAndGet() }
    }

    /**
     * 急がない取得。目の前の取得が終わるまで待ち、優先度を最低にした専用スレッド（2本）で通信・解析する。
     * Fire TV の回線を再生開始と取り合わないよう、同時に走る数も絞る。
     */
    override suspend fun <T> lowPriority(block: suspend () -> T): T {
        // すでに急がない取得の中なら、そのまま続ける（入れ子で待ち合って止まらないように）
        if (kotlin.coroutines.coroutineContext[LowPriority] != null) return block()
        return withContext(LowPriority() + lowDispatcher) { lowGate.withPermit {
            while (foreground.get() > 0) kotlinx.coroutines.delay(150)
            block()
        } }
    }

    private val lowGate = kotlinx.coroutines.sync.Semaphore(2)

    /** 今の処理が急がない取得なら最低優先度のスレッド、そうでなければ通常の I/O スレッド */
    private suspend fun ioFor(): kotlin.coroutines.CoroutineContext =
        if (kotlin.coroutines.coroutineContext[LowPriority] != null) lowDispatcher else Dispatchers.IO

    /** 急がない取得の中であることを示す目印。キーは別に持つ（自分自身をキーにすると初期化時に null になり落ちる） */
    private class LowPriority : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<LowPriority>
    }

    private val lowDispatcherDelegate = lazy {
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread({
                // Linux 側の優先度も最低にする（Thread.priority だけでは効かないことがある）
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST) }
                r.run()
            }, "nicotv-low").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }.asCoroutineDispatcher()
    }

    private val lowDispatcher by lowDispatcherDelegate

    /** 表示中のサムネイルの最高画質を調べる（設定でオンのときだけ）。ゲストとして取得する */
    suspend fun probeQuality(id: String): String? = probeGate.withPermit {
        // 詳細を開くなど、目の前の操作のための通信を優先する
        lowPriority { probeQualityInner(id) }
    }

    private suspend fun probeQualityInner(id: String): String? = run {
        withContext(lowDispatcher) {
            try {
                val url = "https://www.nicovideo.jp/api/watch/v3_guest/$id?actionTrackId=${actionTrackId()}"
                val guest = guestHttp.newCall(get(url)).execute().use { res ->
                    runCatching { json.parseToJsonElement(res.body?.boundedText().orEmpty()) }.getOrNull()
                }
                maxQualityOf(guest)?.let { onQualityKnown?.invoke(id, it); return@withContext it }
                // dアニメストアなど会員限定・ログインが必要な動画は、ゲストでは画質が分からない。
                // ログイン済みなら、ログインした状態の動画情報から調べる（詳細画面を開くときと同じ取得）
                val p = guest["data"]["payment"]["video"]
                val needsLogin = p["isAdmission"].bool == true || p["isPremium"].bool == true || p["isPpv"].bool == true || guest["meta"]["status"].int == 403
                if (needsLogin && cookieJar.hasSession()) {
                    val (root, _) = watchData(id, fresh = false)
                    maxQualityOf(root)
                } else null
            } catch (_: IOException) {
                null
            } finally {
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /** 同じ動画の動画情報を取得中なら、その結果を待って使う（詳細を開いてすぐ再生を押したときに二重に取得しない） */
    private val watchInflight = HashMap<String, kotlinx.coroutines.Deferred<Pair<JsonElement?, LoadResult.Failed?>>>()
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private suspend fun watchData(id: String, fresh: Boolean): Pair<JsonElement?, LoadResult.Failed?> {
        val session = cookieJar.sessionKey()
        val cacheKey = session + ":" + id
        val cached = synchronized(watchCache) { pruneWatchCache(); watchCache[cacheKey] }
        // 再生開始時は必ず取り直す (権利・アクセスキーの期限があるため)。ただし今まさに取得中のものは新しいので使う
        if (!fresh && cached != null && System.currentTimeMillis() - cached.at < 60_000) return cached.data to null
        val job = synchronized(watchInflight) {
            watchInflight[cacheKey]?.takeIf { it.isActive } ?: appScope.async {
                // 画面を離れて呼び出し元が取り消されても、取得は最後まで行って結果を残す
                try { fetchWatch(id, cacheKey) } finally { synchronized(watchInflight) { watchInflight.remove(cacheKey) } }
            }.also { watchInflight[cacheKey] = it }
        }
        val result = job.await()
        if (cookieJar.sessionKey() != session) return null to LoadResult.Failed(FailKind.LOGIN_REQUIRED, "アカウントが変わりました")
        return result
    }

    private suspend fun fetchWatch(id: String, cacheKey: String): Pair<JsonElement?, LoadResult.Failed?> {
        val path = if (cookieJar.hasSession()) "v3" else "v3_guest"
        val url = "https://www.nicovideo.jp/api/watch/$path/$id?actionTrackId=${actionTrackId()}"
        return when (val r = call(get(url))) {
            is Http.Err -> null to LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) {
                    // 権限不足時も data にメタ情報が入ることがある
                    val data = r.body["data"]
                    if (data != null && data["video"] != null) {
                        rememberWatch(cacheKey, r.body)
                        return r.body to null
                    }
                    if (data["reasonCode"].str == "HARMFUL_VIDEO") {
                        return null to LoadResult.Failed(
                            if (cookieJar.hasSession()) FailKind.NO_ENTITLEMENT else FailKind.LOGIN_REQUIRED,
                            if (cookieJar.hasSession()) SENSITIVE_HELP else "センシティブな作品（年齢制限）はログインが必要です",
                        )
                    }
                    null to metaFailure(r.body, r.code)
                } else {
                    rememberWatch(cacheKey, r.body)
                    maxQualityOf(r.body)?.let { q -> onQualityKnown?.invoke(id, q) }
                    r.body to null
                }
            }
        }
    }

    private fun billingOf(d: JsonElement?): BillingKind {
        val p = d["payment"]["video"]
        return when {
            p == null -> BillingKind.UNKNOWN
            p["isPpv"].bool == true -> BillingKind.PPV
            p["isAdmission"].bool == true -> BillingKind.CHANNEL_MEMBER
            p["isPremium"].bool == true -> BillingKind.PREMIUM
            p["billingType"].str == "free" -> BillingKind.FREE
            else -> BillingKind.UNKNOWN
        }
    }

    private fun entitlementOf(root: JsonElement): Pair<Entitlement, String?> {
        val status = root["meta"]["status"].int
        val d = root["data"]
        val domand = d["media"]["domand"]
        val available = domand["videos"].arr.any { it["isAvailable"].bool == true }
        val reason = d["okReason"].str ?: root["data"]["reasonCode"].str ?: root["meta"]["errorCode"].str
        return when {
            status == 200 && domand != null && available -> Entitlement.AVAILABLE to reason
            // センシティブな作品（年齢制限）。ログインしていて、アカウントの表示設定がオンなら再生できる
            reason == "HARMFUL_VIDEO" && !cookieJar.hasSession() -> Entitlement.LOGIN_REQUIRED to "センシティブな作品（年齢制限）はログインが必要です"
            reason == "HARMFUL_VIDEO" -> Entitlement.SENSITIVE to SENSITIVE_HELP
            d["video"]["isDeleted"].bool == true -> Entitlement.DELETED to reason
            status == 200 && domand == null && d["media"]["delivery"] != null ->
                Entitlement.UNSUPPORTED_FORMAT to "旧配信方式（暗号化等）はこのアプリでは未対応"
            !cookieJar.hasSession() && billingOf(d) != BillingKind.FREE -> Entitlement.LOGIN_REQUIRED to reason
            billingOf(d) != BillingKind.FREE && billingOf(d) != BillingKind.UNKNOWN -> Entitlement.PURCHASE_REQUIRED to reason
            status == 403 -> Entitlement.PURCHASE_REQUIRED to reason
            else -> Entitlement.UNKNOWN to reason
        }
    }

    override suspend fun videoDetail(id: String, force: Boolean): LoadResult<VideoDetail> = foregroundCall {
        val key = detailKey(id)
        videoDetailInner(id, force).also { r ->
            // 説明・タグなどはほとんど変わらないので保存し、次に開いたときは通信せずに出す
            if (r is LoadResult.Ok) withContext(Dispatchers.IO) {
                meta.write(key, json.encodeToString(VideoDetail.serializer(), r.value))
                r.value.series?.let { rememberSeriesOf(id, it) }
            }
        }
    }

    /** 視聴できるかどうかはログインの有無で変わるので、保存を分ける */
    private fun detailKey(id: String) = "detail:" + (cookieJar.sessionKey() + ":") + id

    override fun cachedDetail(id: String): Cached<VideoDetail>? = meta.read(detailKey(id))?.let { e ->
        runCatching { Cached(json.decodeFromString(VideoDetail.serializer(), e.text), e.savedAt) }.getOrNull()
    }

    private suspend fun videoDetailInner(id: String, force: Boolean): LoadResult<VideoDetail> {
        val (root, fail) = watchData(id, fresh = force)
        if (fail != null) return fail
        val d = root!!["data"]
        val v = d["video"] ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "video がありません")
        val billing = billingOf(d)
        val summary = VideoSummary(
            provider = Provider.NICO,
            id = v["id"].str ?: id,
            title = v["title"].str ?: id,
            thumbnailUrl = v["thumbnail"]["player"].str ?: v["thumbnail"]["largeUrl"].str ?: v["thumbnail"]["url"].str,
            durationSec = v["duration"].int,
            viewCount = v["count"]["view"].long,
            commentCount = v["count"]["comment"].long,
            ownerName = d["owner"]["nickname"].str ?: d["channel"]["name"].str,
            ownerIconUrl = d["owner"]["iconUrl"].str ?: d["channel"]["thumbnail"]["url"].str,
            ownerId = d["owner"]["id"].str ?: d["channel"]["id"].str,
            registeredAt = v["registeredAt"].str,
            isPaid = billing == BillingKind.PPV || billing == BillingKind.CHANNEL_MEMBER,
        )
        val (ent, reason) = entitlementOf(root)
        // 視聴ページからのシリーズ判定は画面側で同時に行う（ここで待つと詳細の表示が遅れる）
        val series = d["series"]?.let(::seriesRefOf)
        return LoadResult.Ok(
            VideoDetail(
                summary = summary,
                description = (v["description"].str ?: "").replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""),
                tags = d["tag"]["items"].arr.mapNotNull { it["name"].str },
                entitlement = ent,
                billing = billing,
                entitlementReason = reason,
                series = series,
                channelName = d["channel"]["name"].str,
            ),
        )
    }

    override suspend fun preparePlayback(id: String): LoadResult<PlaybackDescriptor> = foregroundCall { preparePlaybackInner(id) }

    private suspend fun preparePlaybackInner(id: String): LoadResult<PlaybackDescriptor> {
        val (root, fail) = watchData(id, fresh = true)
        if (fail != null) return fail
        val (ent, reason) = entitlementOf(root!!)
        if (!ent.playable) {
            val kind = when (ent) {
                Entitlement.LOGIN_REQUIRED -> FailKind.LOGIN_REQUIRED
                Entitlement.DELETED -> FailKind.NOT_FOUND
                Entitlement.UNSUPPORTED_FORMAT -> FailKind.UNSUPPORTED
                else -> FailKind.NO_ENTITLEMENT
            }
            return LoadResult.Failed(kind, reason ?: ent.label)
        }
        val d = root["data"]
        val domand = d["media"]["domand"]
        val key = domand["accessRightKey"].str ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "accessRightKey がありません")
        val videos = domand["videos"].arr.filter { it["isAvailable"].bool == true }
        val audio = domand["audios"].arr.filter { it["isAvailable"].bool == true }
            .maxByOrNull { it["qualityLevel"].int ?: 0 }?.get("id").str
            ?: return LoadResult.Failed(FailKind.UNSUPPORTED, "利用できる音声がありません")
        // 提供された画質をすべて要求し、ExoPlayer の自動画質に任せる (権限外の画質は要求しない)
        val outputs = buildJsonArray {
            videos.mapNotNull { it["id"].str }.forEach { vid ->
                add(buildJsonArray { add(JsonPrimitive(vid)); add(JsonPrimitive(audio)) })
            }
        }
        val body = buildJsonObject { put("outputs", outputs) }
        val url = "https://nvapi.nicovideo.jp/v1/watch/$id/access-rights/hls?actionTrackId=${d["client"]["watchTrackId"].str ?: actionTrackId()}"
        val r = call(post(url, body, mapOf("X-Access-Right-Key" to key, "X-Request-With" to "https://www.nicovideo.jp")))
        if (r is Http.Err) return LoadResult.Failed(r.kind, r.message)
        r as Http.Ok
        val contentUrl = r.body["data"]["contentUrl"].str ?: return metaFailure(r.body, r.code)
        val nv = d["comment"]["nvComment"]
        val commentSource = nv?.let {
            val server = it["server"].str
            val threadKey = it["threadKey"].str
            val params = it["params"]
            val targets = params["targets"].arr.mapNotNull { t -> t["id"].str?.let { tid -> ThreadTarget(tid, t["fork"].str ?: "main") } }
            if (server != null && threadKey != null && params != null) CommentSourceInfo(server, threadKey, params.toString(), id, targets) else null
        }
        val top = videos.maxByOrNull { it["qualityLevel"].int ?: 0 }?.get("label").str
        // シーク用の縮小画像は、ログインしていて動画が対応している場合だけ取る（公式も会員向けの機能）
        val sbAvailable = domand["isStoryboardAvailable"].bool == true
        val storyboard = if (cookieJar.hasSession() && sbAvailable) StoryboardSource(id, key, d["client"]["watchTrackId"].str ?: actionTrackId()) else null
        android.util.Log.i("NicoTVStoryboard", "$id available=$sbAvailable session=${cookieJar.hasSession()}")
        return LoadResult.Ok(PlaybackDescriptor(contentUrl, isHls = true, qualityLabel = top, commentSource = commentSource, storyboard = storyboard))
    }

    // ---------- シーク用の縮小画像（ストーリーボード） ----------

    /**
     * 公式プレイヤーと同じ手順で取る: access-rights/storyboard に動画のアクセスキーを付けて問い合わせ（201）、
     * 返った contentUrl の JSON（列数・行数・間隔・縮小画像の大きさ・画像名）を読み、
     * 画像は contentUrl の最後の要素を画像名に置き換えた URL（署名はそのまま）から取る。
     * 参考: niconicolibs/niconico.py の get_storyboard_url / download_storyboards
     */
    override suspend fun storyboard(source: StoryboardSource): Storyboard? = withContext(ioFor()) {
        val t0 = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("https://nvapi.nicovideo.jp/v1/watch/${source.videoId}/access-rights/storyboard?actionTrackId=${source.trackId}")
                .post(ByteArray(0).toRequestBody(null))
                .header("X-Access-Right-Key", source.accessRightKey)
                .header("X-Request-With", "nicovideo")
                .build()
            val contentUrl = http.newCall(req).execute().use { res ->
                val body = runCatching { json.parseToJsonElement(res.body?.boundedText().orEmpty()) }.getOrNull()
                val u = body["data"]["contentUrl"].str
                if (u == null) android.util.Log.i("NicoTVStoryboard", "${source.videoId} rights HTTP ${res.code} ${body["meta"]["errorCode"].str ?: ""}")
                u
            } ?: return@withContext null
            val base = contentUrl.toHttpUrlOrNull() ?: return@withContext null
            val board = http.newCall(get(contentUrl)).execute().use { res ->
                if (!res.isSuccessful) { android.util.Log.i("NicoTVStoryboard", "${source.videoId} meta HTTP ${res.code}"); return@use null }
                runCatching { json.parseToJsonElement(res.body?.boundedText().orEmpty()) }.getOrNull()
            } ?: return@withContext null
            val columns = board["columns"].int ?: return@withContext null
            val rows = board["rows"].int ?: return@withContext null
            // 間隔はミリ秒（sm9 で 2000）。秒で返ってきた場合にも備える
            val interval = (board["interval"].long ?: return@withContext null).let { if (it in 1..60) it * 1000 else it }
            val w = board["thumbnailWidth"].int ?: return@withContext null
            val h = board["thumbnailHeight"].int ?: return@withContext null
            val names = board["images"].arr.sortedBy { it["timestamp"].long ?: 0 }.mapNotNull { it["url"].str }
            // 長い動画でも取りすぎないよう、シートは最大60枚（2秒間隔・10×10なら約3時間分）まで
            val sheets = ArrayList<ByteArray>()
            for (name in names.take(60)) {
                val url = base.newBuilder().setPathSegment(base.pathSize - 1, name).build()
                val bytes = http.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                    if (res.isSuccessful) res.body?.bytes() else null
                } ?: break
                sheets += bytes
            }
            android.util.Log.i(
                "NicoTVStoryboard",
                "${source.videoId} ok ${columns}x$rows ${interval}ms ${w}x$h sheets=${sheets.size}/${names.size} bytes=${sheets.sumOf { it.size }} ${System.currentTimeMillis() - t0}ms",
            )
            if (sheets.isEmpty()) null else Storyboard(columns, rows, interval, w, h, sheets)
        } catch (e: IOException) {
            android.util.Log.i("NicoTVStoryboard", "${source.videoId} failed ${e.javaClass.simpleName}")
            null
        }
    }

    // ---------- コメント ----------

    override suspend fun fetchComments(source: CommentSourceInfo, whenEpochSec: Long?, only: ThreadTarget?): LoadResult<CommentBatch> {
        var params = json.parseToJsonElement(source.paramsJson)
        if (only != null && params is JsonObject) {
            // 過去ログは1スレッドずつさかのぼる（スレッドごとに投稿の時期が大きく違うため）
            params = JsonObject(params + ("targets" to buildJsonArray {
                add(buildJsonObject { put("id", only.id); put("fork", only.fork) })
            }))
        }
        val body = buildJsonObject {
            put("params", params)
            put("threadKey", source.threadKey)
            put("additionals", buildJsonObject { if (whenEpochSec != null) put("when", whenEpochSec) })
        }
        val r = call(post("${source.server.trimEnd('/')}/v1/threads", body))
        if (r is Http.Err) return LoadResult.Failed(r.kind, r.message)
        r as Http.Ok
        if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
        val threads = r.body["data"]["threads"] as? kotlinx.serialization.json.JsonArray
            ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "threads がありません")
        var total = 0L
        val out = ArrayList<CommentRecord>()
        for (t in threads) {
            val threadId = t["id"].str ?: continue
            val fork = t["fork"].str ?: "main"
            total += t["commentCount"].long ?: 0
            for (c in t["comments"].arr) {
                val no = c["no"].long ?: continue
                val cmds = c["commands"].arr.mapNotNull { it.str }
                // 「invisible」は投稿者が見えなくしたコメント（公式でも表示しない）
                if ("invisible" in cmds) continue
                out += CommentRecord(
                    videoId = source.videoId,
                    threadId = threadId,
                    fork = fork,
                    no = no,
                    commentId = c["id"].str,
                    vposMs = c["vposMs"].long ?: 0,
                    postedAt = parseIsoMillis(c["postedAt"].str),
                    body = c["body"].str ?: "",
                    userId = c["userId"].str,
                    position = CommentCommands.position(cmds),
                    size = CommentCommands.size(cmds),
                    color = CommentCommands.color(cmds),
                    isOwner = fork == "owner",
                    fromPastLog = whenEpochSec != null,
                    nicoru = c["nicoruCount"].int ?: 0,
                    score = c["score"].int ?: 0,
                    full = "full" in cmds,
                    ender = "ender" in cmds,
                )
            }
        }
        return LoadResult.Ok(CommentBatch(out, total))
    }

    /** ログイン中ユーザー (未検証)。 */
    data class AccountIdentity(val id: String, val nickname: String, val premium: Boolean)

    suspend fun me(): LoadResult<AccountIdentity> {
        return when (val r = call(get("https://nvapi.nicovideo.jp/v1/users/me"))) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val u = r.body["data"]["user"]
                val name = u["nickname"].str ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "user がありません")
                val id = u["id"].str?.takeIf { it.isNotBlank() } ?: return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "user.id がありません")
                LoadResult.Ok(AccountIdentity(id, name, u["isPremium"].bool == true))
            }
        }
    }

    /**
     * アカウントの視聴履歴（公式・読込のみ）。ページ単位で新しい順。
     * 再生位置は公式が返した場合だけ使い、推測しない (HIS-04)。
     */
    suspend fun officialHistory(cursor: String? = null): LoadResult<OfficialHistoryPage> {
        // v1 は廃止 (404)。v2 は 2026-09-25 に実機で確認: {items:[{itemId, viewedAt, video}], nextCursor}。再生位置は含まれない
        val url = "https://nvapi.nicovideo.jp/v2/users/me/watch/history?pageSize=50" + sens() + (cursor?.let { "&cursor=" + enc(it) } ?: "")
        return when (val r = call(get(url))) {
            is Http.Err -> LoadResult.Failed(r.kind, r.message)
            is Http.Ok -> {
                if (r.body["meta"]["status"].int != 200) return metaFailure(r.body, r.code)
                val data = r.body["data"]
                val items = (data["items"] ?: data["histories"] ?: data["watchHistories"]) as? kotlinx.serialization.json.JsonArray
                // 応答の形を確かめるため、キー名だけを記録する（値は記録しない）
                android.util.Log.i(
                    "NicoTVHistory",
                    "keys data=${(data as? JsonObject)?.keys} item=${(items?.firstOrNull() as? JsonObject)?.keys} video=${(items?.firstOrNull()?.get("video") as? JsonObject)?.keys?.take(12)}",
                )
                if (items == null) return LoadResult.Failed(FailKind.UNKNOWN_FORMAT, "items がありません")
                val list = items.mapNotNull { it ->
                    val v = parseNvapiVideo(it["video"] ?: it) ?: return@mapNotNull null
                    OfficialHistoryItem(
                        video = v,
                        lastViewedAt = parseIsoMillis((it["lastViewedAt"] ?: it["lastWatchedAt"] ?: it["watchedAt"] ?: it["viewedAt"]).str),
                        positionSec = (it["playbackPosition"] ?: it["resumePosition"] ?: it["position"]).let { p -> p.int ?: p["seconds"].int },
                        views = (it["views"] ?: it["watchCount"]).int,
                    )
                }
                val total = (data["totalCount"] ?: data["total"]).int
                val next = data["nextCursor"].str?.takeIf { it.isNotBlank() && it != cursor }
                android.util.Log.i("NicoTVHistory", "cursor=${cursor != null} items=${list.size} hasNext=${next != null}")
                LoadResult.Ok(OfficialHistoryPage(list, total, nextCursor = next))
            }
        }
    }

    companion object {
        /** 「あとで見る」を表す一覧の ID */
        const val WATCH_LATER = "watch-later"
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 9; AFTR) NicoTV-FireTV/0.1"
        /** 年齢制限のランキングのジャンル */
        const val R18_GENRE = "r18"

        /** センシティブな作品を再生できないときの案内 */
        const val SENSITIVE_HELP = "センシティブな作品（年齢制限）です。ニコニコのアカウントで「センシティブな作品」の表示設定がオフになっています。" +
            "公式サイト・公式アプリのアカウント設定でオンにすると、このアプリでも再生できます（18 歳以上の方のみ）。"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        const val LOGIN_URL = "https://account.nicovideo.jp/login?site=niconico&next_url=%2F"
    }
}

private val pageJson = Json { ignoreUnknownKeys = true }

/**
 * 公式の視聴ページに埋め込まれた server-response（HTML エスケープされた JSON）を、位置の検索で切り出して解析する。
 * 正規表現は使わない。Android の正規表現はネイティブ（ICU）で動き、数 MB ある属性値に `[^"]+` を当てると
 * 1文字ごとに後戻り用の記録を積んで一時的に数百 MB を使い、端末全体がメモリ不足で固まったため (2026-09-27 実機で確認)
 */
internal fun serverResponseOf(html: String): JsonElement? {
    val marker = "name=\"server-response\" content=\""
    val at = html.indexOf(marker)
    if (at < 0) return null
    val start = at + marker.length
    val end = html.indexOf('"', start)
    if (end <= start) return null
    return runCatching { pageJson.parseToJsonElement(unescapeHtml(html, start, end)) }.getOrNull()
}

/** 視聴ページから、動画が属するシリーズを読む */
internal fun seriesFromWatchPage(html: String): SeriesRef? = serverResponseOf(html)?.let(::seriesFromServerResponse)

internal fun seriesFromServerResponse(root: JsonElement): SeriesRef? = root["data"]["response"]["series"]?.let(::seriesRefOf)

internal fun qualityFromServerResponse(root: JsonElement): String? =
    root["data"]["response"]["media"]["domand"]["videos"].arr.maxByOrNull { it["qualityLevel"].int ?: -1 }?.get("label").str

internal fun seriesRefOf(s: JsonElement): SeriesRef? {
    val id = s["id"].str ?: return null
    return SeriesRef(id, s["title"].str ?: "シリーズ")
}

/**
 * HTML の文字参照（&quot; &amp; &#39; &#x2F; など）を戻す。[start]〜[end] の範囲だけを読む。
 * 大きな文字列でも一度の走査で済むよう、正規表現を使わずに1文字ずつ読む。知らない参照はそのまま残す
 */
internal fun unescapeHtml(s: String, start: Int = 0, end: Int = s.length): String {
    val out = StringBuilder(end - start)
    var i = start
    while (i < end) {
        val c = s[i]
        if (c == '&') {
            // 文字参照の最大長までだけ探索する。& が連続しても残り全文を繰り返し走査しない。
            val limit = minOf(end - 1, i + 12)
            var semi = i + 1
            while (semi <= limit && s[semi] != ';') semi++
            if (semi in (i + 2)..minOf(end - 1, i + 12)) {
                val e = s.substring(i + 1, semi)
                val decoded: String? = when {
                    e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                    e.startsWith("#") -> e.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                    else -> when (e) { "quot" -> "\""; "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "apos" -> "'"; else -> null }
                }
                if (decoded != null) { out.append(decoded); i = semi + 1; continue }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/** [open] と [close] の間の文字列（最初の1つ） */
internal fun between(s: String, open: String, close: String): String? {
    val a = s.indexOf(open)
    if (a < 0) return null
    val b = s.indexOf(close, a + open.length)
    return if (b < 0) null else s.substring(a + open.length, b)
}

/** [open] と [close] で囲まれた部分をすべて */
internal fun splitBetween(s: String, open: String, close: String): Sequence<String> = sequence {
    var from = 0
    while (true) {
        val a = s.indexOf(open, from)
        if (a < 0) break
        val b = s.indexOf(close, a + open.length)
        if (b < 0) break
        yield(s.substring(a + open.length, b))
        from = b + close.length
    }
}

data class OfficialHistoryItem(val video: VideoSummary, val lastViewedAt: Long?, val positionSec: Int?, val views: Int?)
data class OfficialHistoryPage(val items: List<OfficialHistoryItem>, val totalCount: Int?, val nextCursor: String?)

/** コメントのコマンド解釈。許可リスト方式で、未知の命令は無視する (03 §8)。 */
object CommentCommands {
    private val colors = mapOf(
        "white" to 0xFFFFFFFF, "red" to 0xFFFF0000, "pink" to 0xFFFF8080, "orange" to 0xFFFFC000,
        "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "blue" to 0xFF0000FF,
        "purple" to 0xFFC000FF, "black" to 0xFF000000,
        "white2" to 0xFFCCCC99, "niconicowhite" to 0xFFCCCC99, "red2" to 0xFFCC0033, "truered" to 0xFFCC0033,
        "pink2" to 0xFFFF33CC, "orange2" to 0xFFFF6600, "passionorange" to 0xFFFF6600,
        "yellow2" to 0xFF999900, "madyellow" to 0xFF999900, "green2" to 0xFF00CC66, "elementalgreen" to 0xFF00CC66,
        "cyan2" to 0xFF00CCCC, "blue2" to 0xFF3399FF, "marineblue" to 0xFF3399FF,
        "purple2" to 0xFF6633CC, "nobleviolet" to 0xFF6633CC, "black2" to 0xFF666666,
    )
    private val hex = Regex("^#[0-9a-fA-F]{6}$")

    fun position(cmds: List<String>) = when {
        "ue" in cmds -> CommentPosition.UE
        "shita" in cmds -> CommentPosition.SHITA
        else -> CommentPosition.NAKA
    }

    fun size(cmds: List<String>) = when {
        "big" in cmds -> CommentSize.BIG
        "small" in cmds -> CommentSize.SMALL
        else -> CommentSize.MEDIUM
    }

    fun color(cmds: List<String>): Long {
        for (c in cmds) {
            colors[c]?.let { return it }
            if (hex.matches(c)) return 0xFF000000 or c.substring(1).toLong(16)
        }
        return 0xFFFFFFFF
    }
}
