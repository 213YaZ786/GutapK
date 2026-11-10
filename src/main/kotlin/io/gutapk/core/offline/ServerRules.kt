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

    // The game's data files are its APK's (its assets) and the answers recorded.
    fun learn(recordings: List<Recording>, apk: Path? = null): List<Learned> {
        val statics = GameState.statics(apk, recordings)
        return recordings.groupBy { it.origin }.mapNotNull { (origin, all) -> learn(origin, all.sortedBy { it.receivedResponseMillis }, statics) }
    }

    private fun learn(origin: String, recordings: List<Recording>, statics: List<Any?>): Learned? {
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
        val increments = incrementRules(snapshots, others)
        val whens = whenRules(others, statics)
        val learnt = (setRules(snapshots, others, increments) + appendRules(snapshots, others) + increments).map { r ->
            whens[r["command"]]?.let { linkedMapOf<String, Any?>().apply { putAll(r); put("when", it) } } ?: r
        }
        val answerRules = answerRules(others, command)
        val timed = timerRules(snapshots, others, statics)
        val (variants, variantTimers, moves) = variantRules(learnt, timed, statics, snapshots.last().state)
        val rules = learnt + variants
        val timers = timed + variantTimers
        val economy = Economy.learn(snapshots.map { it.time to it.state }, others, statics, timers, whens, rules, moves)

        val document = linkedMapOf<String, Any?>("command" to command, "login" to login.toList(), "time" to time, "answers" to samples, "answerRules" to answerRules, "rules" to rules, "timers" to timers)
        economy?.let { document["economy"] = it }
        packs.entries.firstOrNull()?.let { (name, list) -> document["pack"] = linkedMapOf("command" to name, "list" to list) }
        val lines = listOf(
            "$origin: a local game server learnt from ${calls.size} commands, login ${login.joinToString()}, " +
                "${state.size} messages kept, ${rules.size} rules, ${answerRules.size} answer rules, ${samples.size} commands answered alike",
        ) + (rules + answerRules).map { r -> "  ${r["command"]}: ${r["op"] ?: "answer"} ${r["path"]}" + (r["value"]?.let { v -> " = " + (if (v is Map<*, *>) "{${v.keys.joinToString()}}" else v.toString()) } ?: "") } +
            timers.map { t -> "  timer: ${t["list"]} ${t["field"]} ${t["value"]} ends after the time of ${(t["durations"] as Map<*, *>).size} documents of the game's data" } +
            (economy?.get("effects") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { e ->
                "  economy: ${e["command"]} ${e["op"]} " + listOf("element", "field", "times", "price", "resource").mapNotNull { k -> e[k]?.let { "$k=$it" } }.joinToString(" ")
            }
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
    private fun setRules(snapshots: List<Snapshot>, calls: List<Call>, increments: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val intervals = intervals(snapshots, calls)
        val results = mutableListOf<Map<String, Any?>>()
        // A field of a message (the player's step): the last call of the command between two logins sets it.
        for (name in calls.map { it.name }.distinct()) {
            val found = intervals.filter { i -> i.between.any { it.name == name } }
            val fieldsValues = found.map { i -> i.between.last { it.name == name } }
            // The paths that changed to a value one of the calls sent.
            val relations = LinkedHashSet<Pair<String, String>>()
            found.forEachIndexed { k, i ->
                for (d in i.diffs) {
                    if (d.operation == StateDiff.Operation.REMOVE || added(d.path, i.diffs) || '#' in d.path) continue
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
        // A field of an element (a building's state, its time): the last call naming
        // the element between two logins sets it, to a field the call sent (its time
        // included) or to the same value every time. The fields are those that
        // changed in some element of a list. The loop above, its sightings per element.
        // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#setRules
        val attributes = LinkedHashSet<Pair<String, String>>()
        val kept = mutableListOf<Triple<Map<String, Any?>, Pair<String, String>, Boolean>>()
        for (i in intervals) {
            for (d in i.diffs) {
                if (d.operation == StateDiff.Operation.REMOVE || d.value is Map<*, *> || d.value is List<*> || added(d.path, i.diffs)) continue
                val at = d.path.indexOf("/#")
                val inner = if (at < 0) "" else d.path.substring(at + 2).substringAfter('/', "")
                if (inner.isNotEmpty() && '#' !in inner) attributes.add(d.path.substring(0, at) to inner)
            }
        }
        for (name in calls.map { it.name }.distinct()) {
            for (xField in calls.filter { it.name == name }.flatMap { it.xFields.keys }.toSet()) {
                for ((list, inner) in attributes) {
                    val yField = "$list/#$$xField/$inner"
                    if (increments.any { it["command"] == name && it["path"] == yField }) continue
                    val found = intervals.flatMap { i ->
                        i.between.filter { c ->
                            val id = c.xFields[xField]?.toString()
                            c.name == name && id != null && i.between.last { o -> o.xFields.values.any { it.toString() == id } } === c && valueAt(i.target.state, "$list/#$id") != null
                        }.map { i to it }
                    }
                    if (found.isEmpty()) continue
                    val yValues = found.map { (i, c) -> valueAt(i.target.state, concrete(yField, c)) }
                    if (yValues.any { it is Map<*, *> || it is List<*> }) continue
                    val relation = (found.first().second.xFields.keys + "optime").firstOrNull { x ->
                        // Seen once, a text or a time (the call's own time) is taken, a small number is chance.
                        val single = yValues.singleOrNull()
                        RelationFinder.executeOnFields(found.map { it.second.fields[x] }, yValues, allowConstant = single is String || (single as? Number)?.toLong()?.let { it > 1_000_000_000L } == true)
                    }
                    val constant = yValues.first().takeIf { y -> y != null && yValues.all { StateDiff.same(it, y) } && (found.size >= 2 || y is String) }
                    // Whether the call changed it once at least: a value it only left as it was proves nothing.
                    val changed = found.indices.any { k -> !StateDiff.same(valueAt(found[k].first.source.state, concrete(yField, found[k].second)), yValues[k]) }
                    if (relation != null && changed) {
                        results.add(linkedMapOf("command" to name, "op" to "set", "path" to yField, "value" to "$$relation"))
                    } else if (constant != null) {
                        kept.add(Triple(linkedMapOf("command" to name, "op" to "set", "path" to yField, "value" to constant), list to inner, changed))
                    }
                }
            }
        }
        // A value a call left as it was is a rule when another call sets that field to
        // another value: the upgrade that ends with diamonds puts back the state the
        // start of the upgrade changed.
        for ((relation, attribute, changed) in kept) {
            if (changed || kept.any { (o, a, c) -> c && a == attribute && !StateDiff.same(o["value"], relation["value"]) }) results.add(relation)
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
        val results = LinkedHashMap<Pair<String, String>, MutableMap<String, Any?>>()
        for (i in intervals(snapshots, calls)) {
            for (d in i.diffs) {
                val target = d.value as? Number ?: continue
                if (target is Double || d.operation == StateDiff.Operation.REMOVE || added(d.path, i.diffs)) continue
                val source = d.srcValue
                if (source != null && (source !is Number || source is Double)) continue
                val id = d.path.split('/').lastOrNull { it.startsWith("#") }?.substring(1) ?: continue
                val related = i.between.filter { c -> c.xFields.values.any { it.toString() == id } }.groupBy { it.name }
                // Every command that explains it is a candidate, the check below keeps those that hold.
                for ((_, group) in related) {
                    val start = (source as? Number)?.toLong() ?: (target.toLong() - group.size)
                    if (target.toLong() - start != group.size.toLong() || start < 0) continue
                    val call = group.first()
                    val path = general(d.path, call) ?: continue
                    val relation = results.getOrPut(call.name to path) { linkedMapOf("command" to call.name, "op" to "increment", "path" to path) }
                    // A number the element does not hold yet starts at the default: a building without a grade is at 1.
                    if (source == null && relation["default"] == null) relation["default"] = start.toInt()
                }
            }
        }
        // Kept only when it holds between every two logins the command named an
        // element in, as RelationFinder keeps a relation that holds for every pair:
        // each element named went up by as many times as it was named, a number it
        // holds in neither login being its default. Where another command named the
        // same element too, the interval says nothing.
        val intervals = intervals(snapshots, calls)
        return results.values.filter { relation ->
            val path = relation["path"] as String
            intervals.all { i ->
                i.between.filter { it.name == relation["command"] }.groupBy { concrete(path, it) }.all { (p, group) ->
                    val shared = results.values.any { o -> o !== relation && o["path"] == path && i.between.any { it.name == o["command"] && concrete(path, it) == p } }
                    val element = p.substringBeforeLast('/')
                    val present = valueAt(i.source.state, element) != null && valueAt(i.target.state, element) != null
                    val after = valueAt(i.target.state, p) as? Number
                    val before = valueAt(i.source.state, p) as? Number
                    // Held in neither login, the number did not move.
                    val target = after ?: relation["default"] as? Number ?: if (before == null) 0 else null
                    val source = before ?: relation["default"] as? Number ?: if (after == null) 0 else null
                    // Shared with another command, the number went up by as many at least.
                    val delta = if (target == null || source == null) null else target.toLong() - source.toLong()
                    !present || delta == null || (if (shared) delta >= group.size else delta == group.size.toLong())
                }
            }
        }
    }

    // timers: an element that changed between two logins though no call named it
    // finished something the server timed: a text went from A to B (UPGRADE to
    // NORMAL), a time from t1 to t2, a number up by one (the grade). The time it
    // took is found in the game's data files the game downloaded: the document
    // whose id is a field of the element (its article), and in it the document
    // whose field is the element's new number and another field t2 - t1. Every
    // document of the file gives its duration to the rule, by article and number
    // before, and the phone ends a timer once its time is up.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#incrementRules
    // not ported: read, valueAt (called as they are, not copied)
    private fun timerRules(snapshots: List<Snapshot>, calls: List<Call>, statics: List<Any?>): List<Map<String, Any?>> {
        val results = LinkedHashMap<String, Map<String, Any?>>()
        for (i in intervals(snapshots, calls)) {
            val changed = i.diffs.filter { d -> d.operation != StateDiff.Operation.REMOVE && "/#" in d.path && !added(d.path, i.diffs) }
                .groupBy { d -> d.path.substring(0, d.path.indexOf('/', d.path.indexOf("/#") + 2).let { if (it < 0) d.path.length else it }) }
            for ((element, diffs) in changed) {
                val id = element.substringAfterLast("/#")
                if (i.between.any { c -> c.xFields.values.any { it.toString() == id } }) continue
                val target = valueAt(i.target.state, element) as? Map<*, *> ?: continue
                val inner = { d: StateDiff.Diff -> d.path.removePrefix("$element/") }
                val texts = diffs.filter { it.value is String && it.srcValue is String }
                val times = diffs.filter { d ->
                    val t2 = (d.value as? Number)?.toLong()
                    val t1 = (d.srcValue as? Number)?.toLong()
                    t1 != null && t2 != null && t1 > 1_000_000_000L && t2 > t1 && t2 <= i.target.time / 1000 + TIME_SLACK
                }
                val counters = diffs.filter { d -> d !in times && d.value is Number && ((d.srcValue as? Number)?.let { (d.value as Number).toLong() - it.toLong() == 1L } ?: (d.srcValue == null)) }
                if (texts.size != 1 || times.size != 1 || counters.size != 1) continue
                val duration = (times[0].value as Number).toLong() - (times[0].srcValue as Number).toLong()
                val grade = (counters[0].value as Number).toLong()
                val (key, table) = lookup(statics, target, grade, duration) ?: continue
                val path = element.substringBeforeLast("/#")
                val relation = linkedMapOf<String, Any?>(
                    "list" to path, "field" to inner(texts[0]), "value" to texts[0].srcValue, "since" to inner(times[0]),
                    "key" to listOf(key, inner(counters[0])), "default" to (grade - 1).toInt(), "durations" to table,
                    "then" to listOf(
                        linkedMapOf("op" to "increment", "path" to inner(counters[0])),
                        linkedMapOf("op" to "set", "path" to inner(texts[0]), "value" to texts[0].value),
                        linkedMapOf("op" to "set", "path" to inner(times[0]), "value" to "\$end"),
                    ),
                )
                results.putIfAbsent("$path ${inner(texts[0])} ${texts[0].srcValue}", relation)
            }
        }
        return results.values.toList()
    }

    // The durations of a timer in the game's data: a list of documents with an id
    // one of which has the id a field of the element holds, holding a list whose
    // document has the element's new number in one field and the duration seen in
    // another. Every document of the list gives its durations, keyed by its id and
    // the number before. Null when no data file holds the duration seen.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    private fun lookup(statics: List<Any?>, element: Map<*, *>, grade: Long, duration: Long): Pair<String, Map<String, Any?>>? {
        val lists = mutableListOf<List<Map<*, *>>>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> node.values.forEach { walk(it) }
                is List<*> -> if (node.isNotEmpty() && node.all { it is Map<*, *> && it["id"] != null }) lists.add(node.map { it as Map<*, *> }) else node.forEach { walk(it) }
            }
        }
        statics.forEach { walk(it) }
        for (list in lists) {
            for ((key, value) in element) {
                if (key == "id" || (value !is Number && value !is String)) continue
                val document = list.firstOrNull { it["id"].toString() == value.toString() } ?: continue
                for ((listKey, inner) in document) {
                    val grades = (inner as? List<*>)?.filterIsInstance<Map<*, *>>() ?: continue
                    val sample = grades.firstOrNull { g -> g.values.any { (it as? Number)?.toLong() == grade } } ?: continue
                    val gradeKey = sample.entries.firstOrNull { (it.value as? Number)?.toLong() == grade }?.key ?: continue
                    val durationKey = sample.entries.firstOrNull { it.key != gradeKey && (it.value as? Number)?.toLong() == duration }?.key ?: continue
                    // This list first, then the others written alike (the second base
                    // has its own file of buildings), an id already given kept.
                    val table = LinkedHashMap<String, Any?>()
                    for (other in (listOf(list) + lists.filter { it !== list }).flatten()) {
                        for (g in (other[listKey] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
                            val number = (g[gradeKey] as? Number)?.toLong() ?: continue
                            val seconds = (g[durationKey] as? Number)?.toLong() ?: continue
                            table.putIfAbsent("${other["id"]},${number - 1}", seconds.toInt())
                        }
                    }
                    return key.toString() to table
                }
            }
        }
        return null
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

    // when: the text fields of a command's request that held one value in every
    // call recorded ({"locationType": "MAIN"}). The rules learnt from them hold
    // for such a request only: one the recordings never showed (the second base,
    // whose elements share the ids of the first) leaves the state as it is.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#answerRules
    // not ported: getValues, executeOnFields (the request alone is read, no path of the answer)
    private fun whenRules(calls: List<Call>, statics: List<Any?>): Map<String, Map<String, Any?>> {
        val results = LinkedHashMap<String, Map<String, Any?>>()
        for ((name, group) in calls.groupBy { it.name }) {
            val fields = LinkedHashMap<String, Any?>()
            for (xField in group.flatMap { it.xFields.keys }.toSet()) {
                val xValues = group.map { it.fields[xField] }
                if (!xValues.all { it is String && it == xValues.first() }) continue
                // One call says nothing of the values a field takes (a nick), unless
                // the game's data gives that value to a field of that name (MAIN).
                if (group.size > 1 || xValues.first() in valuesOf(statics, xField)) fields[xField] = xValues.first()
            }
            if (fields.isNotEmpty()) results[name] = fields
        }
        return results
    }

    // variants: a rule learnt for one value of its when field holds for the other
    // values the game's data gives that field, on the part of the state named like
    // that value. The second base was never recorded: requests with locationType
    // SECOND do to location/second_location what those with MAIN did to
    // location/location. A timer runs on such a part too.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#whenRules
    // not ported: getValues (the data is walked for one field's texts)
    // Returns the rules, the timers and the moves, each list the rules of a value
    // were moved from, to, and that value, for the economy to move its effects alike.
    private fun variantRules(rules: List<Map<String, Any?>>, timers: List<Map<String, Any?>>, statics: List<Any?>, state: Map<String, Any?>): Triple<List<Map<String, Any?>>, List<Map<String, Any?>>, List<Map<String, Any?>>> {
        val results = mutableListOf<Map<String, Any?>>()
        val values = LinkedHashSet<String>()
        val moves = LinkedHashSet<Map<String, Any?>>()
        for (rule in rules) {
            val fields = (rule["when"] as? Map<*, *>)?.takeIf { it.size == 1 } ?: continue
            val (xField, xValue) = fields.entries.single()
            for (value in valuesOf(statics, xField.toString()).filter { it != xValue }) {
                val path = moved(rule["path"].toString(), value, state) ?: continue
                values.add(value)
                results.add(LinkedHashMap(rule).apply { put("path", path); put("when", mapOf(xField to value)) })
                moves.add(linkedMapOf("from" to rule["path"].toString().substringBefore("/#"), "to" to path.substringBefore("/#"), "when" to mapOf(xField.toString() to value)))
            }
        }
        val timed = timers.flatMap { timer -> values.mapNotNull { value -> moved(timer["list"].toString(), value, state)?.let { LinkedHashMap(timer).apply { put("list", it) } } } }
        return Triple(results, timed, moves.toList())
    }

    // The texts the game's data gives a field.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    private fun valuesOf(statics: List<Any?>, field: String): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(v: Any?) {
            when (v) {
                is Map<*, *> -> v.forEach { (k, x) -> if (k == field && x is String) out.add(x) else walk(x) }
                is List<*> -> v.forEach { walk(it) }
            }
        }
        statics.forEach { walk(it) }
        return out
    }

    // A path moved to the part of the state named like a value: at the first of its
    // segments whose document holds one other key of the same kind with the value
    // as a word of its name, the path still leading to a list or a document there.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    // not ported: valueAt (called as it is, on each part of the path)
    private fun moved(path: String, value: String, root: Any?): String? {
        val segments = path.split('/')
        val word = value.lowercase()
        for (i in 1 until segments.size) {
            if (segments[i].startsWith("#")) break
            val node = valueAt(root, segments.take(i).joinToString("/")) as? Map<*, *> ?: break
            val here = node[segments[i]] ?: break
            val keys = node.keys.map { it.toString() }.filter { k ->
                val x = node[k]
                k != segments[i] && word in k.lowercase().split('_') && (x is Map<*, *>) == (here is Map<*, *>) && (x is List<*>) == (here is List<*>)
            }
            if (keys.size != 1) continue
            val out = segments.take(i) + keys.single() + segments.drop(i + 1)
            val list = out.takeWhile { !it.startsWith("#") }.joinToString("/")
            return if (valueAt(root, list) != null) out.joinToString("/") else null
        }
        return null
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
