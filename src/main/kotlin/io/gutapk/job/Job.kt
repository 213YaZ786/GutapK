package io.gutapk.job

import io.gutapk.tools.CancelledByUser
import io.gutapk.tools.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface JobEvent {
    data class Step(val label: String, val index: Int, val total: Int) : JobEvent
    data class Line(val text: String) : JobEvent
    data class Progress(val done: Long, val total: Long) : JobEvent
    data class Finished(val ok: Boolean, val message: String) : JobEvent
}

enum class JobState { IDLE, RUNNING, CANCELLING, DONE, FAILED, CANCELLED }

interface JobSink {
    fun emit(event: JobEvent)
}

data class JobView(
    val title: String,
    val state: JobState,
    val step: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val message: String = "",
) {
    val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    val active: Boolean get() = state == JobState.RUNNING || state == JobState.CANCELLING
}

class Job internal constructor(val title: String) : JobSink {
    private val _view = MutableStateFlow(JobView(title, JobState.IDLE))
    val view: StateFlow<JobView> = _view

    @Volatile
    var cancelRequested: Boolean = false
        private set

    // What a successful job hands back, a path for instance. It becomes the
    // message of the finished view.
    @Volatile
    var result: String = ""

    fun cancel() {
        cancelRequested = true
        _view.value = _view.value.copy(state = JobState.CANCELLING)
    }

    // Every line goes to the run log, so a failed download can be explained
    // after the fact without having watched it.
    override fun emit(event: JobEvent) {
        val v = _view.value
        when (event) {
            is JobEvent.Step -> {
                RunLog.line("[$title] step ${event.index}/${event.total} ${event.label}")
                _view.value = v.copy(step = event.label)
            }
            is JobEvent.Line -> RunLog.line("[$title] ${event.text}")
            is JobEvent.Progress -> _view.value = v.copy(done = event.done, total = event.total)
            is JobEvent.Finished -> {
                RunLog.line("[$title] finished ok=${event.ok} ${event.message}")
                _view.value = v.copy(
                    state = if (event.ok) JobState.DONE else JobState.FAILED,
                    message = event.message,
                )
            }
        }
    }

    internal fun run(work: (Job) -> Unit) {
        _view.value = _view.value.copy(state = JobState.RUNNING)
        RunLog.line("[$title] started")
        try {
            work(this)
            emit(JobEvent.Finished(true, result))
        } catch (e: CancelledByUser) {
            RunLog.line("[$title] cancelled")
            _view.value = _view.value.copy(state = JobState.CANCELLED)
        } catch (e: Exception) {
            emit(JobEvent.Finished(false, e.message ?: e.javaClass.simpleName))
        }
    }
}

// One job at a time. Two downloads competing for the same folder is the
// kind of race that corrupts an install, and nothing needs parallelism yet.
object JobQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _current = MutableStateFlow<Job?>(null)
    val current: StateFlow<Job?> = _current

    fun start(title: String, work: (Job) -> Unit): Job? {
        val running = _current.value
        if (running != null && running.view.value.active) return null
        val job = Job(title)
        _current.value = job
        scope.launch { job.run(work) }
        return job
    }
}
