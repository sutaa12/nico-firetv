package app.nicotv.firetv.ui.player

/** 準備完了・バッファ待ち・次話待ちのどの段階でも、非表示にすると再生要求を解除する。 */
class PlaybackGate(var foreground: Boolean) {
    private var requested = true
    val canPlay: Boolean get() = foreground && requested
    fun resumeScreen() { foreground = true }
    fun hideScreen() { foreground = false; requested = false }
    fun requestPlay(): Boolean { if (foreground) requested = true; return canPlay }
    fun pause() { requested = false }
}
