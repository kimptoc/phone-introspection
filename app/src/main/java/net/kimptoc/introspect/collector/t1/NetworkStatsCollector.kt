package net.kimptoc.introspect.collector.t1

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Per-UID mobile & wifi bytes, bucketed (spec §3 T1). Gated to the same
 * 30-minute reconciliation cadence as [UsageForegroundCollector] for the
 * same storage-growth reason.
 */
class NetworkStatsCollector : Collector {
    override val id = "network"
    override val tier = Tier.T1

    private val prefsName = "network_stats_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 30 * 60 * 1000L

    override fun isAvailable(context: Context): Boolean = UsageAccess.isGranted(context)

    override fun collect(context: Context): List<Sample> {
        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyList()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun < intervalMs) return emptyList()

        val windowStart = if (lastRun > 0L) lastRun else now - intervalMs
        val samples = mutableListOf<Sample>()
        samples += queryType(networkStatsManager, ConnectivityManager.TYPE_MOBILE, "mobile", windowStart, now)
        samples += queryType(networkStatsManager, ConnectivityManager.TYPE_WIFI, "wifi", windowStart, now)

        prefs.edit().putLong(lastRunKey, now).apply()
        return samples
    }

    private fun queryType(
        networkStatsManager: NetworkStatsManager,
        networkType: Int,
        typeName: String,
        start: Long,
        end: Long,
    ): List<Sample> {
        val samples = mutableListOf<Sample>()
        val stats = try {
            networkStatsManager.querySummary(networkType, null, start, end)
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: RuntimeException) {
            return emptyList()
        }

        stats.use {
            val bucket = NetworkStats.Bucket()
            while (it.hasNextBucket()) {
                it.getNextBucket(bucket)
                samples += Sample(end, id, "${bucket.uid}:$typeName:rx", valueNum = bucket.rxBytes.toDouble())
                samples += Sample(end, id, "${bucket.uid}:$typeName:tx", valueNum = bucket.txBytes.toDouble())
            }
        }
        return samples
    }
}
