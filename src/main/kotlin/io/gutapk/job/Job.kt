package io.gutapk.job

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
