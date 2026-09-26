# ビルドとインストール

[README に戻る](../README.md) · [使い方](USER_GUIDE.md)

この手順では、PC で APK を作り、同じネットワーク上の Fire TV にインストールします。
APK の作成を「ビルド」、ストアを使わずに端末へ入れることを「サイドロード」と呼びます。
初回は開発ツールやライブラリをダウンロードするため、インターネット接続とPCの空き容量が必要です。

## 1. PC を準備する

必要なものは次のとおりです。

- [Android Studio](https://developer.android.com/studio) と Android SDK。
- JDK 17。Android Studio を使う場合も、プロジェクトの Gradle JDK を JDK 17 に設定します。
- SDK Manager でインストールする Android SDK Platform 35、Android SDK Build-Tools 34.0.0、Android SDK Platform-Tools（`adb` を含みます）。
- Git、または GitHub の「Code → Download ZIP」で入手したソースコード。

ビルドツールのバージョンは[ルートの設定](../build.gradle.kts)と[Gradle Wrapper の設定](../gradle/wrapper/gradle-wrapper.properties)で固定しています。
Gradle を別途インストールする必要はありません。

Git を使う場合は、次のように取得します。

```sh
git clone https://github.com/sutaa12/nico-firetv.git
cd nico-firetv
```

Android Studio でこのフォルダを開き、SDK の場所を設定して同期します。
`local.properties` は各PCで作られる設定ファイルなので、Git には含めません。

ターミナルからビルドする場合は、`JAVA_HOME` を JDK 17 の場所に、`ANDROID_HOME` を Android SDK の場所に設定してください。
`adb` は SDK 内の `platform-tools` にあります。
そこを `PATH` に追加し、次のコマンドが使えることを確認します。

```sh
java -version
adb version
```

## 2. APK を作る

プロジェクトのフォルダで実行します。

macOS / Linux:

```sh
./gradlew :app:assembleDebug
```

Windows（PowerShell）:

```powershell
.\gradlew.bat :app:assembleDebug
```

成功すると `app/build/outputs/apk/debug/app-debug.apk` ができます。
個人で試すための debug APK です。

## 3. Fire TV を接続する

1. PC と Fire TV を同じ信頼できるネットワークに接続します。
2. Fire TV の「設定 → マイ Fire TV → 開発者オプション」で ADB デバッグを有効にします。
3. 開発者オプションが見当たらない場合は「バージョン情報」で端末名を7回選ぶ手順を確認します。名称や手順は機種によって異なります。
4. Fire TV のネットワーク情報で IP アドレスを確認します。

詳しくは [Amazon 公式の ADB 接続手順](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html)を参照してください。
端末が追加のインストール許可を求めた場合は、その画面と公式手順に従います。

以下の `FIRE_TV_IP` は、そのまま入力せず、確認した IP アドレスに置き換えてください。

```sh
adb connect FIRE_TV_IP:5555
```

テレビに接続確認が出たら、この PC からの接続を許可します。

```sh
adb -s FIRE_TV_IP:5555 get-state
```

`device` と表示されたら、インストールできます。
`unauthorized` はテレビ側の接続許可待ち、`offline` は未接続の状態です。

## 4. インストールして起動する

```sh
adb -s FIRE_TV_IP:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FIRE_TV_IP:5555 shell am start -n app.nicotv.firetv/.MainActivity
```

インストール後は Fire TV のアプリ一覧から「NicoTV」を開けます。
操作方法は[使い方](USER_GUIDE.md)を参照してください。
作業が終わったら ADB デバッグをオフにします。

macOS / Linux では、PC の準備と Fire TV の設定を済ませた後、次のスクリプトでビルド・接続・インストール・起動をまとめて実行することもできます。

```sh
./scripts/install-firetv.sh FIRE_TV_IP
```

## 更新するとき

同じソースフォルダと署名鍵で再ビルドし、同じ `install -r` の手順で更新します。
アプリのデータは通常そのまま引き継がれます。

別のPCで作った debug APK や、署名鍵を変更した APK は上書きできない場合があります。
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` が出たら、以前と同じ署名鍵を使ってください。
アンインストールや端末設定の「データを消去」をすると、ログイン情報・履歴・お気に入りなどが失われます。
これらを更新エラーの解決策としてすぐに実行しないでください。

## 自分で配布する場合

release ビルドには署名鍵を設定していません。
配布用には自分の署名鍵を安全に用意し、[Android 公式の署名手順](https://developer.android.com/studio/publish/app-signing)に従ってください。
秘密鍵、パスワード、`local.properties`、実際のアカウントデータは Git に追加しないでください。

本プロジェクトの MIT 表記に加え、[第三者のライセンス](../THIRD_PARTY_NOTICES.md)を維持してください。
公開ソースからビルドできることと、各サービス・ストアで配布が認められることは別です。

## ビルドで困ったとき

| 症状 | 確認すること |
| --- | --- |
| Java が見つからない・バージョンが違う | `JAVA_HOME` と Android Studio の Gradle JDK が JDK 17 を指しているか |
| SDK location not found | Android Studio の SDK 設定、`local.properties` または `ANDROID_HOME` |
| SDK やライセンスが不足 | SDK Manager で必要なパッケージとライセンスを確認する |
| `adb` が見つからない | Platform-Tools のインストールと `PATH` |
| Fire TV に接続できない | IP アドレス、同じネットワークか、ADB デバッグ、テレビの接続許可 |
| `gradlew` の実行権限がない | macOS / Linux で `chmod +x gradlew scripts/install-firetv.sh` を実行する |
