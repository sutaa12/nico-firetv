# 第三者の権利表記

[本プロジェクトの MIT License](LICENSE)は、以下の第三者の著作物のライセンスを置き換えません。
再配布する場合は、該当するライセンスと著作権表示を保ってください。

## 同梱フォント

### Noto Color Emoji

- ファイル：`app/src/main/assets/NotoColorEmoji.ttf`
- 配布元：[googlefonts/noto-emoji](https://github.com/googlefonts/noto-emoji)
- ライセンス：[SIL Open Font License 1.1](app/src/main/assets/licenses/NotoColorEmoji_OFL.txt)
- 著作権表示：Copyright 2013 Google LLC（配布元ライセンス）、Copyright 2022 Google Inc.（同梱フォントのメタデータ）。

フォントのメタデータは Version 2.057 です。

### BIZ UDPGothic Bold

- ファイル：`app/src/main/res/font/biz_udp_gothic_bold.ttf`
- 配布元：[googlefonts/morisawa-biz-ud-gothic](https://github.com/googlefonts/morisawa-biz-ud-gothic)
- ライセンス：[SIL Open Font License 1.1](app/src/main/assets/licenses/BIZ_UDPGothic_OFL.txt)
- 著作権表示：Copyright 2022 The BIZ UDGothic Project Authors。

フォントのメタデータは Version 1.051 です。
OFL 文書は配布元の表記を保持しています。
これらのフォントは MIT ではなく OFL の条件で配布されます。

## 参考にした実装

### niconicomments

コメントの文字サイズ・行数の計算にあたり、[xpadev-net/niconicomments](https://github.com/xpadev-net/niconicomments) を参考にしています。
Copyright (c) 2022 xpadev-net。
[MIT ライセンス全文](app/src/main/assets/licenses/niconicomments_MIT.txt)を同梱しています。

### niconico.py

API の形式やストーリーボードの取得手順の確認に、[niconicolibs/niconico.py](https://github.com/niconicolibs/niconico.py) を参考にしています。
Copyright (c) 2024 Negima1072。
[MIT ライセンス全文](app/src/main/assets/licenses/niconico.py_MIT.txt)を同梱しています。

### Grayjay のニコニコプラグイン

マイリストと「あとで見る」の API URL・応答フィールドの確認に、[futo-org/grayjay-plugin-niconico](https://github.com/futo-org/grayjay-plugin-niconico) を参照しています。
同プロジェクトは AGPL-3.0 です。
Grayjay のコードやプラグイン自体は、本リポジトリ・APK に同梱していません。

## ビルドに同梱するもの

`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` は [Gradle](https://github.com/gradle/gradle/tree/v8.11.1) の Gradle Wrapper です。
[Apache License 2.0](gradle/wrapper/LICENSE) を適用し、各ファイルに含まれる表記も保持しています。
Wrapper JAR は Gradle 8.11.1 の[公式チェックサム](https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256)と照合しています。

## ビルド時に取得する主な依存ライブラリ

| ライブラリ | ライセンス・配布元 |
| --- | --- |
| AndroidX / Compose / TV / Media3 | [Apache-2.0 / AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| Kotlin | [Apache-2.0 / JetBrains](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| kotlinx.coroutines | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| kotlinx.serialization | [Apache-2.0](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt) |
| OkHttp | [Apache-2.0](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| Coil | [Apache-2.0](https://github.com/coil-kt/coil/blob/main/LICENSE.txt) |
| ZXing | [Apache-2.0](https://github.com/zxing/zxing/blob/master/LICENSE) |
| JUnit 4（テスト用） | [EPL-1.0](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |

バージョンの正本は [app/build.gradle.kts](app/build.gradle.kts) です。
推移的な依存関係にも各配布元の条件が適用されます。
APK などを再配布する場合は、そのビルドで実際に含まれる依存物のライセンスと NOTICE も確認してください。

## サービス上のコンテンツ

アプリが取得する動画、サムネイル、コメントなどの権利は、各権利者に帰属します。
デモ動画も外部配信元から取得するもので、本リポジトリには同梱していません。
「ニコニコ」「Fire TV」などの名称や商標に、本プロジェクトの MIT ライセンスが適用されるわけではありません。
