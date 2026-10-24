package id.ringan.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * DNS pribadi (DNS-over-TLS) bawaan Android 9+, untuk menyaring domain iklan dan pelacak di semua
 * aplikasi TV. Mengubahnya butuh izin WRITE_SECURE_SETTINGS, yang hanya bisa diberikan sekali lewat
 * adb (lihat README). Iklan YouTube tidak ikut tersaring karena datang dari server yang sama dengan videonya.
 */
object PrivateDns {

    class Provider(val label: Int, val shortLabel: Int, val host: String?)

    val PROVIDERS = listOf(
        Provider(R.string.dns_off, R.string.dns_off_short, null),
        Provider(R.string.dns_adguard, R.string.dns_adguard_short, "dns.adguard-dns.com"),
        Provider(R.string.dns_adguard_family, R.string.dns_adguard_family_short, "family.adguard-dns.com"),
        Provider(R.string.dns_mullvad_family, R.string.dns_mullvad_family_short, "family.dns.mullvad.net"),
    )

    private val handler = Handler(Looper.getMainLooper())

    fun supported() = Build.VERSION.SDK_INT >= 28

    fun canChange(context: Context) = supported() &&
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Host DNS pribadi yang sedang dipaksa; null kalau memakai DNS bawaan. */
    fun currentHost(context: Context): String? {
        val resolver = context.contentResolver
        return if (Settings.Global.getString(resolver, KEY_MODE) == MODE_HOSTNAME) {
            Settings.Global.getString(resolver, KEY_HOST)
        } else {
            null
        }
    }

    /**
     * Pasang [host] (null = DNS bawaan), lalu setelah [CHECK_DELAY_MS] pastikan internet masih jalan.
     * Kalau server DNS tidak bisa dihubungi, pengaturan sebelumnya dipulihkan supaya TV tidak kehilangan
     * internet. [onResult] dipanggil di thread utama: true kalau berhasil.
     */
    fun change(context: Context, host: String?, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val previousMode = Settings.Global.getString(resolver, KEY_MODE)
        val previousHost = Settings.Global.getString(resolver, KEY_HOST)
        write(app, host)
        if (host == null) {
            onResult(true)
            return
        }
        handler.postDelayed({
            if (validated(app)) {
                onResult(true)
            } else {
                Settings.Global.putString(resolver, KEY_HOST, previousHost)
                Settings.Global.putString(resolver, KEY_MODE, previousMode ?: MODE_AUTOMATIC)
                onResult(false)
            }
        }, CHECK_DELAY_MS)
    }

    private fun write(context: Context, host: String?) {
        val resolver = context.contentResolver
        if (host == null) {
            Settings.Global.putString(resolver, KEY_MODE, MODE_AUTOMATIC)
        } else {
            Settings.Global.putString(resolver, KEY_HOST, host)
            Settings.Global.putString(resolver, KEY_MODE, MODE_HOSTNAME)
        }
    }

    /** Android memeriksa ulang jaringan setelah DNS pribadi diganti; gagal kalau server DNS tidak terjangkau. */
    private fun validated(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // Nama kolom Settings.Global yang dipakai halaman "DNS Pribadi" di Android ponsel.
    private const val KEY_MODE = "private_dns_mode"
    private const val KEY_HOST = "private_dns_specifier"
    private const val MODE_HOSTNAME = "hostname"
    private const val MODE_AUTOMATIC = "opportunistic"
    private const val CHECK_DELAY_MS = 8_000L
}
