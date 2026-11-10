package io.gutapk.core.offline

import io.gutapk.tools.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

// The progress the local game server keeps on the phone (state.bson), as the
// Admin shows it to be balanced: the resources, the elements of the lists (the
// buildings, at their grade) and the player's numbers. Nothing is known of a
// game in advance: the resources are the names the game's data files price
// things in (a document of upper case names and numbers, {"GOLD": 900}) that
// the state holds a number by (CAPACITY_GOLD), an element is named by the data
// document whose id one of its fields holds. The values are read and written
// with the paths valueAt reads, #x naming the element whose id is x.
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
// not ported: learn, read, files (the state is read from the phone and written back to it, nothing is learnt)
// not ported: valueAt, values (the walk below keeps its own path and its element, with changes it walks the same segments)
object GameState {
    enum class Group { RESOURCES, ELEMENTS, PLAYER }

    // One value the Admin shows: its group, what it is (the resource, the element's
    // name), the path in the state and the value the phone holds, null when the
    // element has none yet. For the Admin's sections: section is what the value
    // belongs to (the resource, the state's name of the elements' list, the
    // message), name the element's or the holder's name in the player's language
    // and key the number's own name.
    data class Field(
        val group: Group,
        val label: String,
        val path: String,
        val value: Any?,
        val section: String = "",
        val name: String = "",
        val key: String = "",
    )

    // The data files read at most, a game's files are a few megabytes each.
    private const val DATA_LIMIT = 8_000_000

    // The state as the phone sends it: { messages: [ {<name field>: "...", ...} ] }.
    fun read(bytes: ByteArray): Map<String, Any?>? = Bson.document(bytes)?.takeIf { it["messages"] is List<*> }

    fun write(state: Map<String, Any?>): ByteArray = Bson.encode(state)

    // The field naming each message: the one every message holds as a text, a different one each.
    private fun nameField(messages: List<Map<*, *>>): String? = messages.firstOrNull()?.keys?.map { it.toString() }?.firstOrNull { k ->
        messages.all { it[k] is String } && messages.map { it[k] }.toSet().size == messages.size
    }

