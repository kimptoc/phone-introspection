package net.kimptoc.introspect.collector

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kimptoc.introspect.collector.t0.BatteryCollector
import net.kimptoc.introspect.collector.t0.BootCollector
import net.kimptoc.introspect.collector.t0.DozeScreenCollector
import net.kimptoc.introspect.collector.t0.ExitReasonCollector
import net.kimptoc.introspect.collector.t0.StorageCollector
import net.kimptoc.introspect.collector.t0.ThermalCollector
import net.kimptoc.introspect.collector.t1.InstalledPackagesCollector
import net.kimptoc.introspect.collector.t1.NetworkStatsCollector
import net.kimptoc.introspect.collector.t1.UsageEventsCollector
import net.kimptoc.introspect.collector.t1.UsageForegroundCollector
import net.kimptoc.introspect.collector.t2.BatteryAttributionCollector
import net.kimptoc.introspect.collector.t2.LogcatCollector
import net.kimptoc.introspect.collector.t3.BatteryStatsCollector
import net.kimptoc.introspect.collector.t3.SensorServiceCollector

/**
 * Every known collector, in one place. [availableCollectors] is re-probed
 * on demand so a tier going dark (e.g. Shizuku dying after reboot) shows
 * up immediately rather than silently.
 */
object CollectorRegistry {

    val all: List<Collector> = listOf(
        BatteryCollector(),
        ThermalCollector(),
        BootCollector(),
        DozeScreenCollector(),
        ExitReasonCollector(),
        StorageCollector(),
        UsageEventsCollector(),
        UsageForegroundCollector(),
        NetworkStatsCollector(),
        InstalledPackagesCollector(),
        BatteryAttributionCollector(),
        LogcatCollector(),
        SensorServiceCollector(),
        BatteryStatsCollector(),
    )

    fun availableCollectors(context: Context): List<Collector> =
        all.filter { it.isAvailable(context) }

    /** Tier -> whether at least one of its collectors is currently live. */
    fun tierStatus(context: Context): Map<Tier, Boolean> =
        Tier.entries.associateWith { tier ->
            all.filter { it.tier == tier }.any { it.isAvailable(context) }
        }

    fun collectAll(context: Context): List<Sample> =
        availableCollectors(context).flatMap { it.collect(context) }

    // MonitoringService's periodic loop, its event listeners, and
    // SamplingWorker's independent 15-minute WorkManager tick are all
    // separate entry points into collectAll() that can run concurrently -
    // each collector's own watermark gate assumes only one collect() call
    // at a time, so two concurrent callers can both read the same stale
    // watermark before either writes it back, producing duplicate rows.
    // A lock scoped to MonitoringService alone doesn't cover this: it has
    // to live here, at the one choke point every caller actually shares.
    private val collectMutex = Mutex()

    suspend fun collectAllLocked(context: Context): List<Sample> = collectMutex.withLock {
        collectAll(context)
    }
}
