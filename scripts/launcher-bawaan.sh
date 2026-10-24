#!/usr/bin/env bash
# Matikan atau nyalakan lagi launcher bawaan Android TV / Google TV.
# Google TV selalu kembali ke launcher bawaannya saat Home ditekan, jadi launcher itu
# perlu dimatikan agar Ringan yang terbuka. Aplikasinya tidak dihapus dan bisa dinyalakan lagi.
#
# Pakai:
#   ./scripts/launcher-bawaan.sh matikan
#   ./scripts/launcher-bawaan.sh nyalakan
# Set ANDROID_SERIAL=ip:port kalau ada lebih dari satu perangkat di `adb devices`.
set -euo pipefail

PACKAGES=(
  com.google.android.apps.tv.launcherx     # Google TV
  com.google.android.tungsten.setupwraith  # Google TV: mengambil alih Home kalau launcherx mati
  com.google.android.tvlauncher            # Android TV
  com.google.android.leanbacklauncher      # Android TV lama (7 ke bawah)
)

action=${1:-}
if [[ $action != matikan && $action != nyalakan ]]; then
  echo "Pakai: $0 matikan|nyalakan" >&2
  exit 1
fi

if [[ $action == matikan ]] && ! adb shell pm path id.ringan.launcher >/dev/null 2>&1; then
  echo "Ringan belum terpasang. Jalankan ./scripts/pasang.sh dulu supaya TV tetap punya launcher." >&2
  exit 1
fi

installed=$(adb shell pm list packages | tr -d '\r')
for pkg in "${PACKAGES[@]}"; do
  grep -qx "package:$pkg" <<<"$installed" || continue
  if [[ $action == matikan ]]; then
    adb shell pm disable-user --user 0 "$pkg"
  else
    adb shell pm enable "$pkg"
  fi
done

adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null

if [[ $action == matikan ]]; then
  # Tanpa launcher Google TV, tombol Settings di remote membuat penerima tombol bawaan crash.
  # Layanan aksesibilitas Ringan mencegahnya, tapi harus dinyalakan sendiri di TV.
  echo
  echo "Nyalakan 'Ringan: tombol remote' di Setelan → Aksesibilitas (bagian Layanan),"
  echo "lalu restart TV sekali. Halamannya dibuka sekarang kalau tersedia."
  adb shell am start -n com.android.tv.settings/.oemlink.AccessibilitySettingsActivity >/dev/null 2>&1 || true
fi
