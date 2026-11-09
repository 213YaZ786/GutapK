package io.gutapk.core.apk

// hosts are the regexes of Exodus Privacy's network_signature, searched in a
// host name where the code signature is searched in a class name.
class Tracker(val name: String, val categories: List<String>, val prefixes: List<String>, val hosts: List<Regex> = emptyList())

// Exodus Privacy's signatures are class name prefixes, checked here against
// the sorted class names of the app, one binary search each.
object Trackers {
    fun detect(classes: List<String>, trackers: List<Tracker>): List<Tracker> =
        trackers.filter { t -> t.prefixes.any { hasPrefix(classes, it) } }

    // The trackers whose network signature matches one of the hosts:
    // detect_trackers_in_list with the host names for class_list and the
    // network signature for the code signature. A regex of 3 characters or
    // fewer is skipped as Exodus skips such a code signature, it would match
    // anything.
    // not ported: _compile_signatures (a Tracker holds its regexes compiled when the list is read)
    // not ported: load_trackers_signatures (TrackerList downloads and reads the list)
    // https://github.com/Exodus-Privacy/exodus-core/blob/v1/exodus_core/analysis/static_analysis.py#detect_trackers_in_list
    fun detect_trackers_in_list(class_list: List<String>, signatures: List<Tracker>): List<Tracker> {
        fun _detect_tracker(sig: Regex, tracker: Tracker, class_list: List<String>): Tracker? {
            for (clazz in class_list) {
                if (sig.containsMatchIn(clazz)) return tracker
            }
            return null
        }

        val results = mutableListOf<Tracker>()
        val args = signatures.flatMap { tracker -> tracker.hosts.filter { it.pattern.length > 3 }.map { it to tracker } }
        for ((sig, tracker) in args) {
            val res = _detect_tracker(sig, tracker, class_list)
            if (res != null && res !in results) results.add(res)
        }
        return results
    }

    private fun hasPrefix(sorted: List<String>, prefix: String): Boolean {
        val at = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        return at < sorted.size && sorted[at].startsWith(prefix)
    }
}
