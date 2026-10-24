package id.ringan.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import java.util.Locale

/**
 * Jam tayang yang diubah langsung di TV, tanpa laptop. Disimpan di TV dan mengalahkan bagian
 * "parental" di config.json, supaya config.json yang dikirim ulang (misalnya untuk API key) tidak
 * menghapusnya. "Pakai lagi nilai dari config.json" menghapus semuanya.
 */
object ParentSettings {

    fun apply(context: Context, base: ParentalRules?): ParentalRules? {
        val prefs = prefs(context)
        val pin = prefs.getString(KEY_PIN, null) ?: base?.pin ?: return null
        val from = base ?: ParentalRules.DEFAULT
        return from.copy(
            pin = pin,
            sessionMinutes = prefs.getInt(KEY_SESSION, from.sessionMinutes),
            breakMinutes = prefs.getInt(KEY_BREAK, from.breakMinutes),
            dailyMinutes = prefs.getInt(KEY_DAILY, from.dailyMinutes),
            bedtimeMinute = if (prefs.contains(KEY_BEDTIME)) {
                prefs.getInt(KEY_BEDTIME, NO_BEDTIME).takeIf { it != NO_BEDTIME }
            } else {
                from.bedtimeMinute
            },
            wakeupMinute = prefs.getInt(KEY_WAKEUP, from.wakeupMinute),
            unlockMinutes = prefs.getInt(KEY_UNLOCK, from.unlockMinutes),
        )
    }

    fun hasOverrides(context: Context) = prefs(context).all.isNotEmpty()

    fun reset(context: Context) = prefs(context).edit().clear().apply()

    private fun save(context: Context, key: String, value: Int) = prefs(context).edit().putInt(key, value).apply()

    private fun savePin(context: Context, pin: String) = prefs(context).edit().putString(KEY_PIN, pin).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("parent_settings", Context.MODE_PRIVATE)

    /**
     * Menu pengaturan dari papan. Kalau jam tayang belum aktif, orang tua membuat PIN dulu; kalau
     * sudah, PIN lama diminta. [onChanged] dipanggil setiap ada yang tersimpan.
     */
    fun open(activity: Activity, onChanged: () -> Unit) {
        val rules = RinganConfig.load(activity).parental
        if (rules == null) {
            activity.askNewPin(onCancel = null) {
                savePin(activity, it)
                onChanged()
                showMenu(activity, onChanged)
            }
        } else {
            activity.askParentPin(rules) { showMenu(activity, onChanged) }
        }
    }

