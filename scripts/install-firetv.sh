#!/bin/sh
# macOS / Linux: ./scripts/install-firetv.sh <Fire TV の IP アドレス>
set -eu

usage() {
    echo "使い方: $0 <Fire TV の IP アドレス>" >&2
    echo "JDK 17、Android SDK、adb が必要です。詳しくは docs/BUILDING.md を参照してください。" >&2
}

if [ "$#" -eq 1 ] && { [ "$1" = "--help" ] || [ "$1" = "-h" ]; }; then
    usage
    exit 0
fi
if [ "$#" -ne 1 ]; then
    usage
    exit 1
fi
case "$1" in
    ''|-*|*[!A-Za-z0-9.-]*)
        echo "Fire TV の IP アドレスを、ポート番号を付けずに指定してください。" >&2
        exit 1
        ;;
esac
if ! command -v adb >/dev/null 2>&1; then
    echo "adb が見つかりません。Android SDK Platform-Tools を PATH に追加してください。" >&2
    exit 1
fi
# JDK 17 を探す。macOS の /usr/bin/java は Java が無くても存在する入口なので、実際に動くか確かめる
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    JAVA_HOME=""
    for candidate in \
        "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
        /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -n "$JAVA_HOME" ]; then
    export JAVA_HOME
elif ! java -version >/dev/null 2>&1; then
    echo "JDK 17 が見つかりません。brew install openjdk@17 で入れるか、JAVA_HOME を設定してください。" >&2
    exit 1
fi

TARGET="$1:5555"
cd "$(dirname "$0")/.."

echo "▶ APK をビルドします"
./gradlew :app:assembleDebug

echo "▶ Fire TV に接続します。テレビに確認画面が出たら、この PC からの接続を許可してください。"
adb connect "$TARGET"
attempt=0
while [ "$(adb -s "$TARGET" get-state 2>/dev/null || true)" != "device" ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        echo "接続できませんでした。テレビの確認画面、ADB デバッグ、IP アドレスを確認してください。" >&2
        exit 1
    fi
    sleep 2
done

echo "▶ インストールします"
adb -s "$TARGET" install -r app/build/outputs/apk/debug/app-debug.apk
echo "▶ 起動します"
adb -s "$TARGET" shell am start -n app.nicotv.firetv/.MainActivity
echo "✔ 完了。作業後は Fire TV の ADB デバッグをオフにしてください。"
