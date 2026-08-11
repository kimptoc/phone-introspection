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

    override fun dumpsys(service: String, args: Array<String>, timeoutMs: Int, maxChars: Int, truncated: BooleanArray): String {
        // String[] rather than a space-joined String: no parsing/splitting
        // ambiguity (a double space would've produced an empty-string arg),
        // and ProcessBuilder's list form means each element reaches exec()
        // as a single argument regardless of content - there's no shell
        // involved to reinterpret it, unlike a string that gets split and
        // handed to something shell-like.
        val command = mutableListOf("dumpsys", service)
        command += args

        // Merge stderr into stdout rather than draining it separately: an
        // undrained stderr pipe can fill and block the child mid-write,
        // which would otherwise show up as a spurious "ERROR timeout" with
        // no indication the real cause was our own missing drain (the same
        // class of bug already fixed once in LogcatCollector's exec).
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        try {
            // Cap while reading, not after: a full dumpsys output (e.g.
            // batterystats) can be multi-MB, and materializing all of it
            // before truncating defeats the point of a cap - it's also a
            // Binder transaction limit (~1MB) waiting to happen once a
            // larger service is added. Keep draining past the cap so the
            // process doesn't block on a full pipe; just stop accumulating.
            val sb = StringBuilder(maxChars)
            var readerFailed: String? = null
            val readerThread = Thread {
                try {
                    val reader = InputStreamReader(process.inputStream)
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (sb.length < maxChars) sb.append(buf, 0, n)
                    }
                } catch (e: Exception) {
                    // An uncaught exception here would hit the default
                    // uncaught-exception handler and could take down this
                    // whole daemon process, not just this thread.
                    readerFailed = e.javaClass.simpleName
                }
            }
            readerThread.start()

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            // The process is now in a terminal state either way, so its
            // stdout pipe closes and the reader thread should reach EOF
            // promptly. Thread.join() only guarantees the memory-visibility
            // needed to safely read `sb` below if the thread has actually
            // terminated by the time it returns - a short, generous bound
            // covers the real-world case without risking an indefinite hang
            // on this synchronous Binder call in the pathological one.
            readerThread.join(2000)
            if (readerThread.isAlive) return "ERROR reader_stuck"

            if (!finished) return "ERROR timeout"
            readerFailed?.let { return "ERROR $it" }
            if (process.exitValue() != 0) return "ERROR exit=${process.exitValue()}"

            // This is the only place that ever sees the pre-truncation
            // length, so it's the only place that can say for certain
            // whether the cap actually cut anything - a downstream
            // length-of-the-returned-string check can't distinguish
            // "capped" from "a genuine dump that happened to land at
            // exactly maxChars", since both produce the same-length result.
            val wasTruncated = sb.length > maxChars
            if (truncated.isNotEmpty()) truncated[0] = wasTruncated
            return if (wasTruncated) sb.substring(0, maxChars) else sb.toString()
        } catch (e: Exception) {
            return "ERROR ${e.javaClass.simpleName}"
        } finally {
            process.destroyForcibly()
        }
    }
}
