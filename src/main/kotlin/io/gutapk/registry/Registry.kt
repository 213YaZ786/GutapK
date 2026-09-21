package io.gutapk.registry

enum class Source { REPO, BUILD, APK, DEVICE }

interface Feature {
    val id: String
    val label: String
    val sources: Set<Source>
}

object Registry {
    private val entries = LinkedHashMap<String, Feature>()

    fun register(feature: Feature) {
        require(entries.put(feature.id, feature) == null) { "duplicate feature id: ${feature.id}" }
    }

    fun clear() = entries.clear()

    fun all(): List<Feature> = entries.values.toList()

    fun forSource(source: Source): List<Feature> = entries.values.filter { source in it.sources }
}
