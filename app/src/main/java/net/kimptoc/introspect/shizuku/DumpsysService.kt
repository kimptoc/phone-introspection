package net.kimptoc.introspect.shizuku

import androidx.annotation.Keep
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in a separate process spawned by Shizuku's server (shell UID, not
 * this app's own restricted UID), which is what gives `dumpsys` real
 * access here — unlike [net.kimptoc.introspect.collector.t2.LogcatCollector]'s
 * exec-from-our-own-process approach, which stayed sandboxed no matter what
 * permissions were granted.
 *
 * Bound once from [net.kimptoc.introspect.shizuku.ShizukuManager] and
 * reused; a no-arg constructor is required by Shizuku's server, which is
 * what actually instantiates this class.
 */
@Keep
class DumpsysService : IDumpsysService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun dumpsys(service: String, timeoutMs: Int, maxChars: Int): String {
        val process = ProcessBuilder("dumpsys", service).start()
        try {
            // Cap while reading, not after: a full dumpsys output (e.g.
            // batterystats) can be multi-MB, and materializing all of it
            // before truncating defeats the point of a cap - it's also a
            // Binder transaction limit (~1MB) waiting to happen once a
            // larger service is added. Keep draining past the cap so the
            // process doesn't block on a full pipe; just stop accumulating.
            val sb = StringBuilder(maxChars)
            val readerThread = Thread {
                val reader = InputStreamReader(process.inputStream)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (sb.length < maxChars) sb.append(buf, 0, n)
                }
            }
            readerThread.start()

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            readerThread.join(1000)

            if (!finished) return "ERROR timeout"
            if (process.exitValue() != 0) return "ERROR exit=${process.exitValue()}"
            return if (sb.length > maxChars) sb.substring(0, maxChars) else sb.toString()
        } catch (e: Exception) {
            return "ERROR ${e.javaClass.simpleName}"
        } finally {
            process.destroyForcibly()
        }
    }
}
