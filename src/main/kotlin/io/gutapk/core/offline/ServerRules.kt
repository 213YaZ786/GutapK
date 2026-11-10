package io.gutapk.core.offline

import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

// The local game server's files, learnt from what a game said online. A game
// whose server speaks BSON commands ({name: "<command>", ...fields}, as Jungle
// Heat's does) keeps the player's game on that server: the answer to its login
// carries it whole, as named messages (character, location, ...), and most
// other commands are only answered "status". So the phone can stand in for the
// server with three things, all found in the recordings, none known in advance:
//  - the state: the named messages of the login answers, the latest of each;
//  - the samples: every command recorded with its answer, to answer alike;
//  - the rules: how each command changes the state, found by comparing two
//    logins (StateDiff) and binding what changed to the fields of the commands
//    sent in between (RelationFinder). The step a tutorial command sent is the
//    tutor field of the next login, the building a command bought is the new
//    element with its id, cellI and cellJ, the building a command upgraded is
//    the element of that id whose grade went up by one.
// The rules document is the one GameServer.java reads on the phone. The
// recordings are the cache Store.java writes, as CacheInterceptor offers it
// each answer, and OfflineBuild carries them into the APK.
// https://github.com/213YaZ786/GutapK/blob/ca70230/src/dex/java/io/gutapk/offline/Store.java
// https://github.com/213YaZ786/GutapK/blob/ca70230/src/dex/java/io/gutapk/offline/CacheInterceptor.java
// https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt
// https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py
// https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java
// not ported: __init__, findOnSymbol, findOnFields, executeOnSymbol, _findRelationType, _checkEqualityRelation, _equalRelation, _sizeRelation (RelationFinder.kt ports the relation, this file only hands it the values)
// not ported: _generateAttributeValuesForSymbol, _getAllFieldsValues, _generateConcatData, _generateDataValues, _generateSizeValues (the values are a game's fields and state, not a symbol's cells)
// not ported: compareArray, compareObjects, addRemaining, removeRemaining, computeArray, computeObject, computeRelativePath, computeUnchangedValues, getLCS, getUnchangedPart, getMatchingValuePath (StateDiff.kt ports the diff, this file reads its result)
// not ported: introduceCopyOperation, introduceMoveOperation, introduceExplicitRemoveAndAddOperation, createJsonPointerInstance, getJsonNode, getJsonNodes, getJsonPointerRoot, isAllowed, allowed, isNumber, updateCounters, updatePath, updatePathWithCounters (no JSON Pointer nor patch is written)
// not ported: executeOnFields, generateDiffs (called here, RelationFinder.kt and StateDiff.kt port them)
// not ported: Store, Entry, evictAll, get, put, remove, replace, latest, latestKey, latestOfEach, matches, key, readAll, readFrom, writeAside, writeTo, sha256, hex, url, urls, endpoint, contentType, returns (the phone's cache, only the file format readFrom reads is followed, in read below)
// not ported: intercept, handle, response, error (the phone's interceptor, which offers the answers this file reads)
// not ported: run (OfflineBuild carries into the APK the files this one writes)
object ServerRules {
    // Where the files sit in the APK, beside the recordings.
    const val SERVER = "server"
    const val RULES = "rules.bson"
    const val STATE = "state.bson"

    // Fields every command carries that say nothing about it: its number in the
    // session and the time it was sent.
    private val SKIPPED = setOf("n", "optime")

    // A login answer holds at least this many named messages.
    private const val LOGIN_MESSAGES = 5

    // A number is the time of an answer when within this many seconds of it.
    private const val TIME_SLACK = 10

    // The samples kept per command, the latest first, so rules.bson stays small.
    private const val EXAMPLES = 12

    class Recording(
        val origin: String,
        val requestMethod: String,
        val path: String,
        val query: String,
        val code: Int,
        val responseHeaders: Map<String, String>,
        val receivedResponseMillis: Long,
        val request: ByteArray,
        val body: ByteArray,
    ) {
        val contentType: String? get() = responseHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
    }

    class Learned(val origin: String, val rules: Map<String, Any?>, val state: Map<String, Any?>, val lines: List<String>)

