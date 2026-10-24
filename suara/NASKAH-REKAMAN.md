# Naskah rekaman pengumuman Ringan

Daftar semua kalimat yang diucapkan Ringan, untuk direkam dengan suara asli.

Simpan rekaman di `suara/asli/`, lalu jalankan `./scripts/kirim-suara.sh`. Ekspor Voice Studio (misalnya `penumpang-tujuan-youtube-silakan-naik_voice-studio_....wav`) dikenali dari namanya, lalu bel bawaannya dibuang. Rekaman lain diberi nama persis seperti di tabel. Kalimat yang belum direkam dibacakan suara TTS.

## Kalimat untuk suara TTS (kalau rekamannya tidak ada)

| Kapan | Kalimat |
| --- | --- |
| Membuka aplikasi dari papan | Penumpang tujuan **{nama aplikasi}**, silakan naik di gerbang **{gerbang}**. |
| Masuk waktu sholat (papan sedang tampil) | Perhatian. Waktu sholat **{Subuh/Dzuhur/Ashar/Maghrib/Isya}** telah tiba untuk wilayah **Makassar** dan sekitarnya. |
| 5 menit sebelum batas tercapai | Perhatian. Pesawat akan mendarat lima menit lagi. Bersiap-siap, ya. |
| Batas sesi menonton tercapai | Perhatian. Pesawat telah mendarat. Waktunya istirahat sebentar. |
| Jatah menonton hari ini habis | Perhatian. Jatah terbang hari ini sudah habis. Sampai jumpa besok. |
| Jam tidur | Perhatian. Gerbang sudah ditutup. Waktunya tidur. Selamat malam. |

Sebelum setiap kalimat, Ringan membunyikan `bel.wav`, atau bel tiga nada bawaan kalau file itu tidak ada.

## Yang perlu direkam (17 file, ditambah 1 opsional)

Setiap file berisi **satu kalimat utuh**. Kalimat yang disambung dari potongan-potongan rekaman biasanya terdengar patah, jadi bagian yang berubah-ubah dibuat per kalimat.

Untuk rekaman, **nomor gerbang tidak diucapkan**. Nomor gerbang ikut berubah setiap kali urutan papan diubah, dan nomornya tetap terlihat di layar.

### Naik pesawat (membuka aplikasi)

| File | Kalimat |
| --- | --- |
| `naik_youtube.wav` | Penumpang tujuan YouTube, silakan naik. |
| `naik_karaoke_anak.wav` | Penumpang tujuan Karaoke Anak, silakan naik. |
| `naik_lagu_daerah.wav` | Penumpang tujuan Lagu Daerah, silakan naik. |
| `naik_lagu_nasional.wav` | Penumpang tujuan Lagu Nasional, silakan naik. |
| `naik_youtube_music.wav` | Penumpang tujuan YouTube Music, silakan naik. |
| `naik_netflix.wav` | Penumpang tujuan Netflix, silakan naik. |
| `naik_vidio.wav` | Penumpang tujuan Vidio, silakan naik. |
| `naik_disney_plus.wav` | Penumpang tujuan Disney Plus, silakan naik. |

Ini isi papanmu sekarang. Untuk aplikasi lain yang nanti ditambahkan ke papan, nama filenya `naik_` diikuti nama di papan dalam huruf kecil, dengan spasi dan tanda baca diganti `_` dan `+` ditulis `plus` (contoh: Prime Video → `naik_prime_video.wav`). Kalau rekamannya tidak ada, Ringan memakai suara TTS untuk aplikasi itu.

### Waktu sholat

| File | Kalimat |
| --- | --- |
| `sholat_subuh.wav` | Perhatian. Waktu sholat Subuh telah tiba untuk wilayah Makassar dan sekitarnya. |
| `sholat_dzuhur.wav` | Perhatian. Waktu sholat Dzuhur telah tiba untuk wilayah Makassar dan sekitarnya. |
| `sholat_ashar.wav` | Perhatian. Waktu sholat Ashar telah tiba untuk wilayah Makassar dan sekitarnya. |
| `sholat_maghrib.wav` | Perhatian. Waktu sholat Maghrib telah tiba untuk wilayah Makassar dan sekitarnya. |
| `sholat_isya.wav` | Perhatian. Waktu sholat Isya telah tiba untuk wilayah Makassar dan sekitarnya. |

### Jam tayang anak

| File | Kalimat |
| --- | --- |
| `mendarat_5_menit.wav` | Perhatian. Pesawat akan mendarat lima menit lagi. Bersiap-siap, ya. |
| `mendarat.wav` | Perhatian. Pesawat telah mendarat. Waktunya istirahat sebentar. |
| `jatah_habis.wav` | Perhatian. Jatah terbang hari ini sudah habis. Sampai jumpa besok. |
| `waktu_tidur.wav` | Perhatian. Gerbang sudah ditutup. Waktunya tidur. Selamat malam. |

### Opsional

| File | Isi |
| --- | --- |
| `bel.wav` | Bunyi bel pengganti bel tiga nada bawaan (1–2 detik). Kalau tidak ada, bel bawaan yang dipakai. Dari ekspor Voice Studio, file ini dibuat otomatis dari bel pembukanya; `bel.wav` buatan sendiri di `suara/asli/` didahulukan. |

### Azan

Bukan kalimat untuk direkam sendiri, melainkan rekaman azan pilihanmu. Diputar setelah pengumuman waktu sholat.

| File | Isi |
| --- | --- |
| `azan.mp3` | Azan lengkap, untuk Dzuhur, Ashar, Maghrib, dan Isya. |
| `azan_subuh.mp3` | Opsional: azan Subuh dengan *ash-shalatu khairum minan naum*. Kalau tidak ada, Subuh memakai `azan.mp3`. |

Nama file harus persis seperti di tabel; nama lain seperti `Adzan Makkah.mp3` dilewati skrip. Boleh MP3, OGG, atau WAV. Azan yang terpasang sekarang adalah `azan.ogg` dari Masjid Nabawi; sumber dan lisensinya ada di `asli/SUMBER.md`.

Kalimatnya boleh diubah sesuai selera, misalnya "Ayo, penumpang tujuan YouTube, silakan naik!", selama nama filenya tetap sama.

## Format audio

- **WAV**, **MP3**, atau **OGG**. Ekstensinya boleh beda dari tabel; yang penting nama depannya sama.
- **Mono**, 44,1 kHz atau 48 kHz.
- Hening di awal dan akhir dipotong otomatis (sisa 0,1 detik), dan volume semua file disamakan dengan puncak paling tinggi −1 dBFS. WAV harus 16-bit. MP3 dan OGG disamakan kerasnya lewat ffmpeg (kalau terpasang) lalu dikirim sebagai MP3, tanpa dipotong heningnya.
- Jangan memasukkan bel ke dalam rekaman suara, karena Ringan sudah memutarnya lebih dulu. Pengecualiannya ekspor Voice Studio: belnya dikenali dan dibuang otomatis.
- Rekam semua file di ruangan, jarak mikrofon, dan gaya bicara yang sama, supaya terdengar seperti satu petugas bandara yang sama.

## Tips gaya bicara

- Bayangkan petugas pengumuman bandara: tenang, jelas, sedikit lebih lambat dari bicara biasa.
- Untuk kalimat naik pesawat, nada ramah dan ceria karena pendengarnya anak-anak.
- Beri jeda singkat pada koma ("Penumpang tujuan YouTube, … silakan naik").
- Rekam 2–3 kali per kalimat lalu pilih yang paling enak didengar.
