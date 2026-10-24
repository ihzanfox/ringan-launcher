package id.ringan.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
        val reason = Parental.tick(this, rules, elapsed, interactive && !PrayerCall.playing) ?: return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_LANDED, reason.name),
        )
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
            KeyEvent.KEYCODE_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
        const val TICK_MS = 30_000L
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
