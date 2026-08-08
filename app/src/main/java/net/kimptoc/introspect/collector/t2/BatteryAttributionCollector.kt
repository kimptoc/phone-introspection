package net.kimptoc.introspect.collector.t2

import android.content.Context
import android.content.pm.PackageManager
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import java.lang.reflect.InvocationTargetException

/**
 * Per-UID battery consumption attribution (spec §3 T2), via
 * `android.os.BatteryStatsManager#getBatteryUsageStats()`. That class,
 * `BatteryUsageStats` and `UidBatteryConsumer` are all `@SystemApi` —
 * confirmed absent from the public SDK stub jars for API 33 through 36 —
 * so this collector reflects into the framework rather than calling them
 * directly. Reflection into hidden/SystemApi framework classes is itself
 * blocked by Android's hidden-API enforcement unless the device has
 * `hidden_api_policy` relaxed (scripts/provision.sh). That means this
 * collector needs the `BATTERY_STATS` grant *and* the hidden_api_policy
 * setting together — the spec lists them as separate T2 bullets, but for
 * this specific signal they're coupled, not independent.
 *
 * `getUidBatteryConsumers()` reports power consumed (mAh) since the last
 * full charge, not a per-cycle delta — there's no windowed query on this
 * surface. Each cycle is a fresh snapshot; "who's actually draining this
 * discharge cycle" comes from comparing successive stored snapshots, not
 * from anything this collector computes itself.
 */
class BatteryAttributionCollector : Collector {
    override val id = "battery_attribution"
    override val tier = Tier.T2

    private val prefsName = "battery_attribution_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 30 * 60 * 1000L
    private val permission = "android.permission.BATTERY_STATS"
    private val serviceName = "batterystats"

    override fun isAvailable(context: Context): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()

        // Always advance the watermark, success or failure: a persistent
        // failure (wrong OS version, reflection blocked) must not retry
        // every ~60s sampling tick forever - that's up to 1,440 rows/day
        // of the same failure, which is its own storage-growth problem
        // (spec §7). One reflect_status row per 30 min either way.
        prefs.edit().putLong(lastRunKey, now).apply()

        // reflect_status is always emitted, success or failure: this signal
        // depends on hidden_api_policy as well as the permission (see class
        // doc), and a silent, permanently-empty collector is exactly what
        // spec §2/§7 says a tier must never do. "T2: live" in the UI only
        // means the permission is granted, not that reflection is working.
        return try {
            val snapshot = readSnapshot(context, now)
            snapshot + Sample(now, id, "reflect_status", valueNum = snapshot.size.toDouble(), valueText = "ok")
        } catch (e: ReflectiveOperationException) {
            listOf(Sample(now, id, "reflect_status", valueNum = 0.0, valueText = statusText(e)))
        } catch (e: SecurityException) {
            listOf(Sample(now, id, "reflect_status", valueNum = 0.0, valueText = statusText(e)))
        } catch (e: RuntimeException) {
            listOf(Sample(now, id, "reflect_status", valueNum = 0.0, valueText = statusText(e)))
        }
    }

    /**
     * `Method.invoke()` wraps whatever the target threw in an
     * `InvocationTargetException` - reporting that class name alone would
     * always say "InvocationTargetException" and hide the actual cause
     * (e.g. a `NoSuchMethodException` chained from inside the framework
     * call). Unwrap it so reflect_status names the real failure.
     */
    private fun statusText(e: Throwable): String {
        val real = if (e is InvocationTargetException) e.cause ?: e else e
        val message = real.message?.take(100)
        return if (message.isNullOrBlank()) real.javaClass.simpleName else "${real.javaClass.simpleName}: $message"
    }

    private fun readSnapshot(context: Context, now: Long): List<Sample> {
        val manager = context.getSystemService(serviceName)
            ?: error("getSystemService(\"$serviceName\") returned null")

        val stats = manager.javaClass.getMethod("getBatteryUsageStats").invoke(manager)
            ?: error("getBatteryUsageStats() returned null")

        @Suppress("UNCHECKED_CAST")
        val consumers = stats.javaClass.getMethod("getUidBatteryConsumers").invoke(stats) as? List<Any>
            ?: error("getUidBatteryConsumers() returned non-List or null")

        val pm = context.packageManager
        val samples = mutableListOf<Sample>()
        for (consumer in consumers) {
            val consumerClass = consumer.javaClass
            val consumedPower = consumerClass.getMethod("getConsumedPower").invoke(consumer) as? Double
                ?: continue
            if (consumedPower <= 0.0) continue

            val uid = consumerClass.getMethod("getUid").invoke(consumer) as? Int ?: continue
            val label = pm.getPackagesForUid(uid)?.firstOrNull() ?: "uid_$uid"

            samples += Sample(
                timestamp = now,
                collectorId = id,
                key = label,
                valueNum = consumedPower,
                valueText = "uid=$uid",
            )
        }
        return samples
    }
}
