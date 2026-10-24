package id.ringan.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.DialogInterface
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val YOUTUBE_TV = "com.google.android.youtube.tv"

fun Context.colorOf(id: Int): Int =
    if (Build.VERSION.SDK_INT >= 23) getColor(id) else @Suppress("DEPRECATION") resources.getColor(id)

fun Context.toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

fun Activity.startSafely(intent: Intent): Boolean =
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        toast(R.string.cannot_open)
        false
    } catch (e: SecurityException) {
        toast(R.string.cannot_open)
        false
    }

fun Activity.launchApp(app: AppEntry) {
    if (app.packageName in SettingsGuard.PACKAGES) withSettingsPin { startApp(app) } else startApp(app)
}

private fun Activity.startApp(app: AppEntry) {
    val category = if (app.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
    val intent = Intent(Intent.ACTION_MAIN)
        .addCategory(category)
        .setComponent(app.component)
        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    if (startSafely(intent)) LauncherPrefs(this).markOpened(app.packageName)
}

/** Membuka hasil pencarian YouTube; aplikasi YouTube TV didahulukan. [key] = baris papan yang dicatat. */
fun Activity.openKaraoke(query: String, key: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(Intent(intent).setPackage(YOUTUBE_TV))
    } catch (e: ActivityNotFoundException) {
        if (!startSafely(intent)) return
    }
    LauncherPrefs(this).markOpened(key)
}

/** Jalankan [action] kalau gerbang jam tayang terbuka; kalau tertutup, minta PIN orang tua dulu. */
fun Activity.withParentalUnlock(action: () -> Unit) {
    val rules = RinganConfig.current.parental
    if (rules == null || Parental.lockReason(this, rules) == null) {
        action()
        return
    }
    unlockWithPin(rules, action)
}

/** PIN orang tua, lalu pilih berapa lama gerbang dibuka (termasuk sampai jam bangun untuk tamu). */
fun Activity.unlockWithPin(rules: ParentalRules, onUnlocked: () -> Unit) {
    askParentPin(rules) {
        val now = System.currentTimeMillis()
        val wakeup = Parental.nextWakeup(rules)
        val minutes = (listOf(rules.unlockMinutes) + UNLOCK_CHOICES).distinct().sorted().filter { now + it * 60_000L < wakeup }
        val labels = minutes.map {
            if (it % 60 == 0) getString(R.string.unlock_hours, it / 60) else getString(R.string.unlock_minutes, it)
        } + getString(R.string.unlock_until_wakeup, clockText(wakeup))
        AlertDialog.Builder(this)
            .setTitle(R.string.unlock_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val until = minutes.getOrNull(which)?.let { now + it * 60_000L } ?: wakeup
                Parental.unlock(this, until)
                Toast.makeText(this, getString(R.string.pin_unlocked, clockText(until)), Toast.LENGTH_LONG).show()
                onUnlocked()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/** Pilihan lama buka gerbang selain unlockMinutes dari config, dalam menit. */
private val UNLOCK_CHOICES = listOf(60, 120, 240)

fun clockText(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

/** PIN orang tua dengan roda angka; PIN salah hanya memunculkan pesan. */
fun Activity.askParentPin(rules: ParentalRules, onSuccess: () -> Unit) {
    askWheels(getString(R.string.pin_title), getString(R.string.pin_hint), List(rules.pin.length) { digitWheel() }, R.string.pin_open) {
        if (it.joinToString("") == rules.pin) onSuccess() else toast(R.string.pin_wrong)
    }
}

/** Satu kolom roda angka: teks tiap pilihan dan pilihan awal. */
class Wheel(val labels: List<String>, val selected: Int = 0)

fun digitWheel() = Wheel((0..9).map { it.toString() })

/**
 * Roda angka seperti gembok koper: remote TV biasanya tidak punya tombol angka, jadi tiap kolom
 * diputar dengan atas/bawah, dipindah dengan kiri/kanan, dan OK langsung menyimpan. [onDone]
 * menerima indeks pilihan tiap kolom. [separator] ditaruh di antara kolom, misalnya ":" untuk jam.
 * [onCancel] dipanggil saat Batal atau Kembali ditekan.
 */
fun Activity.askWheels(
    title: String,
    message: String?,
    columns: List<Wheel>,
    positive: Int,
    separator: String? = null,
    onCancel: (() -> Unit)? = null,
    onDone: (List<Int>) -> Unit,
) {
    val wheels = columns.map { column ->
        NumberPicker(this).apply {
            minValue = 0
            maxValue = column.labels.lastIndex
            displayedValues = column.labels.toTypedArray()
            value = column.selected.coerceIn(0, column.labels.lastIndex)
            wrapSelectorWheel = column.labels.size > 2
            // Tanpa ini NumberPicker memunculkan keyboard di layar saat difokus.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Bingkai amber menandai digit yang sedang diputar.
            setBackgroundResource(R.drawable.board_row)
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
    }
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        val margin = (12 * resources.displayMetrics.density).toInt()
        wheels.forEachIndexed { index, wheel ->
            if (index > 0 && separator != null) {
                addView(TextView(this@askWheels).apply {
                    text = separator
                    textSize = 28f
                })
            }
            addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(margin, margin, margin, margin) })
        }
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setView(row)
        .setPositiveButton(positive) { _, _ -> onDone(wheels.map { it.value }) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
        .setOnCancelListener { onCancel?.invoke() }
        .show()
    wheels.forEach { wheel ->
        wheel.setOnKeyListener { _, keyCode, event ->
            val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            if (ok && event.action == KeyEvent.ACTION_UP) dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            ok
        }
    }
    wheels.first().requestFocus()
}

/** Jalankan [action] yang membuka Setelan Android; kalau jam tayang aktif, minta PIN dulu (lihat [SettingsGuard]). */
fun Activity.withSettingsPin(action: () -> Unit) {
    val rules = RinganConfig.current.parental
    if (rules == null || !SettingsGuard.needsPin()) {
        action()
        return
    }
    askParentPin(rules) {
        SettingsGuard.grant()
        action()
    }
}

fun Activity.openAndroidSettings() = withSettingsPin { startSafely(Intent(Settings.ACTION_SETTINGS)) }

fun Activity.openAppInfo(packageName: String) = withSettingsPin {
    startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

fun Activity.uninstall(packageName: String) {
    startSafely(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)))
}
