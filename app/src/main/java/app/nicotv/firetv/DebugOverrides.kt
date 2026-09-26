package app.nicotv.firetv

import app.nicotv.firetv.data.local.BoostLevel
import app.nicotv.firetv.data.local.CollisionPolicy
import app.nicotv.firetv.data.local.CommentMode
import app.nicotv.firetv.data.local.Settings

/**
 * 負荷試験用の一時的な上書き（デバッグ版のみ・保存しない）。
 * adb から起動したときだけ使い、利用者の設定や視聴履歴は変更しない。
 */
object DebugOverrides {
    @Volatile var active = false
    /** デモのシリーズ取得をわざと遅くする（再生開始が待たないことの確認用） */
    @Volatile var slowSeries = false
    /** デモ動画にも縮小画像（数字を描いただけの画像）を付ける（シーク表示の確認用） */
    @Volatile var demoStoryboard = false
    @Volatile var maxOnScreen: Int? = null
    @Volatile var danmaku: Boolean? = null
    @Volatile var boost: BoostLevel? = null
    /** 描画時間を集計してログへ出す */
    @Volatile var perfLog = false
    /** 比較用: コメントを出さない / ぼかし影を使わない / 不透明度用の合成層を使わない */
    @Volatile var commentsOff = false
    @Volatile var noHalo = false
    @Volatile var noOffscreen = false

    fun apply(s: Settings): Settings {
        if (!active) return s
        return s.copy(
            commentMode = if (commentsOff) CommentMode.OFF else CommentMode.BOOST,
            boostConsented = true,
            boostLevel = boost ?: s.boostLevel,
            collision = when (danmaku) { true -> CollisionPolicy.DANMAKU; false -> CollisionPolicy.READABLE; null -> s.collision },
            saveWatchHistory = false,
        )
    }
}
