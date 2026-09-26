package app.nicotv.firetv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.nicotv.firetv.data.OwnerRef
import app.nicotv.firetv.data.Provider
import app.nicotv.firetv.data.VideoSummary
import app.nicotv.firetv.data.demo.DemoProvider
import app.nicotv.firetv.data.local.BoostLevel
import app.nicotv.firetv.ui.AppState
import app.nicotv.firetv.ui.Route
import app.nicotv.firetv.ui.NicoTvApp
import app.nicotv.firetv.ui.NicoTvTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as NicoTvApplication).container }

    override fun onStop() {
        super.onStop()
        container.flushQualities()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 再生中にスクリーンセーバーへ落ちないようにする
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            NicoTvTheme {
                val accountScope by container.store.scope.collectAsState()
                LaunchedEffect(Unit) { container.refreshAuth() }
                key(accountScope) {
                    val scope = rememberCoroutineScope()
                    val app = remember { AppState(container, scope, exitApp = { finish() }) }
                    LaunchedEffect(Unit) { startStressTestIfRequested(app) }
                    NicoTvApp(app)
                }
            }
        }
    }

    /**
     * デバッグ版のみ: adb から負荷試験を始める。例
     * adb shell am start -n app.nicotv.firetv/.MainActivity --es test stress --ei max 250 --ez danmaku true --es boost ABS30K
     */
    private fun startStressTestIfRequested(app: AppState) {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        // 投稿者ページの確認用: --es test owner --es owner ch2650205
        if (intent?.getStringExtra("test") == "owner") {
            val id = intent.getStringExtra("owner") ?: return
            app.navigate(Route.Owner(OwnerRef(Provider.NICO, id, null, null)))
            return
        }
        if (intent?.getStringExtra("test") == "detail") {
            val id = intent.getStringExtra("video") ?: return
            DebugOverrides.slowSeries = true
            DebugOverrides.demoStoryboard = intent.getBooleanExtra("storyboard", false)
            val v = container.demo.let { d -> kotlinx.coroutines.runBlocking { d.videoDetail(id) } }.let { (it as? app.nicotv.firetv.data.LoadResult.Ok)?.value?.summary } ?: return
            app.navigate(Route.Detail(v))
            return
        }
        if (intent?.getStringExtra("test") != "stress") return
        DebugOverrides.active = true
        DebugOverrides.perfLog = true
        DebugOverrides.maxOnScreen = intent.getIntExtra("max", -1).takeIf { it > 0 }
        DebugOverrides.commentsOff = intent.getBooleanExtra("off", false)
        DebugOverrides.noHalo = intent.getBooleanExtra("nohalo", false)
        DebugOverrides.noOffscreen = intent.getBooleanExtra("nooffscreen", false)
        DebugOverrides.danmaku = if (intent.hasExtra("danmaku")) intent.getBooleanExtra("danmaku", false) else null
        DebugOverrides.boost = intent.getStringExtra("boost")?.let { runCatching { BoostLevel.valueOf(it) }.getOrNull() }
        if (intent.hasExtra("fade")) app.c.store.updateSettings { it.copy(fadeOldComments = intent.getBooleanExtra("fade", false)) }
        val v = VideoSummary(Provider.DEMO, DemoProvider.STRESS_ID, "負荷テスト")
        app.navigate(Route.Player(listOf(app.newEntry(v, "負荷テスト")), 0, intent.getLongExtra("startMs", 0L), continuous = false, seriesId = null, seriesTitle = null))
    }
}
