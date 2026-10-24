package id.ringan.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Satu baris karaoke di papan: nama tampilan dan kata kunci pencarian YouTube. */
data class KaraokeChannel(val name: String, val query: String)

/** Pengaturan dari config.json yang dikirim lewat scripts/kirim-config.sh. */
data class RinganConfig(
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val karaoke: List<KaraokeChannel>,
    /** Bunyi bel dan pengumuman suara saat membuka aplikasi dari papan. */
    val announcements: Boolean,
    val prayerEnabled: Boolean,
    /** Menit pengaman yang ditambahkan ke waktu sholat (Kemenag lazimnya 2). */
    val prayerIhtiyat: Int,
    /** Pengumuman saat masuk waktu sholat. */
    val prayerReminder: Boolean,
    /** Azan setelah pengumuman sholat, kalau rekaman azan.* sudah dikirim ke TV. */
    val azan: Boolean,
    /** Jam tayang anak; null kalau bagian "parental" tidak diisi atau PIN-nya tidak valid. */
    val parental: ParentalRules?,
    val airport: String,
    val flightsApiKey: String?,
    /** "rapidapi", "apimarket", atau "aerodatabox" (langsung): tempat API key didaftarkan. */
    val flightsProvider: String,
    val flightsRefreshMinutes: Int,
    /** API key FlightAware AeroAPI untuk status langsung; null kalau tidak dipakai. */
    val flightAwareKey: String?,
    /** Kode ICAO bandara untuk FlightAware, misalnya WAAA untuk UPG. */
    val flightAwareAirport: String,
    val flightAwareRefreshMinutes: Int,
    /** Batas biaya FlightAware per bulan kalender (UTC), dalam dolar AS. */
    val flightAwareBudgetUsd: Double,
) {
    companion object {
        val DEFAULT = RinganConfig(
            city = "Makassar",
            latitude = -5.1477,
            longitude = 119.4327,
            karaoke = listOf(
                KaraokeChannel("Karaoke Anak", "karaoke anak"),
                KaraokeChannel("Lagu Daerah", "karaoke lagu daerah makassar bugis"),
                KaraokeChannel("Lagu Nasional", "karaoke lagu nasional indonesia"),
            ),
            announcements = true,
            prayerEnabled = true,
            prayerIhtiyat = 2,
            prayerReminder = true,
            azan = true,
            parental = null,
            airport = "UPG",
            flightsApiKey = null,
            flightsProvider = "rapidapi",
            // Kuota gratis AeroDataBox ±200 permintaan per bulan; tiap 4 jam = paling banyak 180.
            flightsRefreshMinutes = 240,
            flightAwareKey = null,
            flightAwareAirport = "WAAA",
            flightAwareRefreshMinutes = 15,
            // Paket Personal gratis US$5 per bulan; sisakan sedikit untuk jaga-jaga.
            flightAwareBudgetUsd = 4.5,
        )

        /** Pengaturan terakhir yang dibaca, untuk bagian yang tidak bisa menunggu pembacaan file. */
        @Volatile
        var current: RinganConfig = DEFAULT
            private set

        /**
         * File kecil, aman dibaca di thread utama. Kolom yang tidak ada memakai nilai [DEFAULT];
         * jam tayang yang diubah di TV mengalahkan config.json.
         */
        fun load(context: Context): RinganConfig =
            read(context).let { it.copy(parental = ParentSettings.apply(context, it.parental)) }.also { current = it }

        private fun read(context: Context): RinganConfig {
            val dir = context.getExternalFilesDir(null) ?: return DEFAULT
            val json = try {
                JSONObject(File(dir, "config.json").readText())
            } catch (e: IOException) {
                return DEFAULT
            } catch (e: JSONException) {
                return DEFAULT
            }

            val flights = json.optJSONObject("flights")
            val flightAware = json.optJSONObject("flightaware")
            val prayer = json.optJSONObject("prayer")
            return RinganConfig(
                city = json.optString("city", DEFAULT.city),
                latitude = json.optDouble("latitude", DEFAULT.latitude),
                longitude = json.optDouble("longitude", DEFAULT.longitude),
                karaoke = karaoke(json.opt("karaoke")),
                announcements = json.optBoolean("announcements", DEFAULT.announcements),
                prayerEnabled = prayer?.optBoolean("enabled", DEFAULT.prayerEnabled) ?: DEFAULT.prayerEnabled,
                prayerIhtiyat = prayer?.optInt("ihtiyatMinutes", DEFAULT.prayerIhtiyat)?.coerceIn(0, 10)
                    ?: DEFAULT.prayerIhtiyat,
                prayerReminder = prayer?.optBoolean("reminder", DEFAULT.prayerReminder) ?: DEFAULT.prayerReminder,
                azan = prayer?.optBoolean("azan", DEFAULT.azan) ?: DEFAULT.azan,
                parental = parental(json.optJSONObject("parental")),
                airport = flights?.optString("airport")?.trim()?.uppercase()?.takeIf { it.length == 3 } ?: DEFAULT.airport,
                flightsApiKey = flights?.optString("apiKey")?.trim()?.takeIf { it.isNotEmpty() },
                flightsProvider = flights?.optString("provider", DEFAULT.flightsProvider)?.trim()?.lowercase()
                    ?: DEFAULT.flightsProvider,
                flightsRefreshMinutes = flights?.optInt("refreshMinutes", DEFAULT.flightsRefreshMinutes)
                    ?.coerceAtLeast(MIN_REFRESH_MINUTES) ?: DEFAULT.flightsRefreshMinutes,
                // Placeholder dari config.contoh.json dianggap belum diisi.
                flightAwareKey = flightAware?.optString("apiKey")?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("TEMPEL_") },
                flightAwareAirport = flightAware?.optString("airport")?.trim()?.uppercase()
                    ?.takeIf { it.length == 4 } ?: DEFAULT.flightAwareAirport,
                flightAwareRefreshMinutes = flightAware?.optInt("refreshMinutes", DEFAULT.flightAwareRefreshMinutes)
                    ?.coerceAtLeast(MIN_LIVE_REFRESH_MINUTES) ?: DEFAULT.flightAwareRefreshMinutes,
                flightAwareBudgetUsd = flightAware?.optDouble("monthlyBudgetUsd", DEFAULT.flightAwareBudgetUsd)
                    ?.coerceAtLeast(0.0) ?: DEFAULT.flightAwareBudgetUsd,
            )
        }

        /**
         * Daftar {"name", "query"}, atau satu teks (format lama) yang hanya mengganti kata kunci
         * baris karaoke pertama.
         */
        private fun karaoke(value: Any?): List<KaraokeChannel> = when (value) {
            is JSONArray -> (0 until value.length())
                .mapNotNull { value.optJSONObject(it) }
                .mapNotNull { item ->
                    val query = item.optString("query").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    KaraokeChannel(item.optString("name").trim().ifEmpty { "Karaoke" }, query)
                }
                .ifEmpty { DEFAULT.karaoke }
            is String -> listOf(DEFAULT.karaoke[0].copy(query = value)) + DEFAULT.karaoke.drop(1)
            else -> DEFAULT.karaoke
        }

        private fun parental(json: JSONObject?): ParentalRules? {
            json ?: return null
            // Tanpa PIN yang valid, gerbang tidak bisa dibuka; lebih aman fitur ini dimatikan.
            val pin = json.optString("pin").trim().takeIf { PIN.matches(it) } ?: return null
            val default = ParentalRules.DEFAULT
            return ParentalRules(
                pin = pin,
                sessionMinutes = json.optInt("sessionMinutes", default.sessionMinutes).coerceAtLeast(0),
                breakMinutes = json.optInt("breakMinutes", default.breakMinutes).coerceAtLeast(1),
                dailyMinutes = json.optInt("dailyMinutes", default.dailyMinutes).coerceAtLeast(0),
                bedtimeMinute = if (json.has("bedtime")) clock(json.optString("bedtime")) else default.bedtimeMinute,
                wakeupMinute = clock(json.optString("wakeup")) ?: default.wakeupMinute,
                unlockMinutes = json.optInt("unlockMinutes", default.unlockMinutes).coerceAtLeast(1),
            )
        }

        /** "20:30" -> 1230; teks kosong atau salah -> null. */
        private fun clock(text: String): Int? {
            val match = CLOCK.matchEntire(text.trim()) ?: return null
            val (hour, minute) = match.destructured
            return hour.toInt() * 60 + minute.toInt()
        }

        private val PIN = Regex("\\d{4,6}")
        private val CLOCK = Regex("([01]?\\d|2[0-3])[:.]([0-5]\\d)")
        private const val MIN_REFRESH_MINUTES = 15
        private const val MIN_LIVE_REFRESH_MINUTES = 5
    }
}
