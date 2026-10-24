#!/usr/bin/env python3
"""Rapikan rekaman pengumuman Ringan sebelum dikirim ke TV (dipanggil oleh kirim-suara.sh).

Untuk tiap WAV di folder asli:
- beri nama sesuai naskah: ekspor Voice Studio seperti
  "penumpang-tujuan-youtube-silakan-naik_voice-studio_kore_....wav" menjadi naik_youtube.wav;
- buang bel yang ditambahkan Voice Studio di awal dan akhir; bel pembukanya disimpan sekali
  sebagai bel.wav, karena Ringan memutar bel sendiri sebelum setiap kalimat;
- potong hening di awal dan akhir, sisakan 0,1 detik;
- samakan kerasnya suara antarfile, puncak paling tinggi -1 dBFS.

File MP3/OGG (misalnya azan) disamakan kerasnya lewat ffmpeg dan disimpan sebagai MP3. Tanpa
ffmpeg, file itu disalin apa adanya.

Pakai: python3 scripts/rapikan-suara.py [folder-asli] [folder-hasil]
"""
import array
import math
import re
import shutil
import subprocess
import sys
import wave
from pathlib import Path

WINDOW_S = 0.01
SILENCE_DB = -55     # ekor bel pembuka dianggap habis di bawah ini
EDGE_DB = -50        # batas potong hening di ujung kalimat
SPEECH_DB = -40      # bagian yang dipakai untuk mengukur kerasnya suara
PAD_S = 0.1
FADE_S = 0.03
TARGET_DB = -20      # kerasnya bagian bicara (RMS), sama untuk semua file
TARGET_LUFS = -20    # kerasnya MP3/OGG; -20 dBFS RMS pada kalimat pengumuman terukur sekitar -20 LUFS
PEAK_DB = -1
BELL_S = 1.8         # bel pembuka dipendekkan, ekornya dipudarkan
BELL_FADE_S = 0.4

NAMES = [
    (r"penumpang-tujuan-(.+?)(?:-silakan.*)?", lambda m: "naik_" + m.group(1).replace("-", "_")),
    (r"perhatian-waktu-sholat-([a-z]+).*", lambda m: "sholat_" + m.group(1)),
    (r"perhatian-pesawat-telah-mendarat.*", lambda m: "mendarat"),
    (r"perhatian-pesawat-akan-mendarat.*", lambda m: "mendarat_5_menit"),
    (r"perhatian-jatah-terbang.*", lambda m: "jatah_habis"),
    (r"perhatian-gerbang-sudah-ditutup.*", lambda m: "waktu_tidur"),
    # Sudah memakai nama di naskah.
    (r"([a-z0-9_]+)", lambda m: m.group(1)),
]


def target_name(stem):
    stem = stem.split("_voice-studio")[0].lower()
    for pattern, name in NAMES:
        match = re.fullmatch(pattern, stem)
        if match:
            return name(match)
    return None


