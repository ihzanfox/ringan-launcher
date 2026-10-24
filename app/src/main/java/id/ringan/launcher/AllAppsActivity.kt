package id.ringan.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Grid semua aplikasi. Dari sini aplikasi bisa ditambahkan ke papan atau disembunyikan. */
class AllAppsActivity : Activity() {

    /** Disimpan di tag tiap tile. [topRow] = baris paling atas, fokus di sana menggulir ke judul. */
    private class TileTag(val app: AppEntry, val topRow: Boolean)

    private class GridSize(val columns: Int, val cardWidth: Int, val cardHeight: Int)

    private lateinit var prefs: LauncherPrefs
    private lateinit var scroll: ScrollView
    private lateinit var grid: LinearLayout

    private val loader = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadToken = 0
    private var apps: List<AppEntry> = emptyList()

    /** Kartu diperlebar dari tile_min_w supaya kolom tepat memenuhi lebar layar. */
    private val gridSize by lazy {
        val available = resources.displayMetrics.widthPixels - 2 * resources.getDimensionPixelSize(R.dimen.edge_h)
        val chrome = 2 * (resources.getDimensionPixelSize(R.dimen.tile_gap) + resources.getDimensionPixelSize(R.dimen.focus_ring_pad))
        val columns = (available / (resources.getDimensionPixelSize(R.dimen.tile_min_w) + chrome)).coerceAtLeast(1)
        val cardWidth = available / columns - chrome
        GridSize(columns, cardWidth, cardWidth * 9 / 16)
    }
    private val focusLift by lazy { resources.getDimension(R.dimen.focus_lift) }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        prefs = LauncherPrefs(this)
        RinganConfig.load(this)
        scroll = findViewById(R.id.scroll)
        grid = findViewById(R.id.grid)
        findViewById<View>(R.id.menu).setOnClickListener { showLauncherMenu() }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        // Halaman ini bagian dari launcher: tidak dihitung sebagai waktu menonton.
        Parental.boardVisible = true
    }

    override fun onPause() {
        Parental.boardVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val tag = currentFocus?.tag as? TileTag
            if (tag != null) showAppMenu(tag.app) else showLauncherMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadApps() {
        val token = ++loadToken
        loader.execute {
            val loaded = AppLoader.load(applicationContext)
            mainHandler.post {
                if (token == loadToken && !isDestroyed) {
                    apps = loaded
                    render()
                }
            }
        }
    }

    private fun render(focusPackage: String? = (currentFocus?.tag as? TileTag)?.app?.packageName) {
        val hidden = prefs.hidden
        grid.removeAllViews()
        apps.filter { it.packageName !in hidden }.chunked(gridSize.columns).forEachIndexed { index, rowApps ->
            val row = LinearLayout(this).apply { clipChildren = false }
            for (app in rowApps) row.addView(createTile(app, row, topRow = index == 0))
            grid.addView(row)
        }
        val target = focusPackage?.let { pkg -> tiles().firstOrNull { (it.tag as TileTag).app.packageName == pkg } }
        (target ?: tiles().firstOrNull())?.requestFocus()
    }

    private fun tiles(): Sequence<View> = (0 until grid.childCount).asSequence()
        .map { grid.getChildAt(it) as ViewGroup }
        .flatMap { row -> (0 until row.childCount).asSequence().map { row.getChildAt(it) } }

    private fun createTile(app: AppEntry, parent: ViewGroup, topRow: Boolean): View {
        val tile = layoutInflater.inflate(R.layout.item_tile, parent, false)
        val frame = tile.findViewById<View>(R.id.frame)
        val card = tile.findViewById<View>(R.id.card)
        card.layoutParams.width = gridSize.cardWidth
        card.layoutParams.height = gridSize.cardHeight
        card.clipToOutline = true
        if (app.banner != null) {
            tile.findViewById<ImageView>(R.id.banner).apply {
                setImageDrawable(app.banner)
                visibility = View.VISIBLE
            }
        } else {
            tile.findViewById<ImageView>(R.id.icon).apply {
                setImageDrawable(app.icon)
                visibility = View.VISIBLE
            }
        }
        tile.findViewById<TextView>(R.id.label).text = app.label
        tile.contentDescription = app.label
        tile.tag = TileTag(app, topRow)

        tile.setOnClickListener { withParentalUnlock { launchApp(app) } }
        tile.setOnLongClickListener {
            showAppMenu(app)
            true
        }
        tile.setOnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            frame.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(if (hasFocus) focusLift else 0f)
                .setDuration(FOCUS_ANIM_MS)
                .start()
            if (hasFocus && topRow) scroll.smoothScrollTo(0, 0)
        }
        return tile
    }

    private fun showAppMenu(app: AppEntry) {
        val board = prefs.board
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (app.packageName in board) {
            actions += R.string.remove_from_board to { prefs.board = board - app.packageName }
        } else {
            actions += R.string.add_to_board to {
                prefs.board = board + app.packageName
                Toast.makeText(this, getString(R.string.added_to_board, app.label), Toast.LENGTH_SHORT).show()
            }
        }
        actions += R.string.hide to { hide(app) }
        actions += R.string.app_info to { openAppInfo(app.packageName) }
        if (!app.isSystem) actions += R.string.uninstall to { uninstall(app.packageName) }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun hide(app: AppEntry) {
        val hidden = prefs.hidden
        val visible = apps.filter { it.packageName !in hidden }
        val index = visible.indexOf(app)
        val neighbor = visible.getOrNull(index + 1) ?: visible.getOrNull(index - 1)

        prefs.hidden = hidden + app.packageName
        render(neighbor?.packageName)
        Toast.makeText(this, getString(R.string.hidden_toast, app.label), Toast.LENGTH_LONG).show()
    }

    private fun showLauncherMenu() {
        val hidden = prefs.hidden
        val hiddenApps = apps.filter { it.packageName in hidden }
        val items = arrayOf(
            getString(R.string.parent_settings_menu),
            getString(R.string.android_settings),
            getString(R.string.hidden_apps, hiddenApps.size),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                when (which) {
                    // Papan membaca ulang pengaturan saat kembali ke depan.
                    0 -> ParentSettings.open(this) {}
                    1 -> openAndroidSettings()
                    2 -> showHiddenApps(hiddenApps)
                }
            }
            .show()
    }

    private fun showHiddenApps(hiddenApps: List<AppEntry>) {
        if (hiddenApps.isEmpty()) {
            toast(R.string.no_hidden_apps)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_apps_title)
            .setItems(hiddenApps.map { it.label }.toTypedArray()) { _, which ->
                prefs.hidden = prefs.hidden - hiddenApps[which].packageName
                render()
            }
            .show()
    }

    private companion object {
        const val FOCUS_SCALE = 1.1f
        const val FOCUS_ANIM_MS = 150L
    }
}
