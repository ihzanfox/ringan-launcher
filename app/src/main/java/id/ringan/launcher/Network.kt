package id.ringan.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private const val TIMEOUT_MS = 8_000

/** Server menjawab dengan kode selain 2xx, misalnya 403 kalau API key belum berlangganan. */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code dari $url")

/** Panggil dari thread latar. API key tidak pernah ikut dalam pesan error. */
private fun <T> httpGet(url: String, headers: Map<String, String>, read: (InputStream) -> T): T {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val code = connection.responseCode
        if (code !in 200..299) throw HttpStatusException(code, url)
        return connection.inputStream.use(read)
    } finally {
        connection.disconnect()
    }
}

fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String =
    httpGet(url, headers) { it.bufferedReader().readText() }

fun httpGetBytes(url: String): ByteArray = httpGet(url, emptyMap()) { it.readBytes() }

class HourForecast(val hour: String, val temperature: Int, val code: Int, val isDay: Boolean)

class WeatherNow(
    val temperature: Int,
    val code: Int,
    val isDay: Boolean,
    val min: Int,
    val max: Int,
    val windKmh: Float,
    val precipitationMm: Float,
    /** Enam jam berikutnya, mulai jam penuh setelah sekarang. */
    val hourly: List<HourForecast>,
)

object Weather {

    /** Open-Meteo: gratis, tanpa API key, tanpa pelacakan. */
    fun fetchOrNull(latitude: Double, longitude: Double): WeatherNow? = try {
        val json = JSONObject(
            httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,is_day,wind_speed_10m,precipitation" +
                    "&hourly=temperature_2m,weather_code,is_day&forecast_hours=8" +
                    "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2",
            ),
        )
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        WeatherNow(
            temperature = current.getDouble("temperature_2m").roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            windKmh = current.optDouble("wind_speed_10m", 0.0).toFloat(),
            precipitationMm = current.optDouble("precipitation", 0.0).toFloat(),
            hourly = hourly(json.optJSONObject("hourly"), current.optString("time")),
        )
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    /** Jam lokal berformat "2026-10-24T16:00"; yang lebih besar dari [now] (format sama) diambil. */
    private fun hourly(hourly: JSONObject?, now: String): List<HourForecast> {
        hourly ?: return emptyList()
        val times = hourly.optJSONArray("time") ?: return emptyList()
        val temperatures = hourly.optJSONArray("temperature_2m") ?: return emptyList()
        val codes = hourly.optJSONArray("weather_code") ?: return emptyList()
        val days = hourly.optJSONArray("is_day")
        return (0 until times.length())
            .filter { times.optString(it) > now }
            .take(HOURS_AHEAD)
            .map {
                HourForecast(
                    hour = times.optString(it).substringAfter('T').substringBefore(':'),
                    temperature = temperatures.optDouble(it).roundToInt(),
                    code = codes.optInt(it),
                    isDay = days?.optInt(it, 1) != 0,
                )
            }
    }

    private const val HOURS_AHEAD = 6

    /** Kode cuaca WMO yang dipakai Open-Meteo. */
    fun describe(code: Int): Int = when (code) {
        0 -> R.string.w_clear
        1 -> R.string.w_mostly_clear
        2 -> R.string.w_partly_cloudy
        45, 48 -> R.string.w_fog
        in 51..57 -> R.string.w_drizzle
        61, 80 -> R.string.w_rain_light
        63, 66, 81 -> R.string.w_rain
        65, 67, 82 -> R.string.w_rain_heavy
        in 71..77, 85, 86 -> R.string.w_snow
        in 95..99 -> R.string.w_storm
        else -> R.string.w_overcast
    }

    fun scene(code: Int, isDay: Boolean): WeatherView.Scene = when (code) {
        0, 1 -> if (isDay) WeatherView.Scene.SUN else WeatherView.Scene.MOON
        2 -> if (isDay) WeatherView.Scene.PARTLY_DAY else WeatherView.Scene.PARTLY_NIGHT
        45, 48 -> WeatherView.Scene.FOG
        in 51..67, in 80..82 -> WeatherView.Scene.RAIN
        in 71..77, 85, 86 -> WeatherView.Scene.SNOW
        in 95..99 -> WeatherView.Scene.STORM
        else -> WeatherView.Scene.CLOUD
    }

