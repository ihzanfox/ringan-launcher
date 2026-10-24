package id.ringan.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Layar Home: papan keberangkatan berisi aplikasi pilihan, jam, cuaca, jadwal sholat, dan jadwal
 * penerbangan bandara. Juga menampilkan layar "mendarat" saat gerbang jam tayang anak ditutup.
 */
class MainActivity : Activity() {

    private sealed class BoardItem(val key: String) {
        class App(val app: AppEntry) : BoardItem(app.packageName)
        class Karaoke(val index: Int) : BoardItem(LauncherPrefs.karaokeKey(index))
        object AllApps : BoardItem(ALL_APPS_KEY)
    }

    private lateinit var prefs: LauncherPrefs
    private lateinit var scroll: ScrollView
    private lateinit var rows: LinearLayout
    private lateinit var clock: FlapView
    private lateinit var date: FlapView
    private lateinit var weatherIcon: WeatherView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherPlace: TextView
    private lateinit var flightsIcon: ImageView
    private lateinit var flightsTitle: TextView
    private lateinit var flightsSource: TextView
    private lateinit var flightsHint: TextView
    private lateinit var flightsRows: LinearLayout
    private lateinit var boardContent: ViewGroup
    private lateinit var prayerView: TextView
    private lateinit var flightLeft: TextView
    private lateinit var weatherHourly: LinearLayout
    private lateinit var landing: View
    private lateinit var landingTitle: FlapView
    private lateinit var landingMessage: TextView
    private lateinit var landingUnlock: View

    private val appLoader = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadToken = 0
    private var apps: List<AppEntry>? = null
    private var renderedBoard: List<String>? = null

    private var config = RinganConfig.DEFAULT
    private var weather: WeatherNow? = null
    private var weatherFetchedAt = 0L
    private var refreshing = false
    private var flights: FlightBoards? = null
    /**
     * True kalau key FlightAware diisi. Lisensi AeroAPI Personal melarang datanya dicampur dengan
     * penyedia data penerbangan lain, jadi dalam mode ini AeroDataBox tidak dipakai sama sekali.
     */
    private var flightsLive = false
    private var airlineLogos: Map<String, Bitmap> = emptyMap()
    /** Hanya dipakai dari thread [network]. */
    private val logoLoader by lazy { AirlineLogos(applicationContext) }
    private var showingArrivals = false

    private var announcer: Announcer? = null
    /** Selama pengumuman berbunyi, tekanan OK berikutnya diabaikan. */
    private var announcing = false
    private var prayerDay: String? = null
    private var prayerToday: List<PrayerTimes.Time> = emptyList()

    private val clockFormat by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dateFormat by lazy { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    private val dayFormat by lazy { SimpleDateFormat("EEEE", Locale.getDefault()) }
    private val shortDateFormat by lazy { SimpleDateFormat("d MMM", Locale.getDefault()) }
    private val dayKeyFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    private val billingMonthFormat by lazy {
        SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    /** Selama layar terlihat: papan penerbangan disaring ulang tiap 30 detik, cuaca tiap 30 menit. */
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshOnlineInfo()
            mainHandler.postDelayed(this, BOARD_REFRESH_MS)
        }
    }

    /** Panel penerbangan bergantian antara keberangkatan dan kedatangan, seperti papan bandara. */
    private val rotateFlightsTask = object : Runnable {
        override fun run() {
            if (flights != null) {
                showingArrivals = !showingArrivals
                showFlights()
            }
            mainHandler.postDelayed(this, FLIGHTS_ROTATE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_board)
        prefs = LauncherPrefs(this)
        config = RinganConfig.load(this)
        prefs.addKaraokeRowsOnce(config.karaoke.size)
        // Disiapkan sejak awal supaya pengumuman pertama tidak kehilangan suara.
        if (config.announcements || config.parental != null) announcer = Announcer(this)
        scroll = findViewById(R.id.scroll)
        rows = findViewById(R.id.rows)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        weatherIcon = findViewById(R.id.weather_icon)
        weatherTemp = findViewById(R.id.weather_temp)
        weatherDesc = findViewById(R.id.weather_desc)
        weatherPlace = findViewById(R.id.weather_place)
        flightsIcon = findViewById(R.id.flights_icon)
        flightsTitle = findViewById(R.id.flights_title)
        flightsSource = findViewById(R.id.flights_source)
        flightsHint = findViewById(R.id.flights_hint)
        flightsRows = findViewById(R.id.flights_rows)
        boardContent = findViewById(R.id.board_content)
        prayerView = findViewById(R.id.prayer_next)
        flightLeft = findViewById(R.id.flight_left)
        weatherHourly = findViewById(R.id.weather_hourly)
        landing = findViewById(R.id.landing)
        landingTitle = findViewById(R.id.landing_title)
        landingMessage = findViewById(R.id.landing_message)
        landingUnlock = findViewById(R.id.landing_unlock)
        landingUnlock.setOnClickListener {
            config.parental?.let { rules -> unlockWithPin(rules) { updateParental() } }
        }
        weatherPlace.text = config.city
        flightsTitle.text = getString(R.string.flights_title_departures, config.airport)

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })

