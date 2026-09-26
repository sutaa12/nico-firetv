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
if [ -n "${JAVA_HOME:-}" ]; then
    if [ ! -x "$JAVA_HOME/bin/java" ]; then
        echo "JAVA_HOME の場所に Java がありません。JDK 17 の場所を確認してください。" >&2
        exit 1
    fi
elif ! command -v java >/dev/null 2>&1; then
    echo "Java が見つかりません。JDK 17 を設定してください。" >&2
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
