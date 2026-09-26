package app.nicotv.firetv.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.nicotv.firetv.AuthState
import app.nicotv.firetv.data.nico.NicoProvider
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.Palette
import app.nicotv.firetv.ui.TvText
import kotlinx.coroutines.launch

/**
 * 公式ログイン画面を表示する (05 §3 の「WebView上の公式ログイン」)。
 * 入力は公式ページに対して行われ、アプリはパスワードを読まない。
 * 画面遷移ではなく、ユーザー情報を取得できたときだけ成功とする。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("ニコニコ公式のログイン画面を読み込み中…") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var checking by remember { mutableStateOf(false) }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else app.back()
    }

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp)) {
            Text("ニコニコにログイン（公式画面）", fontSize = TvText.heading)
            Text(
                "方向キーで矢印を動かし、決定でクリック（入力欄をクリックするとキーボード）。画面の端でさらに押すとスクロール、早送り・巻戻しで大きくスクロール。戻るでキーボードを閉じる／前のページ／中止。 $status",
                fontSize = TvText.small, color = Palette.textDim,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val web = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString + " NicoTV-FireTV/0.1"
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            status = "読み込み中…"
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            status = ""
                            if (checking || !app.c.cookieJar.hasSession()) return
                            checking = true
                            status = "ログインを確認しています…"
                            scope.launch {
                                app.c.refreshAuth()
                                val a = app.c.auth.value
                                if (a is AuthState.LoggedIn) {
                                    app.back()
                                } else {
                                    status = "ログインを確認できませんでした。もう一度お試しください。"
                                    checking = false
                                }
                            }
                        }
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    loadUrl(NicoProvider.LOGIN_URL)
                    webView = this
                }
                // Web ページは方向キーでボタンを選べないため、疑似マウスカーソルで操作する
                CursorWebLayout(ctx, web).also { it.post { it.requestFocus() } }
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
}
