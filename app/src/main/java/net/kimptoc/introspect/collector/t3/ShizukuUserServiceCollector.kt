package net.kimptoc.introspect.collector.t3

import android.content.Context
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier
import net.kimptoc.introspect.shizuku.DumpsysResult
import net.kimptoc.introspect.shizuku.ShizukuManager

/**
 * Shared plumbing for every T3 collector that makes a single synchronous
 * call through the bound Shizuku UserService ([net.kimptoc.introspect.shizuku.IDumpsysService]):
 * the watermark gate, the [DumpsysResult.NotBoundYet] early-return (a
 * transient bind-in-progress state, not a completed attempt - burning the
 * gate on it would undo most of what prewarming from
 * `MonitoringService.onCreate()` was for), and the NotPermitted/Error ->
 * [Sample] status-row mapping, so a tier going dark shows up in the data
 * rather than silently (spec §2).
 *
 * A concrete collector supplies only what actually varies: [id],
 * [intervalMs], how the underlying call is made ([call]), and how a
 * successful result maps to samples ([transform]). [timeoutMs] and
 * [statusKey] have defaults that fit every current subclass.
 *
 * Extracted after five collectors ([SensorServiceCollector],
 * [BatteryStatsCollector], [DeviceIdleCollector], [CpuInfoCollector],
 * [PowerCollector] - raw dumpsys text, layered under [DumpsysCollector] -
 * plus [ProcessCountCollector], whose parsed `ps -A` count shares this
 * plumbing without sharing dumpsys text capture) had independently copied
 * this same ~45-line state machine and had already started drifting
 * (SensorServiceCollector predated the `truncated` handling the other
 * dumpsys collectors shipped with). The plumbing bugs - like the
 * three-round truncation-boundary saga on `DumpsysService`, which only the
 * text-capture path inherits - now need fixing once, here.
 */
abstract class ShizukuUserServiceCollector : Collector {
    override val tier = Tier.T3

    /** Minimum time between real (non-`NotBoundYet`) attempts. */
    protected abstract val intervalMs: Long

    protected open val timeoutMs: Int = 5000

    /**
     * Key the status rows (not_bound_yet / not_permitted / error, and
     * `truncated` for the text collectors) are written under - distinct
     * per collector family so a viewer can't confuse a process-count
     * outage with a dumpsys one.
     */
    protected open val statusKey: String = "dump_status"

    /** Performs the actual UserService call. */
    protected abstract fun call(): DumpsysResult

    /** Maps a successful call result to samples. */
    protected abstract fun transform(now: Long, result: DumpsysResult.Success): List<Sample>

    override fun isAvailable(context: Context): Boolean = ShizukuManager.isPermissionGranted()

    override fun collect(context: Context): List<Sample> {
        val prefs = context.getSharedPreferences("${id}_collector", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(lastRunKey, 0L)
        if (now - lastRun in 0 until intervalMs) return emptyList()

        val result = call()

        if (result is DumpsysResult.NotBoundYet) {
            return listOf(Sample(now, id, statusKey, valueNum = 0.0, valueText = "not_bound_yet"))
        }
        prefs.edit().putLong(lastRunKey, now).apply()

        return when (result) {
            is DumpsysResult.Success -> transform(now, result)
            is DumpsysResult.NotPermitted -> listOf(
                Sample(now, id, statusKey, valueNum = 0.0, valueText = "not_permitted"),
            )
            is DumpsysResult.Error -> listOf(
                Sample(now, id, statusKey, valueNum = 0.0, valueText = result.detail),
            )
            is DumpsysResult.NotBoundYet -> emptyList() // unreachable, handled above
        }
    }

    private companion object {
        const val lastRunKey = "last_run_timestamp"
    }
}