def read_wav(path):
    with wave.open(str(path)) as w:
        if w.getsampwidth() != 2:
            raise ValueError("hanya WAV 16-bit yang didukung")
        channels, rate = w.getnchannels(), w.getframerate()
        data = array.array("h", w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        data.byteswap()
    if channels > 1:
        data = array.array("h", (sum(data[i:i + channels]) // channels for i in range(0, len(data), channels)))
    return data, rate


def write_wav(path, samples, rate):
    data = array.array("h", samples)
    if sys.byteorder == "big":
        data.byteswap()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(data.tobytes())


def db(rms):
    return 20 * math.log10(rms / 32767) if rms > 0 else -120.0


def levels(samples, rate):
    """Kerasnya tiap potongan 10 ms, dalam dBFS."""
    w = int(rate * WINDOW_S)
    return [db(math.sqrt(sum(s * s for s in samples[i:i + w]) / w)) for i in range(0, len(samples) - w + 1, w)], w


def voice_studio_bounds(level, w, rate):
    """(akhir bel pembuka, awal bel penutup) dalam sampel; None kalau polanya tidak ditemukan."""
    after = int(1.5 / WINDOW_S)
    intro = next((k for k in range(after, len(level)) if level[k] < SILENCE_DB), None)
    if intro is None:
        return None
    # Bel penutup diawali hentakan dari hening, setelah kalimat terakhir.
    onsets = [k for k in range(intro + 1, len(level) - 3) if level[k - 1] < EDGE_DB and max(level[k:k + 3]) > -30]
    if not onsets or (len(level) - onsets[-1]) * WINDOW_S > 4:
        return None
    return intro * w, onsets[-1] * w


def trim(samples, level, w, rate, start, end):
    """Potong hening di ujung bagian [start, end), sisakan PAD_S."""
    loud = [k for k in range(start // w, end // w) if level[k] > EDGE_DB]
    if not loud:
        return None
    pad = int(rate * PAD_S)
    return max(start, loud[0] * w - pad), min(end, (loud[-1] + 1) * w + pad)


def gain_for(samples, rate):
    """Penguat supaya bagian bicara sekeras TARGET_DB, tanpa puncak melewati PEAK_DB."""
    level, w = levels(samples, rate)
    active = [s for k, value in enumerate(level) if value > SPEECH_DB for s in samples[k * w:(k + 1) * w]]
    rms = math.sqrt(sum(s * s for s in active) / len(active)) if active else 0
    peak = max(abs(s) for s in samples) or 1
    gain = 10 ** ((TARGET_DB - db(rms)) / 20) if rms else 1
    return min(gain, 10 ** (PEAK_DB / 20) * 32767 / peak)


def loudness(path):
    """(kerasnya dalam LUFS, puncak dalam dBFS) diukur ffmpeg; None kalau gagal."""
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af", "ebur128=peak=true", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    level = re.findall(r"^\s+I:\s+(-?[\d.]+) LUFS", result.stderr, re.M)
    peak = re.findall(r"^\s+Peak:\s+(-?[\d.]+) dBFS", result.stderr, re.M)
    return (float(level[-1]), float(peak[-1])) if level and peak else None


def convert_compressed(path, out):
    """Samakan kerasnya MP3/OGG dengan pengumuman (penguat tetap, dinamika tidak diubah), simpan sebagai MP3."""
    measured = loudness(path) if shutil.which("ffmpeg") else None
    if measured is None:
        return None
    gain = min(TARGET_LUFS - measured[0], PEAK_DB - measured[1])
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(path),
         "-af", f"volume={gain:.1f}dB", "-c:a", "libmp3lame", "-b:a", "128k", str(out)],
        check=True,
    )
    return gain


def shape(samples, gain, rate, fade_in_s, fade_out_s):
    n = len(samples)
    fade_in, fade_out = max(1, int(rate * fade_in_s)), max(1, int(rate * fade_out_s))
    out = []
    for i, s in enumerate(samples):
        envelope = min(1.0, i / fade_in, (n - 1 - i) / fade_out)
        out.append(max(-32767, min(32767, round(s * gain * envelope))))
    return out


def main():
    source = Path(sys.argv[1] if len(sys.argv) > 1 else "suara/asli")
    target = Path(sys.argv[2] if len(sys.argv) > 2 else "suara/siap")
    if not source.is_dir():
        sys.exit(f"Folder {source} tidak ada. Taruh rekaman di sana dulu.")
    target.mkdir(parents=True, exist_ok=True)
    # Folder hasil selalu dibuat ulang, supaya rekaman yang dihapus ikut hilang dari TV.
    for old in target.iterdir():
        if old.suffix.lower() in (".wav", ".mp3", ".ogg"):
            old.unlink()

    bell_saved = False
    written = {}
    for path in sorted(source.iterdir()):
        suffix = path.suffix.lower()
        if suffix not in (".wav", ".mp3", ".ogg"):
            continue
        name = target_name(path.stem)
        if name is None:
            print(f"  lewati {path.name}: namanya tidak dikenali")
            continue
        if name in written:
            print(f"  {name}: {path.name} menggantikan {written[name]}")
        written[name] = path.name
        if suffix != ".wav":
            gain = convert_compressed(path, target / f"{name}.mp3")
            if gain is None:
                shutil.copyfile(path, target / f"{name}{suffix}")
                print(f"  {name}{suffix:5s} disalin apa adanya (ffmpeg tidak ada atau gagal mengukur)")
            else:
                print(f"  {name}.mp3 volume {gain:+.1f} dB (dari {path.name})")
            continue

        samples, rate = read_wav(path)
        level, w = levels(samples, rate)
        start, end = 0, len(samples)
        bounds = voice_studio_bounds(level, w, rate) if "_voice-studio" in path.stem else None
        if bounds:
            start, end = bounds
        cut = trim(samples, level, w, rate, start, end)
        if cut is None:
            print(f"  lewati {path.name}: tidak ada suara")
            continue
        clip = samples[cut[0]:cut[1]]
        gain = gain_for(clip, rate)
        write_wav(target / f"{name}.wav", shape(clip, gain, rate, FADE_S, FADE_S), rate)
        note = " (bel Voice Studio dibuang)" if bounds else ""
        print(f"  {name}.wav {len(clip) / rate:4.1f} dtk{note}")

        # bel.wav buatan sendiri (kalau ada) didahulukan.
        if bounds and not bell_saved and "bel" not in written:
            # Bel pembuka sama di semua ekspor; penguatnya ikut file asalnya supaya imbang dengan suara.
            bell = samples[:min(bounds[0], int(rate * BELL_S))]
            write_wav(target / "bel.wav", shape(bell, gain, rate, 0.005, BELL_FADE_S), rate)
            bell_saved = True
            print(f"  bel.wav {len(bell) / rate:4.1f} dtk (dari {name})")


if __name__ == "__main__":
    main()