    /** 0..1 untuk banyaknya tetes hujan: dari curah hujan saat ini, atau dari kode cuaca kalau curahnya 0. */
    fun intensity(code: Int, precipitationMm: Float): Float {
        val fromCode = when (code) {
            in 51..57 -> 0.2f
            61, 80 -> 0.4f
            63, 66, 81 -> 0.7f
            65, 67, 82, in 95..99 -> 1f
            else -> 0.5f
        }
        return maxOf(fromCode, (precipitationMm / 4f).coerceAtMost(1f))
    }
}

enum class Tone(val color: Int) {
    GOOD(R.color.status_good),
    NEUTRAL(R.color.dim),
    WARNING(R.color.amber),
    BAD(R.color.status_bad),
}

class FlightRow(
    val time: String,
    /** Kota tujuan untuk keberangkatan, kota asal untuk kedatangan. */
    val city: String,
    val number: String,
    /** Kode IATA maskapai untuk mencari logonya, misalnya "JT". */
    val airlineCode: String,
    val airlineName: String,
    /** Null kalau hanya terjadwal: papan cukup menampilkan status yang perlu diperhatikan. */
    val status: String?,
    val tone: Tone,
)

class FlightBoards(val departures: List<FlightRow>, val arrivals: List<FlightRow>)

object Flights {

    private const val RAPIDAPI_HOST = "aerodatabox.p.rapidapi.com"
    private const val APIMARKET_URL = "https://prod.api.market/api/v1/aedbx/aerodatabox"
    private const val DIRECT_URL = "https://api.aerodatabox.com"

    /** Penerbangan berstatus langsung (boarding, berangkat, ...) masih tampil selama ini setelah lewat. */
    private const val KEEP_PAST_MS = 15 * 60_000L
    private const val DELAY_THRESHOLD_MS = 10 * 60_000L

    /** Nama kota yang tidak bisa ditebak dari data AeroDataBox (misalnya bandara Ternate tercatat di "Sango"). */
    private val CITY_NAMES = mapOf("TTE" to "Ternate", "BPN" to "Balikpapan", "BUW" to "Baubau")

    /** Tanpa info langsung, status hanya jadwal; penerbangan seperti ini hilang begitu jamnya lewat. */
    private val SCHEDULE_ONLY = setOf("", "Unknown", "Expected")

    /**
     * Keberangkatan dan kedatangan mulai 20 menit lalu sampai 12 jam ke depan. Satu panggilan
     * memakai 2 unit dari kuota AeroDataBox, jadi panggil jarang dan simpan hasilnya.
     */
    fun fetch(config: RinganConfig): String {
        val key = requireNotNull(config.flightsApiKey)
        val query = "/flights/airports/iata/${config.airport}?offsetMinutes=-20&durationMinutes=720" +
            "&withLeg=false&withCancelled=true&withCodeshared=false" +
            "&withCargo=false&withPrivate=false&withLocation=false"
        return when (config.flightsProvider) {
            "apimarket" -> httpGet(APIMARKET_URL + query, mapOf("x-api-market-key" to key))
            "aerodatabox" -> httpGet(DIRECT_URL + query, mapOf("X-Api-Key" to key))
            else -> httpGet(
                "https://$RAPIDAPI_HOST$query",
                mapOf("X-RapidAPI-Key" to key, "X-RapidAPI-Host" to RAPIDAPI_HOST),
            )
        }
    }

    /** Murah, tanpa jaringan: dipanggil ulang tiap kali papan diperbarui untuk membuang yang sudah lewat. */
    fun parse(context: Context, response: JSONObject, now: Long, limit: Int) = FlightBoards(
        departures = parseList(context, response.optJSONArray("departures"), now, limit, arrivals = false),
        arrivals = parseList(context, response.optJSONArray("arrivals"), now, limit, arrivals = true),
    )

    private fun parseList(context: Context, flights: JSONArray?, now: Long, limit: Int, arrivals: Boolean): List<FlightRow> {
        if (flights == null) return emptyList()
        return (0 until flights.length())
            .mapNotNull { flights.optJSONObject(it) }
            .filter { it.optString("codeshareStatus") != "IsCodeshared" && !it.optBoolean("isCargo") }
            .mapNotNull { toRow(context, it, now, arrivals) }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
    }

