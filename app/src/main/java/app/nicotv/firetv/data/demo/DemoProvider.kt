package app.nicotv.firetv.data.demo

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
import app.nicotv.firetv.data.StoryboardSource
import app.nicotv.firetv.data.Storyboard
import app.nicotv.firetv.data.SeriesSnapshot
import app.nicotv.firetv.data.MylistPage
import app.nicotv.firetv.data.MylistSummary
import app.nicotv.firetv.data.OwnerProfile
import app.nicotv.firetv.data.OwnerRef
import app.nicotv.firetv.data.SeriesSummary
import app.nicotv.firetv.data.UploadSort
import app.nicotv.firetv.data.VideoPage
import app.nicotv.firetv.data.ThreadTarget
import app.nicotv.firetv.data.VideoDetail
import app.nicotv.firetv.data.VideoSummary
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * ネットワークやアカウントなしでリモコン操作・連続再生・コメント描画を確かめるためのデモ取得元。
 * 映像は公開されているテスト用サンプル動画を使う。
 */
class DemoProvider : CatalogProvider {
    override val provider = Provider.DEMO

    private data class DemoVideo(
        val id: String,
        val title: String,
        val url: String,
        val durationSec: Int,
        val billing: BillingKind = BillingKind.FREE,
        val entitlement: Entitlement = Entitlement.AVAILABLE,
        val seriesId: String? = null,
    )

    private val tv = "https://test-videos.co.uk/vids/"
    private val exo = "https://storage.googleapis.com/exoplayer-test-media-"

