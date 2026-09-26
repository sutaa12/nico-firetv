package app.nicotv.firetv.data.nico

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 公式ログイン画面 (WebView) と API・動画配信で同じ Cookie を共有する。
 * パスワードはアプリで扱わず、公式画面で本人が入力したセッションだけを使う (05 §3)。
 */
class WebViewCookieJar : CookieJar {
    private val manager: CookieManager by lazy {
        CookieManager.getInstance().also { it.setAcceptCookie(true) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val base = url.toString()
        cookies.forEach { manager.setCookie(base, it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = manager.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    private fun session(): String? = manager.getCookie("https://www.nicovideo.jp/")
        ?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("user_session=") }
        ?.substringAfter('=')?.takeIf { it.isNotBlank() }

    fun hasSession(): Boolean = session() != null

    fun sessionKey(): String = session()?.let { app.nicotv.firetv.data.local.cacheScope(it) } ?: "guest"

    fun clearAll(onDone: () -> Unit) {
        manager.removeAllCookies { manager.flush(); onDone() }
    }
}
