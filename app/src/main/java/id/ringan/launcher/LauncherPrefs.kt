package id.ringan.launcher

import android.annotation.SuppressLint
import android.content.Context

class LauncherPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Isi papan keberangkatan sesuai urutan: nama paket aplikasi, atau kunci karaoke ([karaokeKey]). */
    var board: List<String>
        get() = prefs.getString(KEY_BOARD, null)?.split('\n')?.filter { it.isNotEmpty() } ?: DEFAULT_BOARD
        set(value) = prefs.edit().putString(KEY_BOARD, value.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, null).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN, value).apply()

    /** Kapan terakhir mencoba mengambil jadwal penerbangan, agar kegagalan tidak menguras kuota. */
    var flightsAttemptAt: Long
        get() = prefs.getLong(KEY_FLIGHTS_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_FLIGHTS_ATTEMPT, value).apply()

    /** Sidik pengaturan (bandara, provider, hash API key) saat percobaan terakhir. */
    var flightsAttemptConfig: String?
        get() = prefs.getString(KEY_FLIGHTS_ATTEMPT_CONFIG, null)
        set(value) = prefs.edit().putString(KEY_FLIGHTS_ATTEMPT_CONFIG, value).apply()

    /** Kode HTTP percobaan terakhir yang gagal, -1 untuk gangguan jaringan, 0 kalau berhasil. */
    var flightsError: Int
        get() = prefs.getInt(KEY_FLIGHTS_ERROR, 0)
        set(value) = prefs.edit().putInt(KEY_FLIGHTS_ERROR, value).apply()

    var flightAwareAttemptAt: Long
        get() = prefs.getLong(KEY_FA_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_FA_ATTEMPT, value).apply()

    /** Kode HTTP percobaan FlightAware terakhir yang gagal, -1 untuk gangguan jaringan, 0 kalau berhasil. */
    var flightAwareError: Int
        get() = prefs.getInt(KEY_FA_ERROR, 0)
        set(value) = prefs.edit().putInt(KEY_FA_ERROR, value).apply()

    /** Panggilan FlightAware yang berhasil pada [month] ("2026-10", UTC); bulan lain dianggap 0. */
    fun flightAwareCalls(month: String): Int =
        if (prefs.getString(KEY_FA_MONTH, null) == month) prefs.getInt(KEY_FA_CALLS, 0) else 0

    // commit, bukan apply: penghitung biaya harus sudah tersimpan sebelum proses mungkin ditutup sistem.
    @SuppressLint("ApplySharedPref")
    fun addFlightAwareCalls(month: String, calls: Int) {
        prefs.edit()
            .putString(KEY_FA_MONTH, month)
            .putInt(KEY_FA_CALLS, flightAwareCalls(month) + calls)
            .commit()
    }

    /** Sekali saja: sisipkan baris karaoke tambahan tepat setelah baris karaoke yang sudah ada. */
    fun addKaraokeRowsOnce(channels: Int) {
        if (prefs.getBoolean(KEY_KARAOKE_ROWS, false)) return
        val current = board.toMutableList()
        val missing = (1 until channels).map { karaokeKey(it) }.filter { it !in current }
        val at = current.indexOf(KARAOKE).let { if (it >= 0) it + 1 else minOf(1, current.size) }
        current.addAll(at, missing)
        board = current
        prefs.edit().putBoolean(KEY_KARAOKE_ROWS, true).apply()
    }

    /** "2026-10-24 MAGHRIB": pengingat sholat terakhir, supaya tidak diumumkan dua kali. */
    var lastPrayerAnnounced: String?
        get() = prefs.getString(KEY_PRAYER_ANNOUNCED, null)
        set(value) = prefs.edit().putString(KEY_PRAYER_ANNOUNCED, value).apply()

    fun lastOpened(key: String): Long = prefs.getLong(KEY_OPENED + key, 0L)

    fun markOpened(key: String) = prefs.edit().putLong(KEY_OPENED + key, System.currentTimeMillis()).apply()

    companion object {
        const val KARAOKE = "ringan:karaoke"

        /** Baris karaoke pertama memakai kunci lama supaya papan yang sudah tersimpan tetap cocok. */
        fun karaokeKey(index: Int) = if (index == 0) KARAOKE else "$KARAOKE:$index"

        fun karaokeIndex(key: String): Int? = when {
            key == KARAOKE -> 0
            key.startsWith("$KARAOKE:") -> key.substringAfter("$KARAOKE:").toIntOrNull()
            else -> null
        }

        /** Isi awal; paket yang tidak terpasang dilewati saja. */
        private val DEFAULT_BOARD = listOf(
            "com.google.android.youtube.tv",
            KARAOKE,
            "$KARAOKE:1",
            "$KARAOKE:2",
            "com.google.android.youtube.tvmusic",
            "com.netflix.ninja",
            "com.vidio.android.tv",
            "in.startv.hotstar.dplus.tv",
        )

        private const val KEY_BOARD = "board"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_OPENED = "opened:"
        private const val KEY_FLIGHTS_ATTEMPT = "flights_attempt"
        private const val KEY_FLIGHTS_ATTEMPT_CONFIG = "flights_attempt_config"
        private const val KEY_FLIGHTS_ERROR = "flights_error"
        private const val KEY_FA_ATTEMPT = "fa_attempt"
        private const val KEY_FA_ERROR = "fa_error"
        private const val KEY_FA_MONTH = "fa_month"
        private const val KEY_FA_CALLS = "fa_calls"
        private const val KEY_KARAOKE_ROWS = "karaoke_rows_v2"
        private const val KEY_PRAYER_ANNOUNCED = "prayer_announced"
    }
}
