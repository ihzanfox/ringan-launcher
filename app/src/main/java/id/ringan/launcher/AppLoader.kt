package id.ringan.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import java.text.Collator

class AppEntry(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    val isLeanback: Boolean,
    val isSystem: Boolean,
)

object AppLoader {

    /** Jalankan di thread latar: memuat label dan gambar puluhan aplikasi cukup lambat. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val apps = LinkedHashMap<String, AppEntry>()
        // Aktivitas TV didahulukan; aplikasi ponsel hanya masuk kalau tidak punya versi TV.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (info in pm.queryLaunchers(category)) {
                val activity = info.activityInfo
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg in apps) continue
                apps[pkg] = AppEntry(
                    packageName = pkg,
                    component = ComponentName(pkg, activity.name),
                    label = info.loadLabel(pm).toString(),
                    banner = activity.loadBanner(pm),
                    icon = info.loadIcon(pm),
                    isLeanback = category == Intent.CATEGORY_LEANBACK_LAUNCHER,
                    isSystem = activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
        }
        val collator = Collator.getInstance()
        return apps.values.sortedWith(compareBy(collator) { it.label })
    }

    private fun PackageManager.queryLaunchers(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= 33) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }
    }
}
