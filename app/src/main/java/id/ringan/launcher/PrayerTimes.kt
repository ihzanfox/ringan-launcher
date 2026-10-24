package id.ringan.launcher

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Jadwal sholat dihitung offline dari posisi matahari, tanpa internet. Metode Kemenag:
 * Subuh 20° dan Isya 18° di bawah ufuk, Ashar mazhab Syafi'i (bayangan = 1 kali tinggi benda).
 * Rumusnya mengikuti PrayTimes.org; diuji cocok dengan jadwal Kemenag dari Aladhan untuk
 * Makassar (selisih paling banyak 1 menit sebelum ihtiyat).
 */
object PrayerTimes {

    enum class Prayer(val label: Int) {
        FAJR(R.string.prayer_fajr),
        SUNRISE(R.string.prayer_sunrise),
        DHUHR(R.string.prayer_dhuhr),
        ASR(R.string.prayer_asr),
        MAGHRIB(R.string.prayer_maghrib),
        ISHA(R.string.prayer_isha),
    }

    /** [minutes] = menit sejak tengah malam, waktu setempat. */
    class Time(val prayer: Prayer, val minutes: Int)

    private const val FAJR_ANGLE = 20.0
    private const val ISHA_ANGLE = 18.0
    private const val RISE_SET_ANGLE = 0.833

    /**
     * Semua waktu untuk tanggal [day] di zona waktu perangkat. [ihtiyatMinutes] (pengaman, lazimnya
     * 2 menit pada jadwal Kemenag) ditambahkan ke waktu sholat, tidak ke waktu terbit.
     */
    fun forDay(day: Calendar, latitude: Double, longitude: Double, ihtiyatMinutes: Int): List<Time> {
        val zone = day.timeZone ?: TimeZone.getDefault()
        val zoneHours = zone.getOffset(day.timeInMillis) / 3_600_000.0
        val jd = julian(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH)) -
            longitude / (15 * 24)
        val sun = SunMath(jd, latitude)

        val raw = linkedMapOf(
            Prayer.FAJR to sun.angleTime(FAJR_ANGLE, 5.0 / 24, beforeNoon = true),
            Prayer.SUNRISE to sun.angleTime(RISE_SET_ANGLE, 6.0 / 24, beforeNoon = true),
            Prayer.DHUHR to sun.midDay(12.0 / 24),
            Prayer.ASR to sun.asrTime(13.0 / 24),
            Prayer.MAGHRIB to sun.angleTime(RISE_SET_ANGLE, 18.0 / 24, beforeNoon = false),
            Prayer.ISHA to sun.angleTime(ISHA_ANGLE, 18.0 / 24, beforeNoon = false),
        )
        return raw.map { (prayer, hours) ->
            val local = hours + zoneHours - longitude / 15
            val extra = if (prayer == Prayer.SUNRISE) 0 else ihtiyatMinutes
            Time(prayer, floor(local * 60 + 0.5).toInt() + extra)
        }
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** Posisi matahari dan waktu-waktu turunannya, dalam jam (waktu matahari UTC) untuk satu tanggal. */
    private class SunMath(private val jd: Double, private val latitude: Double) {

        /** Deklinasi (derajat) dan persamaan waktu (jam) pada [dayFraction] hari. */
        private fun position(dayFraction: Double): Pair<Double, Double> {
            val d = jd + dayFraction - 2451545.0
            val g = fixAngle(357.529 + 0.98560028 * d)
            val q = fixAngle(280.459 + 0.98564736 * d)
            val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
            val e = 23.439 - 0.00000036 * d
            val ra = Math.toDegrees(atan2(dcos(e) * dsin(l), dcos(l))) / 15
            val equation = q / 15 - fixHour(ra)
            val declination = Math.toDegrees(asin(dsin(e) * dsin(l)))
            return declination to equation
        }

        fun midDay(dayFraction: Double): Double = fixHour(12 - position(dayFraction).second)

        fun angleTime(angle: Double, dayFraction: Double, beforeNoon: Boolean): Double {
            val declination = position(dayFraction).first
            val t = Math.toDegrees(
                acos((-dsin(angle) - dsin(declination) * dsin(latitude)) / (dcos(declination) * dcos(latitude))),
            ) / 15
            return midDay(dayFraction) + if (beforeNoon) -t else t
        }

        fun asrTime(dayFraction: Double): Double {
            val declination = position(dayFraction).first
            val angle = -Math.toDegrees(atan(1 / (1 + dtan(abs(latitude - declination)))))
            return angleTime(angle, dayFraction, beforeNoon = false)
        }

        private fun dsin(d: Double) = sin(Math.toRadians(d))
        private fun dcos(d: Double) = cos(Math.toRadians(d))
        private fun dtan(d: Double) = tan(Math.toRadians(d))
        private fun fixAngle(a: Double) = fix(a, 360.0)
        private fun fixHour(h: Double) = fix(h, 24.0)
        private fun fix(a: Double, b: Double): Double {
            val r = a - b * floor(a / b)
            return if (r < 0) r + b else r
        }
    }
}