    // シリーズの公式順とタイトルの話数をわざと一致させない (SRT-01 の確認用)。自動次話を試しやすいよう各話は10秒
    private val videos = listOf(
        DemoVideo("demo1", "【デモ】テスト放送 第1話「はじまり」", tv + "bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4", 10, seriesId = "s1"),
        DemoVideo("demo2", "【デモ】テスト放送 第2話「つづき」", tv + "jellyfish/mp4/h264/720/Jellyfish_720_10s_1MB.mp4", 10, seriesId = "s1"),
        DemoVideo("demo3", "【デモ】テスト放送 総集編（公式順は3番目）", tv + "sintel/mp4/h264/720/Sintel_720_10s_1MB.mp4", 10, seriesId = "s1"),
        DemoVideo("demo4", "【デモ】テスト放送 第3話「会員限定の回」", tv + "bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_5MB.mp4", 10,
            billing = BillingKind.CHANNEL_MEMBER, entitlement = Entitlement.PURCHASE_REQUIRED, seriesId = "s1"),
        DemoVideo("demo5", "【デモ】テスト放送 第4話「おわり」", tv + "jellyfish/mp4/h264/720/Jellyfish_720_10s_5MB.mp4", 10, seriesId = "s1"),
        DemoVideo("demo6", "【デモ】Big Buck Bunny", exo + "0/BigBuckBunny_320x180.mp4", 596),
        DemoVideo("demo7", "【デモ】フレームカウンター（同期確認用・1時間）", exo + "1/mp4/frame-counter-one-hour.mp4", 3600),
        DemoVideo(STRESS_ID, "【負荷テスト】弾幕（最大30,000件・1:00〜1:10に集中）", exo + "0/BigBuckBunny_320x180.mp4", 596),
        DemoVideo("demo8", "【デモ】HLS 配信テスト（Apple サンプル）",
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8", 600),
    )

    private fun summary(v: DemoVideo, rank: Int? = null) = VideoSummary(
        provider = Provider.DEMO,
        id = v.id,
        title = v.title,
        thumbnailUrl = null,
        durationSec = v.durationSec,
        viewCount = (v.id.hashCode().toLong() and 0xFFFF) * 7,
        commentCount = 1200,
        ownerName = "デモ投稿者",
        ownerId = "demo-owner",
        registeredAt = "2026-09-01T12:00:00+09:00",
        rank = rank,
        isPaid = v.billing != BillingKind.FREE,
        maxQuality = when {
            v.url.endsWith(".m3u8") -> "1080p"
            v.url.contains("320x180") -> "180p"
            v.url.contains("frame-counter") -> "360p"
            else -> "720p"
        },
    )

    override val rankingGenres = listOf(RankingGenre("all", "全ジャンル（デモ）"), RankingGenre("anime", "アニメ（デモ）"))
    override val rankingTerms = listOf(RankingTerm("24h", "24時間"), RankingTerm("week", "週間"))

    override suspend fun ranking(genre: RankingGenre, term: RankingTerm, page: Int, force: Boolean): LoadResult<RankingSnapshot> {
        delay(200)
        val list = if (genre.id == "anime") videos.filter { it.seriesId != null } else videos.reversed()
        return LoadResult.Ok(
            RankingSnapshot(genre, term, list.mapIndexed { i, v -> summary(v, i + 1) }, System.currentTimeMillis(), hasNext = false),
        )
    }

    override suspend fun search(query: String, target: SearchTarget, sort: SearchSort, offset: Int): LoadResult<SearchPage> {
        delay(200)
        val hits = videos.filter { it.title.contains(query, ignoreCase = true) }.map { summary(it) }
        return LoadResult.Ok(SearchPage(hits, hits.size.toLong(), null))
    }

    override suspend fun videoDetail(id: String, force: Boolean): LoadResult<VideoDetail> {
        val v = videos.find { it.id == id } ?: return LoadResult.Failed(FailKind.NOT_FOUND, "デモ動画が見つかりません")
        return LoadResult.Ok(
            VideoDetail(
                summary = summary(v),
                description = "リモコン操作と連続再生、コメント描画を確認するためのデモ動画です。\n映像: 公開されているテスト用サンプル（test-videos.co.uk、ExoPlayer テスト素材、Apple HLS サンプル）。",
                tags = listOf("デモ", "テスト", if (v.seriesId != null) "シリーズ" else "単発"),
                entitlement = v.entitlement,
                billing = v.billing,
                entitlementReason = if (v.entitlement == Entitlement.PURCHASE_REQUIRED) "デモ：チャンネル会員限定の回を想定" else null,
                series = v.seriesId?.let { SeriesRef(it, "【デモ】テスト放送") },
                channelName = null,
            ),
        )
    }

    override suspend fun series(id: String, force: Boolean): LoadResult<SeriesSnapshot> {
        // 確認用（デバッグの起動オプションのときだけ）: 実際のシリーズ取得程度の時間をかけ、再生開始がこれを待たないことを見る
        if (app.nicotv.firetv.DebugOverrides.slowSeries) delay(2_000)
        val eps = videos.filter { it.seriesId == id }.mapIndexed { i, v -> SeriesEpisode(summary(v), i + 1) }
        if (eps.isEmpty()) return LoadResult.Failed(FailKind.NOT_FOUND, "シリーズが見つかりません")
        return LoadResult.Ok(SeriesSnapshot(Provider.DEMO, id, "【デモ】テスト放送", eps, eps.size, false, System.currentTimeMillis()))
    }

    override suspend fun ownerProfile(owner: OwnerRef): LoadResult<OwnerProfile> =
        LoadResult.Ok(OwnerProfile(owner.copy(name = "デモ投稿者"), "デモ用の投稿者です。", 1234, videos.size))

    override suspend fun ownerVideos(owner: OwnerRef, sort: UploadSort, page: Int): LoadResult<VideoPage> {
        val list = when (sort) { UploadSort.OLDEST -> videos; else -> videos.reversed() }.map { summary(it) }
        return LoadResult.Ok(VideoPage(list, list.size, false))
    }

    override suspend fun ownerSeries(owner: OwnerRef): LoadResult<List<SeriesSummary>> =
        LoadResult.Ok(listOf(SeriesSummary(Provider.DEMO, "s1", "【デモ】テスト放送", null, 5)))

    override suspend fun ownerMylists(owner: OwnerRef): LoadResult<List<MylistSummary>> =
        LoadResult.Ok(listOf(MylistSummary(Provider.DEMO, "m1", "【デモ】おすすめマイリスト", 4, null)))

    override suspend fun mylist(id: String, page: Int): LoadResult<MylistPage> =
        LoadResult.Ok(MylistPage(id, "【デモ】おすすめマイリスト", listOf("demo6", "demo1", "demo8", "demo7").mapNotNull { vid -> videos.find { it.id == vid }?.let { summary(it) } }, 4, false))

    override suspend fun seriesOfVideo(videoId: String): SeriesRef? =
        videos.find { it.id == videoId }?.seriesId?.let { SeriesRef(it, "【デモ】テスト放送") }

    /** 確認用: 1秒ごとに秒数を描いた 120×90 の縮小画像を、10×10 マスのシートにする */
    override suspend fun storyboard(source: StoryboardSource): Storyboard? {
        // 確認用: 取得に時間がかかる場合（シーク中に取得が終わって画像に切り替わるか）を見る
        if (app.nicotv.firetv.DebugOverrides.slowSeries) delay(2_000)
        val cols = 10; val rows = 10; val w = 120; val h = 90
        val bmp = android.graphics.Bitmap.createBitmap(cols * w, rows * h, android.graphics.Bitmap.Config.RGB_565)
        val c = android.graphics.Canvas(bmp)
        val bg = android.graphics.Paint()
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 40f; textAlign = android.graphics.Paint.Align.CENTER }
        for (i in 0 until cols * rows) {
            val x = (i % cols) * w; val y = (i / cols) * h
            bg.color = android.graphics.Color.HSVToColor(floatArrayOf((i * 37f) % 360f, 0.6f, 0.7f))
            c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), bg)
            c.drawText("${i}s", x + w / 2f, y + h / 2f + 14f, text)
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return Storyboard(cols, rows, 1_000, w, h, listOf(out.toByteArray()))
    }

    override suspend fun preparePlayback(id: String): LoadResult<PlaybackDescriptor> {
        delay(150)
        val v = videos.find { it.id == id } ?: return LoadResult.Failed(FailKind.NOT_FOUND, "デモ動画が見つかりません")
        if (!v.entitlement.playable) return LoadResult.Failed(FailKind.NO_ENTITLEMENT, "デモ：チャンネル会員限定の回を想定")
        return LoadResult.Ok(
            PlaybackDescriptor(
                v.url, isHls = v.url.endsWith(".m3u8"), qualityLabel = null, commentSource = CommentSourceInfo("demo", "demo", "{}", v.id, targetsOf(v.id)),
                storyboard = if (app.nicotv.firetv.DebugOverrides.demoStoryboard) StoryboardSource(v.id, "demo", "demo") else null,
            ),
        )
    }

    private val phrases = listOf(
        "きたああああ", "ｗｗｗ", "ｗｗｗ", "8888888", "草", "うぽつ", "ここすき", "かわいい", "神回",
        "wwwwwwwwwwwwwwwwwwww", "作画いいな", "ここ好き", "！？", "おつ", "わこつ", "えぇ…", "これは名作",
        "BGMいいね", "何度見ても泣ける", "うおおおおおおおおおお", "↑それな", "初見です", "リピート勢です",
        // 新しい絵文字の表示確認用（Android 9 標準の絵文字フォントには無いもの）
        "🫱👩🫲お前やりますねぇスギ", "🥹🫶", "🫠溶けた", "👨‍👩‍👧家族", "❤️好き",
    )

    /** 投稿者コメントの一部を、改行・長文の表示確認用にする（上固定） */
    private fun ownerBody(i: Int) = when (i) {
        1 -> "投稿者からのとても長いお知らせです。上固定のコメントが画面の幅より長いときは、公式と同じく幅に収まるよう小さくして表示します。"
        2 -> AA_SAMPLE
        else -> "【投稿者】${i + 1}"
    }

    /**
     * 実際のコメントサーバーと同じ振る舞いをまねた、動画ごとの固定のコメント集合。
     * - 通常取得: 各スレッドの、動画全体（投稿時〜今）に散らばった抜粋を最大1,000件
     * - when 指定: その時刻より前に投稿されたものを新しい順に最大1,000件
     * 投稿者コメント (owner) は投稿日時が古く、第2のメインスレッド (チャンネル用) は投稿が少ない。
     */
    private class Pool(val threads: Map<ThreadTarget, List<CommentRecord>>)

    private val pools = HashMap<String, Pool>()

    private fun pool(videoId: String): Pool = synchronized(pools) {
        pools.getOrPut(videoId) {
            val stress = videoId == STRESS_ID
            val durMs = if (stress) 120_000L else (videos.find { it.id == videoId }?.durationSec ?: 60) * 1000L
            val upload = NOW_MS - 400L * 86_400_000L
            fun make(t: ThreadTarget, count: Int, seed: Int): List<CommentRecord> {
                val rnd = Random(videoId.hashCode() * 31 + seed)
                return (0 until count).map { i ->
                    val burst = stress && i % 10 < 3
                    val vpos = if (t.fork == "owner") i * durMs / maxOf(1, count) else if (burst) rnd.nextLong(60_000, 70_000) else rnd.nextLong(0, durMs)
                    val cmds = when (rnd.nextInt(25)) { 0 -> listOf("ue", "red"); 1 -> listOf("shita", "yellow"); 2 -> listOf("big", "cyan"); 3 -> listOf("small"); 4 -> listOf("#FF9900"); else -> emptyList() }
                    CommentRecord(
                        videoId = videoId, threadId = t.id, fork = t.fork, no = i + 1L, commentId = null, vposMs = vpos,
                        // 投稿者コメントは投稿時。それ以外は投稿時から今までに分布（番号が大きいほど新しい）
                        postedAt = if (t.fork == "owner") upload else upload + (NOW_MS - upload) * (i + 1L) / count,
                        body = if (t.fork == "owner") ownerBody(i) else phrases[rnd.nextInt(phrases.size)],
                        userId = "demo-user-${rnd.nextInt(5000)}",
                        position = when { "ue" in cmds || t.fork == "owner" -> CommentPosition.UE; "shita" in cmds -> CommentPosition.SHITA; else -> CommentPosition.NAKA },
                        size = when { "big" in cmds -> CommentSize.BIG; "small" in cmds -> CommentSize.SMALL; else -> CommentSize.MEDIUM },
                        color = app.nicotv.firetv.data.nico.CommentCommands.color(cmds),
                        isOwner = t.fork == "owner", fromPastLog = false,
                        nicoru = when (rnd.nextInt(40)) { 0 -> 25; 1, 2 -> 6; else -> 0 },
                        score = if (rnd.nextInt(60) == 0) -6000 else 0,
                    )
                }
            }
            val main = ThreadTarget(if (stress) "stress" else "demo-main", "main")
            val community = ThreadTarget("demo-community", "main")
            val owner = ThreadTarget("demo-main", "owner")
            Pool(
                mapOf(
                    owner to make(owner, 20, 1),
                    main to make(main, if (stress) 40_000 else 12_000, 2),
                    community to make(community, if (stress) 0 else 3_000, 3),
                ),
            )
        }
    }

    fun targetsOf(videoId: String): List<ThreadTarget> = pool(videoId).threads.keys.toList()

    override suspend fun fetchComments(source: CommentSourceInfo, whenEpochSec: Long?, only: ThreadTarget?): LoadResult<CommentBatch> {
        delay(if (whenEpochSec == null) 150 else 250)
        val p = pool(source.videoId)
        val out = ArrayList<CommentRecord>()
        for ((t, list) in p.threads) {
            if (only != null && t != only) continue
            // 通常取得は、実際のサーバーと同じく動画全体（投稿時〜今）に散らばった抜粋を返す
            val page = if (whenEpochSec == null) list.filterIndexed { i, _ -> i % maxOf(1, list.size / 1_000) == 0 }.take(1_000)
            else list.filter { (it.postedAt ?: 0) < whenEpochSec * 1000 }.takeLast(1_000)
            out += page.map { it.copy(fromPastLog = whenEpochSec != null) }
        }
        return LoadResult.Ok(CommentBatch(out, p.threads.values.sumOf { it.size }.toLong()))
    }

    companion object {
        const val AA_SAMPLE = "( ˃̣̣̥᷄ᯅ˂̣̣̥᷅ )\n　 /⌒ヽค้้้้้้้้้้้้้้้้้้้้้้้้้้้้้้้้้้้้⊂⌒﹨\n ⊂ ノヽ_ω_ 人 ⊃"
        const val STRESS_ID = "demo_stress"
        const val NOW_MS = 1_790_000_000_000L
    }
}
