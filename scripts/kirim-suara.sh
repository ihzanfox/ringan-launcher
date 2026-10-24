#!/usr/bin/env bash
# Rapikan rekaman pengumuman di suara/asli/ lalu kirim ke Ringan di TV.
# Nama file, bel bawaan Voice Studio, hening, dan volume diurus scripts/rapikan-suara.py.
# Kalimat yang belum direkam tetap dibacakan suara TTS, jadi rekaman boleh dikirim sebagian.
#
# Pakai:
#   ./scripts/kirim-suara.sh
# Set ANDROID_SERIAL=ip:port kalau ada lebih dari satu perangkat di `adb devices`.
set -euo pipefail
cd "$(dirname "$0")/.."

REMOTE_DIR=/sdcard/Android/data/id.ringan.launcher/files/suara

python3 -I scripts/rapikan-suara.py suara/asli suara/siap
shopt -s nullglob
files=(suara/siap/*.wav suara/siap/*.mp3 suara/siap/*.ogg)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "Tidak ada rekaman untuk dikirim. Taruh file di suara/asli/ dulu." >&2
  exit 1
fi

# Folder di TV dikosongkan dulu, supaya rekaman yang dihapus kembali ke suara TTS.
adb shell rm -rf "$REMOTE_DIR"
adb shell mkdir -p "$REMOTE_DIR"
adb push "${files[@]}" "$REMOTE_DIR/" 2>&1 | tail -1
# Tidak perlu restart: Ringan mencari rekaman setiap kali akan mengumumkan.
echo "${#files[@]} rekaman terkirim ke TV."
