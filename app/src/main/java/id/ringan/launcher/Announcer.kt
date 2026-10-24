package id.ringan.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Pengumuman ala bandara: bunyi bel lalu kalimatnya. Rekaman di folder suara/ (dikirim lewat
 * scripts/kirim-suara.sh) didahulukan; kalimat yang belum direkam dibacakan mesin suara bawaan TV.
 * Tanpa bel.wav, bel "ding-dong-ding" dibuat langsung. Kalau suara Indonesia tidak tersedia,
 * hanya bunyi bel yang diputar.
 */
class Announcer(context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val chime = buildChime()
    private val recordings = context.getExternalFilesDir(null)?.let { File(it, "suara") }
    private val players = mutableListOf<MediaPlayer>()
    private var chimeTrack: AudioTrack? = null
    private var speechReady = false
    /** Mesin suara butuh 1-2 detik untuk tersambung; sebelum itu pengumuman menunggu sebentar. */
    private var initDone = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            // Kalau gagal, callback bisa terpanggil di dalam konstruktor sebelum tts terisi.
            if (status == TextToSpeech.SUCCESS && ::tts.isInitialized) {
                val result = tts.setLanguage(Locale("id", "ID"))
                speechReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (speechReady) preferOfflineVoice()
            }
            initDone = true
        }
    }

    /**
     * Bunyi bel lalu rekaman [clip] (lihat [clipName]), atau [text] lewat mesin suara kalau rekamannya
     * tidak ada. [onDone] dipanggil sekali di thread utama: [LEAD_AFTER_START_MS] setelah suara mulai
     * (sisa kalimat terdengar sambil aplikasi dimuat), saat suara selesai, atau paling lambat
     * [MAX_WAIT_MS] kalau suara macet. Dengan [untilEnd], [onDone] menunggu kalimatnya selesai.
     */
    fun announce(text: String, clip: String? = null, untilEnd: Boolean = false, onDone: (() -> Unit)? = null) {
        val finished = AtomicBoolean(false)
        val finish = Runnable { if (finished.compareAndSet(false, true)) onDone?.invoke() }
        handler.postDelayed(finish, if (untilEnd) MAX_SENTENCE_MS else MAX_WAIT_MS)

        val speakText = { speak(text, finish, INIT_WAIT_STEPS, untilEnd) }
        val say = {
            val recording = clip?.let(::recording)
            if (recording == null) {
                speakText()
            } else {
                play(
                    recording,
                    onStarted = { if (!untilEnd) handler.postDelayed(finish, LEAD_AFTER_START_MS) },
                    onEnded = { handler.post(finish) },
                    onFailed = speakText,
                )
            }
        }
        val bell = recording(BELL)
        if (bell == null) chimeThen(say) else play(bell, onEnded = say, onFailed = { chimeThen(say) })
    }

    fun hasRecording(names: List<String>) = names.any { recording(it) != null }

    /** Memutar rekaman pertama yang ada dari [names] sampai habis; false kalau tidak ada satu pun. */
    fun playRecording(names: List<String>, onEnded: () -> Unit): Boolean {
        val file = names.firstNotNullOfOrNull(::recording) ?: return false
        play(file, onEnded = onEnded, onFailed = onEnded)
        return true
    }

    private fun chimeThen(next: () -> Unit) {
        playChime()
        handler.postDelayed(next, CHIME_MS)
    }

    private fun recording(name: String): File? =
        recordings?.let { dir -> AUDIO_TYPES.map { File(dir, "$name.$it") }.firstOrNull { it.isFile } }

    /** Rekaman diputar seperti suara media, jadi ikut volume TV seperti bel bawaan. */
    private fun play(file: File, onStarted: () -> Unit = {}, onEnded: () -> Unit, onFailed: () -> Unit) {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // Lewat file descriptor: pemutar media sistem belum tentu boleh membuka folder data aplikasi.
            FileInputStream(file).use { player.setDataSource(it.fd) }
        } catch (e: IOException) {
            player.release()
            onFailed()
            return
        } catch (e: RuntimeException) {
            player.release()
            onFailed()
            return
        }
        players += player
        player.setOnPreparedListener {
            it.start()
            onStarted()
        }
        player.setOnCompletionListener {
            players -= it
            it.release()
            onEnded()
        }
        player.setOnErrorListener { mp, _, _ ->
            players -= mp
            mp.release()
            onFailed()
            true
        }
        player.prepareAsync()
    }

    private fun speak(text: String, finish: Runnable, waitSteps: Int, untilEnd: Boolean) {
        if (!initDone && waitSteps > 0) {
            handler.postDelayed({ speak(text, finish, waitSteps - 1, untilEnd) }, INIT_WAIT_STEP_MS)
            return
        }
        if (!speechReady) {
            handler.post(finish)
            return
        }
        val id = "ringan-${System.nanoTime()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                if (utteranceId == id && !untilEnd) handler.postDelayed(finish, LEAD_AFTER_START_MS)
            }
            override fun onDone(utteranceId: String) {
                if (utteranceId == id) handler.post(finish)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                if (utteranceId == id) handler.post(finish)
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /**
     * Suara bawaan Google untuk bahasa Indonesia bisa berupa suara server (lewat internet).
     * Kalau ada suara Indonesia yang terpasang di TV, pakai itu: lebih cepat dan tanpa internet.
     */
    private fun preferOfflineVoice() {
        val voices = try {
            tts.voices
        } catch (e: RuntimeException) {
            // Beberapa mesin suara melempar error kalau daftar suara belum siap.
            null
        } ?: return
        voices
            .filter { it.locale.language in INDONESIAN && !it.isNetworkConnectionRequired }
            .minByOrNull { it.latency }
            ?.let { tts.voice = it }
    }

    /** Menghentikan bel, rekaman, dan suara TTS; [onDone] yang belum terpanggil dibatalkan. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        chimeTrack?.release()
        chimeTrack = null
        players.forEach { it.release() }
        players.clear()
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun playChime() {
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            chime.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.write(chime, 0, chime.size)
        track.play()
        chimeTrack?.release()
        chimeTrack = track
        handler.postDelayed({
            if (chimeTrack === track) chimeTrack = null
            track.release()
        }, CHIME_MS + 200)
    }

    /** Tiga nada naik (C-E-G) dengan peluruhan seperti bel; 16-bit mono. */
    private fun buildChime(): ShortArray {
        val notes = doubleArrayOf(523.25, 659.25, 783.99)
        val noteSamples = (SAMPLE_RATE * NOTE_SECONDS).toInt()
        val total = noteSamples * notes.size + SAMPLE_RATE / 2
        val out = DoubleArray(total)
        notes.forEachIndexed { n, freq ->
            val start = n * noteSamples
            for (i in 0 until total - start) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = min(1.0, t / 0.01) * exp(-t * 3.5)
                out[start + i] += envelope * (sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t))
            }
        }
        return ShortArray(total) { (out[it] * 0.3 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }

    companion object {
        /**
         * Nama file rekaman tanpa ekstensi: [prefix] lalu [label] dalam huruf kecil, "+" menjadi
         * "plus" dan selain huruf/angka menjadi "_". "Disney+" -> naik_disney_plus.
         */
        fun clipName(prefix: String, label: String): String =
            prefix + label.lowercase(Locale.ROOT).replace("+", " plus ").replace(NOT_SLUG, "_").trim('_')

        private val NOT_SLUG = Regex("[^a-z0-9]+")
        private const val BELL = "bel"
        private val AUDIO_TYPES = listOf("wav", "mp3", "ogg")
        private const val SAMPLE_RATE = 22050
        private const val NOTE_SECONDS = 0.32
        private const val CHIME_MS = 1000L
        private const val LEAD_AFTER_START_MS = 1800L
        private const val MAX_WAIT_MS = 11_000L
        /** Kalimat pengumuman terpanjang sekitar 10 detik termasuk bel. */
        private const val MAX_SENTENCE_MS = 20_000L
        private const val INIT_WAIT_STEPS = 10
        private const val INIT_WAIT_STEP_MS = 200L

        /** Android lama memakai kode "in" untuk bahasa Indonesia. */
        private val INDONESIAN = setOf("in", "id", "ind")
    }
}
