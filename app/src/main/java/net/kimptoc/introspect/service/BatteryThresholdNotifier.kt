package net.kimptoc.introspect.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import net.kimptoc.introspect.MainActivity
import net.kimptoc.introspect.R

/**
 * Fires a one-shot notification the first time battery level drops below
 * a fixed threshold while discharging, then re-arms once it's charged
 * back above it. Useful for charge/discharge test cycles (e.g. Samsung
 * support asking to charge to 100% and check battery usage once past
 * 50%). Not user-configurable — THRESHOLD_PCT is a fixed constant.
 */
object BatteryThresholdNotifier {
    private const val THRESHOLD_PCT = 50
    private const val PREFS_NAME = "battery_threshold_notifier"
    private const val ARMED_KEY = "armed"
    private const val CHANNEL_ID = "battery_threshold"
    private const val NOTIFICATION_ID = 2

    fun onBatteryChanged(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = 100.0 * level / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!prefs.contains(ARMED_KEY)) {
            // First-ever observation: ACTION_BATTERY_CHANGED is sticky and replays the
            // current state as soon as the receiver registers, which isn't a real
            // crossing. Seed from the current reading instead of assuming one happened.
            prefs.edit().putBoolean(ARMED_KEY, pct >= THRESHOLD_PCT).apply()
            return
        }

        val armed = prefs.getBoolean(ARMED_KEY, true)

        if (pct >= THRESHOLD_PCT) {
            if (!armed) prefs.edit().putBoolean(ARMED_KEY, true).apply()
            return
        }

        if (armed && !plugged) {
            notify(context, pct)
            prefs.edit().putBoolean(ARMED_KEY, false).apply()
        }
    }

    private fun notify(context: Context, pct: Double) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.battery_threshold_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.battery_threshold_title))
            .setContentText(context.getString(R.string.battery_threshold_text, pct.toInt()))
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