    // The recordings of a folder as Store.java writes them: the metadata (.0),
    // the answer (.1), the request body when there was one (.2).
    // https://github.com/213YaZ786/GutapK/blob/ca70230/src/dex/java/io/gutapk/offline/Store.java#readFrom
    // not ported: Entry (a Recording holds the request body as well), requestBodyHash (read past, the body is at hand)
    fun read(folder: Path?): List<Recording> = OfflineBuild.files(folder).filter { it.fileName.toString().endsWith(OfflineBuild.ENTRY) }.mapNotNull { metadata ->
        val key = metadata.fileName.toString().removeSuffix(OfflineBuild.ENTRY)
        val body = metadata.resolveSibling("$key.1")
        val requestBody = metadata.resolveSibling("$key.2")
        runCatching {
            val source = BufferedReader(StringReader(Files.readString(metadata)))
            val origin = source.readLine()
            val requestMethod = source.readLine()
            val path = source.readLine()
            val query = source.readLine()
            source.readLine()
            val code = source.readLine().trim().toInt()
            val size = source.readLine().trim().toInt()
            val responseHeaders = LinkedHashMap<String, String>()
            for (i in 0 until size) {
                val line = source.readLine()
                val index = line.indexOf(": ")
                if (index > 0) responseHeaders[line.substring(0, index)] = line.substring(index + 2)
            }
            val receivedResponseMillis = source.readLine().trim().toLong()
            Recording(
                origin, requestMethod, path, query, code, responseHeaders, receivedResponseMillis,
                if (Files.isRegularFile(requestBody)) Files.readAllBytes(requestBody) else ByteArray(0),
                if (Files.isRegularFile(body)) Files.readAllBytes(body) else ByteArray(0),
            )
        }.getOrNull()
    }

    // One command a game sent: alone in a request, or one of a pack. Its answer is
    // the request's, or the pack answer's element at the same place.
    class Call(val time: Long, val name: String, val fields: Map<String, Any?>, val answer: Map<String, Any?>?, val type: String?) {
        // The fields a rule can be made of: numbers and texts, the command's name and its counters left out.
        val xFields: Map<String, Any?> get() = fields.filter { (k, v) -> k !in SKIPPED && k != fields.keys.first() && (v is Number || v is String) }
    }

    // A login, the time it was answered and its state: message name to message.
    private class Snapshot(val time: Long, val state: Map<String, Any?>)

    fun learn(recordings: List<Recording>): List<Learned> = recordings.groupBy { it.origin }.mapNotNull { (origin, all) -> learn(origin, all.sortedBy { it.receivedResponseMillis }) }