        // Launcher tidak boleh tertutup oleh tombol Back; Back cukup kembali ke baris pertama.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { goToTop() },
            )
        }

        loadApps()
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) openAndroidSettings()
    }

    override fun onResume() {
        super.onResume()
        Parental.boardVisible = true
        reloadConfig()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
        if (apps != null) {
            if (prefs.board != renderedBoard) render() else refreshStatuses()
        }
        mainHandler.post(refreshTask)
        mainHandler.postDelayed(rotateFlightsTask, FLIGHTS_ROTATE_MS)
        updateLanding()
    }

    override fun onPause() {
        Parental.boardVisible = false
        unregisterReceiver(timeReceiver)
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.removeCallbacks(rotateFlightsTask)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        appLoader.shutdownNow()
        network.shutdownNow()
        announcer?.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            // Dibawa ke depan oleh RemoteKeyService: Setelan Android dibuka tanpa PIN.
            openAndroidSettings()
            return
        }
        val landed = intent.getStringExtra(EXTRA_LANDED)?.let { name -> LockReason.values().firstOrNull { it.name == name } }
        if (landed != null) {
            // Dibawa ke depan oleh RemoteKeyService karena gerbang jam tayang ditutup.
            updateLanding()
            if (config.announcements) announce(getString(landingAnnouncement(landed)), landingClip(landed))
        } else {
            // Tombol Home ditekan saat sudah di launcher.
            goToTop()
        }
    }

    // Hanya terpanggil di bawah Android 13; versi baru memakai OnBackInvokedCallback di onCreate.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Hanya dipakai di bawah Android 13")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goToTop()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val item = currentFocus?.tag as? BoardItem
            if (item != null) showRowMenu(item) else showLauncherMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateClock() {
        val now = Date()
        clock.setText(clockFormat.format(now))
        date.setText(dateFormat.format(now))
        updatePrayer()
        updateFlightLeft()
        updateLanding()
    }

    /** Setelah gerbang dibuka, dikunci, atau aturannya diubah: header dan layar mendarat langsung menyesuaikan. */
    private fun updateParental() {
        updateFlightLeft()
        updateLanding()
    }

    /** Sisa waktu terbang anak di bawah jadwal sholat, atau sampai kapan gerbang dibuka orang tua. */
    private fun updateFlightLeft() {
        val rules = config.parental
        val until = rules?.let { Parental.unlockedUntil(this) } ?: 0L
        val left = rules?.let { Parental.minutesUntilLanding(this, it) }
        val text = when {
            rules == null -> null
            until > 0 -> getString(R.string.flight_unlocked, clockText(until))
            left == null -> null
            left < 60 -> getString(R.string.flight_left_minutes, left)
            else -> getString(R.string.flight_left_hours, left / 60, left % 60)
        }
        flightLeft.text = text
        flightLeft.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Waktu sholat berikutnya di header papan. Pengumuman dan azannya diurus [PrayerCall]. */
    private fun updatePrayer() {
        if (!config.prayerEnabled) {
            prayerView.visibility = View.GONE
            return
        }
        prayerView.visibility = View.VISIBLE
        val now = Calendar.getInstance()
        val today = dayKeyFormat.format(now.time)
        if (prayerDay != today) {
            prayerToday = PrayerTimes.forDay(now, config.latitude, config.longitude, config.prayerIhtiyat)
            prayerDay = today
        }
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val prayers = prayerToday.filter { it.prayer != PrayerTimes.Prayer.SUNRISE }

        // Setelah Isya, yang berikutnya Subuh besok (selisih harian beberapa detik saja).
        val next = prayers.firstOrNull { it.minutes > minute } ?: prayers.first()
        val wait = (next.minutes - minute).let { if (it <= 0) it + 24 * 60 else it }
        val head = getString(R.string.prayer_next, getString(next.prayer.label), clockText(next.minutes))
        val tail = if (wait < 60) getString(R.string.prayer_in_minutes, wait)
        else getString(R.string.prayer_in_hours, wait / 60, wait % 60)
        prayerView.text = SpannableStringBuilder(head).apply {
            val start = length
            append(tail)
            setSpan(ForegroundColorSpan(colorOf(R.color.dim)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun clockText(minutes: Int) = String.format(Locale.US, "%02d:%02d", minutes / 60 % 24, minutes % 60)

    private fun showPrayerSchedule() {
        val now = Calendar.getInstance()
        val times = PrayerTimes.forDay(now, config.latitude, config.longitude, config.prayerIhtiyat)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.prayer_schedule_title, config.city))
            .setItems(times.map { "${getString(it.prayer.label)}   ${clockText(it.minutes)}" }.toTypedArray(), null)
            .show()
    }

    /** Layar "mendarat" menutupi papan selama gerbang jam tayang tertutup. */
    private fun updateLanding() {
        val rules = config.parental
        val reason = rules?.let { Parental.lockReason(this, it) }
        if (rules == null || reason == null) {
            if (landing.visibility == View.VISIBLE) {
                landing.visibility = View.GONE
                boardContent.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                goToTop()
            }
            return
        }
        val reopens = clockFormat.format(Date(Parental.reopensAt(this, rules, reason)))
        landingTitle.setText(getString(landingTitle(reason)))
        landingMessage.text = getString(landingMessage(reason), reopens)
        if (landing.visibility != View.VISIBLE) {
            landing.visibility = View.VISIBLE
            // Baris papan di belakang tidak boleh terjangkau remote.
            boardContent.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            landingUnlock.requestFocus()
        }
    }

    private fun landingTitle(reason: LockReason) = when (reason) {
        LockReason.BREAK -> R.string.landing_break_title
        LockReason.DAILY -> R.string.landing_daily_title
        LockReason.BEDTIME -> R.string.landing_bedtime_title
    }

    private fun landingMessage(reason: LockReason) = when (reason) {
        LockReason.BREAK -> R.string.landing_break_message
        LockReason.DAILY -> R.string.landing_daily_message
        LockReason.BEDTIME -> R.string.landing_bedtime_message
    }

    private fun landingAnnouncement(reason: LockReason) = when (reason) {
        LockReason.BREAK -> R.string.announce_break
        LockReason.DAILY -> R.string.announce_daily
        LockReason.BEDTIME -> R.string.announce_bedtime
    }

    /** Nama rekaman di suara/NASKAH-REKAMAN.md. */
    private fun landingClip(reason: LockReason) = when (reason) {
        LockReason.BREAK -> "mendarat"
        LockReason.DAILY -> "jatah_habis"
        LockReason.BEDTIME -> "waktu_tidur"
    }

    /**
     * config.json dibaca ulang tiap papan dibuka, karena kirim-config.sh sengaja tidak
     * menutup paksa Ringan (itu ikut mematikan layanan aksesibilitasnya).
     */
    private fun reloadConfig() = applyConfig(RinganConfig.load(this))

    private fun applyConfig(loaded: RinganConfig) {
        val karaokeChanged = loaded.karaoke != config.karaoke
        val prayerChanged = loaded.latitude != config.latitude || loaded.longitude != config.longitude ||
            loaded.prayerIhtiyat != config.prayerIhtiyat
        config = loaded
        if (prayerChanged) prayerDay = null
        if (announcer == null && (config.announcements || config.parental != null)) {
            announcer = Announcer(this)
        }
        if (karaokeChanged && apps != null) render()
    }

    private fun announce(text: String, clip: String? = null, onDone: (() -> Unit)? = null) {
        val voice = announcer ?: Announcer(this).also { announcer = it }
        voice.announce(text, clip, onDone = onDone)
    }

    private fun loadApps() {
        val token = ++loadToken
        appLoader.execute {
            val loaded = AppLoader.load(applicationContext)
            mainHandler.post {
                if (token == loadToken && !isDestroyed) {
                    val firstLoad = apps == null
                    apps = loaded
                    render(intro = firstLoad)
                }
            }
        }
    }

    private fun boardItems(): List<BoardItem> {
        val installed = apps.orEmpty().associateBy { it.packageName }
        val items = prefs.board.mapNotNull { key ->
            val karaoke = LauncherPrefs.karaokeIndex(key)
            when {
                karaoke != null -> BoardItem.Karaoke(karaoke).takeIf { karaoke < config.karaoke.size }
                else -> installed[key]?.let { BoardItem.App(it) }
            }
        }
        return items + BoardItem.AllApps
    }

    private fun render(focusKey: String? = (currentFocus?.tag as? BoardItem)?.key, intro: Boolean = false) {
        renderedBoard = prefs.board
        rows.removeAllViews()
        boardItems().forEachIndexed { index, item ->
            val row = layoutInflater.inflate(R.layout.item_board_row, rows, false)
            val icon = row.findViewById<ImageView>(R.id.icon)
            val name = row.findViewById<FlapView>(R.id.name)
            val gate = row.findViewById<FlapView>(R.id.gate)
            val status = row.findViewById<FlapView>(R.id.status)

            icon.clipToOutline = true
            when (item) {
                is BoardItem.App -> icon.setImageDrawable(item.app.icon)
                is BoardItem.Karaoke -> icon.setImageResource(KARAOKE_ICONS[item.index % KARAOKE_ICONS.size])
                BoardItem.AllApps -> icon.setImageResource(R.drawable.ic_board_all)
            }
            if (intro) {
                // Nama tujuan berbalik satu per satu saat launcher pertama dibuka.
                name.postDelayed({ name.setText(label(item)) }, index * INTRO_STAGGER_MS)
            } else {
                name.setText(label(item), animate = false)
            }
            gate.setText(gateCode(index), animate = false)
            status.setText(statusFor(item), animate = false)

            row.tag = item
            row.contentDescription = label(item)
            row.setOnClickListener { open(item) }
            row.setOnLongClickListener {
                showRowMenu(item)
                true
            }
            row.setOnFocusChangeListener { _, hasFocus ->
                status.textColor = colorOf(if (hasFocus) R.color.amber else R.color.dim)
                status.setText(if (hasFocus) focusedStatus(item) else statusFor(item))
            }
            rows.addView(row)
        }

        val target = focusKey?.let { key -> rowViews().firstOrNull { (it.tag as BoardItem).key == key } }
        (target ?: rows.getChildAt(0))?.requestFocus()
    }

    private fun rowViews(): List<View> = (0 until rows.childCount).map { rows.getChildAt(it) }

    private fun refreshStatuses() {
        for (row in rowViews()) {
            if (!row.hasFocus()) row.findViewById<FlapView>(R.id.status).setText(statusFor(row.tag as BoardItem))
        }
    }

    private fun label(item: BoardItem): String = when (item) {
        is BoardItem.App -> item.app.label
        is BoardItem.Karaoke -> config.karaoke[item.index].name
        BoardItem.AllApps -> getString(R.string.all_apps_row)
    }

    /** A1, A2, A3, B1, ... hanya hiasan ala gerbang bandara. */
    private fun gateCode(index: Int) = "${'A' + index / 3}${index % 3 + 1}"

    private fun focusedStatus(item: BoardItem) =
        getString(if (item is BoardItem.AllApps) R.string.status_view else R.string.status_boarding)

    /** Kapan terakhir dibuka dari launcher: jam hari ini, KEMARIN, nama hari, atau tanggal. */
    private fun statusFor(item: BoardItem): String {
        if (item is BoardItem.AllApps) return ""
        val opened = prefs.lastOpened(item.key)
        if (opened == 0L) return "-"
        val then = Date(opened)
        val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(opened)) / DAY_MS).toInt()
        return when {
            days <= 0 -> clockFormat.format(then)
            days == 1 -> getString(R.string.status_yesterday)
            days < 7 -> dayFormat.format(then)
            else -> shortDateFormat.format(then)
        }
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().run {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun open(item: BoardItem) {
        if (item is BoardItem.AllApps) {
            startActivity(Intent(this, AllAppsActivity::class.java))
            return
        }
        if (announcing) return
        withParentalUnlock {
            val go = {
                when (item) {
                    is BoardItem.App -> launchApp(item.app)
                    is BoardItem.Karaoke -> openKaraoke(config.karaoke[item.index].query, item.key)
                    BoardItem.AllApps -> Unit
                }
            }
            if (!config.announcements) {
                go()
                return@withParentalUnlock
            }
            val gate = gateCode(rowViews().indexOfFirst { (it.tag as BoardItem).key == item.key }.coerceAtLeast(0))
            announcing = true
            // Rekaman tidak menyebut gerbang, karena nomornya berubah kalau urutan papan diubah.
            announce(getString(R.string.announce_boarding, label(item), gate), Announcer.clipName("naik_", label(item))) {
                announcing = false
                go()
            }
        }
    }

    private fun showRowMenu(item: BoardItem) {
        if (item is BoardItem.AllApps) {
            showLauncherMenu()
            return
        }
        // Simpan hanya yang tampil, supaya paket yang sudah dicopot tidak mengganggu urutan.
        val board = boardItems().filter { it !is BoardItem.AllApps }.map { it.key }.toMutableList()
        val index = board.indexOf(item.key)
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (index > 0) actions += R.string.move_up to { moveRow(board, index, index - 1) }
        if (index in 0 until board.lastIndex) actions += R.string.move_down to { moveRow(board, index, index + 1) }
        actions += R.string.remove_from_board to {
            board.removeAt(index)
            prefs.board = board
            render(focusKey = board.getOrNull(index) ?: board.lastOrNull())
        }
        if (item is BoardItem.App) actions += R.string.app_info to { openAppInfo(item.app.packageName) }

        AlertDialog.Builder(this)
            .setTitle(label(item))
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun moveRow(board: MutableList<String>, from: Int, to: Int) {
        board.add(to, board.removeAt(from))
        prefs.board = board
        render()
    }

    private fun showLauncherMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.all_apps) to { startActivity(Intent(this, AllAppsActivity::class.java)) },
            getString(R.string.android_settings) to { openAndroidSettings() },
        )
        config.parental?.let { rules ->
            val until = Parental.unlockedUntil(this)
            if (until > 0) {
                actions += getString(R.string.parental_relock_menu, clockText(until)) to {
                    Parental.relock(this)
                    toast(R.string.parental_relocked)
                    updateParental()
                }
            } else {
                actions += getString(R.string.parental_unlock_menu) to { unlockWithPin(rules) { updateParental() } }
            }
        }
        actions += getString(R.string.parent_settings_menu) to {
            ParentSettings.open(this) {
                reloadConfig()
                updateParental()
            }
        }
        if (config.prayerEnabled) {
            actions += getString(R.string.prayer_schedule) to { showPrayerSchedule() }
            if (config.prayerReminder || config.azan) {
                actions += getString(R.string.azan_test_menu) to { PrayerCall.test(this, config) }
            }
        }
        val labels = actions.map { it.first }.toMutableList()
        val extra = mutableListOf<() -> Unit>()
        config.karaoke.forEachIndexed { index, channel ->
            val key = LauncherPrefs.karaokeKey(index)
            if (key !in prefs.board) {
                labels += getString(R.string.add_karaoke, channel.name)
                extra += {
                    prefs.board = prefs.board + key
                    render(focusKey = key)
                }
            }
        }
        val all = actions.map { it.second } + extra
        // Baris informasi (jam tayang, FlightAware); memilihnya hanya menutup menu.
        config.parental?.let { labels += getString(R.string.parental_usage, Parental.usedTodayMinutes(this, it)) }
        // Baris informasi pemakaian FlightAware; memilihnya hanya menutup menu.
        flightAwareUsage()?.let { labels += it }
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(labels.toTypedArray()) { _, which -> all.getOrNull(which)?.invoke() }
            .show()
    }

    /** Kenapa status FlightAware belum tampil: batas biaya, key ditolak, atau gagal mengambil. */
    private fun flightAwareHint(): String {
        val month = billingMonthFormat.format(Date())
        val maxCalls = config.flightAwareBudgetUsd * FlightAware.CALLS_PER_USD
        val error = prefs.flightAwareError
        return when {
            prefs.flightAwareCalls(month) + FlightAware.CALLS_PER_REFRESH > maxCalls -> getString(
                R.string.flightaware_budget_reached,
                String.format(Locale.getDefault(), "%.2f", config.flightAwareBudgetUsd),
            )
            error in 401..403 -> getString(R.string.flightaware_key_rejected, error)
            else -> getString(R.string.flightaware_unavailable)
        }
    }

    private fun flightAwareUsage(): String? {
        if (config.flightAwareKey == null) return null
        val month = billingMonthFormat.format(Date())
        val usd = { value: Double -> String.format(Locale.getDefault(), "%.2f", value) }
        val usage = getString(
            R.string.flightaware_usage,
            usd(prefs.flightAwareCalls(month).toDouble() / FlightAware.CALLS_PER_USD),
            usd(config.flightAwareBudgetUsd),
        )
        val error = prefs.flightAwareError
        return if (error > 0) getString(R.string.flightaware_usage_error, usage, error) else usage
    }

    private fun goToTop() {
        scroll.smoothScrollTo(0, 0)
        rows.getChildAt(0)?.requestFocus()
    }

    private fun refreshOnlineInfo() {
        if (refreshing) return
        refreshing = true
        val needWeather = weather == null || SystemClock.elapsedRealtime() - weatherFetchedAt > WEATHER_REFRESH_MS
        network.execute {
            // Jadwal penerbangan biasanya dari cache, jadi tampilkan dulu; cuaca menyusul.
            val loaded = RinganConfig.load(applicationContext)
            val live = loaded.flightAwareKey != null
            val boards = if (live) loadLiveFlights(loaded) else loadFlights(loaded)
            val logos = boards?.let { b ->
                logoLoader.load((b.departures + b.arrivals).map { it.airlineCode }.toSet())
            }.orEmpty()
            mainHandler.post {
                if (isDestroyed) return@post
                applyConfig(loaded)
                flights = boards
                flightsLive = live
                airlineLogos = logos
                showFlights()
            }

            val newWeather = if (needWeather) Weather.fetchOrNull(loaded.latitude, loaded.longitude) else null
            mainHandler.post {
                refreshing = false
                if (isDestroyed) return@post
                if (newWeather != null) {
                    weather = newWeather
                    weatherFetchedAt = SystemClock.elapsedRealtime()
                }
                if (needWeather || newWeather != null) showWeather()
            }
        }
    }

    private fun showWeather() {
        val now = weather
        if (now == null) {
            weatherTemp.setText(R.string.weather_no_temp)
            weatherDesc.setText(R.string.weather_unavailable)
            weatherPlace.text = config.city
            weatherIcon.setWeather(WeatherView.Scene.CLOUD, windKmh = 0f, intensity = 0f)
            return
        }
        weatherTemp.text = getString(R.string.temperature, now.temperature)
        weatherDesc.setText(Weather.describe(now.code))
        weatherPlace.text = getString(R.string.weather_place, config.city, now.min, now.max)
        weatherIcon.setWeather(
            Weather.scene(now.code, now.isDay),
            now.windKmh,
            Weather.intensity(now.code, now.precipitationMm),
        )
        showHourly(now.hourly)
    }

    private fun showHourly(hours: List<HourForecast>) {
        weatherHourly.visibility = if (hours.isEmpty()) View.GONE else View.VISIBLE
        while (weatherHourly.childCount > hours.size) weatherHourly.removeViewAt(weatherHourly.childCount - 1)
        while (weatherHourly.childCount < hours.size) {
            weatherHourly.addView(layoutInflater.inflate(R.layout.item_hour, weatherHourly, false))
        }
        hours.forEachIndexed { index, hour ->
            val cell = weatherHourly.getChildAt(index)
            cell.findViewById<TextView>(R.id.hour).text = hour.hour
            cell.findViewById<WeatherView>(R.id.hour_icon).apply {
                animated = false
                setWeather(Weather.scene(hour.code, hour.isDay), windKmh = 0f, intensity = Weather.intensity(hour.code, 0f))
            }
            cell.findViewById<TextView>(R.id.hour_temp).text = getString(R.string.temperature, hour.temperature)
        }
    }

    /**
     * Jalankan di thread latar. Jadwal disimpan di file dan hanya diambil ulang setelah
     * flightsRefreshMinutes, karena launcher sering ditutup sistem saat aplikasi lain berjalan.
     * Mengembalikan null kalau API key belum diisi atau data belum pernah berhasil diambil.
     */
    private fun loadFlights(config: RinganConfig): FlightBoards? {
        if (config.flightsApiKey == null) return null
        val cacheFile = File(getExternalFilesDir(null) ?: filesDir, FLIGHTS_CACHE)
        val now = System.currentTimeMillis()
        var cache = readJson(cacheFile)
            ?.takeIf { it.optString("airport") == config.airport && it.optString("kind") == FLIGHTS_CACHE_KIND }

        val stale = cache == null || now - cache.optLong("fetchedAt") > config.flightsRefreshMinutes * 60_000L
        // Jeda hanya berlaku setelah percobaan gagal. Pengaturan yang baru diganti (misalnya API key)
        // juga boleh langsung dicoba.
        val attemptConfig = "${config.airport}|${config.flightsProvider}|${config.flightsApiKey.hashCode()}"
        val mayRetry = prefs.flightsError == 0 ||
            prefs.flightsAttemptConfig != attemptConfig ||
            now - prefs.flightsAttemptAt > FLIGHTS_RETRY_MS
        if (stale && mayRetry) {
            prefs.flightsAttemptAt = now
            prefs.flightsAttemptConfig = attemptConfig
            try {
                cache = JSONObject()
                    .put("airport", config.airport)
                    .put("kind", FLIGHTS_CACHE_KIND)
                    .put("fetchedAt", now)
                    .put("data", JSONObject(Flights.fetch(config)))
                cacheFile.writeText(cache.toString())
                prefs.flightsError = 0
            } catch (e: HttpStatusException) {
                prefs.flightsError = e.code
            } catch (e: IOException) {
                // Tetap pakai jadwal lama kalau ada.
                prefs.flightsError = -1
            } catch (e: JSONException) {
                // Jawaban server bukan JSON; sama, pakai jadwal lama.
                prefs.flightsError = -1
            }
        }
        val data = cache?.optJSONObject("data") ?: return null
        return Flights.parse(applicationContext, data, now, MAX_FLIGHTS)
    }

    /**
     * Jalankan di thread latar. Status langsung FlightAware, diambil paling sering tiap
     * flightAwareRefreshMinutes dan berhenti begitu biaya bulan ini mencapai flightAwareBudgetUsd.
     * Null kalau datanya belum ada atau sudah terlalu lama; papan lalu menampilkan keterangan,
     * bukan jadwal dari penyedia lain.
     */
    private fun loadLiveFlights(config: RinganConfig): FlightBoards? {
        if (config.flightAwareKey == null) return null
        val cacheFile = File(getExternalFilesDir(null) ?: filesDir, LIVE_CACHE)
        val now = System.currentTimeMillis()
        var cache = readJson(cacheFile)?.takeIf { it.optString("airport") == config.flightAwareAirport }
        val refreshMs = config.flightAwareRefreshMinutes * 60_000L

        val stale = cache == null || now - cache.optLong("fetchedAt") > refreshMs
        val month = billingMonthFormat.format(Date(now))
        val maxCalls = config.flightAwareBudgetUsd * FlightAware.CALLS_PER_USD
        val withinBudget = prefs.flightAwareCalls(month) + FlightAware.CALLS_PER_REFRESH <= maxCalls
        val mayRetry = prefs.flightAwareError == 0 || now - prefs.flightAwareAttemptAt > FLIGHTS_RETRY_MS
        if (stale && withinBudget && mayRetry) {
            prefs.flightAwareAttemptAt = now
            try {
                val data = FlightAware.fetch(config, now) { prefs.addFlightAwareCalls(month, 1) }
                cache = JSONObject()
                    .put("airport", config.flightAwareAirport)
                    .put("fetchedAt", now)
                    .put("data", data)
                cacheFile.writeText(cache.toString())
                prefs.flightAwareError = 0
            } catch (e: HttpStatusException) {
                prefs.flightAwareError = e.code
            } catch (e: IOException) {
                prefs.flightAwareError = -1
            } catch (e: JSONException) {
                prefs.flightAwareError = -1
            }
        }
        // Status yang sudah lama menyesatkan; lebih baik tampilkan keterangan saja.
        if (cache == null || now - cache.optLong("fetchedAt") > refreshMs * 2) return null
        val data = cache.optJSONObject("data") ?: return null
        return FlightAware.parse(applicationContext, data, now, MAX_FLIGHTS)
    }

    private fun readJson(file: File): JSONObject? = try {
        JSONObject(file.readText())
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    private fun showFlights() {
        val arrivals = showingArrivals
        flightsIcon.setImageResource(if (arrivals) R.drawable.ic_plane_landing else R.drawable.ic_plane)
        flightsTitle.text = getString(
            if (arrivals) R.string.flights_title_arrivals else R.string.flights_title_departures,
            config.airport,
        )
        flightsSource.setText(if (flightsLive) R.string.flights_source_live else R.string.flights_attribution)
        val list = flights?.let { if (arrivals) it.arrivals else it.departures }
        val hint = when {
            list == null && flightsLive -> flightAwareHint()
            list == null && config.flightsApiKey == null -> getString(R.string.flights_not_configured)
            list == null && prefs.flightsError in 401..403 ->
                getString(R.string.flights_key_rejected, prefs.flightsError)
            list == null -> getString(R.string.flights_unavailable)
            list.isEmpty() -> getString(if (arrivals) R.string.flights_none_arrivals else R.string.flights_none_departures)
            else -> null
        }
        flightsHint.text = hint
        flightsHint.visibility = if (hint == null) View.GONE else View.VISIBLE

        val rows = list.orEmpty()
        while (flightsRows.childCount > rows.size) flightsRows.removeViewAt(flightsRows.childCount - 1)
        while (flightsRows.childCount < rows.size) {
            flightsRows.addView(layoutInflater.inflate(R.layout.item_flight_row, flightsRows, false))
        }
        // Baris dipakai ulang, jadi jam yang berubah ikut berbalik seperti papan sungguhan.
        rows.forEachIndexed { index, flight ->
            val row = flightsRows.getChildAt(index)
            row.findViewById<FlapView>(R.id.time).setText(flight.time)
            row.findViewById<TextView>(R.id.city).text = flight.city
            // Tanpa logo, kode maskapai tampil di kotaknya dan namanya ikut di baris kedua.
            val logo = airlineLogos[flight.airlineCode]
            row.findViewById<ImageView>(R.id.logo).apply {
                setImageBitmap(logo)
                contentDescription = flight.airlineName
                visibility = if (logo != null) View.VISIBLE else View.GONE
            }
            row.findViewById<TextView>(R.id.logo_code).apply {
                text = flight.airlineCode.ifEmpty { "?" }
                visibility = if (logo == null) View.VISIBLE else View.GONE
            }
            row.findViewById<TextView>(R.id.flight).text =
                if (logo == null && flight.airlineName.isNotEmpty()) "${flight.number} · ${flight.airlineName}" else flight.number
            row.findViewById<TextView>(R.id.status).apply {
                text = flight.status
                visibility = if (flight.status == null) View.GONE else View.VISIBLE
                setTextColor(colorOf(flight.tone.color))
            }
        }
    }

    companion object {
        /** Diisi RemoteKeyService saat membawa papan ke depan karena gerbang jam tayang ditutup. */
        const val EXTRA_LANDED = "id.ringan.launcher.LANDED"
        /** Dari RemoteKeyService: Setelan Android dibuka tanpa PIN, minta PIN lalu buka lagi. */
        const val EXTRA_OPEN_SETTINGS = "id.ringan.launcher.OPEN_SETTINGS"

        private val KARAOKE_ICONS = intArrayOf(
            R.drawable.ic_board_karaoke,
            R.drawable.ic_board_karaoke_2,
            R.drawable.ic_board_karaoke_3,
        )
        private const val ALL_APPS_KEY = "ringan:all-apps"
        private const val INTRO_STAGGER_MS = 120L
        private const val BOARD_REFRESH_MS = 30_000L
        private const val WEATHER_REFRESH_MS = 30 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val MAX_FLIGHTS = 4
        private const val FLIGHTS_CACHE = "flights-cache.json"
        private const val LIVE_CACHE = "flights-live-cache.json"
        /** Cache lama yang hanya berisi keberangkatan dianggap kedaluwarsa. */
        private const val FLIGHTS_CACHE_KIND = "both"
        private const val FLIGHTS_ROTATE_MS = 10_000L
        // Permintaan yang gagal tidak memotong kuota, jadi cukup diberi jeda singkat.
        private const val FLIGHTS_RETRY_MS = 5 * 60_000L
    }
}
