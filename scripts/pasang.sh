#!/usr/bin/env bash
# Build Ringan, pasang ke TV lewat adb, lalu jadikan launcher utama.
#
# Pakai:
#   ./scripts/pasang.sh 192.168.1.20         # alamat IP TV (port 5555)
#   ./scripts/pasang.sh 192.168.1.20:37123   # IP dan port dari "Wireless debugging"
#   ./scripts/pasang.sh                      # TV sudah terhubung ke adb
set -euo pipefail
cd "$(dirname "$0")/.."

APK=app/build/outputs/apk/release/app-release.apk
HOME_ACTIVITY=id.ringan.launcher/.MainActivity
STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [[ -z ${JAVA_HOME:-} && -d $STUDIO_JDK ]]; then
  export JAVA_HOME=$STUDIO_JDK
fi

if [[ $# -ge 1 ]]; then
  target=$1
  [[ $target == *:* ]] || target="$target:5555"
  adb connect "$target"
  export ANDROID_SERIAL=$target
fi

./gradlew -q assembleRelease
adb install -r "$APK"

if adb shell cmd package set-home-activity "$HOME_ACTIVITY"; then
  echo "Ringan sekarang launcher utama."
else
  echo "Gagal mengatur launcher utama otomatis. Tekan Home di remote lalu pilih Ringan."
fi
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo
echo "Kalau tombol Home masih membuka launcher bawaan (umum di Google TV), jalankan:"
echo "  ./scripts/launcher-bawaan.sh matikan"