    private fun showMenu(activity: Activity, onChanged: () -> Unit) {
        val rules = RinganConfig.load(activity).parental ?: return
        val back = { showMenu(activity, onChanged) }
        val reopen = {
            onChanged()
            back()
        }
        val items = mutableListOf<Pair<String, () -> Unit>>(
            activity.getString(R.string.settings_session, activity.minutesText(rules.sessionMinutes)) to {
                activity.askMinutes(R.string.settings_session_title, SESSION_CHOICES, rules.sessionMinutes, back) {
                    save(activity, KEY_SESSION, it)
                    reopen()
                }
            },
            activity.getString(R.string.settings_break, activity.minutesText(rules.breakMinutes)) to {
                activity.askMinutes(R.string.settings_break_title, BREAK_CHOICES, rules.breakMinutes, back) {
                    save(activity, KEY_BREAK, it)
                    reopen()
                }
            },
            activity.getString(R.string.settings_daily, activity.minutesText(rules.dailyMinutes)) to {
                activity.askMinutes(R.string.settings_daily_title, DAILY_CHOICES, rules.dailyMinutes, back) {
                    save(activity, KEY_DAILY, it)
                    reopen()
                }
            },
            activity.getString(R.string.settings_bedtime, rules.bedtimeMinute?.let(::clockOfDay) ?: activity.getString(R.string.value_none)) to {
                activity.askClock(R.string.settings_bedtime_title, rules.bedtimeMinute, allowNone = true, back) {
                    save(activity, KEY_BEDTIME, it ?: NO_BEDTIME)
                    reopen()
                }
            },
            activity.getString(R.string.settings_wakeup, clockOfDay(rules.wakeupMinute)) to {
                activity.askClock(R.string.settings_wakeup_title, rules.wakeupMinute, allowNone = false, back) {
                    if (it != null) save(activity, KEY_WAKEUP, it)
                    reopen()
                }
            },
            activity.getString(R.string.settings_unlock, activity.minutesText(rules.unlockMinutes)) to {
                activity.askMinutes(R.string.settings_unlock_title, UNLOCK_CHOICES, rules.unlockMinutes, back) {
                    save(activity, KEY_UNLOCK, it)
                    reopen()
                }
            },
            activity.getString(R.string.settings_pin) to {
                activity.askNewPin(back) {
                    savePin(activity, it)
                    activity.toast(R.string.pin_changed)
                    reopen()
                }
            },
            activity.getString(R.string.settings_dns, activity.dnsText()) to { activity.chooseDns { showMenu(activity, onChanged) } },
        )
        if (hasOverrides(activity)) {
            items += activity.getString(R.string.settings_reset) to {
                reset(activity)
                activity.toast(R.string.settings_reset_done)
                onChanged()
                // Tanpa PIN di config.json, jam tayang ikut mati dan menunya tidak perlu dibuka lagi.
                if (RinganConfig.load(activity).parental != null) showMenu(activity, onChanged)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(R.string.settings_close, null)
            .show()
    }

    private fun Activity.minutesText(minutes: Int): String = when {
        minutes == 0 -> getString(R.string.value_unlimited)
        minutes % 60 == 0 -> getString(R.string.unlock_hours, minutes / 60)
        else -> getString(R.string.unlock_minutes, minutes)
    }

    /** Satu roda menit; nilai sekarang ikut dimasukkan kalau tidak ada di daftar pilihan. */
    private fun Activity.askMinutes(title: Int, choices: List<Int>, current: Int, onCancel: () -> Unit, onPick: (Int) -> Unit) {
        val values = (choices + current).distinct().sorted()
        askWheels(
            getString(title),
            getString(R.string.wheel_hint),
            listOf(Wheel(values.map { minutesText(it) }, values.indexOf(current))),
            R.string.settings_save,
            onCancel = onCancel,
        ) { onPick(values[it[0]]) }
    }

    /** Roda jam dan menit (kelipatan 5); dengan [allowNone], jam "--" berarti tidak dipakai. */
    private fun Activity.askClock(title: Int, current: Int?, allowNone: Boolean, onCancel: () -> Unit, onPick: (Int?) -> Unit) {
        val hours = (if (allowNone) listOf(-1) else emptyList()) + (0..23).toList()
        val minutes = ((0..55 step 5).toList() + (current?.rem(60) ?: 0)).distinct().sorted()
        askWheels(
            getString(title),
            getString(if (allowNone) R.string.wheel_hint_clock_none else R.string.wheel_hint),
            listOf(
                Wheel(hours.map { if (it < 0) "--" else String.format(Locale.US, "%02d", it) }, hours.indexOf(current?.div(60) ?: -1).coerceAtLeast(0)),
                Wheel(minutes.map { String.format(Locale.US, "%02d", it) }, minutes.indexOf(current?.rem(60) ?: 0)),
            ),
            R.string.settings_save,
            separator = ":",
            onCancel = onCancel,
        ) { (hour, minute) -> onPick(hours[hour].takeIf { it >= 0 }?.let { it * 60 + minutes[minute] }) }
    }

    /** PIN baru 4 angka, dimasukkan dua kali supaya tidak salah ketik. */
    private fun Activity.askNewPin(onCancel: (() -> Unit)?, onPin: (String) -> Unit) {
        val wheels = List(PIN_LENGTH) { digitWheel() }
        askWheels(getString(R.string.pin_new_title), getString(R.string.pin_hint), wheels, R.string.settings_next, onCancel = onCancel) { first ->
            askWheels(getString(R.string.pin_confirm_title), getString(R.string.pin_hint), wheels, R.string.settings_save, onCancel = onCancel) { second ->
                if (first == second) {
                    onPin(first.joinToString(""))
                } else {
                    toast(R.string.pin_mismatch)
                    onCancel?.invoke()
                }
            }
        }
    }

    private fun Activity.dnsText(): String {
        if (!PrivateDns.supported()) return getString(R.string.dns_unsupported_short)
        val host = PrivateDns.currentHost(this) ?: return getString(R.string.dns_off_short)
        return PrivateDns.PROVIDERS.firstOrNull { it.host == host }?.let { getString(it.shortLabel) } ?: host
    }

    private fun Activity.chooseDns(onDone: () -> Unit) {
        if (!PrivateDns.canChange(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dns_title)
                .setMessage(if (PrivateDns.supported()) R.string.dns_need_permission else R.string.dns_unsupported)
                .setPositiveButton(android.R.string.ok) { _, _ -> onDone() }
                .setOnCancelListener { onDone() }
                .show()
            return
        }
        val current = PrivateDns.currentHost(this)
        val providers = PrivateDns.PROVIDERS
        AlertDialog.Builder(this)
            .setTitle(R.string.dns_title)
            .setSingleChoiceItems(
                providers.map { getString(it.label) }.toTypedArray(),
                providers.indexOfFirst { it.host == current },
            ) { dialog, which ->
                dialog.dismiss()
                val provider = providers[which]
                if (provider.host == current) {
                    onDone()
                    return@setSingleChoiceItems
                }
                val name = getString(provider.shortLabel)
                if (provider.host != null) toast(getString(R.string.dns_checking, name))
                PrivateDns.change(this, provider.host) { ok ->
                    toast(
                        when {
                            !ok -> getString(R.string.dns_failed, name)
                            provider.host == null -> getString(R.string.dns_off_done)
                            else -> getString(R.string.dns_on, name)
                        }
                    )
                    if (!isFinishing) onDone()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }

    private fun clockOfDay(minute: Int) = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

    private const val KEY_PIN = "pin"
    private const val KEY_SESSION = "session_minutes"
    private const val KEY_BREAK = "break_minutes"
    private const val KEY_DAILY = "daily_minutes"
    private const val KEY_BEDTIME = "bedtime_minute"
    private const val KEY_WAKEUP = "wakeup_minute"
    private const val KEY_UNLOCK = "unlock_minutes"
    private const val NO_BEDTIME = -1
    private const val PIN_LENGTH = 4

    private val SESSION_CHOICES = listOf(0, 15, 20, 30, 45, 60, 75, 90, 120, 150, 180, 240)
    private val BREAK_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)
    private val DAILY_CHOICES = listOf(0, 30, 60, 90, 120, 150, 180, 240, 300, 360)
    private val UNLOCK_CHOICES = listOf(15, 30, 45, 60, 90, 120)
}
