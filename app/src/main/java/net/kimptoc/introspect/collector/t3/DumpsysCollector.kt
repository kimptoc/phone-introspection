package net.kimptoc.introspect.collector.t3

import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * Shared plumbing for every T3 collector that wraps a single `dumpsys`
 * call: the watermark gate, the [DumpsysResult.NotBoundYet] early-return
 * (a transient bind-in-progress state, not a completed attempt - burning
 * the gate on it would undo most of what prewarming from
 * `MonitoringService.onCreate()` was for), and the
 * Success/NotPermitted/Error -> [Sample] mapping, including
 * [DumpsysResult.Success.truncated] (computed by `DumpsysService` against
 * the real pre-truncation length - see [DeviceIdleCollector]'s KDoc for
 * why that can't be re-derived downstream from the returned string's own
 * length). Whether `truncated` shows up as a rare exception or the
 * permanent steady state depends entirely on the subclass's own
 * [maxChars] vs. the real dump size: [DeviceIdleCollector]/[CpuInfoCollector]
 * pick a cap with headroom, so it should stay false; [SensorServiceCollector]/
 * [BatteryStatsCollector] deliberately cap well below their multi-hundred-KB
 * real dumps (spec §7's storage-growth caution), so `truncated=true` on
 * every single cycle is the expected, permanent state for those two, not
 * a signal something's wrong.
 *
 * Extracted after four collectors ([SensorServiceCollector],
 * [BatteryStatsCollector], [DeviceIdleCollector], [CpuInfoCollector]) had
 * independently copied this same ~45-line state machine, and had already
 * started drifting: `SensorServiceCollector` predated the `truncated`
 * handling the other three shipped with and had no way to pick it up
 * automatically. Each subclass now only supplies [id], [service], the
 * dumpsys [args], [intervalMs], [maxChars], and its own domain-specific
 * KDoc explaining what the dump actually contains and why that cadence/cap
 * was chosen - the plumbing bugs (like the three-round truncation-boundary
 * saga on `DumpsysService`) only need fixing once, here.
 */
abstract class DumpsysCollector : Collector {
    override val tier = Tier.T3

    /** The `dumpsys <service>` argument, e.g. `"sensorservice"`. */
    protected abstract val service: String
    protected open val args: Array<String> = emptyArray()

    /** Minimum time between real (non-`NotBoundYet`) attempts. */
    protected abstract val intervalMs: Long
    protected abstract val maxChars: Int
    protected open val timeoutMs: Int = 5000

    private val lastRunKey = "last_run_timestamp"

    override fun isAvailable(context: Context): Boolean = ShizukuManager.isPermissionGranted()

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences("${id}_collector", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()

        val result = ShizukuManager.dumpsys(service, args = args, timeoutMs = timeoutMs, maxChars = maxChars)

        if (result is DumpsysResult.NotBoundYet) {
            return listOf(Sample(now, id, "dump_status", valueNum = 0.0, valueText = "not_bound_yet"))
        }
        prefs.edit().putLong(lastRunKey, now).apply()

        return when (result) {
            is DumpsysResult.Success -> {
                val samples = mutableListOf(
                    Sample(now, id, "dump", valueNum = result.text.length.toDouble(), valueText = result.text),
                )
                if (result.truncated) {
                    samples += Sample(now, id, "dump_status", valueNum = 1.0, valueText = "truncated")
                }
                samples
            }
            is DumpsysResult.NotPermitted -> listOf(
                Sample(now, id, "dump_status", valueNum = 0.0, valueText = "not_permitted"),
            )
            is DumpsysResult.Error -> listOf(
                Sample(now, id, "dump_status", valueNum = 0.0, valueText = result.detail),
            )
            is DumpsysResult.NotBoundYet -> emptyList() // unreachable, handled above
        }
    }
}
