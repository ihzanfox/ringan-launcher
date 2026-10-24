# Ringan: launcher Android TV bergaya papan bandara

Launcher pengganti untuk Android TV dan Google TV. Layar utamanya berupa papan keberangkatan bandara: aplikasi pilihan tampil sebagai "tujuan" dengan huruf *split-flap* yang berbalik, ditemani jam, cuaca, dan jadwal keberangkatan serta kedatangan asli dari Bandara Sultan Hasanuddin (UPG). Tidak ada iklan, rekomendasi, atau baris "Untuk Anda".

- **Internet hanya untuk tiga hal:** cuaca dari [Open-Meteo](https://open-meteo.com) (tanpa API key, paling sering tiap 30 menit), jadwal penerbangan dari [AeroDataBox](https://aerodatabox.com) (paling sering tiap 4 jam), logo maskapai dari CDN Aviasales `pics.avs.io` (sekali unduh per maskapai, lalu disimpan di TV), dan bila diaktifkan, status langsung dari FlightAware (paling sering tiap 15 menit, dengan batas biaya bulanan). Semuanya hanya diambil saat layar launcher terlihat. Tidak ada iklan, analitik, atau pelacakan.
- **Untuk anak:** tiga baris karaoke (anak, lagu daerah, lagu nasional), pengumuman suara ala bandara saat membuka aplikasi, serta jam tayang dan jam tidur dengan PIN orang tua.
- **Jadwal sholat Makassar** di kepala papan, dihitung offline, dengan pengumuman dan azan saat masuk waktu sholat.
- **Ikon cuaca beranimasi** yang digambar langsung tanpa gambar atau library: matahari berputar, bulan dan bintang berkelip, awan bergoyang secepat angin yang sebenarnya, tetes hujan sebanyak intensitas hujan, petir saat badai, salju, dan kabut. Animasi hanya berjalan saat launcher terlihat, 20 frame per detik.
- **Kecil.** APK release sekitar 90 KB, tanpa library tambahan, tanpa service yang jalan di latar.
- Mendukung Android 5.0 sampai 16.

## Cara pakai

| Tombol remote | Di papan | Di Semua aplikasi |
| --- | --- | --- |
| OK | Buka aplikasi | Buka aplikasi |
| Tahan OK, atau Menu | Pindah ke atas/bawah, hapus dari papan, info aplikasi. Di baris "Aplikasi lain": menu Ringan | Tambah/hapus dari papan, sembunyikan, info aplikasi, copot |
| Roda gigi di kanan atas | — | Pengaturan orang tua, Pengaturan Android, aplikasi tersembunyi |
| Back / Home | Kembali ke baris pertama | Kembali ke papan |

- Baris **Karaoke Anak**, **Lagu Daerah**, dan **Lagu Nasional** membuka pencarian YouTube masing-masing. Nama dan kata kuncinya diatur di `karaoke` pada `config.json`; baris yang dihapus dari papan bisa ditambahkan lagi lewat menu Ringan (tahan OK di baris "Aplikasi lain").
- Baris **Aplikasi lain** membuka grid semua aplikasi. Tambahkan aplikasi ke papan dari sana.
- Kolom **Status** menunjukkan kapan aplikasi terakhir dibuka (jam, KEMARIN, nama hari, atau tanggal).
- Kalau YouTube menanyakan "Siapa yang menonton?", pilih profil sekali; sesudahnya Karaoke langsung membuka hasil pencarian.

## Build

Butuh JDK 17 atau lebih baru. JDK bawaan Android Studio juga bisa:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

Hasilnya ada di `app/build/outputs/apk/release/app-release.apk`. APK release ditandatangani dengan kunci debug supaya bisa langsung dipasang ke TV sendiri. Ganti `signingConfig` di `app/build.gradle.kts` kalau mau menyebarkannya.

## Pasang ke TV

1. Di TV, buka **Settings → System → About** lalu klik **Android TV OS build** 7 kali sampai Developer options aktif.
2. Di **Developer options**, nyalakan **USB debugging** atau **Network debugging**.
   Untuk Google TV (Android 12+), pakai **Wireless debugging**: pilih *Pair device with pairing code*, lalu di Mac jalankan `adb pair IP:PORT` dengan kode yang tampil.
3. Jalankan:

   ```bash
   ./scripts/pasang.sh 192.168.1.20
   ```

   Ganti dengan IP TV kamu (tambahkan `:port` kalau memakai Wireless debugging). Skrip ini membangun APK, memasangnya, dan menjadikannya launcher utama.

### Google TV tetap membuka launcher bawaan?

Google TV selalu kembali ke launcher bawaannya saat Home ditekan. Matikan launcher bawaan:

```bash
./scripts/launcher-bawaan.sh matikan
```

Aplikasinya hanya dinonaktifkan, tidak dihapus. Untuk mengembalikan:

```bash
./scripts/launcher-bawaan.sh nyalakan
```

Selama launcher bawaan mati, fitur yang ada di dalamnya ikut hilang: rekomendasi, profil, panel dashboard, dan tombol Notification serta Guide. Factory reset juga mengembalikan semuanya.

### Tombol remote saat launcher bawaan mati

Di TV seperti Xiaomi, tombol **Settings** di remote membuka dashboard Google TV lewat penerima tombol bawaan pabrik. Tanpa launcher Google TV, penerima itu crash. Setelah dua kali crash, sistem memblokirnya, sehingga tombol **Input** dan tombol pintasan aplikasi ikut mati sampai TV direstart. Launcher Google TV juga tidak bisa dinyalakan sebagian: begitu paketnya aktif, ia selalu merebut tombol Home.

Ringan menyediakan layanan aksesibilitas **"Ringan: tombol remote"** yang menangkap tombol itu lebih dulu:

| Tombol | Saat launcher Google TV mati |
| --- | --- |
| Settings | Membuka Setelan Android |
| Notification, Guide, BUTTON_3 | Kembali ke papan Ringan |
| Tombol lain (Input, pintasan aplikasi, D-pad, ...) | Tidak disentuh |

Layanan ini hanya menyaring tombol remote: tidak membaca isi layar dan tidak berlangganan event lain. Kalau launcher Google TV dinyalakan lagi, semua tombol dilewatkan seperti biasa.

Cara mengaktifkan: **Setelan → Aksesibilitas → (Layanan) Ringan: tombol remote → Aktifkan**, setujui peringatan izinnya, lalu restart TV sekali untuk membersihkan blokir lama. `./scripts/launcher-bawaan.sh matikan` membuka halaman itu otomatis kalau tersedia.

Catatan untuk pengujian: tombol yang disimulasikan dengan `adb shell input keyevent` tidak melewati layanan aksesibilitas, jadi uji dengan tombol fisik remote.

## Fitur keluarga

### Pengumuman suara

Saat aplikasi dibuka dari papan, terdengar bel lalu *"Penumpang tujuan YouTube, silakan naik."* Aplikasinya terbuka 1,8 detik setelah suara mulai bicara, jadi sisa kalimat terdengar sambil aplikasi dimuat (sekitar 4,5 detik dari tombol ditekan). Matikan dengan `"announcements": false`.

Ringan memutar **rekaman suara** dari `suara/` kalau ada; naskah dan nama filenya di [suara/NASKAH-REKAMAN.md](suara/NASKAH-REKAMAN.md). Cara memperbarui rekaman:

1. Taruh file rekaman di `suara/asli/`. Ekspor Voice Studio boleh dengan nama aslinya. Rekaman lain dinamai sesuai naskah, misalnya `naik_prime_video.wav`.
2. Jalankan `./scripts/kirim-suara.sh`. Skrip ini memberi nama sesuai naskah, membuang bel bawaan Voice Studio (bel pembukanya disimpan sebagai `bel.wav`), memotong hening, dan menyamakan volume. Hasilnya disimpan di `suara/siap/` lalu dikirim ke TV. Ringan tidak perlu di-restart.

Kalimat yang belum direkam (misalnya aplikasi yang baru ditambahkan ke papan) dibacakan mesin suara bawaan TV dan tetap menyebut gerbangnya. Kalau TV hanya punya suara Indonesia versi server, kalimatnya disintesis Google lewat internet (isinya hanya nama aplikasi dan gerbang). Untuk suara offline yang lebih cepat, pasang data suara bahasa Indonesia di **Setelan → Aksesibilitas → Text to speech**; Ringan otomatis memakai suara offline kalau ada. Tanpa `bel.wav`, Ringan memakai bel tiga nada bawaannya.

Rekaman waktu sholat menyebut Makassar. Kalau `city` diganti, rekam ulang `sholat_*.wav` atau hapus dari `suara/asli/` supaya nama kotanya dibacakan TTS.

### Jadwal sholat

Waktu sholat berikutnya tampil di kanan atas papan, misalnya *"Maghrib 17:56 · 1 j 31 mnt lagi"*. Jadwal lengkap ada di menu Ringan: tahan OK pada baris "Aplikasi lain" (atau tombol Menu).

- Dihitung **offline** dari posisi matahari dengan metode Kemenag (Subuh 20°, Isya 18°, Ashar mazhab Syafi'i), memakai `latitude` dan `longitude` di config.
- Ditambah ihtiyat 2 menit (`prayer.ihtiyatMinutes`). Tanpa ihtiyat, hasilnya untuk Makassar 24 Oktober 2026 sama dengan jadwal Kemenag di Aladhan, kecuali Ashar yang selisih 1 menit karena pembulatan.
- Saat masuk waktu sholat, terdengar bel, *"Waktu sholat Maghrib telah tiba untuk wilayah Makassar dan sekitarnya."*, lalu **azan**. Ini berlaku apa pun yang sedang tampil, selama TV menyala; saat TV standby tidak ada suara.
- Video yang sedang diputar berhenti sebentar dan lanjut sendiri setelah azan selesai. Sudah diuji dengan YouTube; aplikasi video lain biasanya sama karena Ringan memakai mekanisme audio focus bawaan Android.
- Selama pengumuman dan azan, layar penuh menampilkan ajakan sholat:
  - nama sholat di kotak papan beserta jam dan kota;
  - *Hayya 'alash-shalah* (*marilah menunaikan sholat*), atau untuk Subuh *ash-shalatu khairum minan naum* (*sholat lebih baik daripada tidur*), dalam huruf Arab dan Latin;
  - ajakan *"Yuk, berhenti sejenak, ambil wudhu, dan sholat Maghrib berjamaah."*

  Layar tidak mati atau masuk screensaver selama azan.
- **Azan tidak bisa dihentikan.** Semua tombol remote diabaikan sampai azan selesai, kecuali volume dan mute. Tombol power tetap berfungsi. Kalau ada aplikasi yang mencoba bersuara, Ringan mengambil alih suaranya lagi sehingga videonya berhenti kembali.
- Rekaman azan yang dipakai sekarang adalah `suara/asli/azan.ogg`: azan di Masjid Nabawi rekaman ejaz215, berlisensi CC BY 3.0. Sumber dan kewajiban atribusinya dicatat di [suara/asli/SUMBER.md](suara/asli/SUMBER.md).
- Untuk mengganti azan, simpan rekaman lain sebagai `azan.mp3` atau `azan.ogg` di `suara/asli/`, lalu jalankan `./scripts/kirim-suara.sh`. Skrip itu menyamakan kerasnya dengan pengumuman lewat ffmpeg (`brew install ffmpeg`). Untuk Subuh boleh ada `azan_subuh.*` (dengan *ash-shalatu khairum minan naum*); kalau tidak ada, Subuh memakai azan yang sama. Tanpa rekaman azan, hanya pengumumannya yang diputar.
- Coba lewat menu Ringan (tahan OK pada baris "Aplikasi lain", atau tombol Menu) → **Uji pengumuman sholat dan azan**. Suaranya mulai 10 detik kemudian, jadi sempat membuka video untuk melihat videonya berhenti. Uji ini memakai azan untuk waktu sholat berikutnya, dan khusus uji, tombol Kembali bisa menghentikannya.
- Matikan pengumuman dengan `"prayer": {"reminder": false}` dan azan dengan `"prayer": {"azan": false}`.
- Pengumuman dan azan dijalankan layanan aksesibilitas "Ringan: tombol remote", jadi layanan itu harus aktif. Waktu azan tidak dihitung sebagai jam tayang anak.

### Jam tayang dan jam tidur

Aktif kalau sudah ada PIN orang tua, baik dibuat di TV (lihat di bawah) maupun lewat bagian `parental` di `config.json` dengan PIN 4–6 angka:

| Kolom | Arti | Contoh |
| --- | --- | --- |
| `pin` | PIN orang tua | `"2580"` |
| `sessionMinutes` | Batas sekali menonton (0 = tanpa batas) | `60` |
| `breakMinutes` | Lama istirahat setelah batas sesi | `10` |
| `dailyMinutes` | Jatah per hari (0 = tanpa batas) | `120` |
| `bedtime`, `wakeup` | Jam tidur dan jam bangun (`""` = tanpa jam tidur) | `"20:30"`, `"06:00"` |
| `unlockMinutes` | Pilihan pertama lama gerbang dibuka setelah PIN benar | `30` |

- Yang dihitung adalah waktu layar menyala sementara papan Ringan tidak tampil, termasuk HDMI dan Setelan. "Hari" anak dimulai pada jam bangun.
- Di kanan atas papan, di bawah jadwal sholat, tampil **sisa waktu terbang**: *"Sisa terbang 25 mnt"*. Angka ini adalah batas yang paling dulu tercapai, entah sisa sesi, sisa jatah hari ini, atau waktu sampai jam tidur. Selama gerbang dibuka dengan PIN, yang tampil *"Gerbang dibuka sampai 00:05"*.
- **5 menit sebelum mendarat**, saat anak sedang menonton, muncul tanda *PESAWAT AKAN MENDARAT* di atas layar dan pengumuman *"Pesawat akan mendarat lima menit lagi. Bersiap-siap, ya."* Selama pengumuman, suara video dikecilkan sebentar. Rekamannya `mendarat_5_menit.wav` (lihat naskah); tanpa rekaman, kalimatnya dibacakan TTS.
- Saat batas tercapai, Ringan otomatis mengambil alih layar dengan tampilan **MENDARAT**, **JATAH HABIS**, atau **WAKTU TIDUR**, plus pengumuman suara. Aplikasi dari papan maupun dari Semua aplikasi hanya bisa dibuka dengan PIN.
- **Setelan Android dikunci dengan PIN** selama jam tayang aktif, kapan pun. Dari Setelan, layanan Ringan bisa dimatikan, dan semua kunci ikut lepas. PIN diminta di setiap jalan masuk ke Setelan:
  - tombol Settings di remote;
  - menu "Pengaturan Android";
  - ikon Setelan di Aplikasi lain;
  - "Info aplikasi";
  - jalan lain seperti Asisten Google.

  Kalau Setelan terbuka tanpa PIN, papan Ringan langsung maju dan meminta PIN. Setelah PIN benar, Setelan bebas dibuka selama 15 menit.
- PIN dimasukkan dengan roda angka: atas/bawah mengubah angka, kiri/kanan pindah digit, OK untuk membuka.
- **Bisa diatur langsung di TV, tanpa laptop:** buka **Aplikasi lain**, pilih roda gigi di kanan atas → **Pengaturan orang tua**, lalu masukkan PIN. Bisa juga dari menu Ringan (tahan OK pada baris "Aplikasi lain"). Batas sesi, lama istirahat, jatah harian, jam tidur, jam bangun, lama buka, dan PIN diubah dengan roda angka. Kalau belum ada PIN, menu ini meminta membuat PIN dulu, jadi jam tayang bisa diaktifkan sepenuhnya dari TV.
- Nilai yang diubah di TV disimpan di TV dan mengalahkan `config.json`, jadi mengirim ulang `config.json` (misalnya untuk API key) tidak menghapusnya. Pilih **Pakai lagi nilai dari config.json** di menu yang sama untuk membatalkan semua perubahan dari TV.
- Setelah PIN benar, pilih berapa lama gerbang dibuka: `unlockMinutes` (misalnya 30 menit), 1 jam, 2 jam, 4 jam, atau **sampai jam bangun (mode tamu)**. Pilihan yang melewati jam bangun tidak ditampilkan.
- Selama gerbang dibuka, semua kunci berhenti: batas sesi, jatah harian, dan jam tidur. Waktu menonton juga tidak dihitung, jadi jatah anak tidak terpakai oleh tamu.
- Untuk membuka sebelum terkunci, misalnya saat tamu datang: tahan OK pada baris "Aplikasi lain" (atau tombol Menu) → **Buka kunci jam tayang (PIN)**. Selama terbuka, menu yang sama berisi **Kunci lagi sekarang** untuk menutup gerbang lebih awal tanpa PIN.
- Penghitung, peringatan, pengambilalihan layar, dan kunci Setelan dijalankan oleh layanan aksesibilitas "Ringan: tombol remote", jadi layanan itu harus aktif. Untuk mengunci Setelan, layanan ini hanya diberi tahu kapan aplikasi Setelan membuka jendela, tidak membaca isi layar dan tidak melihat aplikasi lain. Tanpa layanan itu, yang tersisa hanya kunci saat membuka aplikasi dari Ringan.
- Batasannya: ini pengaman tingkat rumah, bukan kunci mutlak. Anak masih bisa membuka aplikasi lewat Asisten Google, tapi dalam 30 detik Ringan menariknya kembali. Selama gerbang tertutup, Setelan juga ikut terkunci sampai PIN dimasukkan.

### DNS bebas iklan

Di menu **Pengaturan orang tua** ada pilihan **DNS bebas iklan**. Pilihan ini memakai DNS pribadi bawaan Android (DNS-over-TLS) untuk menyaring domain iklan dan pelacak di semua aplikasi TV:

| Pilihan | Server | Yang disaring |
| --- | --- | --- |
| AdGuard | `dns.adguard-dns.com` | Iklan dan pelacak |
| AdGuard Keluarga | `family.adguard-dns.com` | Iklan, pelacak, situs dewasa, plus SafeSearch dan Mode Terbatas YouTube |
| Mullvad Keluarga | `family.dns.mullvad.net` | Iklan, pelacak, dan situs dewasa |
| Mati | DNS bawaan TV | — |

- **Butuh izin sekali dari laptop**, karena Android tidak mengizinkan aplikasi biasa mengubah DNS. Jalankan ini sendiri:
  ```bash
  adb shell pm grant id.ringan.launcher android.permission.WRITE_SECURE_SETTINGS
  ```
  Izin ini tetap ada saat Ringan diperbarui, dan hilang kalau Ringan dicopot. Butuh Android 9 atau lebih baru.
- Setelah DNS dipilih, Ringan menunggu 8 detik lalu memastikan internet masih jalan. Kalau server DNS tidak bisa dihubungi, pengaturan sebelumnya dipulihkan otomatis.
- **Iklan YouTube tidak ikut terblokir**, karena iklannya datang dari server yang sama dengan videonya. Yang tersaring adalah iklan dan pelacak di aplikasi lain, browser, dan layanan bawaan TV.
- Kalau suatu saat server DNS-nya mati, TV kehilangan internet. Pilih **Mati** di menu ini; menu Ringan tetap bisa dibuka tanpa internet. Kalau ada aplikasi yang bermasalah setelah DNS diaktifkan, matikan juga dari sini.

### Prakiraan per jam

Kartu cuaca menampilkan 6 jam berikutnya (jam, ikon kecil, suhu), dari permintaan Open-Meteo yang sama tanpa panggilan tambahan. Ikon kecilnya tidak beranimasi supaya tetap ringan.

## Cuaca, karaoke, dan papan penerbangan

Semua pengaturan ada di satu file, `config.json`, yang dikirim ke TV lewat adb. File ini berisi API key, jadi sudah dikecualikan di `.gitignore` dan di TV disimpan di folder privat aplikasi.

```bash
cp config.contoh.json config.json
# edit config.json, lalu:
./scripts/kirim-config.sh
```

Tanpa `config.json`, cuaca dan jadwal sholat memakai Makassar, tiga baris karaoke memakai kata kunci bawaan, jam tayang nonaktif, dan panel penerbangan meminta API key.

`kirim-config.sh` sengaja tidak menutup paksa Ringan, karena Android ikut mematikan layanan aksesibilitas aplikasi yang ditutup paksa. Ringan membaca ulang `config.json` setiap kali papannya dibuka.

### API key AeroDataBox

1. Daftar paket **Basic** (gratis) lewat [RapidAPI](https://rapid.aerodatabox.com/pricing) atau [API.Market](https://apimarket.aerodatabox.com/).
2. Pastikan sudah menekan tombol berlangganan paket **Basic**; tanpa itu RapidAPI menolak dengan pesan *"You are not subscribed to this API"*.
3. Salin API key-nya ke `flights.apiKey`, lalu isi `flights.provider` sesuai tempat mendaftar: `"rapidapi"`, `"apimarket"`, atau `"aerodatabox"` (daftar langsung di situs AeroDataBox).
4. Jalankan `./scripts/kirim-config.sh`. Kalau key ditolak, panel di TV menampilkan kode HTTP-nya.

Panel kanan bergantian tiap 10 detik antara KEBERANGKATAN dan KEDATANGAN, masing-masing 5 penerbangan terdekat. Keduanya datang dari satu permintaan yang sama. Tiap baris menampilkan logo maskapai di kotak putih; kalau logonya tidak tersedia (misalnya Pelita Air), yang tampil kode maskapai dan namanya. Status hanya ditulis kalau perlu diperhatikan (BATAL, TUNDA, BOARDING, dan seterusnya); penerbangan yang sekadar terjadwal tidak diberi tulisan.

Paket Basic memberi 400 unit per bulan, dan satu kali ambil jadwal bandara memakai 2 unit, jadi sekitar 200 kali per bulan. Karena itu Ringan mengambil jadwal 12 jam ke depan paling sering tiap 4 jam (`refreshMinutes: 240`, paling banyak ±180 kali per bulan), menyimpannya di TV, lalu menyaring penerbangan yang sudah lewat tiap 30 detik tanpa memakai kuota. Akibatnya status seperti BOARDING atau TERTUNDA bisa terlambat sampai 4 jam. Kalau naik ke paket berbayar, perkecil `refreshMinutes`.

Paket gratis hanya untuk penggunaan non-komersial dan wajib mencantumkan sumber data, karena itu ada tulisan "AeroDataBox" di judul panel. Ganti `flights.airport` dengan kode IATA lain (misalnya `CGK`) untuk bandara lain.

Arti status keberangkatan: TERJADWAL, CHECK-IN, BOARDING, TUTUP (gerbang ditutup), BERANGKAT, TUNDA dengan jam baru, BATAL, dan DIALIHKAN. Untuk kedatangan: TERJADWAL, DI UDARA, MENDARAT, TIBA, TUNDA, BATAL, dan DIALIHKAN.

Untuk UPG, AeroDataBox (per Oktober 2026) hanya punya data jadwal, tanpa status langsung. Jadi yang muncul umumnya TERJADWAL atau BATAL, dan penerbangan hilang dari papan begitu jamnya lewat. Status langsung tiap bandara bisa dicek gratis (0 unit) di endpoint `/health/services/airports/{ICAO}/feeds`.

### Status langsung dari FlightAware (opsional, berbayar per pemakaian)

AeroDataBox tidak punya status langsung untuk UPG, dan penerima sinyal ADS-B komunitas belum ada di Sulawesi. FlightAware punya: pesawat yang sedang di udara, perkiraan jam tiba, keterlambatan, gate, dan pembatalan.

Paket **AeroAPI Personal** tidak punya biaya minimum dan memberi gratis US$5 per bulan (US$10 untuk yang mengirim data ADS-B ke FlightAware), khusus penggunaan pribadi. Satu kali ambil daftar keberangkatan atau kedatangan (sampai 15 penerbangan) berharga US$0,005.

1. Buat akun di [flightaware.com](https://www.flightaware.com) (*Join FlightAware*).
2. Di [portal AeroAPI](https://www.flightaware.com/aeroapi/portal/) (*My AeroAPI*), buka **Account and Billing → Manage Subscription**, pilih **Personal**, setujui lisensinya, lalu **Save Changes**. Pastikan *Subscription* tidak lagi tertulis *None*.
3. Di tab **Overview**, tekan **Add API Key** lalu salin key-nya. Selama langganan masih *None*, key yang dibuat tidak akan muncul di daftar.
4. Tempel di `flightaware.apiKey` pada `config.json`, lalu jalankan `./scripts/kirim-config.sh`.

Cara Ringan menjaga biaya:

- Satu pembaruan = 2 panggilan (keberangkatan dan kedatangan) = US$0,01. Pembaruan paling sering tiap `refreshMinutes` (15), dan hanya saat layar launcher terlihat.
- Begitu pemakaian bulan ini (bulan kalender UTC) mencapai `monthlyBudgetUsd` (4,5, atau 900 panggilan), Ringan berhenti memanggil FlightAware sampai bulan berikutnya. Hanya panggilan yang berhasil yang dihitung.
- Lisensi AeroAPI Personal (klausul 12) melarang data FlightAware dipakai bersamaan dengan penyedia data penerbangan lain. Karena itu, selama `flightaware.apiKey` diisi, panel **hanya** memakai FlightAware. Kalau batas tercapai, panggilan gagal, atau data lebih tua dari 2 kali `refreshMinutes`, panel menampilkan keterangannya, bukan jadwal AeroDataBox. Untuk kembali ke AeroDataBox, kosongkan `flightaware.apiKey`.
- Pemakaian bulan ini terlihat di menu Ringan (tahan OK pada baris "Aplikasi lain").

Penghitung ini hanya mencatat panggilan dari Ringan. Kalau key yang sama dipakai di tempat lain, pantau juga pemakaian di portal AeroAPI.

Arti status FlightAware: DI UDARA, MENDARAT, TIBA, TUNDA dengan perkiraan jam baru, BERANGKAT, GATE (nomor gerbang), BATAL, dan DIALIHKAN.

## Struktur kode

- `MainActivity.kt`: papan keberangkatan (layar Home), jam, cuaca, dan panel penerbangan beserta cache-nya.
- `AllAppsActivity.kt`: grid semua aplikasi.
- `FlapView.kt`: teks split-flap; hanya digambar ulang saat hurufnya berubah.
- `WeatherView.kt`: ikon cuaca beranimasi.
- `AirlineLogos.kt`: unduh dan simpan logo maskapai.
- `RemoteKeyService.kt`: layanan aksesibilitas penyaring tombol remote dan penghitung jam tayang.
- `Parental.kt`: aturan jam tayang, jam tidur, dan PIN.
- `PrayerTimes.kt`: perhitungan jadwal sholat offline.
- `PrayerCall.kt`: pengumuman dan azan saat masuk waktu sholat.
- `ParentSettings.kt`: menu pengaturan orang tua di TV, disimpan terpisah dari `config.json`.
- `PrivateDns.kt`: DNS bebas iklan lewat DNS pribadi Android.
- `Announcer.kt`: bel bandara dan pengumuman suara (rekaman, dengan cadangan TTS).
- `Network.kt`: permintaan ke Open-Meteo dan AeroDataBox, plus terjemahan status penerbangan ke teks papan.
- `FlightAware.kt`: status langsung dari FlightAware AeroAPI.
- `RinganConfig.kt`: membaca `config.json`.
- `AppLoader.kt`, `LauncherPrefs.kt`, `AppActions.kt`: daftar aplikasi, isi papan, dan cara membuka aplikasi.
- `app/src/main/res/values/colors.xml` dan `dimens.xml`: warna papan dan ukuran kotak huruf.

## Lisensi

Kode dan dokumentasi Ringan memakai [lisensi MIT](LICENSE), © 2026 andihzan.

Rekaman azan di `suara/asli/` punya lisensi sendiri dari perekamnya: `azan.ogg` CC BY 3.0 (wajib mencantumkan nama perekam) dan `azan_subuh.mp3` CC0. Rinciannya ada di [suara/asli/SUMBER.md](suara/asli/SUMBER.md).
