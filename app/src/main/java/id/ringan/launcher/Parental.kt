package id.ringan.launcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Aturan jam tayang dari config.json bagian "parental". Menit 0 = tanpa batas. */
class ParentalRules(
    val pin: String,
    val sessionMinutes: Int,
    val breakMinutes: Int,
    val dailyMinutes: Int,
    /** Menit sejak tengah malam; null kalau tidak memakai jam tidur. */
    val bedtimeMinute: Int?,
    val wakeupMinute: Int,
    val unlockMinutes: Int,
)

enum class LockReason { BREAK, DAILY, BEDTIME }

/**
 * Jam tayang anak. Yang dihitung adalah waktu layar menyala sementara papan Ringan tidak tampil
 * (sedang di aplikasi, HDMI, atau Setelan). Penghitungnya RemoteKeyService, karena hanya layanan
 * itu yang terus hidup dan boleh membawa Ringan ke depan. "Hari" anak dimulai pada jam bangun.
 * Selama gerbang dibuka dengan PIN (misalnya ada tamu), waktu menonton tidak dihitung.
 */
object Parental {

    /** Diisi MainActivity dan AllAppsActivity: true selama layar Ringan tampil. */
    @Volatile
    var boardVisible = false

    /** Dipanggil tiap beberapa detik. Mengembalikan alasan kunci kalau anak sedang menonton saat gerbang tertutup. */
    fun tick(context: Context, rules: ParentalRules, elapsedMs: Long, interactive: Boolean): LockReason? {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val day = dayKey(now, rules.wakeupMinute)
        val newDay = prefs.getString(KEY_DAY, null) != day
        var used = if (newDay) 0L else prefs.getLong(KEY_USED, 0L)
        var session = if (newDay) 0L else prefs.getLong(KEY_SESSION, 0L)
        var rest = if (newDay) 0L else prefs.getLong(KEY_REST, 0L)
        var breakUntil = prefs.getLong(KEY_BREAK_UNTIL, 0L)
        val unlocked = now < prefs.getLong(KEY_UNLOCK_UNTIL, 0L)

        val watching = interactive && !boardVisible
        if (watching && !unlocked) {
            used += elapsedMs
            session += elapsedMs
            rest = 0
        } else if (!watching) {
            // Istirahat (di papan atau layar mati) selama waktu jeda mengulang hitungan sesi.
            rest += elapsedMs
            if (rest >= rules.breakMinutes * MINUTE_MS) session = 0
        }
        if (!unlocked && rules.sessionMinutes > 0 && session >= rules.sessionMinutes * MINUTE_MS) {
            breakUntil = now + rules.breakMinutes * MINUTE_MS
            session = 0
        }
        prefs.edit()
            .putString(KEY_DAY, day)
            .putLong(KEY_USED, used)
            .putLong(KEY_SESSION, session)
            .putLong(KEY_REST, rest)
            .putLong(KEY_BREAK_UNTIL, breakUntil)
            .apply()
        return if (watching) lockReason(context, rules, now) else null
    }

    /** Alasan gerbang sedang tertutup, atau null kalau boleh menonton (termasuk saat dibuka dengan PIN). */
    fun lockReason(context: Context, rules: ParentalRules?, now: Long = System.currentTimeMillis()): LockReason? {
        if (rules == null) return null
        val prefs = prefs(context)
        if (now < prefs.getLong(KEY_UNLOCK_UNTIL, 0L)) return null
        val today = prefs.getString(KEY_DAY, null) == dayKey(now, rules.wakeupMinute)
        return when {
            inBedtime(now, rules) -> LockReason.BEDTIME
            rules.dailyMinutes > 0 && today && prefs.getLong(KEY_USED, 0L) >= rules.dailyMinutes * MINUTE_MS ->
                LockReason.DAILY
            now < prefs.getLong(KEY_BREAK_UNTIL, 0L) -> LockReason.BREAK
            else -> null
        }
    }

    /** Kapan gerbang dibuka lagi, untuk ditampilkan di layar "mendarat". */
    fun reopensAt(context: Context, rules: ParentalRules, reason: LockReason): Long = when (reason) {
        LockReason.BREAK -> prefs(context).getLong(KEY_BREAK_UNTIL, 0L)
        LockReason.DAILY, LockReason.BEDTIME -> nextMinuteOfDay(rules.wakeupMinute)
    }

    fun usedTodayMinutes(context: Context, rules: ParentalRules): Int {
        val prefs = prefs(context)
        if (prefs.getString(KEY_DAY, null) != dayKey(System.currentTimeMillis(), rules.wakeupMinute)) return 0
        return (prefs.getLong(KEY_USED, 0L) / MINUTE_MS).toInt()
    }

    /** PIN benar: gerbang dibuka sampai [until] dan hitungan sesi diulang. */
    fun unlock(context: Context, until: Long) {
        prefs(context).edit()
            .putLong(KEY_UNLOCK_UNTIL, until)
            .putLong(KEY_BREAK_UNTIL, 0L)
            .putLong(KEY_SESSION, 0L)
            .apply()
    }

    /** Menutup gerbang lagi sebelum waktunya, misalnya setelah tamu pulang. */
    fun relock(context: Context) {
        prefs(context).edit().putLong(KEY_UNLOCK_UNTIL, 0L).apply()
    }

    /** Sampai kapan gerbang dibuka dengan PIN; 0 kalau tidak sedang dibuka. */
    fun unlockedUntil(context: Context): Long =
        prefs(context).getLong(KEY_UNLOCK_UNTIL, 0L).takeIf { it > System.currentTimeMillis() } ?: 0L

    /** Jam bangun berikutnya, untuk pilihan "buka sampai jam bangun". */
    fun nextWakeup(rules: ParentalRules): Long = nextMinuteOfDay(rules.wakeupMinute)

    private fun inBedtime(now: Long, rules: ParentalRules): Boolean {
        val bed = rules.bedtimeMinute ?: return false
        val wake = rules.wakeupMinute
        val minute = minuteOfDay(now)
        return if (bed < wake) minute in bed until wake else minute >= bed || minute < wake
    }

    private fun minuteOfDay(millis: Long): Int = Calendar.getInstance().run {
        timeInMillis = millis
        get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
    }

    private fun nextMinuteOfDay(minute: Int): Long = Calendar.getInstance().run {
        val now = timeInMillis
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
        timeInMillis
    }

    /** Tanggal "hari anak": dimulai pada jam bangun, bukan tengah malam. */
    private fun dayKey(now: Long, wakeupMinute: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now - wakeupMinute * MINUTE_MS))

    private fun prefs(context: Context) = context.getSharedPreferences("parental", Context.MODE_PRIVATE)

    private const val MINUTE_MS = 60_000L
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used_ms"
    private const val KEY_SESSION = "session_ms"
    private const val KEY_REST = "rest_ms"
    private const val KEY_BREAK_UNTIL = "break_until"
    private const val KEY_UNLOCK_UNTIL = "unlock_until"
}
