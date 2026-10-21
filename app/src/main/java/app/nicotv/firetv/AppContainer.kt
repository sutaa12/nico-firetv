package app.nicotv.firetv

import android.content.Context
import app.nicotv.firetv.data.CatalogProvider
import app.nicotv.firetv.data.LoadResult
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.demo.DemoProvider
import app.nicotv.firetv.data.local.LocalStore
import app.nicotv.firetv.data.nico.NicoProvider
import app.nicotv.firetv.data.nico.WebViewCookieJar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AuthState {
    data object Guest : AuthState
    data object Checking : AuthState
    data class LoggedIn(val userId: String, val nickname: String, val premium: Boolean) : AuthState
    data class Expired(val message: String) : AuthState
}

/** アプリ全体で共有する依存関係。 */
class AppContainer(context: Context) {
    val store = LocalStore(context)
    val cookieJar = WebViewCookieJar()
    /** 更新の少ない情報（シリーズ・動画情報・一覧の応答）の保存先 */
    val metaCache = app.nicotv.firetv.data.local.MetaCache(java.io.File(context.filesDir, "meta-cache"))
    val nico = NicoProvider(cookieJar, metaCache).also { p ->
        // センシティブな作品の表示設定を一覧の取得に反映する
        p.showSensitive = store.settings.showSensitive
    }
    /** シーク用の縮小画像の保存先 */
    val storyboards = app.nicotv.firetv.data.local.StoryboardStore(java.io.File(context.filesDir, "storyboard"))
    val demo = DemoProvider()

    /** 取得済みコメントの圧縮キャッシュと、その書き込み用（画面の寿命に左右されない） */
    val commentCache = app.nicotv.firetv.comments.CommentCache(java.io.File(context.filesDir, "comment-cache"))
    val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val _auth = MutableStateFlow<AuthState>(AuthState.Guest)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    /** アカウント・取得元の切替ごとに増える世代番号。古い応答を捨てるのに使う */
    @Volatile var generation: Int = 0
        private set

    val catalog: CatalogProvider
        get() = if (store.settings.dataSource == Provider.DEMO) demo else nico

    /** 再取得できる画質ラベルは最大1024件。保存要求も一件にまとめる。 */
    private val qualityFile = java.io.File(context.filesDir, "qualities.tsv")
    val qualities = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val qualityOrder = LinkedHashSet<String>()
    private val qualityWrites = kotlinx.coroutines.channels.Channel<Map<String, String>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val probing = HashSet<String>()

    private fun rememberQuality(k: String, q: String) {
        qualityOrder.remove(k); qualityOrder.add(k)
        qualities[k] = q
        while (qualityOrder.size > 1024) {
            val oldest = qualityOrder.first()
            qualityOrder.remove(oldest); qualities.remove(oldest)
        }
    }

    fun qualityOf(v: app.nicotv.firetv.data.VideoSummary): String? = v.maxQuality ?: qualities[app.nicotv.firetv.data.local.key(v.provider, v.id)]

    /** 設定でオンのときだけ、表示中のサムネイルの画質を調べる */
    suspend fun probeQuality(v: app.nicotv.firetv.data.VideoSummary) {
        if (!store.settings.probeQuality || v.provider != Provider.NICO || qualityOf(v) != null) return
        synchronized(probing) { if (!probing.add(v.id)) return }
        try { nico.probeQuality(v.id) } finally { synchronized(probing) { probing.remove(v.id) } }
    }

    init {
        runCatching { qualityFile.forEachLine { l ->
            val t = l.indexOf('\t')
            if (t in 1..128 && l.length <= 256) rememberQuality(l.substring(0, t), l.substring(t + 1))
        } }
        store.data.value.qualityCache.forEach { (k, v) -> rememberQuality(k, v) }
        ioScope.launch {
            for (snapshot in qualityWrites) {
                runCatching {
                    val tmp = java.io.File(qualityFile.parentFile, qualityFile.name + ".tmp")
                    tmp.writeText(snapshot.entries.joinToString("") { "${it.key}\t${it.value}\n" })
                    check(tmp.renameTo(qualityFile))
                }.onSuccess { store.takeLegacyQualities() }
            }
        }
        flushQualities()
        applyScope()
        nico.onQualityKnown = { id, q ->
            val k = app.nicotv.firetv.data.local.key(Provider.NICO, id)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (qualities[k] != q) {
                    rememberQuality(k, q)
                    flushQualities()
                }
            }
        }
    }

    fun flushQualities() { qualityWrites.trySend(qualities.toMap()) }

    private fun applyScope() {
        val scope = when {
            store.settings.dataSource == Provider.DEMO -> "demo"
            _auth.value is AuthState.LoggedIn -> "nico-id:" + (_auth.value as AuthState.LoggedIn).userId
            else -> "guest"
        }
        if (scope != store.scope.value) {
            generation++
            nico.invalidateSession()
            store.setScope(scope)
        }
    }

    fun setDataSource(p: Provider) {
        store.updateSettings { it.copy(dataSource = p) }
        applyScope()
    }

    /** 画面遷移ではなく、実際にユーザー情報を取得できたときだけログイン成功とする (05 §4)。 */
    suspend fun refreshAuth() {
        val session = cookieJar.sessionKey()
        if (!cookieJar.hasSession()) {
            _auth.value = AuthState.Guest
            applyScope()
            return
        }
        _auth.value = AuthState.Checking
        val result = nico.me()
        if (session != cookieJar.sessionKey()) {
            _auth.value = AuthState.Expired("ログイン状態が変わりました。再確認してください")
            applyScope()
            return
        }
        _auth.value = when (val r = result) {
            is LoadResult.Ok -> AuthState.LoggedIn(r.value.id, r.value.nickname, r.value.premium)
            is LoadResult.Failed -> AuthState.Expired(r.kind.label + "（" + r.message + "）")
        }
        applyScope()
    }

    fun logout(onDone: () -> Unit) {
        cookieJar.clearAll {
            _auth.value = AuthState.Guest
            applyScope()
            onDone()
        }
    }
}
