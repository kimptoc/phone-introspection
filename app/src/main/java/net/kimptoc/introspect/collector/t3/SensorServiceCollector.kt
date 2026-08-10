package net.kimptoc.introspect.collector.t3

import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * Raw `dumpsys sensorservice` output (spec §3 T3), the first T3 signal
 * shipped: smaller and lower-risk than `batterystats`, and directly
 * applicable to the step-counter debugging use spec §8 calls out — the
 * dump exposes whether the step sensor is batching, its FIFO depth, and
 * which clients are registered, which is usually where hourly step
 * counting goes wrong.
 *
 * Unlike T0-T2, availability here depends on a third-party app (Shizuku)
 * actually running and this app holding a Shizuku-granted permission, on
 * top of the usual Android permission model - see [ShizukuManager].
 * Binding to the dumpsys UserService is asynchronous, so a "not bound
 * yet" cycle is a normal transient state, not a failure - a
 * `dump_status` row records why a cycle produced no dump text, the same
 * discriminator [net.kimptoc.introspect.collector.t2.LogcatCollector]'s
 * `read_status` needed.
 *
 * No parsing yet - stores the raw dump text only, capped, per spec §3 T3's
 * "wrap every parser in try/catch, store the raw text alongside the
 * parsed result" and spec §7's storage-growth caution (this is the
 * "real space consumer" signal spec §7 names by name). Runs hourly, not
 * this app's usual 30 minutes: a full dump repeated too often is exactly
 * the storage growth spec §7 warns against, and sensor client/FIFO state
 * doesn't change on a sub-hour cadence in normal use.
 */
class SensorServiceCollector : Collector {
    override val id = "sensorservice"
    override val tier = Tier.T3

    private val prefsName = "sensorservice_collector"
    private val lastRunKey = "last_run_timestamp"
    private val intervalMs = 60 * 60 * 1000L
    private val maxChars = 20_000

    override fun isAvailable(context: Context): Boolean = ShizukuManager.isPermissionGranted()

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()
        prefs.edit().putLong(lastRunKey, now).apply()

        return when (val result = ShizukuManager.dumpsys("sensorservice", maxChars = maxChars)) {
            is DumpsysResult.Success -> listOf(
                Sample(now, id, "dump", valueNum = result.text.length.toDouble(), valueText = result.text),
            )
            is DumpsysResult.NotBoundYet -> listOf(
                Sample(now, id, "dump_status", valueNum = 0.0, valueText = "not_bound_yet"),
            )
            is DumpsysResult.NotPermitted -> listOf(
                Sample(now, id, "dump_status", valueNum = 0.0, valueText = "not_permitted"),
            )
            is DumpsysResult.Error -> listOf(
                Sample(now, id, "dump_status", valueNum = 0.0, valueText = result.detail),
            )
        }
    }
}