    private fun learn(origin: String, recordings: List<Recording>): Learned? {
        val sent = recordings.filter { it.code in 200..299 }.mapNotNull { r -> Bson.document(r.request)?.let { r to it } }
        // The command field: the first field of the documents sent, when it is text.
        val command = sent.mapNotNull { (_, doc) -> doc.entries.firstOrNull()?.takeIf { it.value is String }?.key }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val commands = sent.filter { (_, doc) -> doc[command] is String }
        if (commands.size < 3) return null

        // A pack: a command holding a list of commands, its name and the list's field.
        val packs = commands.mapNotNull { (_, doc) ->
            doc.entries.firstOrNull { (key, value) -> key != command && value is List<*> && value.isNotEmpty() && value.all { it is Map<*, *> && it[command] is String } }?.let { doc[command] as String to it.key }
        }.toMap()
        val calls = mutableListOf<Call>()
        for ((r, doc) in commands) {
            val name = doc[command] as String
            val answer = Bson.document(r.body)
            val list = packs[name]
            if (list == null) {
                calls.add(Call(r.receivedResponseMillis, name, doc, answer, r.contentType))
                continue
            }
            val answers = (answer?.get(list) as? List<*>).orEmpty()
            (doc[list] as? List<*>).orEmpty().forEachIndexed { i, inner ->
                @Suppress("UNCHECKED_CAST")
                calls.add(Call(r.receivedResponseMillis, (inner as Map<String, Any?>)[command] as String, inner, answers.getOrNull(i) as? Map<String, Any?>, r.contentType))
            }
        }

        // The login: the commands answered with most of the game's messages.
        val names = calls.associateWith { c -> c.answer?.let { messages(it, command) }.orEmpty().map { it[command] as String }.toSet() }
        val most = names.values.maxByOrNull { it.size }.orEmpty()
        if (most.size < LOGIN_MESSAGES) return null
        val login = calls.filter { c -> names[c].orEmpty().let { it.size >= LOGIN_MESSAGES && it.intersect(most).size * 2 >= most.size } }.map { it.name }.toSet()
        val logins = calls.filter { it.name in login && it.answer != null }

        // The state: the latest of each message the logins carried, the echo of a
        // login and the bare messages ({name: "status"}) left out.
        val state = LinkedHashMap<String, Map<String, Any?>>()
        for (c in logins) {
            for (m in messages(c.answer!!, command)) {
                val name = m[command] as String
                if (name !in login && m.size > 1) state[name] = m
            }
        }
        if (state.isEmpty()) return null
        val latest = logins.last()

        // The time fields: the numbers of the latest login equal to the time it was answered.
        val time = mutableListOf<String>()
        for (m in messages(latest.answer!!, command)) {
            val name = m[command] as String
            if (name in state) timePaths(m, name, latest.time / 1000, time)
        }

        // The samples, the latest first, one per distinct request. A login keeps
        // only its latest, the state fills it on the phone.
        val samples = LinkedHashMap<String, Any?>()
        for ((name, group) in calls.filter { it.answer != null && it.name !in packs }.groupBy { it.name }) {
            val found = HashSet<String>()
            samples[name] = group.reversed().filter { found.add((it.fields - SKIPPED).toString()) }.take(if (name in login) 1 else EXAMPLES).map { c ->
                linkedMapOf<String, Any?>("request" to c.fields - SKIPPED, "answer" to c.answer).also { e -> c.type?.let { e["type"] = it } }
            }
        }

        val snapshots = logins.map { c -> Snapshot(c.time, messages(c.answer!!, command).filter { (it[command] as String) in state }.associateBy { it[command] as String }) }
        val others = calls.filter { it.name !in login && it.name !in packs }
        val rules = setRules(snapshots, others) + appendRules(snapshots, others) + incrementRules(snapshots, others)
        val answerRules = answerRules(others, command)

        val document = linkedMapOf<String, Any?>("command" to command, "login" to login.toList(), "time" to time, "answers" to samples, "answerRules" to answerRules, "rules" to rules)
        packs.entries.firstOrNull()?.let { (name, list) -> document["pack"] = linkedMapOf("command" to name, "list" to list) }
        val lines = listOf(
            "$origin: a local game server learnt from ${calls.size} commands, login ${login.joinToString()}, " +
                "${state.size} messages kept, ${rules.size} rules, ${answerRules.size} answer rules, ${samples.size} commands answered alike",
        ) + (rules + answerRules).map { r -> "  ${r["command"]}: ${r["op"] ?: "answer"} ${r["path"]}" + (r["value"]?.let { v -> " = " + (if (v is Map<*, *>) "{${v.keys.joinToString()}}" else v.toString()) } ?: "") }
        return Learned(origin, document, linkedMapOf("messages" to state.values.toList()), lines)
    }

