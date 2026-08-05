package net.kimptoc.introspect.collector.t0

import android.content.Context
import android.os.SystemClock
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Boot timestamp and cumulative deep-sleep time (spec §3). Deep-sleep is
 * derived from the elapsed-vs-uptime gap, so a growing value between
 * samples means the device is going to sleep normally, not that
 * collection stalled.
 */
class BootCollector : Collector {
    override val id = "boot"
    override val tier = Tier.T0

    override fun isAvailable(context: Context): Boolean = true

    override fun collect(context: Context): List<Sample> {
        val now = System.currentTimeMillis()
        val bootTimeMillis = now - SystemClock.elapsedRealtime()
        val deepSleepMillis = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

        return listOf(
            Sample(now, id, "boot_time_ms", valueNum = bootTimeMillis.toDouble()),
            Sample(now, id, "deep_sleep_ms", valueNum = deepSleepMillis.toDouble()),
        )
    }
}
