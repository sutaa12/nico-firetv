package app.nicotv.firetv.data

/**
 * 機能ごとの対応状況 (SYS-08 / 06 §2 Capability Registry)。
 * 未検証・未対応・公式画面のみを区別し、画面だけ動く状態を「対応済み」と表示しない。
 */
enum class CapState(val label: String) {
    LOCAL_OK("端末内で動作"),
    VERIFIED_API("応答形式を確認済み"),
    UNVERIFIED("実装済み・実機未検証"),
    OFFICIAL_ONLY("公式画面へ案内"),
    NOT_IMPLEMENTED("未対応"),
}

data class Capability(val ids: String, val name: String, val state: CapState, val note: String)

object Capabilities {
    const val CHECKED_AT = "2026-09-25"

    val all = listOf(
        Capability("U-01 / TVT-01", "リモコン操作（方向キー・決定・戻る）", CapState.LOCAL_OK, "全画面を方向キー・決定・戻るだけで操作できる設計。Fire TV 実機での確認は未実施"),
        Capability("RNK-01〜08", "公式ランキング", CapState.VERIFIED_API, "nvapi ranking/genre の応答を開発用Macで確認。ジャンル区分はサービス変更で使えなくなる可能性あり"),
        Capability("SRC-01〜02", "キーワード・タグ検索", CapState.VERIFIED_API, "公式スナップショット検索APIを使用。結果は定期更新のため最新ではない"),
        Capability("SER-01", "シリーズの正式順序", CapState.VERIFIED_API, "nvapi v2/series の order を使用。タイトルの話数からは推測しない"),
        Capability("VID-01", "動画情報", CapState.VERIFIED_API, "watch v3_guest の応答を確認。ログイン時は v3 を使用（未検証）"),
        Capability("VID-03 / P0", "無料動画の再生", CapState.UNVERIFIED, "HLS（domand）取得は開発環境から接続確認できていない。実機で最初に確認が必要"),
        Capability("PAY-01〜02", "有料・チャンネル会員限定動画の再生", CapState.UNVERIFIED, "ログイン済みで権利があれば同じ経路で再生を試みる。DRM 保護作品は未対応。P0 未成立"),
        Capability("PAY-06", "購入・加入", CapState.OFFICIAL_ONLY, "QRコードで公式ページを案内。アプリ内では購入しない"),
        Capability("SYS-03", "ログイン", CapState.LOCAL_OK, "公式ログイン画面を WebView（疑似カーソル付き）で表示し、その Cookie を利用。実機でログインでき、アカウントの履歴取得まで確認"),
        Capability("COM-01〜02", "通常コメントの表示・同期", CapState.UNVERIFIED, "描画と同期は端末内で実装。コメント取得（nvcomment）は実機未検証"),
        Capability("COM-03〜05 / P0", "過去コメント増量", CapState.UNVERIFIED, "when 指定の過去ログ取得を最大10ページ・直列で実行。ログインやプレミアムが必要な場合がある。P0 未成立"),
        Capability("CACHE-01", "動画情報・シリーズ・一覧の保存", CapState.LOCAL_OK, "説明・タグ12時間、シリーズは小さな確認で増えた話だけ取得、ランキング10分〜2時間、検索30分、投稿者15分〜1日、画質は無期限。合計64MBまで"),
        Capability("PLY-SB", "シーク中の縮小画像", CapState.UNVERIFIED, "ログイン時のみ。公式のストーリーボード（access-rights/storyboard）を再生開始3秒後に最低の優先度で取得し、動画ごとに保存。表示はエミュレータのデモで確認、実際の取得は実機未検証"),
        Capability("PREM-01", "最高画質を優先・再生速度の記憶", CapState.LOCAL_OK, "回線の推定で画質を下げず最高画質（プレミアム会員は1080pなど）を選ぶ（既定オン）。再生速度（0.5〜2倍）は次の動画・次回にも使う"),
        Capability("PREM-02", "コメントの時点（過去ログ）", CapState.UNVERIFIED, "投稿1日後〜1年後・1年前〜10年前から選び、その時点までのコメントだけを流す。足りなければその時点から過去ログを取る。実機未検証"),
        Capability("COM-15", "取得済みコメントの圧縮保存", CapState.LOCAL_OK, "動画ごとに圧縮して保存し、次回は展開して不足分だけ取得（1件あたり約11バイト、合計300MB・30日まで）"),
        Capability("EMOJI", "新しい絵文字の表示", CapState.LOCAL_OK, "Noto Color Emoji（Unicode 18 まで）を同梱し、コメントの絵文字だけ描き分ける"),
        Capability("LIB-07", "投稿者のページ（投稿動画・シリーズ・マイリスト）", CapState.VERIFIED_API, "ユーザーは応答を確認済み。チャンネルの動画は公開 RSS（新しい順）で取得。チャンネルのシリーズ一覧は公開の取得方法がない"),
        Capability("COM-06〜08", "表示密度・見た目・端末内NG", CapState.LOCAL_OK, "取得量と同時描画数を分離。NG はこのアプリ内のみ（公式NGとは同期しない）"),
        Capability("COM-16", "古いコメントを薄く", CapState.LOCAL_OK, "投稿日時の順位で、いちばん新しいものは100%、いちばん古いものは15%。投稿者コメントはそのまま"),
        Capability("COM-10〜11", "コメント投稿", CapState.NOT_IMPLEMENTED, "P2。未実装"),
        Capability("HIS-01〜03", "端末内の視聴履歴・再開位置", CapState.LOCAL_OK, "5秒ごと＋停止・移動時に保存。実視聴区間で視聴済みを判定"),
        Capability("HIS-04", "アカウントの視聴履歴の読込", CapState.VERIFIED_API, "左メニューの［視聴履歴］。nvapi v2 を実機で確認（20件ずつ・続きは自動読込）。公式の再生位置は含まれない"),
        Capability("HIS-05〜06", "公式履歴への反映・再開位置同期", CapState.NOT_IMPLEMENTED, "P2。端末内の履歴だけを保存し、同期済みとは表示しない"),
        Capability("SER-02〜05", "シリーズ一気再生・自動次話", CapState.LOCAL_OK, "自然終了後にカウントダウン。視聴不可の話で停止"),
        Capability("LIB-01", "自分のマイリスト・あとで見る（閲覧・連続再生）", CapState.UNVERIFIED, "ライブラリの［マイリスト］。v1/users/me/mylists・v1/users/me/watch-later（形式は公開ライブラリの実装で確認、実機未検証）。編集は未対応"),
        Capability("LIB-02〜06", "マイリスト編集・フォロー・いいね", CapState.NOT_IMPLEMENTED, "P2"),
        Capability("PLY-LOOP", "ループ再生（1本・リスト全体）", CapState.LOCAL_OK, "操作バーの［ループ］で オフ→この動画→リスト全体。リスト全体は最後の次に最初へ戻り、自動再生もオンになる。シリーズ・一覧ごとに記憶"),
        Capability("PAY-04", "アニメ作品棚", CapState.NOT_IMPLEMENTED, "利用者の判断で左メニューから外した。アニメはランキングのアニメ区分・検索・シリーズから探せる"),
        Capability("LIV-01〜10", "生放送・タイムシフト", CapState.NOT_IMPLEMENTED, "利用者の判断で左メニューから外した"),
        Capability("EXT-02", "OSメディア連携", CapState.UNVERIFIED, "MediaSession を登録。Alexa 音声操作は未検証"),
    )
}
