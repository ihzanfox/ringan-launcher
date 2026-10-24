package id.ringan.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/**
 * Logo maskapai dari CDN Aviasales (pics.avs.io): gratis, tanpa API key. Tiap logo diunduh
 * sekali lalu disimpan di folder aplikasi. Logo yang tidak tersedia dicoba lagi seminggu kemudian.
 * Hanya dipakai dari satu thread latar, jadi cache memori tidak perlu dikunci.
 */
class AirlineLogos(context: Context) {

    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logos")
    private val loaded = HashMap<String, Bitmap>()
    private val failedAt = HashMap<String, Long>()

    fun load(codes: Collection<String>): Map<String, Bitmap> {
        val result = HashMap<String, Bitmap>()
        for (code in codes) {
            if (!CODE.matches(code)) continue
            val bitmap = loaded[code] ?: loadOne(code)?.also { loaded[code] = it } ?: continue
            result[code] = bitmap
        }
        return result
    }

    private fun loadOne(code: String): Bitmap? {
        val now = System.currentTimeMillis()
        val file = File(dir, "$code.png")
        val missing = File(dir, "$code.none")
        if (!file.exists()) {
            if (missing.exists() && now - missing.lastModified() < RETRY_MISSING_MS) return null
            if (now - (failedAt[code] ?: 0L) < RETRY_FAILED_MS) return null
            try {
                val bytes = httpGetBytes("https://pics.avs.io/$WIDTH_PX/$HEIGHT_PX/$code.png")
                dir.mkdirs()
                file.writeBytes(bytes)
                missing.delete()
            } catch (e: HttpStatusException) {
                if (e.code == 404) {
                    dir.mkdirs()
                    missing.writeText(code)
                } else {
                    failedAt[code] = now
                }
                return null
            } catch (e: IOException) {
                failedAt[code] = now
                return null
            }
        }
        return BitmapFactory.decodeFile(file.path) ?: run {
            // File rusak: hapus supaya diunduh ulang.
            file.delete()
            null
        }
    }

    private companion object {
        val CODE = Regex("[A-Z0-9]{2}")

        // Rasio 2:1, cukup tajam untuk kotak logo 48x24dp.
        const val WIDTH_PX = 96
        const val HEIGHT_PX = 48
        const val RETRY_MISSING_MS = 7 * 24 * 60 * 60_000L
        const val RETRY_FAILED_MS = 5 * 60_000L
    }
}
