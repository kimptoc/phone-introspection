package net.kimptoc.introspect.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import net.kimptoc.introspect.BuildConfig
import rikka.shizuku.Shizuku

/**
 * Singleton wrapper around Shizuku's binder + UserService lifecycle
 * (spec §3 T3). Binding is asynchronous (a [ServiceConnection] callback)
 * and must outlive any single `collect()` call, so this holds the bound
 * [IDumpsysService] reference across cycles rather than trying to force
 * Shizuku's model into the synchronous [net.kimptoc.introspect.collector.Collector]
 * interface directly.
 *
 * `daemon(true)`: Samsung killing this app's process is the normal case
 * here, not the exception (spec §7) - a `daemon(false)` UserService would
 * mean a rebind on every single process restart. [userServiceVersion] is
 * NOT tied to the app's own `versionCode`: Shizuku only restarts an
 * already-running daemon UserService when this version number changes, and
 * during active development the app gets reinstalled far more often than
 * `versionCode` gets bumped. Bump [userServiceVersion] by hand whenever
 * [DumpsysService]'s behaviour changes, or testing will silently run
 * against a stale already-running process.
 */
object ShizukuManager {
    private const val PACKAGE_NAME = "net.kimptoc.introspect"
    private const val userServiceVersion = 4

    @Volatile private var binder: IDumpsysService? = null
    @Volatile private var binding = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(PACKAGE_NAME, DumpsysService::class.java.name)
    )
        .daemon(true)
        .processNameSuffix("dumpsys")
        .debuggable(BuildConfig.DEBUG)
        .version(userServiceVersion)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = if (service.pingBinder()) IDumpsysService.Stub.asInterface(service) else null
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // If a bind attempt fails outright, Shizuku can call this
            // without ever calling onServiceConnected first - if binding
            // isn't reset here too, that leaves it permanently true and
            // ensureBinding()'s guard locks out every future attempt.
            binder = null
            binding = false
        }
    }

    /**
     * Kicks off binding as early as possible (e.g. from
     * `MonitoringService.onCreate()`) so it's likely already connected by
     * the time the first T3 collector's own gate lets it run. Without this,
     * the bind only ever starts lazily from inside [dumpsys], and since
     * that's gated by an hourly watermark, the realistic first real dump
     * would be delayed by a full cycle for no reason - binding itself only
     * takes a moment. Safe to call repeatedly; [ensureBinding] is idempotent.
     */
    fun prewarm() {
        if (isPermissionGranted()) ensureBinding()
    }

    fun isPermissionGranted(): Boolean {
        try {
            if (Shizuku.isPreV11() || !Shizuku.pingBinder()) return false
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            return false
        }
    }

    /**
     * Must be called from an Activity - shows a system permission dialog.
     * Returns false if there was nothing to request (Shizuku not installed,
     * not running, or too old), so the caller can tell the user why nothing
     * happened rather than the button silently no-op'ing.
     */
    fun requestPermission(requestCode: Int): Boolean {
        if (Shizuku.isPreV11() || !Shizuku.pingBinder()) return false
        Shizuku.requestPermission(requestCode)
        return true
    }

    /**
     * Synchronous from the caller's point of view, matching the rest of
     * this app's collectors - but the first call (or any call after the
     * daemon process died) triggers an async bind and returns
     * [DumpsysResult.NotBoundYet] rather than blocking; a later cycle picks
     * up the now-bound service. "Not bound yet" is a legitimate transient
     * state, not a failure, the same distinction `read_status` needed for
     * [net.kimptoc.introspect.collector.t2.LogcatCollector].
     */
    fun dumpsys(service: String, args: Array<String> = emptyArray(), timeoutMs: Int = 5000, maxChars: Int = 200_000): DumpsysResult {
        if (!isPermissionGranted()) return DumpsysResult.NotPermitted

        val current = binder
        if (current == null) {
            ensureBinding()
            return DumpsysResult.NotBoundYet
        }

        return try {
            val text = current.dumpsys(service, args, timeoutMs, maxChars)
            if (text.startsWith("ERROR")) DumpsysResult.Error(text) else DumpsysResult.Success(text)
        } catch (e: Exception) {
            binder = null
            DumpsysResult.Error(e.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun ensureBinding() {
        if (binding || binder != null) return
        binding = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Throwable) {
            binding = false
        }
    }
}

sealed class DumpsysResult {
    data object NotPermitted : DumpsysResult()
    data object NotBoundYet : DumpsysResult()
    data class Success(val text: String) : DumpsysResult()
    data class Error(val detail: String) : DumpsysResult()
}
