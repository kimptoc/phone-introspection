package net.kimptoc.introspect.collector.t1

import android.content.Context
import android.content.pm.PackageManager
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Installed package list with labels and install times (spec §3 T1).
 * QUERY_ALL_PACKAGES is a normal manifest permission for a sideloaded
 * install — no Settings tap needed — so this is live as soon as the app
 * is installed, unlike the other two T1 collectors.
 *
 * Only emits when the package set actually changes (tracked via a stable
 * hash in SharedPreferences), not every cycle — the list itself rarely
 * changes and re-emitting hundreds of rows every 60s would be pure waste.
 */
class InstalledPackagesCollector : Collector {
    override val id = "installed_packages"
    override val tier = Tier.T1

    private val prefsName = "installed_packages_collector"
    private val lastHashKey = "last_hash"

    override fun isAvailable(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED

    override fun collect(context: Context): List<Sample> {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val packages = packageManager.getInstalledPackages(0)

        val entries = packages
            .sortedBy { it.packageName }
            .map { info ->
                val label = info.applicationInfo?.let { packageManager.getApplicationLabel(it) } ?: info.packageName
                Triple(info.packageName, info.firstInstallTime, label.toString())
            }

        val fingerprint = entries.joinToString("|") { "${it.first}:${it.second}" }.hashCode()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (prefs.getInt(lastHashKey, 0) == fingerprint) return emptyList()
        prefs.edit().putInt(lastHashKey, fingerprint).apply()

        val now = System.currentTimeMillis()
        return entries.map { (packageName, installTime, label) ->
            Sample(now, id, packageName, valueNum = installTime.toDouble(), valueText = label)
        }
    }
}
