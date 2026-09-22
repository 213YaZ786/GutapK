package io.gutapk.tools

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import java.io.IOException
import java.nio.file.Path

// Makes a tool ready to run, from inside a job. If it is installed and
// verified, its program path is returned at once. If it is missing or was
// changed, the latest release is looked up and installed first, then
// verified. Everything is said in the job log, so the download is never
// silent even when it happens as a side effect of another action.
object Resolve {
    fun tool(root: Path, spec: ToolSpec, sink: JobSink, cancelled: () -> Boolean): Path {
        val status = Installer.status(root, spec)
        if (status is ToolStatus.Installed && Installer.verify(root, spec)) {
            return Installer.entry(root, spec, status.version)
        }

        sink.emit(JobEvent.Line("${spec.id} is not ready, looking up the latest release"))
        val release = Releases.latest(spec)
        sink.emit(JobEvent.Line("${spec.id} ${release.version} from ${release.url}"))
        Installer.install(root, spec, release, sink, cancelled)

        val now = Installer.status(root, spec)
        if (now !is ToolStatus.Installed || !Installer.verify(root, spec)) {
            throw IOException("${spec.id} could not be made ready")
        }
        return Installer.entry(root, spec, now.version)
    }
}
