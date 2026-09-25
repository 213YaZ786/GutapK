package io.gutapk

import io.gutapk.job.CancelWatch
import io.gutapk.job.Job
import io.gutapk.job.JobEvent
import io.gutapk.job.JobState
import kotlin.test.Test
import io.gutapk.tools.CancelledByUser
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Fix 4 of the 2026-09-24 review: a progress read before Cancel and written
// after it put the job back to RUNNING.
class JobTest {

    @Test
    fun progressAfterCancelKeepsCancelling() {
        val job = Job("t")
        job.emit(JobEvent.Step("download", 1, 3))
        job.cancel()
        job.emit(JobEvent.Progress(5, 10))
        job.emit(JobEvent.Step("check", 2, 3))
        val v = job.view.value
        assertEquals(JobState.CANCELLING, v.state)
        assertEquals(5L, v.done)
        assertEquals("check", v.step)
    }

    @Test
    fun manyThreadsLoseNoCancel() {
        val job = Job("t")
        val threads = (1..8).map { n ->
            Thread { repeat(2000) { job.emit(JobEvent.Progress(it.toLong() + n, 100_000)) } }
        }
        threads.forEach { it.start() }
        job.cancel()
        threads.forEach { it.join() }
        assertEquals(JobState.CANCELLING, job.view.value.state)
    }

    // A child that prints nothing still stops within a moment of cancel,
    // and the job reads as cancelled, not as a failed exit code.
    @Test
    fun cancelEndsAQuietChild() {
        val process = ProcessBuilder("sleep", "30").start()
        val asked = System.currentTimeMillis() + 300
        val started = System.currentTimeMillis()
        assertFailsWith<CancelledByUser> {
            CancelWatch.guard(process, { System.currentTimeMillis() > asked }) {
                val code = process.waitFor()
                if (code != 0) throw java.io.IOException("exited with $code")
            }
        }
        assertTrue(System.currentTimeMillis() - started < 5000)
        assertFalse(process.isAlive)
    }

    @Test
    fun guardPassesResultsAndFailures() {
        val ok = ProcessBuilder("true").start()
        assertEquals(0, CancelWatch.guard(ok, { false }) { ok.waitFor() })
        val bad = ProcessBuilder("false").start()
        assertFailsWith<java.io.IOException> {
            CancelWatch.guard(bad, { false }) { if (bad.waitFor() != 0) throw java.io.IOException("exit") }
        }
    }
}