    @Suppress("UNCHECKED_CAST")
    private fun messages(state: Map<String, Any?>): List<Map<String, Any?>> = (state["messages"] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>()

    // The game's data files: the JSON of the APK's assets and the JSON answers recorded.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#read
    fun statics(apk: Path?, recordings: List<ServerRules.Recording>): List<Any?> {
        val out = mutableListOf<Any?>()
        if (apk != null && Files.isRegularFile(apk)) {
            ZipFile(apk.toFile()).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (!entry.name.startsWith("assets/") || !entry.name.endsWith(".json") || entry.size !in 2..DATA_LIMIT) continue
                    val text = zip.getInputStream(entry).use { String(it.readAllBytes(), Charsets.UTF_8) }
                    runCatching { Json.parse(text.trimStart('﻿')) }.getOrNull()?.let { out.add(it) }
                }
            }
        }
        for (r in recordings) {
            if (r.requestMethod != "GET" || r.body.size !in 2..DATA_LIMIT) continue
            val text = String(r.body, Charsets.UTF_8).trimStart()
            if (text.startsWith("[") || text.startsWith("{")) runCatching { Json.parse(text) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    // The resources: the names of the documents the data prices things in, upper
    // case names with numbers only, met in several documents.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    fun resources(statics: List<Any?>): Set<String> {
        val count = HashMap<String, Int>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    val keys = node.keys.map { it.toString() }
                    if (keys.isNotEmpty() && keys.all { k -> k.matches(Regex("[A-Z][A-Z0-9]*")) } && node.values.all { it is Number }) keys.forEach { count[it] = (count[it] ?: 0) + 1 }
                    node.values.forEach { walk(it) }
                }
                is List<*> -> node.forEach { walk(it) }
            }
        }
        statics.forEach { walk(it) }
        return count.filterValues { it >= 2 }.keys
    }

    // The lists of named data documents: [{"id": 5, "name": "...", ...}].
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    internal fun named(statics: List<Any?>): List<List<Map<*, *>>> {
        val out = mutableListOf<List<Map<*, *>>>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> node.values.forEach { walk(it) }
                is List<*> -> if (node.isNotEmpty() && node.all { it is Map<*, *> && it["id"] != null && it["name"] is String }) out.add(node.map { it as Map<*, *> }) else node.forEach { walk(it) }
            }
        }
        statics.forEach { walk(it) }
        return out
    }

    // How the elements of a list are named: the field every element holds the id
    // of a document of one data list in, the list whose documents share the most
    // field names with the elements (a building and its article both have parameters).
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    internal fun naming(elements: List<Map<*, *>>, lists: List<List<Map<*, *>>>): Pair<String, Map<String, String>>? {
        val keys = elements.flatMap { it.keys }.map { it.toString() }.toSet()
        var best: Pair<String, Map<String, String>>? = null
        var bestShared = -1
        for (list in lists) {
            val ids = list.associate { it["id"].toString() to it["name"] as String }
            val shared = list.flatMap { it.keys }.map { it.toString() }.toSet().intersect(keys).size
            for (k in keys) {
                // A list whose documents hold the field too is another list of such elements (a
                // location of the data), not the documents the field names.
                if (k == "id" || list.any { it.containsKey(k) } || !elements.all { e -> e[k].let { (it is Number || it is String) && ids.containsKey(it.toString()) } }) continue
                if (shared > bestShared) {
                    best = k to ids
                    bestShared = shared
                }
            }
        }
        return best
    }

    // How the Admin names the elements of a list, null for none: a field holding
    // the id of a document (a name ending in id, as articleId, named the most
    // things right on Jungle Heat's save), else the element's own id when one data
    // list alone holds them all (the heroes, whose grade 1 is the id of many
    // documents). A field every element holds the same value of names nothing.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#naming
    // not ported: naming (stays as it is beside this one: the learnt rules lean on it)
    internal fun shownNaming(elements: List<Map<*, *>>, lists: List<List<Map<*, *>>>): Pair<String, Map<String, String>>? {
        val keys = elements.flatMap { it.keys }.map { it.toString() }.toSet()
        var best: Pair<String, Map<String, String>>? = null
        var bestShared = -1
        for (list in lists) {
            val ids = list.associate { it["id"].toString() to it["name"] as String }
            val shared = list.flatMap { it.keys }.map { it.toString() }.toSet().intersect(keys).size
            for (k in keys) {
                if (k == "id" || !k.endsWith("id", ignoreCase = true) || list.any { it.containsKey(k) }) continue
                if (!elements.all { e -> e[k].let { (it is Number || it is String) && ids.containsKey(it.toString()) } }) continue
                if (elements.size > 1 && elements.map { it[k].toString() }.toSet().size == 1) continue
                if (shared > bestShared) {
                    best = k to ids
                    bestShared = shared
                }
            }
        }
        if (best != null) return best
        val owners = lists.filter { list ->
            elements.all { e -> list.any { it["id"].toString() == e["id"].toString() } } && list.none { d -> keys.any { it != "id" && d.containsKey(it) } }
        }.map { list -> list.associate { it["id"].toString() to it["name"] as String } }.distinct()
        return owners.singleOrNull()?.let { "id" to it }
    }

    // The words of the game's own localisation: a text file of the APK whose lines
    // are the names the data uses (Jungle Heat's localization.lid, "LID Gold
    // Mine"), and beside it a file of as many lines in the player's language
    // (localization_fr.lid, "Mine d'or"), else in English, line for line. Empty
    // when the game has none: the names are shown as the data writes them.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#statics
    // not ported: statics (its JSON parse and the recordings: the words are lines of the APK's own files)
    fun localisation(apk: Path?, names: Set<String>, language: String): Map<String, String> {
        if (apk == null || !Files.isRegularFile(apk) || names.isEmpty()) return emptyMap()
        ZipFile(apk.toFile()).use { zip ->
            val texts = LinkedHashMap<String, List<String>>()
            for (entry in zip.entries().asSequence()) {
                if (!entry.name.startsWith("assets/") || entry.isDirectory || entry.size !in 2..DATA_LIMIT) continue
                val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                if (bytes.take(4096).any { it == 0.toByte() }) continue
                texts[entry.name] = String(bytes, Charsets.UTF_8).lines()
            }
            val keys = texts.maxByOrNull { (_, lines) -> lines.count { it in names } } ?: return emptyMap()
            if (keys.value.count { it in names } < 2) return emptyMap()
            val folder = keys.key.substringBeforeLast('/')
            val siblings = texts.filter { (name, lines) -> name != keys.key && name.substringBeforeLast('/') == folder && lines.size == keys.value.size }
            fun spoken(code: String) = siblings.entries.firstOrNull { (name, _) ->
                name.substringAfterLast('/').substringBeforeLast('.').lowercase().let { it.endsWith("_$code") || it.endsWith("-$code") || it.endsWith(".$code") }
            }?.value
            val words = spoken(language) ?: spoken("en") ?: return emptyMap()
            return keys.value.indices.filter { keys.value[it] in names && words[it].isNotBlank() }.associate { keys.value[it] to words[it] }
        }
    }

    // The data's name of each resource: the documents of one list each naming a
    // resource in a text field of their own (currency.json's {"type": "GOLD",
    // "name": "LID Gold"}), the list naming the most of them. A pack of gold, one
    // of several documents naming GOLD, names none.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#resources
    // not ported: resources, walk (the count of the documents a name is met in: the resources are given)
    fun resourceNames(statics: List<Any?>, resources: Set<String>): Map<String, String> {
        var best = emptyMap<String, String>()
        for (list in named(statics)) {
            val keys = list.flatMap { it.keys }.map { it.toString() }.toSet()
            for (k in keys) {
                val held = list.filter { it[k] is String && it[k] in resources }
                if (held.map { it[k] }.toSet().size != held.size) continue
                if (held.size > best.size) best = held.associate { it[k] as String to it["name"] as String }
            }
        }
        return if (best.size >= 2) best else emptyMap()
    }

    // The values of the state the Admin shows, by group: a resource wherever the
    // player holds it, the numbers of each element, the numbers of the messages.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    // words: the game's localisation (localisation), a data name to the player's word.
    fun fields(state: Map<String, Any?>, statics: List<Any?>, words: Map<String, String> = emptyMap()): List<Field> {
        val all = messages(state)
        val nameField = nameField(all) ?: return emptyList()
        val lists = named(statics)
        val out = mutableListOf<Field>()
        // A key several messages hold their content under (data) says what nothing
        // is, as nameField reads the messages' keys: the section of a list is the
        // last other name of its path that is not its message's (Jungle Heat's
        // location/second_location/location is the second base), else the message.
        // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#nameField
        val containers = all.flatMap { m -> m.keys.filter { it != nameField } }.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
        fun section(path: String): String {
            val message = path.substringBefore('/')
            return path.split('/').filter { s -> !s.startsWith("#") && s.toIntOrNull() == null && s !in containers && s != message }.lastOrNull() ?: message
        }
        // The resources the player holds: a name the data prices in that a number of the state is named by.
        val held = mutableSetOf<String>()
        fun keys(node: Any?) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) -> if (v is Number) held.add(k.toString()) else keys(v) }
                is List<*> -> node.forEach { keys(it) }
            }
        }
        keys(state)
        val resources = resources(statics).filter { r -> held.any { k -> k == r || k.endsWith("_$r") } }
        fun resource(key: String): String? = resources.firstOrNull { r -> key == r || key.endsWith("_$r") }
        val resourceWords = resourceNames(statics, resources.toSet())
        // By the element itself: a second base reuses the ids of the first.
        val labels = java.util.IdentityHashMap<Map<*, *>, String>()
        val names = java.util.IdentityHashMap<Map<*, *>, String>()
        fun label(element: Map<*, *>): String = labels[element] ?: "#${element["id"]}"
        fun walk(node: Any?, path: String, element: Map<*, *>?, listed: Boolean) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) -> walk(v, "$path/$k", element, listed) }
                is List<*> -> {
                    val byId = node.isNotEmpty() && node.all { it is Map<*, *> && it["id"] != null }
                    if (!byId) {
                        node.forEachIndexed { index, item -> walk(item, "$path/$index", element, listed || element == null) }
                        return
                    }
                    val elements = node.map { it as Map<*, *> }
                    val naming = shownNaming(elements, lists)
                    for (e in elements) {
                        val word = naming?.second?.get(e[naming.first].toString())?.let { words[it] ?: it }
                        if (word != null) names[e] = word
                        labels[e] = word?.let { "$it #${e["id"]}" } ?: "#${e["id"]}"
                    }
                    // The numbers of an element itself, its name and its times left out. A
                    // number other elements hold and this one not yet (a building at its
                    // first grade has none) is offered empty, to be given.
                    val numbers = elements.flatMap { e -> e.filter { (k, v) -> v is Number && k != "id" && k != naming?.first && v.toLong() < 1_000_000_000L }.keys }.map { it.toString() }.toSet()
                    for (e in elements) {
                        val at = "$path/#${e["id"]}"
                        walk(e, at, e, listed)
                        for (k in numbers) out.add(Field(Group.ELEMENTS, label(e) + " · " + k, "$at/$k", e[k], section(path), names[e] ?: "", k))
                    }
                }
                is Number -> {
                    val key = path.substringAfterLast('/')
                    val r = resource(key)
                    when {
                        // A resource in a list that is not the player's elements (the prizes of a tourney) is not held.
                        r != null && !listed -> out.add(
                            Field(Group.RESOURCES, r + " · " + (element?.let { label(it) + " · " } ?: "") + key, path, node, resourceWords[r]?.let { words[it] ?: it } ?: r, element?.let { names[it] ?: label(it) } ?: "", key),
                        )
                        element == null -> out.add(Field(Group.PLAYER, path, path, node, path.substringBefore('/'), "", path.split('/').drop(1).filter { it !in containers }.joinToString("/")))
                    }
                }
            }
        }
        for (message in all) {
            val name = message[nameField] as String
            message.forEach { (k, v) -> if (k != nameField) walk(v, "$name/$k", null, false) }
        }
        return out
    }

    // The parameters of a data document: the numbers of its lists of one name
    // each ([{"HEALTH": 140}]), the names in lower case.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#resources
    // not ported: resources (the count of the documents a name is met in, one document is read here)
    private fun parameters(document: Map<*, *>): Map<String, Number> {
        val out = LinkedHashMap<String, Number>()
        for (v in document.values) {
            if (v is List<*>) v.filterIsInstance<Map<*, *>>().filter { it.size == 1 }.forEach { p -> p.forEach { (pk, pv) -> if (pv is Number) out[pk.toString().lowercase()] = pv } }
        }
        return out
    }

    // instances: a list of the state whose elements are each the instance of a
    // document of one data list, the element's id the document's and one of its
    // numbers the document's parameter of that name (a hero's health, its
    // HEALTH). Each comes with an element for every document of that data list,
    // made like the first element: the document's id, those numbers the document's.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#naming
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#fields
    // not ported: naming (an instance is named by its id, not by a field holding a document's id)
    // not ported: fields, keys, label, resource (the labels, the groups and the resources, only the lists are walked)
    fun instances(state: Map<String, Any?>, statics: List<Any?>): List<Map<String, Any?>> {
        val all = messages(state)
        val nameField = nameField(all) ?: return emptyList()
        val lists = named(statics)
        val out = mutableListOf<Map<String, Any?>>()
        fun walk(node: Any?, path: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) -> walk(v, "$path/$k") }
                is List<*> -> {
                    if (node.isEmpty() || !node.all { it is Map<*, *> && it["id"] != null }) {
                        node.forEachIndexed { index, item -> walk(item, "$path/$index") }
                        return
                    }
                    val elements = node.map { it as Map<*, *> }
                    val first = elements.first()
                    for (documents in lists) {
                        if (!elements.all { e -> documents.any { it["id"].toString() == e["id"].toString() } }) continue
                        // As naming: documents holding the elements' fields are another list of such
                        // elements (the second base's layout in the data), not what they are made from.
                        if (documents.any { d -> first.keys.any { it != "id" && d.containsKey(it) } }) continue
                        // A number every element holds as its own document gives it.
                        val shared = first.keys.map { it.toString() }.filter { k ->
                            k != "id" && elements.all { e ->
                                val own = parameters(documents.first { it["id"].toString() == e["id"].toString() })
                                e[k] is Number && own[k.lowercase()]?.toString() == e[k].toString()
                            }
                        }
                        if (shared.isEmpty()) continue
                        val entries = documents.map { d ->
                            val given = parameters(d)
                            LinkedHashMap<String, Any?>().apply {
                                first.forEach { (k, v) -> put(k.toString(), v) }
                                // In the type the element holds, an int32 when it does.
                                put("id", if (first["id"] is Int) (d["id"] as Number).toInt() else d["id"])
                                for (k in shared) given[k.lowercase()]?.let { put(k, if (first[k] is Int) it.toInt() else it) }
                            }
                        }
                        out.add(linkedMapOf("path" to path, "entries" to entries))
                        return
                    }
                    elements.forEach { e -> walk(e, "$path/#${e["id"]}") }
                }
            }
        }
        for (message in all) {
            val name = message[nameField] as String
            message.forEach { (k, v) -> if (k != nameField) walk(v, "$name/$k") }
        }
        return out
    }

    // The state with the elements of the instances it lacks added to their lists.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#with
    // not ported: the type of a number changed (the elements are added whole, in the types instances gave)
    fun added(state: Map<String, Any?>, instances: List<Map<String, Any?>>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val copy = Bson.document(Bson.encode(state)) as MutableMap<String, Any?>
        val all = messages(copy)
        val nameField = nameField(all) ?: return copy
        for (instance in instances) {
            val segments = instance["path"].toString().split('/')
            var node: Any? = all.firstOrNull { it[nameField] == segments.first() }
            for (segment in segments.drop(1)) {
                node = when {
                    segment.startsWith("#") -> (node as? List<*>)?.firstOrNull { it is Map<*, *> && it["id"].toString() == segment.substring(1) }
                    node is Map<*, *> -> node[segment]
                    node is List<*> -> segment.toIntOrNull()?.let { node.getOrNull(it) }
                    else -> null
                }
            }
            @Suppress("UNCHECKED_CAST")
            val elements = node as? MutableList<Any?> ?: continue
            for (entry in (instance["entries"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
                if (elements.none { it is Map<*, *> && it["id"].toString() == entry["id"].toString() }) elements.add(entry)
            }
        }
        return copy
    }

    // The state with the values changed, each at its path, in the type the phone held.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    fun with(state: Map<String, Any?>, changes: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val copy = Bson.document(Bson.encode(state)) as MutableMap<String, Any?>
        val all = messages(copy)
        val nameField = nameField(all) ?: return copy
        for ((path, value) in changes) {
            val segments = path.split('/')
            var node: Any? = all.firstOrNull { it[nameField] == segments.first() }
            for (segment in segments.drop(1).dropLast(1)) {
                node = when {
                    segment.startsWith("#") -> (node as? List<*>)?.firstOrNull { it is Map<*, *> && it["id"].toString() == segment.substring(1) }
                    node is Map<*, *> -> node[segment]
                    node is List<*> -> segment.toIntOrNull()?.let { node.getOrNull(it) }
                    else -> null
                }
            }
            @Suppress("UNCHECKED_CAST")
            val parent = node as? MutableMap<String, Any?> ?: continue
            val old = parent[segments.last()]
            // A number the element had not yet takes the type of the numbers around it, an int32 that fits.
            val whole = value is Long || value is Int
            parent[segments.last()] = if ((old is Int || old == null) && whole && (value as Number).toLong() in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
        }
        return copy
    }
}