    /** Hasilnya berpasangan dengan jam jadwal (UTC): papan bandara diurutkan menurut jadwal, bukan revisi. */
    private fun toRow(context: Context, flight: JSONObject, now: Long, arrivals: Boolean): Pair<Long, FlightRow>? {
        val movement = flight.optJSONObject("movement") ?: return null
        val scheduled = movement.optJSONObject("scheduledTime") ?: return null
        val scheduledAt = parseUtc(scheduled.optString("utc")) ?: return null
        val revised = movement.optJSONObject("revisedTime")
        val revisedAt = revised?.let { parseUtc(it.optString("utc")) }
        val departsAt = revisedAt ?: scheduledAt
        val statusCode = flight.optString("status")
        val keepPast = if (revisedAt == null && statusCode in SCHEDULE_ONLY) 0L else KEEP_PAST_MS
        if (departsAt < now - keepPast) return null

        // "airport" di dalam movement adalah bandara di ujung lain: tujuan untuk keberangkatan, asal untuk kedatangan.
        val city = cityName(movement.optJSONObject("airport"))
        val number = flight.optString("number")
        // "Wings Air (Indonesia)" -> "Wings Air"
        val airline = flight.optJSONObject("airline")
        val airlineName = airline?.optString("name").orEmpty().substringBefore(" (")
        val delayed = revisedAt != null && revisedAt - scheduledAt >= DELAY_THRESHOLD_MS

        val (status, tone) = when (statusCode) {
            "CheckIn" -> context.getString(R.string.flight_check_in) to Tone.GOOD
            "Boarding" -> context.getString(R.string.flight_boarding) to Tone.GOOD
            "GateClosed" -> context.getString(R.string.flight_gate_closed) to Tone.WARNING
            "Departed", "EnRoute" -> if (arrivals) {
                context.getString(R.string.flight_en_route) to Tone.GOOD
            } else {
                context.getString(R.string.flight_departed) to Tone.NEUTRAL
            }
            "Approaching" -> if (arrivals) {
                context.getString(R.string.flight_landing) to Tone.GOOD
            } else {
                context.getString(R.string.flight_departed) to Tone.NEUTRAL
            }
            "Arrived" -> context.getString(if (arrivals) R.string.flight_arrived else R.string.flight_departed) to Tone.NEUTRAL
            "Canceled", "CanceledUncertain" -> context.getString(R.string.flight_canceled) to Tone.BAD
            "Diverted" -> context.getString(R.string.flight_diverted) to Tone.WARNING
            "Delayed" -> delayText(context, revised, delayed) to Tone.WARNING
            else -> if (delayed) delayText(context, revised, true) to Tone.WARNING else null to Tone.NEUTRAL
        }
        val row = FlightRow(
            time = localClock(scheduled.optString("local")),
            city = city,
            number = number,
            airlineCode = airline?.optString("iata").orEmpty(),
            airlineName = airlineName,
            status = status,
            tone = tone,
        )
        return scheduledAt to row
    }

    private fun cityName(airport: JSONObject?): String {
        val iata = airport?.optString("iata").orEmpty()
        CITY_NAMES[iata]?.let { return it }
        val name = listOf("municipalityName", "shortName", "name")
            .map { airport?.optString(it).orEmpty() }
            .firstOrNull { it.isNotEmpty() && it != "null" }
            ?: return iata.ifEmpty { "?" }
        // AeroDataBox sering menulis "Kota-Pulau Island", misalnya "Kendari-Celebes Island".
        val city = name.removePrefix("Kotamadya ").removePrefix("Kabupaten ")
        return if (city.endsWith(" Island")) city.substringBefore('-') else city
    }

    private fun delayText(context: Context, revised: JSONObject?, showTime: Boolean): String {
        val time = revised?.let { localClock(it.optString("local")) }
        return if (showTime && !time.isNullOrEmpty()) {
            context.getString(R.string.flight_delayed_to, time)
        } else {
            context.getString(R.string.flight_delayed)
        }
    }

    /** "2026-10-24 14:35+08:00" -> "14:35", jam setempat bandara. */
    private fun localClock(local: String): String = if (local.length >= 16) local.substring(11, 16) else ""

    /** "2026-10-24 06:35Z" -> epoch millis. */
    private fun parseUtc(utc: String): Long? {
        if (utc.length < 16) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(utc.substring(0, 16))
                ?.time
        } catch (e: ParseException) {
            null
        }
    }
}
