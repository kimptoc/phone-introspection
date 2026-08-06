package net.kimptoc.introspect.collector.t1

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * PACKAGE_USAGE_STATS is granted via Settings, not a runtime prompt — the
 * only way to know if it's live is to ask AppOpsManager (spec §3). Shared
 * by every T1 collector's isAvailable() and the onboarding button.
 */
object UsageAccess {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
