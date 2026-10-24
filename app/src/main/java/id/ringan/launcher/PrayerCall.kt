package id.ringan.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Pengumuman dan azan saat masuk waktu sholat, apa pun yang sedang tampil di layar. Diperiksa
 * RemoteKeyService tiap 30 detik, karena hanya layanan itu yang terus hidup. Selama berbunyi,
 * Ringan memegang audio focus sementara: aplikasi video berhenti lalu lanjut sendiri setelahnya.
 * Azan tidak bisa dihentikan; RemoteKeyService menelan tombol remote sampai selesai.
 */
object PrayerCall {

    /** true selama pengumuman atau azan berbunyi. */
    @Volatile
    var playing = false
        private set

    /** true kalau yang berbunyi adalah uji dari menu; hanya uji yang boleh dihentikan. */
    @Volatile
    var testing = false
        private set

    /** Waktu sholat yang sedang diumumkan, untuk layar azan. */
    class Call(val time: PrayerTimes.Time, val city: String, val test: Boolean)

    /** Dipanggil di thread utama: saat pengumuman mulai, lalu null saat azan selesai. */
    var listener: ((Call?) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var announcer: Announcer? = null
    private var audio: AudioManager? = null
    /** AudioFocusRequest di Android 8+; disimpan sebagai Any supaya aman di Android 5-7. */
    private var focusRequest: Any? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Aplikasi lain mengambil suara di tengah azan: rebut kembali supaya videonya berhenti lagi.
        if (change == AudioManager.AUDIOFOCUS_LOSS && playing) handler.postDelayed(::regainFocus, REGAIN_DELAY_MS)
    }

    /** Dipanggil berkala. Tiap waktu sholat hanya diumumkan sekali, dan hanya kalau layar menyala. */
    fun check(context: Context, config: RinganConfig, interactive: Boolean) {
        if (!config.prayerEnabled || !(config.prayerReminder || config.azan)) return
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val due = PrayerTimes.forDay(now, config.latitude, config.longitude, config.prayerIhtiyat)
            .firstOrNull { it.prayer != PrayerTimes.Prayer.SUNRISE && it.minutes == minute } ?: return
        val key = "${dayFormat.format(now.time)} ${due.prayer.name}"
        val prefs = LauncherPrefs(context)
        if (prefs.lastPrayerAnnounced == key) return
        prefs.lastPrayerAnnounced = key
        if (interactive) start(context, config, due, test = false)
    }

    /** Uji dari menu papan: waktu sholat berikutnya, diputar [TEST_DELAY_MS] lagi supaya sempat membuka video. */
    fun test(context: Context, config: RinganConfig) {
        val app = context.applicationContext
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val prayers = PrayerTimes.forDay(now, config.latitude, config.longitude, config.prayerIhtiyat)
            .filter { it.prayer != PrayerTimes.Prayer.SUNRISE }
        val next = prayers.firstOrNull { it.minutes > minute } ?: prayers.first()
        val hasAzan = config.azan && voice(app).hasRecording(azanNames(next.prayer))
        Toast.makeText(app, if (hasAzan) R.string.azan_test else R.string.azan_test_missing, Toast.LENGTH_LONG).show()
        handler.postDelayed({ start(app, config, next, test = true) }, TEST_DELAY_MS)
    }

    /** Hentikan pengumuman dan azan, lalu kembalikan suara ke aplikasi lain. */
    fun stop() {
        announcer?.stop()
        finish()
    }

    private fun start(context: Context, config: RinganConfig, time: PrayerTimes.Time, test: Boolean) {
        if (playing) return
        val prayer = time.prayer
        val app = context.applicationContext
        val voice = voice(app)
        playing = true
        testing = test
        requestFocus(app)
        handler.postDelayed(::finish, MAX_PLAY_MS)

        val name = app.getString(prayer.label)
        listener?.invoke(Call(time, config.city, test))
        val azan = {
            if (!config.azan || !voice.playRecording(azanNames(prayer), onEnded = ::finish)) finish()
        }
        if (config.prayerReminder) {
            val text = app.getString(R.string.announce_prayer, name, config.city)
            voice.announce(text, Announcer.clipName("sholat_", name), untilEnd = true) { azan() }
        } else {
            azan()
        }
    }

    private fun finish() {
        if (!playing) return
        playing = false
        testing = false
        handler.removeCallbacksAndMessages(null)
        abandonFocus()
        listener?.invoke(null)
    }

    /** Azan Subuh berisi "ash-shalatu khairum minan naum", jadi boleh punya rekaman sendiri. */
    private fun azanNames(prayer: PrayerTimes.Prayer) =
        if (prayer == PrayerTimes.Prayer.FAJR) listOf("azan_subuh", "azan") else listOf("azan")

    private fun voice(app: Context) = announcer ?: Announcer(app).also { announcer = it }

    private fun requestFocus(app: Context) {
        val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            manager.requestAudioFocus(request)
            focusRequest = request
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        audio = manager
    }

    private fun regainFocus() {
        val manager = audio ?: return
        if (!playing) return
        if (Build.VERSION.SDK_INT >= 26) {
            (focusRequest as? AudioFocusRequest)?.let { manager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonFocus() {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            (focusRequest as? AudioFocusRequest)?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
        audio = null
        focusRequest = null
    }

    private const val TEST_DELAY_MS = 10_000L
    private const val REGAIN_DELAY_MS = 500L
    /** Pengaman kalau pemutar macet; azan biasanya 3-6 menit. */
    private const val MAX_PLAY_MS = 10 * 60_000L
}
