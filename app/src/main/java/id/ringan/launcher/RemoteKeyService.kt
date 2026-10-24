package id.ringan.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.Locale

/**
 * Menangkap tombol remote yang dulu ditangani launcher Google TV, sebelum sistem meneruskannya.
 * Tanpa ini, tombol Settings membuat penerima tombol Xiaomi crash (dashboard Google TV tidak ada),
 * lalu sistem memblokir proses itu sehingga tombol Input dan tombol aplikasi ikut mati.
 *
 * Hanya menyaring tombol: tidak membaca isi layar dan tidak berlangganan event apa pun.
 * Kalau launcher Google TV dinyalakan lagi, semua tombol dilewatkan seperti biasa.
 *
 * Layanan ini juga menghitung jam tayang anak (lihat [Parental]) dan memutar azan (lihat [PrayerCall]),
 * karena hanya layanan yang terikat sistem seperti ini yang terus hidup dan boleh membawa papan Ringan
 * ke depan saat gerbang ditutup.
 */
class RemoteKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = SystemClock.elapsedRealtime()
    private var azanOverlay: View? = null
    private var landingBanner: View? = null
    private var warningVoice: Announcer? = null
    private var lastSettingsBounce = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        lastTick = SystemClock.elapsedRealtime()
        handler.postDelayed(ticker, TICK_MS)
        PrayerCall.listener = ::showAzanOverlay
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        PrayerCall.listener = null
        PrayerCall.stop()
        showAzanOverlay(null)
        warningVoice?.shutdown()
        super.onDestroy()
    }

    /**
     * Layar penuh berisi ajakan sholat selama pengumuman dan azan. Juga menjelaskan kenapa remote
     * tidak merespons. Jendela overlay aksesibilitas tidak butuh izin tambahan, tidak menerima
     * sentuhan atau fokus, dan menjaga layar tetap menyala selama azan.
     */
    @SuppressLint("InflateParams") // Jendela overlay tidak punya induk.
    private fun showAzanOverlay(call: PrayerCall.Call?) {
        val windows = getSystemService(WINDOW_SERVICE) as WindowManager
        azanOverlay?.let { windows.removeView(it) }
        azanOverlay = null
        if (call == null || Build.VERSION.SDK_INT < 22) return

        val fajr = call.time.prayer == PrayerTimes.Prayer.FAJR
        val name = getString(call.time.prayer.label)
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_azan, null)
        // Nama sholat di tengah kotak-kotak papan: "ISYA" menjadi " ISYA  ".
        view.findViewById<FlapView>(R.id.azan_name).setText(" ".repeat((NAME_CELLS - name.length).coerceAtLeast(0) / 2) + name)
        val clock = String.format(Locale.US, "%02d:%02d", call.time.minutes / 60 % 24, call.time.minutes % 60)
        view.findViewById<TextView>(R.id.azan_time).text = getString(R.string.azan_overlay_time, clock, call.city)
        view.findViewById<TextView>(R.id.azan_arabic).setText(if (fajr) R.string.azan_fajr_arabic else R.string.azan_call_arabic)
        view.findViewById<TextView>(R.id.azan_latin).setText(if (fajr) R.string.azan_fajr_latin else R.string.azan_call_latin)
        view.findViewById<TextView>(R.id.azan_meaning).setText(if (fajr) R.string.azan_fajr_meaning else R.string.azan_call_meaning)
        view.findViewById<TextView>(R.id.azan_invite).text =
            if (fajr) getString(R.string.azan_fajr_invite) else getString(R.string.azan_call_invite, name)
        view.findViewById<TextView>(R.id.azan_hint)
            .setText(if (call.test) R.string.azan_overlay_test_hint else R.string.azan_overlay_hint)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windows.addView(view, params)
            azanOverlay = view
        } catch (e: RuntimeException) {
            // Tanpa layar penuh pun azan dan penyaring tombol harus tetap jalan; layanan ini tidak boleh crash.
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        // Batasi supaya jeda panjang (misalnya TV tidur) tidak terhitung sebagai menonton.
        val elapsed = (now - lastTick).coerceIn(0L, 2 * TICK_MS)
        lastTick = now
        val config = RinganConfig.load(this)
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        PrayerCall.check(this, config, interactive)

        val rules = config.parental ?: return
        // Waktu azan tidak dihitung menonton, dan layar tidak diambil alih di tengah azan.
        val watching = interactive && !PrayerCall.playing
        val reason = Parental.tick(this, rules, elapsed, watching)
        if (reason != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_LANDED, reason.name),
            )
            return
        }
        if (watching && !Parental.boardVisible) Parental.landingWarningDue(this, rules)?.let { warnLanding(config, it) }
    }

    /** Beberapa menit sebelum gerbang tertutup: tanda di atas layar dan pengumuman, video dikecilkan sebentar. */
    private fun warnLanding(config: RinganConfig, minutes: Int) {
        showLandingBanner(minutes)
        if (!config.announcements) return
        val voice = warningVoice ?: Announcer(this).also { warningVoice = it }
        val restore = duckOtherApps()
        voice.announce(getString(R.string.announce_landing_soon), LANDING_SOON_CLIP, untilEnd = true) { restore() }
    }

    @SuppressLint("InflateParams") // Jendela overlay tidak punya induk.
    private fun showLandingBanner(minutes: Int) {
        if (Build.VERSION.SDK_INT < 22) return
        val windows = getSystemService(WINDOW_SERVICE) as WindowManager
        landingBanner?.let { windows.removeView(it) }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_landing_soon, null)
        view.findViewById<TextView>(R.id.landing_soon_hint).text = getString(R.string.landing_soon_hint, minutes)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (32 * resources.displayMetrics.density).toInt()
        }
        try {
            windows.addView(view, params)
            landingBanner = view
        } catch (e: RuntimeException) {
            // Tanpa tanda pun pengumumannya tetap jalan; layanan ini tidak boleh crash.
            landingBanner = null
            return
        }
        handler.postDelayed({
            if (landingBanner === view) {
                windows.removeView(view)
                landingBanner = null
            }
        }, BANNER_MS)
    }

    /** Minta aplikasi lain mengecilkan suaranya selama pengumuman; mengembalikan fungsi untuk melepasnya. */
    private fun duckOtherApps(): () -> Unit {
        if (Build.VERSION.SDK_INT < 26) return {}
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audio.requestAudioFocus(request)
        return { audio.abandonAudioFocusRequest(request) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (PrayerCall.playing) {
            // Selama azan semua tombol ditelan, kecuali volume supaya suaranya tetap bisa dikecilkan.
            // Uji dari menu boleh dihentikan dengan Kembali.
            if (event.keyCode in VOLUME_KEYS) return false
            if (PrayerCall.testing && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                PrayerCall.stop()
            }
            return true
        }
        val target = when (event.keyCode) {
            KeyEvent.KEYCODE_SETTINGS -> if (SettingsGuard.needsPin()) {
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            KeyEvent.KEYCODE_NOTIFICATION, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_BUTTON_3 ->
                Intent(this, MainActivity::class.java)
            else -> return false
        }
        if (googleTvLauncherEnabled()) return false

        // Tekan dan lepas sama-sama ditelan; aksinya dijalankan sekali saat tombol dilepas.
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                // Tetap ditelan: lebih baik tidak terjadi apa-apa daripada penerima bawaan crash.
            }
        }
        return true
    }

    private fun googleTvLauncherEnabled(): Boolean = try {
        when (packageManager.getApplicationEnabledSetting(GOOGLE_TV_LAUNCHER)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
            else -> true
        }
    } catch (e: IllegalArgumentException) {
        // Tidak terpasang.
        false
    }

    /**
     * Hanya menerima event jendela dari aplikasi Setelan (lihat remote_keys.xml). Kalau Setelan terbuka
     * tanpa PIN, lewat jalan apa pun, papan Ringan maju dan meminta PIN.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() !in SettingsGuard.PACKAGES || !SettingsGuard.needsPin()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSettingsBounce < BOUNCE_MS) return
        lastSettingsBounce = now
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
        )
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
        const val TICK_MS = 30_000L
        const val BANNER_MS = 15_000L
        /** Satu kali saja kalau Setelan memunculkan beberapa jendela berturut-turut. */
        const val BOUNCE_MS = 3_000L
        /** Nama rekaman di suara/NASKAH-REKAMAN.md. */
        const val LANDING_SOON_CLIP = "mendarat_5_menit"
        /** Jumlah kotak nama sholat di overlay_azan.xml. */
        const val NAME_CELLS = 7
        val VOLUME_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
        )
    }
}
