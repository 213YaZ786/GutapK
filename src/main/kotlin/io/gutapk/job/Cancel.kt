package io.gutapk.job

import io.gutapk.tools.CancelledByUser
import java.util.concurrent.atomic.AtomicBoolean

// A child can print nothing for minutes, an adb push or a quiet engine for
// instance, so cancel is not left to the reading loop. A watcher ends the
// process and its children as soon as cancel is asked, the body then sees
// its stream close and the job ends as cancelled, not as failed.
object CancelWatch {
    private const val POLL_MS = 200L

    fun <T> guard(process: Process, cancelled: () -> Boolean, body: () -> T): T {
        val stopped = AtomicBoolean(false)
        val watcher = Thread {
            while (process.isAlive) {
                if (cancelled()) {
                    stopped.set(true)
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    break
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        watcher.isDaemon = true
        watcher.start()
        try {
            val result = body()
            if (stopped.get()) throw CancelledByUser()
            return result
        } catch (e: Exception) {
            if (stopped.get() || e is CancelledByUser) throw CancelledByUser()
            throw e
        } finally {
            watcher.interrupt()
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
