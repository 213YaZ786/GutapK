package io.gutapk.core.apk

class Tracker(val name: String, val categories: List<String>, val prefixes: List<String>)

// Exodus Privacy's signatures are class name prefixes, checked here against
// the sorted class names of the app, one binary search each.
object Trackers {
    fun detect(classes: List<String>, trackers: List<Tracker>): List<Tracker> =
        trackers.filter { t -> t.prefixes.any { hasPrefix(classes, it) } }

    private fun hasPrefix(sorted: List<String>, prefix: String): Boolean {
        val at = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        return at < sorted.size && sorted[at].startsWith(prefix)
    }
}
