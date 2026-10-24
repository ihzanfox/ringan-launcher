package id.ringan.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Status penerbangan langsung dari FlightAware AeroAPI. Berbayar per panggilan, jadi
 * MainActivity membatasi frekuensi dan total biayanya per bulan.
 */
object FlightAware {

    private const val BASE_URL = "https://aeroapi.flightaware.com/aeroapi"

    /** Satu panggilan dengan max_pages=1 = satu result set (maks. 15 penerbangan) = US$0,005. */
    const val CALLS_PER_USD = 200

    /** Tiap pembaruan memanggil keberangkatan dan kedatangan. */
    const val CALLS_PER_REFRESH = 2

    private const val WINDOW_PAST_MS = 30 * 60_000L
    private const val WINDOW_FUTURE_MS = 12 * 60 * 60_000L
    private const val KEEP_PAST_MS = 15 * 60_000L
    private const val DELAY_THRESHOLD_S = 10 * 60

    /**
     * Dua panggilan berbayar; [onPaidCall] dipanggil setelah tiap panggilan yang berhasil supaya
     * penghitung biaya tetap tepat walau panggilan kedua gagal. Jendela waktunya dipersempit agar
     * halaman tidak terisi penerbangan lama.
     */
    fun fetch(config: RinganConfig, now: Long, onPaidCall: () -> Unit): JSONObject {
        val headers = mapOf("x-apikey" to requireNotNull(config.flightAwareKey))
        val window = "type=Airline&max_pages=1&start=${iso(now - WINDOW_PAST_MS)}&end=${iso(now + WINDOW_FUTURE_MS)}"
        val base = "$BASE_URL/airports/${config.flightAwareAirport}/flights"
        val departures = httpGet("$base/scheduled_departures?$window", headers).also { onPaidCall() }
        val arrivals = httpGet("$base/scheduled_arrivals?$window", headers).also { onPaidCall() }
        return JSONObject()
            .put("departures", JSONObject(departures).optJSONArray("scheduled_departures") ?: JSONArray())
            .put("arrivals", JSONObject(arrivals).optJSONArray("scheduled_arrivals") ?: JSONArray())
    }

    fun parse(context: Context, data: JSONObject, now: Long, limit: Int) = FlightBoards(
        departures = rows(context, data.optJSONArray("departures"), now, limit, arrivals = false),
        arrivals = rows(context, data.optJSONArray("arrivals"), now, limit, arrivals = true),
    )

    private fun rows(context: Context, flights: JSONArray?, now: Long, limit: Int, arrivals: Boolean): List<FlightRow> {
        if (flights == null) return emptyList()
        return (0 until flights.length())
            .mapNotNull { flights.optJSONObject(it) }
            .mapNotNull { toRow(context, it, now, arrivals) }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
    }

    /** Berpasangan dengan jam jadwal untuk pengurutan, seperti papan bandara. */
    private fun toRow(context: Context, flight: JSONObject, now: Long, arrivals: Boolean): Pair<Long, FlightRow>? {
        // Kedatangan memakai jam tiba di gerbang (in), keberangkatan jam lepas dari gerbang (out).
        val (gate, runway) = if (arrivals) "in" to "on" else "out" to "off"
        val scheduled = time(flight, "scheduled_$gate") ?: time(flight, "scheduled_$runway") ?: return null
        val estimated = time(flight, "estimated_$gate") ?: time(flight, "estimated_$runway")
        val done = if (arrivals) time(flight, "actual_in") else time(flight, "actual_off")
        if (done != null && done < now - KEEP_PAST_MS) return null
        if (flight.optBoolean("cancelled") && scheduled < now - KEEP_PAST_MS) return null

        val home = flight.optJSONObject(if (arrivals) "destination" else "origin")
        val zone = home?.optString("timezone")?.takeIf { it.isNotEmpty() }?.let { TimeZone.getTimeZone(it) }
            ?: TimeZone.getDefault()
        val other = flight.optJSONObject(if (arrivals) "origin" else "destination")
        val city = listOf("city", "name", "code_iata")
            .map { other?.optString(it).orEmpty() }
            .firstOrNull { it.isNotEmpty() && it != "null" } ?: "?"

        val airline = flight.optString("operator_iata").takeIf { it.isNotEmpty() && it != "null" }.orEmpty()
        val number = flight.optString("flight_number").takeIf { it.isNotEmpty() && it != "null" }
        val delaySeconds = flight.optInt(if (arrivals) "arrival_delay" else "departure_delay", 0)
        val delayed = delaySeconds >= DELAY_THRESHOLD_S ||
            (estimated != null && estimated - scheduled >= DELAY_THRESHOLD_S * 1000L)

        val (status, tone) = when {
            flight.optBoolean("cancelled") -> context.getString(R.string.flight_canceled) to Tone.BAD
            flight.optBoolean("diverted") -> context.getString(R.string.flight_diverted) to Tone.WARNING
            arrivals && time(flight, "actual_in") != null -> context.getString(R.string.flight_arrived) to Tone.NEUTRAL
            arrivals && time(flight, "actual_on") != null -> context.getString(R.string.flight_landing) to Tone.GOOD
            arrivals && time(flight, "actual_off") != null -> if (delayed && estimated != null) {
                context.getString(R.string.flight_delayed_to, clock(estimated, zone)) to Tone.WARNING
            } else {
                context.getString(R.string.flight_en_route) to Tone.GOOD
            }
            !arrivals && (time(flight, "actual_off") != null || time(flight, "actual_out") != null) ->
                context.getString(R.string.flight_departed) to Tone.NEUTRAL
            delayed && estimated != null -> context.getString(R.string.flight_delayed_to, clock(estimated, zone)) to Tone.WARNING
            delayed -> context.getString(R.string.flight_delayed) to Tone.WARNING
            !arrivals && flight.optString("gate_origin").let { it.isNotEmpty() && it != "null" } ->
                context.getString(R.string.flight_gate, flight.optString("gate_origin")) to Tone.NEUTRAL
            else -> null to Tone.NEUTRAL
        }

        val row = FlightRow(
            time = clock(scheduled, zone),
            city = city,
            number = if (airline.isNotEmpty() && number != null) "$airline $number" else flight.optString("ident"),
            airlineCode = airline,
            airlineName = "",
            status = status,
            tone = tone,
        )
        return scheduled to row
    }

    private fun time(flight: JSONObject, key: String): Long? {
        val value = flight.optString(key)
        if (value.length < 19 || value == "null") return null
        return try {
            utcFormat().parse(value.substring(0, 19))?.time
        } catch (e: ParseException) {
            null
        }
    }

    private fun clock(millis: Long, zone: TimeZone): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }.format(Date(millis))

    private fun iso(millis: Long): String = utcFormat().format(Date(millis)) + "Z"

    private fun utcFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
