package net.kimptoc.introspect.collector

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kimptoc.introspect.collector.t0.BatteryCollector
import net.kimptoc.introspect.collector.t0.BootCollector
import net.kimptoc.introspect.collector.t0.DozeScreenCollector
import net.kimptoc.introspect.collector.t0.ExitReasonCollector
import net.kimptoc.introspect.collector.t0.MemoryCollector
import net.kimptoc.introspect.collector.t0.StorageCollector
import net.kimptoc.introspect.collector.t0.ThermalCollector
import net.kimptoc.introspect.collector.t1.InstalledPackagesCollector
import net.kimptoc.introspect.collector.t1.NetworkStatsCollector
import net.kimptoc.introspect.collector.t1.UsageEventsCollector
import net.kimptoc.introspect.collector.t1.UsageForegroundCollector
import net.kimptoc.introspect.collector.t2.BatteryAttributionCollector
import net.kimptoc.introspect.collector.t2.LogcatCollector
import net.kimptoc.introspect.collector.t3.BatteryStatsCollector
import net.kimptoc.introspect.collector.t3.CpuInfoCollector
import net.kimptoc.introspect.collector.t3.DeviceIdleCollector
import net.kimptoc.introspect.collector.t3.PowerCollector
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
        MemoryCollector(),
        UsageEventsCollector(),
        UsageForegroundCollector(),
        NetworkStatsCollector(),
        InstalledPackagesCollector(),
        BatteryAttributionCollector(),
        LogcatCollector(),
        SensorServiceCollector(),
        BatteryStatsCollector(),
        DeviceIdleCollector(),
        CpuInfoCollector(),
        PowerCollector(),
    )

    fun availableCollectors(context: Context): List<Collector> =
        all.filter { it.isAvailable(context) }

    /** Tier -> whether at least one of its collectors is currently live. */
    fun tierStatus(context: Context): Map<Tier, Boolean> =
        Tier.entries.associateWith { tier ->
            all.filter { it.tier == tier }.any { it.isAvailable(context) }
        }

    // MonitoringService's periodic loop, its event listeners, and
    // SamplingWorker's independent 15-minute WorkManager tick are all
    // separate entry points that can run concurrently - each collector's
    // own watermark gate assumes only one collect() call at a time, so two
    // concurrent callers can both read the same stale watermark before
    // either writes it back, producing duplicate rows. private, not just
    // convention: an unlocked collectAll() sitting right next to the
    // locked entry point is a bypass waiting for the next caller who
    // doesn't know to avoid it.
    private val collectMutex = Mutex()

    private fun collectAll(context: Context): List<Sample> =
        availableCollectors(context).flatMap { it.collect(context) }

    suspend fun collectAllLocked(context: Context): List<Sample> = collectMutex.withLock {
        collectAll(context)
    }
}
