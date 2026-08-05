package net.kimptoc.introspect.collector

import android.content.Context
import net.kimptoc.introspect.collector.t0.BatteryCollector
import net.kimptoc.introspect.collector.t0.BootCollector
import net.kimptoc.introspect.collector.t0.DozeScreenCollector
import net.kimptoc.introspect.collector.t0.ExitReasonCollector
import net.kimptoc.introspect.collector.t0.ThermalCollector

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
}
