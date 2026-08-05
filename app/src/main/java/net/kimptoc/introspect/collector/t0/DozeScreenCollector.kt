package net.kimptoc.introspect.collector.t0

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Doze / power-save / battery-optimisation-exemption state, plus screen
 * and keyguard state (spec §3). Doze suppresses the periodic sampler by
 * design (spec §4) — this collector is what makes that gap interpretable
 * rather than mysterious.
 */
class DozeScreenCollector : Collector {
    override val id = "doze"
    override val tier = Tier.T0

    override fun isAvailable(context: Context): Boolean = true

    override fun collect(context: Context): List<Sample> {
        val now = System.currentTimeMillis()
        val samples = mutableListOf<Sample>()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            samples += Sample(now, id, "device_idle", valueText = powerManager.isDeviceIdleMode.toString())
            samples += Sample(now, id, "power_save", valueText = powerManager.isPowerSaveMode.toString())
            samples += Sample(
                now, id, "ignoring_battery_optimizations",
                valueText = powerManager.isIgnoringBatteryOptimizations(context.packageName).toString(),
            )
            samples += Sample(now, id, "screen_on", valueText = powerManager.isInteractive.toString())
        }

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null) {
            samples += Sample(now, id, "keyguard_locked", valueText = keyguardManager.isKeyguardLocked.toString())
        }

        return samples
    }
}
