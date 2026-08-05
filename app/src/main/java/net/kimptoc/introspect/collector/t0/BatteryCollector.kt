package net.kimptoc.introspect.collector.t0

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import net.kimptoc.introspect.collector.Collector
import net.kimptoc.introspect.collector.Sample
import net.kimptoc.introspect.collector.Tier

/**
 * Battery level/status/health/plug type/temperature from the sticky
 * ACTION_BATTERY_CHANGED broadcast, plus instantaneous current and charge
 * counters from BatteryManager. All T0 (spec §3).
 */
class BatteryCollector : Collector {
    override val id = "battery"
    override val tier = Tier.T0

    override fun isAvailable(context: Context): Boolean = true

    override fun collect(context: Context): List<Sample> {
        val now = System.currentTimeMillis()
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return emptyList()

        val samples = mutableListOf<Sample>()

        fun num(key: String, value: Double?) {
            if (value != null) samples += Sample(now, id, key, valueNum = value)
        }

        fun text(key: String, value: String?) {
            if (value != null) samples += Sample(now, id, key, valueText = value)
        }

        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            num("level_pct", 100.0 * level / scale)
        }
        num("temperature_c", sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 })
        num("voltage_mv", sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it >= 0 }?.toDouble())
        text("status", batteryStatusName(sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)))
        text("health", batteryHealthName(sticky.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)))
        text("plugged", pluggedName(sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)))

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (batteryManager != null) {
            num("current_now_ua", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != Int.MIN_VALUE }?.toDouble())
            num("charge_counter_uah", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .takeIf { it != Int.MIN_VALUE }?.toDouble())
            num("energy_counter_nwh", batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                .takeIf { it != Long.MIN_VALUE }?.toDouble())
        }

        return samples
    }

    private fun batteryStatusName(status: Int) = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun batteryHealthName(health: Int) = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    private fun pluggedName(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "unplugged"
        else -> "unknown"
    }
}