    // The named messages of an answer: the documents with a name in its lists
    // (an answer is {data: [{name: ...}, ...]}), else the answer itself.
    private fun messages(answer: Map<String, Any?>, command: String): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val listed = answer.values.filterIsInstance<List<*>>().flatten().filter { it is Map<*, *> && it[command] is String } as List<Map<String, Any?>>
        return listed.ifEmpty { if (answer[command] is String) listOf(answer) else emptyList() }
    }

    private fun timePaths(value: Any?, path: String, now: Long, out: MutableList<String>) {
        when (value) {
            is Map<*, *> -> value.forEach { (k, v) -> timePaths(v, "$path/$k", now, out) }
            is Number -> if (value !is Double && value.toLong() - now in -TIME_SLACK..TIME_SLACK) out.add(path)
        }
    }

    // The value at a path of a state, #x naming the element of a list whose id is x.
    fun valueAt(root: Any?, path: String): Any? = path.split('/').fold(root) { node, segment ->
        when {
            segment.startsWith("#") -> (node as? List<*>)?.firstOrNull { it is Map<*, *> && it["id"].toString() == segment.substring(1) }
            node is Map<*, *> -> node[segment]
            node is List<*> -> segment.toIntOrNull()?.let { node.getOrNull(it) }
            else -> null
        }
    }

    // A path with each #x a call sent as a field written #$field, so it names the
    // element of the next call. Null when an element is not one the call named.
    private fun general(path: String, call: Call): String? {
        val segments = mutableListOf<String>()
        for (segment in path.split('/')) {
            if (!segment.startsWith("#")) {
                segments.add(segment)
                continue
            }
            val field = call.xFields.entries.firstOrNull { (_, v) -> v.toString() == segment.substring(1) }?.key ?: return null
            segments.add("#$$field")
        }
        return segments.joinToString("/")
    }

    // The path a general path names for one call.
    private fun concrete(path: String, call: Call): String = path.split('/').joinToString("/") { s -> if (s.startsWith("#$")) "#" + call.fields[s.substring(2)] else s }

    // The commands sent between each two logins, with the diffs of the two.
    private class Interval(val source: Snapshot, val target: Snapshot, val between: List<Call>, val diffs: List<StateDiff.Diff>)

    private fun intervals(snapshots: List<Snapshot>, calls: List<Call>) = snapshots.zipWithNext().map { (a, b) ->
        Interval(a, b, calls.filter { it.time > a.time && it.time <= b.time }, StateDiff.asDiffs(a.state, b.state))
    }

    // Whether a path lies under an element or a message added whole by the diffs.
    private fun added(path: String, diffs: List<StateDiff.Diff>): Boolean =
        diffs.any { d -> d.operation == StateDiff.Operation.ADD && (d.value is Map<*, *> || d.value is List<*>) && path.startsWith(d.path + "/") }

    // set rules: a field of the last call of a command, between two logins, is the
    // value of a path in the second one, and so between every two logins it was
    // sent between (RelationFinder's x_values and y_values, one pair per interval).
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    // not ported: relation_fcts, _sizeRelation (equality only)
    private fun setRules(snapshots: List<Snapshot>, calls: List<Call>): List<Map<String, Any?>> {
        val intervals = intervals(snapshots, calls)
        val results = mutableListOf<Map<String, Any?>>()
        for (name in calls.map { it.name }.distinct()) {
            val found = intervals.filter { i -> i.between.any { it.name == name } }
            val fieldsValues = found.map { i -> i.between.last { it.name == name } }
            // The paths that changed to a value one of the calls sent.
            val relations = LinkedHashSet<Pair<String, String>>()
            found.forEachIndexed { k, i ->
                for (d in i.diffs) {
                    if (d.operation == StateDiff.Operation.REMOVE || added(d.path, i.diffs)) continue
                    for ((xField, x) in fieldsValues[k].xFields) {
                        if (RelationFinder.equalRelation(x, d.value) && !(x is Number && x.toDouble() == 0.0) && x != "") general(d.path, fieldsValues[k])?.let { relations.add(xField to it) }
                    }
                }
            }
            for ((xField, yField) in relations) {
                val xValues = fieldsValues.map { it.fields[xField] }
                val yValues = found.mapIndexed { k, i -> valueAt(i.target.state, concrete(yField, fieldsValues[k])) }
                // Seen once, only a text is taken: a number met once is as likely chance.
                if (RelationFinder.executeOnFields(xValues, yValues, allowConstant = found.size == 1 && xValues.single() is String)) {
                    results.add(linkedMapOf("command" to name, "op" to "set", "path" to yField, "value" to "$$xField"))
                }
            }
        }
        return results.distinctBy { it["command"] to it["path"] }
    }

    // append rules: an element the diffs ADD to a list, whose id and at least one
    // more field are those a call sent, is what that command adds. Its other
    // numbers within the minutes after the call's time are that time.
    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#addRemaining
    // not ported: pos, targetIdx, deepCopy (an element is named by its id, the rule is a new document)
    private fun appendRules(snapshots: List<Snapshot>, calls: List<Call>): List<Map<String, Any?>> {
        val results = LinkedHashMap<Pair<String, String>, Map<String, Any?>>()
        for (i in intervals(snapshots, calls)) {
            for (d in i.diffs) {
                val jsonNode = d.value as? Map<*, *> ?: continue
                if (d.operation != StateDiff.Operation.ADD || !d.path.substringAfterLast('/').startsWith("#")) continue
                val id = jsonNode["id"].toString()
                val matches = { c: Call -> c.xFields.count { (k, v) -> RelationFinder.equalRelation(v, jsonNode[k]) } }
                val call = i.between.filter { c -> c.xFields.values.any { it.toString() == id } }.maxByOrNull(matches) ?: continue
                if (matches(call) < 2) continue
                val list = general(d.path.substringBeforeLast("/#"), call) ?: continue
                val optime = (call.fields["optime"] as? Number)?.toLong()
                results.putIfAbsent(call.name to list, linkedMapOf("command" to call.name, "op" to "append", "path" to list, "value" to template(jsonNode, call, optime)))
            }
        }
        return results.values.toList()
    }

    // An element as a rule's value: a field the call sent with the same value is
    // $field, a time just after the call's is $optime, the rest stays as seen.
    private fun template(value: Any?, call: Call, optime: Long?, key: String? = null): Any? = when {
        value is Map<*, *> -> value.entries.associateTo(LinkedHashMap<String, Any?>()) { (k, v) -> k.toString() to template(v, call, optime, k.toString()) }
        value is List<*> -> value.map { template(it, call, optime) }
        key != null && call.xFields[key]?.let { RelationFinder.equalRelation(it, value) } == true -> "$$key"
        optime != null && value is Number && value !is Double && value.toLong() in optime - TIME_SLACK..optime + 600 -> "\$optime"
        else -> value
    }

    // increment rules: a number of an element a command named, which went up by as
    // many as that command was sent between the two logins. The first command
    // that explains it takes it: a building's upgrade, not the diamonds that hurry it.
    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#generateDiffs
    // not ported: Operation.TEST, Diff.generateDiff (the diff is read, not written)
    private fun incrementRules(snapshots: List<Snapshot>, calls: List<Call>): List<Map<String, Any?>> {
        val results = LinkedHashMap<Pair<String, String>, Map<String, Any?>>()
        for (i in intervals(snapshots, calls)) {
            for (d in i.diffs) {
                val target = d.value as? Number ?: continue
                if (target is Double || d.operation == StateDiff.Operation.REMOVE || added(d.path, i.diffs)) continue
                val source = d.srcValue
                if (source != null && (source !is Number || source is Double)) continue
                val id = d.path.split('/').lastOrNull { it.startsWith("#") }?.substring(1) ?: continue
                val related = i.between.filter { c -> c.xFields.values.any { it.toString() == id } }.groupBy { it.name }
                val first = related.entries.firstOrNull { (_, group) ->
                    val start = (source as? Number)?.toLong() ?: (target.toLong() - group.size)
                    target.toLong() - start == group.size.toLong() && start >= 0
                } ?: continue
                val call = first.value.first()
                val path = general(d.path, call) ?: continue
                val relation = linkedMapOf<String, Any?>("command" to call.name, "op" to "increment", "path" to path)
                if (source == null) relation["default"] = (target.toLong() - first.value.size).toInt()
                results.putIfAbsent(call.name to path, relation)
            }
        }
        // Kept only when it holds between every two logins the command named an
        // element in, as RelationFinder keeps a relation that holds for every pair:
        // each element named went up by as many times as it was named. Where
        // another command named the same element too, the interval says nothing.
        val intervals = intervals(snapshots, calls)
        return results.values.filter { relation ->
            val path = relation["path"] as String
            intervals.all { i ->
                i.between.filter { it.name == relation["command"] }.groupBy { concrete(path, it) }.all { (p, group) ->
                    val shared = results.values.any { o -> o !== relation && o["path"] == path && i.between.any { it.name == o["command"] && concrete(path, it) == p } }
                    val target = valueAt(i.target.state, p) as? Number
                    val source = valueAt(i.source.state, p) as? Number ?: relation["default"] as? Number
                    shared || target == null || source == null || target.toLong() - source.toLong() == group.size.toLong()
                }
            }
        }
    }

    // answer rules: a path of a command's answer that is, in every sample, the
    // value of one field of its request, and not the same in all of them.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    private fun answerRules(calls: List<Call>, command: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for ((name, group) in calls.filter { it.answer != null }.groupBy { it.name }) {
            for (xField in group.flatMap { it.xFields.keys }.toSet()) {
                val xValues = group.map { it.fields[xField] }
                val paths = group.map { c -> getValues(c.answer!!).filter { (p, v) -> p != command && RelationFinder.equalRelation(c.fields[xField], v) }.keys }.reduce { x, y -> x intersect y }
                for (yField in paths) {
                    if (RelationFinder.executeOnFields(xValues, group.map { valueAt(it.answer, yField) })) results.add(linkedMapOf("command" to name, "path" to yField, "value" to "$$xField"))
                }
            }
        }
        return results
    }

    // The paths of an answer through its documents, to the values at their ends.
    private fun getValues(value: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        fun walk(v: Any?, path: String) {
            if (v is Map<*, *>) v.forEach { (k, x) -> walk(x, if (path.isEmpty()) k.toString() else "$path/$k") } else if (path.isNotEmpty()) out[path] = v
        }
        walk(value, "")
        return out
    }

    // The files the APK carries for the local server.
    fun files(learned: Learned): Map<String, ByteArray> = mapOf(RULES to Bson.encode(learned.rules), STATE to Bson.encode(learned.state))
}
