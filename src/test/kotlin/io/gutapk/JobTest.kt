package io.gutapk

import io.gutapk.job.Job
import io.gutapk.job.JobEvent
import io.gutapk.job.JobState
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
