#!/usr/bin/env bash
# Kirim config.json (kota cuaca, kata kunci karaoke, API key penerbangan) ke Ringan di TV.
# File itu berisi API key AeroDataBox, jadi hanya disimpan di folder privat aplikasi.
#
# Pakai:
#   cp config.contoh.json config.json   # lalu isi API key AeroDataBox
#   ./scripts/kirim-config.sh            # atau: ./scripts/kirim-config.sh file-lain.json
# Set ANDROID_SERIAL=ip:port kalau ada lebih dari satu perangkat di `adb devices`.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG=${1:-config.json}
REMOTE_DIR=/sdcard/Android/data/id.ringan.launcher/files

if [[ ! -f $CONFIG ]]; then
  echo "$CONFIG belum ada. Salin dulu: cp config.contoh.json config.json" >&2
  exit 1
fi
python3 -I -m json.tool "$CONFIG" >/dev/null || { echo "$CONFIG bukan JSON yang valid." >&2; exit 1; }

adb shell mkdir -p "$REMOTE_DIR"
adb push "$CONFIG" "$REMOTE_DIR/config.json" >/dev/null
# Jangan force-stop: Android ikut mematikan layanan aksesibilitas "Ringan: tombol remote".
# Ringan membaca ulang config.json setiap kali papannya dibuka.
adb shell am start -n id.ringan.launcher/.MainActivity >/dev/null
echo "Konfigurasi terkirim dan dibaca ulang oleh Ringan."
