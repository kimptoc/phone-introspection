package net.kimptoc.introspect.collector.t0

import android.content.Context
import android.os.PowerManager
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Thermal status bucket and headroom (spec §3). getThermalHeadroom is the
 * API-30 floor that sets this app's minSdk.
 */
class ThermalCollector : Collector {
    override val id = "thermal"
    override val tier = Tier.T0

    override fun isAvailable(context: Context): Boolean =
        context.getSystemService(Context.POWER_SERVICE) is PowerManager

    override fun collect(context: Context): List<Sample> {
        val now = System.currentTimeMillis()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return emptyList()

        val samples = mutableListOf<Sample>()
        samples += Sample(now, id, "status", valueText = thermalStatusName(powerManager.currentThermalStatus))

        val headroomNow = powerManager.getThermalHeadroom(0)
        if (!headroomNow.isNaN()) {
            samples += Sample(now, id, "headroom_now", valueNum = headroomNow.toDouble())
        }
        val headroom10s = powerManager.getThermalHeadroom(10)
        if (!headroom10s.isNaN()) {
            samples += Sample(now, id, "headroom_10s", valueNum = headroom10s.toDouble())
        }

        return samples
    }

    private fun thermalStatusName(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }
}
