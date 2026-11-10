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

    // A number above this is a time in seconds, not an id or a count.
    private const val EPOCH = 1_000_000_000L

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

    // data: the APK's files written again with the server (a data file without the
    // conditions of what a new player is given), by their name in the APK.
    class Learned(val origin: String, val rules: Map<String, Any?>, val state: Map<String, Any?>, val lines: List<String>, val data: Map<String, ByteArray> = emptyMap())

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
        val names = libraryNames(apk)
        val results = results(names)
        return recordings.groupBy { it.origin }.mapNotNull { (origin, all) ->
            learn(origin, all.sortedBy { it.receivedResponseMillis }, statics, names)?.let { l ->
                if (results.isEmpty()) l else Learned(l.origin, LinkedHashMap(l.rules).apply { put("results", results) }, l.state, l.lines + "  results: ${results.entries.joinToString { (k, v) -> "$k answered $v" }}")
            }
        }
    }

    // results: the commands the game's code names with an answer of its own,
    // "<command>_result" beside "<command>" in a native library (hero_unlocked
    // and hero_unlocked_result). A command never recorded that the phone keeps
    // (a hero unlocked) is answered with that message, its result true.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/Endpoints.kt#scan
    // not ported: scan, runs, parser_file (the strings of a library are read whole, no address is searched)
    // not ported: the assembly stores and the data entries (a game's commands are in its native code here)
    // not ported: Endpoint, Occurrence, Item, likelyServer, linkHost, inCode, order, blocklist, namespace, owned, library, holders (the endpoints, not read here)
    private fun results(names: Set<String>): Map<String, String> =
        names.filter { it.endsWith(RESULT) && it.removeSuffix(RESULT) in names }.sorted().associateBy { it.removeSuffix(RESULT) }

    // The names the game's native libraries hold as C strings: every command the
    // game knows, recorded or not (cancel_remove_obstacle beside remove_obstacle).
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/Endpoints.kt#scan
    // not ported: scan, runs, parser_file (the strings of a library are read whole, no address is searched)
    private fun libraryNames(apk: Path?): Set<String> {
        if (apk == null || !Files.isRegularFile(apk)) return emptySet()
        val names = HashSet<String>()
        java.util.zip.ZipFile(apk.toFile()).use { zip ->
            for (file in zip.entries()) {
                if (file.isDirectory || !file.name.startsWith("lib/") || !file.name.endsWith(".so")) continue
                val content = String(zip.getInputStream(file).use { it.readAllBytes() }, Charsets.ISO_8859_1)
                // A C string: the run of a command's letters between two NULs.
                Regex("(?<=\u0000)[a-z][a-z_]*(?=\u0000)").findAll(content).forEach { names.add(it.value) }
            }
        }
        return names
    }

    private const val RESULT = "_result"

    private fun learn(origin: String, recordings: List<Recording>, statics: List<Any?>, library: Set<String> = emptySet()): Learned? {
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
        // The commands' answers without the envelope the server sometimes put them in.
        val own = enveloped(calls, command, packs, login)

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
        for ((name, group) in own.filter { it.answer != null && it.name !in packs }.groupBy { it.name }) {
            val found = HashSet<String>()
            samples[name] = group.reversed().filter { found.add((it.fields - SKIPPED).toString()) }.take(if (name in login) 1 else EXAMPLES).map { c ->
                linkedMapOf<String, Any?>("request" to c.fields - SKIPPED, "answer" to c.answer).also { e -> c.type?.let { e["type"] = it } }
            }
        }

        val snapshots = logins.map { c -> Snapshot(c.time, messages(c.answer!!, command).filter { (it[command] as String) in state }.associateBy { it[command] as String }) }
        val others = own.filter { it.name !in login && it.name !in packs }
        val increments = incrementRules(snapshots, others)
        val whens = whenRules(others, statics)
        val sets = setRules(snapshots, others, increments)
        // The fields a command carries that its element holds under the same name (a move).
        val carried = execute(snapshots, others).filter { c -> sets.none { it["command"] == c["command"] && it["path"] == c["path"] } }
        val learnt = (sets + carried + appendRules(snapshots, others) + increments).map { r ->
            whens[r["command"]]?.let { w ->
                linkedMapOf<String, Any?>().apply {
                    putAll(r)
                    put("when", w)
                }
            } ?: r
        }
        val progress = progressRules(others, command)
        // The instances of the state kept, then those of each login, the latest first, for
        // the paths it no longer shows: a hero wounded in a fight holds a health that is not
        // its document's, the login before the fight still shows the relation. A path is the first found.
        val instances = (listOf(state.values.toList()) + snapshots.asReversed().map { it.state.values.toList() })
            .flatMap { messages -> GameState.instances(linkedMapOf("messages" to messages), statics) }.distinctBy { it["path"] }
        val timed = timerRules(snapshots, others, statics)
        // The production first: the elements it queues units in are not works.
        val production = productionRules(snapshots, others, statics, learnt + variantRules(learnt, timed, statics, snapshots.last().state).first)
        val producing = production.flatMap { p -> listOf("train", "force", "left").mapNotNull { (p[it] as? Map<*, *>)?.get("command") as? String } }.toSet()
        val worked = startClearing(snapshots, others, statics, learnt.filter { it["command"] !in producing }, timed, whens, library, producing)
        val (variants, variantTimers, moves) = variantRules(learnt, worked.timers, statics, snapshots.last().state)
        val rules = learnt + variants
        val timers = worked.timers + variantTimers
        // The fields that name an element: those a rule or a work reaches it by.
        val elements = rules.mapNotNull { r -> NAMING.matchEntire(r["path"].toString())?.let { r["command"].toString() to it.groupValues[2] } }.toSet() +
            worked.works.flatMap { w -> listOf(w["start"] as Map<*, *>) + (w["ends"] as List<*>).map { it as Map<*, *> }.filter { it["by"] == null } }
                .mapNotNull { m -> m["element"]?.let { m["command"].toString() to it.toString() } }
        val economy = Economy.learn(snapshots.map { it.time to it.state }, others, statics, timers, whens, rules, moves, elements)
        // The commands that change the state: those a rule, a progress, an economy effect, a production or a work names.
        val seen = intervals(snapshots, others)
        val changing = rules.filter { observed(it, seen) }.map { it["command"] as String }.toSet() + progress.map { it["command"] as String } +(economy?.get("effects") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { it["command"] as String } + producing +
            worked.works.flatMap { w -> listOf((w["start"] as Map<*, *>)["command"].toString()) + (w["ends"] as List<*>).map { (it as Map<*, *>)["command"].toString() } }
        val answerRules = getUnitUpgradeLevel(answerRules(others, command) + answerRules(snapshots, others, command, changing, production) + refusalRules(others, command), worked.works)

        val document = linkedMapOf<String, Any?>("command" to command, "login" to login.toList(), "time" to time, "answers" to samples, "answerRules" to answerRules, "rules" to rules, "timers" to timers)
        economy?.let { document["economy"] = it }
        if (worked.works.isNotEmpty()) document["works"] = worked.works
        if (progress.isNotEmpty()) document["progress"] = progress
        if (production.isNotEmpty()) document["production"] = production
        // The fields of the requests the server refused (the signature of an attack): a
        // command never recorded that carries one is refused alike on the phone.
        val refusals = answerRules.filter { it["op"] == "remove" }.map { it["command"] as String }.distinct().map { name ->
            linkedMapOf("command" to name, "fields" to others.filter { it.name == name }.flatMap { it.fields.keys - SKIPPED - command }.distinct())
        }
        if (refusals.isNotEmpty()) document["refusals"] = refusals
        if (instances.isNotEmpty()) document["instances"] = instances
        packs.entries.firstOrNull()?.let { (name, list) -> document["pack"] = linkedMapOf("command" to name, "list" to list) }
        val lines = listOf(
            "$origin: a local game server learnt from ${calls.size} commands, login ${login.joinToString()}, " +
                "${state.size} messages kept, ${rules.size} rules, ${answerRules.size} answer rules, ${samples.size} commands answered alike",
        ) + (rules + answerRules).map { r -> "  ${r["command"]}: ${r["op"] ?: "answer"} ${r["path"]}" + (r["from"]?.let { f -> " from $f" + (r["each"]?.let { e -> " each $e" } ?: "") } ?: "") + (r["value"]?.let { v -> " = " + (if (v is Map<*, *>) "{${v.keys.joinToString()}}" else v.toString()) } ?: "") + (if (r["carried"] == true) " (carried by the name of its field)" else "") } +
            progress.map { p -> "  progress: ${p["command"]} changes the entry of ${p["answer"]} ${p["path"]} named by ${(p["key"] as Map<*, *>).values.joinToString(" ")}: ${p["values"]}" } +
            refusals.map { r -> "  refusal: a command never recorded with one of ${r["fields"]} is refused as ${r["command"]}" } +
            instances.map { i -> "  instances: ${i["path"]} can hold ${(i["entries"] as List<*>).size} documents of the game's data" } +
            timers.map { t -> "  timer: ${t["list"]} ${t["field"]} ${t["value"]} ends after the time of ${(t["durations"] as Map<*, *>).size} documents of the game's data" } +
            worked.works.map { w ->
                val start = w["start"] as Map<*, *>
                val ends = (w["ends"] as List<*>).filterIsInstance<Map<*, *>>().joinToString { "${it["command"]} ${it["role"]}s it" + (if (it["deduced"] == true) " (deduced by family)" else "") }
                "  work: ${start["command"]} puts ${w["list"]} ${w["field"]} in ${w["value"]}" + (start["at"]?.let { " at $it" } ?: "") + (start["element"]?.let { " named by $it" } ?: "") +
                    ", ends after the ${w["time"]} of ${(w["durations"] as Map<*, *>).size} documents of the game's data (by ${(w["key"] as List<*>).first()}), " + (w["then"] as List<*>).filterIsInstance<Map<*, *>>().joinToString(" ") { "${it["op"]}" + (it["path"]?.let { p -> " $p" } ?: "") } +
                    (if (ends.isEmpty()) "" else ", $ends")
            } + worked.notes +
            (if (carried.isEmpty() && sets.none { "/#$" in it["path"].toString() }) emptyList() else listOf("  family (deduced): a command never recorded that names an element of ${elementLists(snapshots.last().state).keys.joinToString()} by id sets the fields it carries under the name the element holds them with (a move)")) +
            production.map { p -> "  production: ${(p["train"] as Map<*, *>)["command"]} queues units in ${p["list"]} ${p["queue"]}, one leaves it each time of ${(p["durations"] as Map<*, *>).size} documents of the game's data, for the elements with ${(p["camp"] as Map<*, *>)["key"]} ${(p["camp"] as Map<*, *>)["value"]}" } +
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

    // enveloped: a command sent alone that the server answered with the pack's
    // envelope (data: [its message, the notifications that rode along]). Its own
    // message is the element named as its other answers are, the rest is not its
    // answer: the rules and the samples are made from the message alone, as from
    // every answer that came bare. A command never answered bare keeps its envelope.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#learn
    // not ported: learn (called as it is, enveloped only reads the calls learn collected: its calls, command, packs and login)
    private fun enveloped(calls: List<Call>, command: String, packs: Map<String, String>, login: Set<String>): List<Call> {
        val named = calls.filter { it.answer != null && it.name !in login && it.name !in packs }.groupBy { it.name }.mapValues { (_, group) ->
            group.mapNotNull { it.answer!![command] as? String }.filter { it !in packs }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
        return calls.map { c ->
            val list = c.answer?.get(command)?.let { packs[it] }
            val message = named[c.name]
            if (c.name in login || c.name in packs || list == null || message == null) return@map c
            @Suppress("UNCHECKED_CAST")
            val answer = (c.answer!![list] as? List<*>).orEmpty().firstOrNull { it is Map<*, *> && it[command] == message } as? Map<String, Any?> ?: return@map c
            Call(c.time, c.name, c.fields, answer, c.type)
        }
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

    // A set rule of a field of an element that the call may only have left as it was: the rule, the
    // list and the field it is about, whether the call changed it once, and the intervals (with the id
    // of the element) where the sightings were, as RelationFinder keeps the x_values and y_values of a relation.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#setRules
    // not ported: executeOnFields, relation_fcts, _sizeRelation, setRules (this class only holds what setRules found, its loop is in setRules)
    private class Kept(val rule: Map<String, Any?>, val attribute: Pair<String, String>, val changed: Boolean, val seen: List<Pair<Interval, String>>)

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
        val kept = mutableListOf<Kept>()
        // Whether something else than the call wrote the value an interval shows at a path: the
        // login that answers refreshes a time to its own, and a later call that names the element by
        // what the object of the value holds (the research of a unit, named by the unit the laboratory
        // keeps beside its text) writes into that object. Such a sample says nothing of the call, as
        // a pair of values RelationFinder's x_values and y_values do not bind is left out of the relation.
        // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
        // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#setRules
        // not ported: relation_fcts, _sizeRelation, setRules (equality only, called as it is)
        // The ids of the elements (executeOnFields' x_values) bound the identifiers that name another kind of thing.
        val ceiling = snapshots.flatMap { s -> elementLists(s.state).values.flatten() }.mapNotNull { (it["id"] as? Number)?.toLong() }.maxOrNull() ?: 0L
        fun overwritten(i: Interval, c: Call, yField: String): Boolean {
            val value = valueAt(i.target.state, yField)
            if (value is Number && value !is Double && value.toLong() > EPOCH && !RelationFinder.equalRelation(c.fields["optime"], value) && Math.abs(value.toLong() - i.target.time / 1000) <= TIME_SLACK) return true
            val holder = valueAt(i.target.state, yField.substringBeforeLast('/')) as? Map<*, *> ?: return false
            val own = yField.substringAfterLast('/')
            return i.between.any { o ->
                o !== c && o.time > c.time && o.xFields.values.any { x -> holder.any { (k, v) -> k.toString() != own && v is Number && v !is Double && v.toLong() in ceiling + 1..EPOCH && RelationFinder.equalRelation(x, v) } }
            }
        }
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
                    // The intervals the command was sent in and the element it names changed in: one that
                    // left the element as it was shows nothing of the rule, the value is the one it had.
                    val found = intervals.flatMap { i ->
                        i.between.filter { c ->
                            val id = c.xFields[xField]?.toString()
                            c.name == name && id != null && i.between.last { o -> o.xFields.values.any { it.toString() == id } } === c && valueAt(i.target.state, "$list/#$id") != null &&
                                i.diffs.any { d -> d.path == "$list/#$id" || d.path.startsWith("$list/#$id/") } && !overwritten(i, c, concrete(yField, c))
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
                        kept.add(Kept(linkedMapOf("command" to name, "op" to "set", "path" to yField, "value" to constant), list to inner, changed, found.map { (i, c) -> i to c.xFields[xField].toString() }))
                    }
                }
            }
        }
        // A value a call left as it was is a rule when another call sets that field to
        // another value in an interval where both named the same element: the upgrade that
        // ends with diamonds puts back the state the start of the upgrade changed. A read
        // (the platform a version is asked for) that leaves it as it was is no rule.
        for (held in kept) {
            if (held.changed || kept.any { o -> o.changed && o.attribute == held.attribute && !StateDiff.same(o.rule["value"], held.rule["value"]) &&
                    held.seen.any { (i, id) -> i.between.any { x -> x.name == o.rule["command"] && x.xFields.values.any { it.toString() == id } } } }) results.add(held.rule)
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
        // The relations seen on a number the element held: one only seen appearing from nothing is a value, not a count.
        val counted = HashSet<Pair<String, String>>()
        // The numbers a relation was seen appearing at: a count shows several, a value the same one.
        val appeared = HashMap<Pair<String, String>, MutableSet<Long>>()
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
                    if (source != null) counted.add(call.name to path) else appeared.getOrPut(call.name to path) { HashSet() }.add(target.toLong())
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
            (relation["default"] == null || (relation["command"].toString() to path) in counted || (appeared[relation["command"].toString() to path]?.size ?: 0) >= 2) && intervals.all { i ->
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

    // The path of a rule through the element a command names by a field: the list, that field, the rest.
    private val NAMING = Regex("^(.+?)/#[$]([^/]+)/(.+)$")

    // The lists of documents with an id in the game's data files, at any depth.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
    // not ported: timerRules (kept as it is, startClearing is its twin for the works it does not cover)
    private fun dataLists(statics: List<Any?>): List<List<Map<*, *>>> {
        val lists = mutableListOf<List<Map<*, *>>>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> node.values.forEach { walk(it) }
                is List<*> -> if (node.isNotEmpty() && node.all { it is Map<*, *> && it["id"] != null }) lists.add(node.map { it as Map<*, *> }) else node.forEach { walk(it) }
            }
        }
        statics.forEach { walk(it) }
        return lists
    }

    // One work (a clearing, the research of a unit): the command that starts it, the text it puts an
    // element of a list in, the time field it sets, and the endings seen of it.
    private class Work(val command: String, val list: String, val element: String?, val field: String, val value: String, var since: String?, val condition: Map<String, Any?>?) {
        val endings = mutableListOf<Ending>()
        var article: String? = null
    }

    // One ending of a work seen between two logins: the element as the first held it, as the second
    // does (null when it is gone), the call that started it in between, the call that finished it
    // before its time (null when the timer did), and the diffs of the element.
    private class Ending(val interval: Interval, val list: String, val source: Map<*, *>, val target: Map<*, *>?, val started: Call?, val finisher: Call?, val diffs: List<StateDiff.Diff>)

    // What startClearing learnt: the works, the timers with what they lacked, a line for each work left out.
    private class Works(val works: List<Map<String, Any?>>, val timers: List<Map<String, Any?>>, val notes: List<String>)

    // works: one per command that puts an element of a list in a text for a time (a clearing, the
    // research of a unit). The start is a set rule of the command (the text a constant, the time
    // $optime), else the call whose time is the one an element changed from, in a login, when no
    // call named it by id (unit_upgrade_start names a unit, the laboratory changed). The end is
    // seen at a later login: after a time no call named the element in (the timer), or right after
    // a call that named it (the force). What the end does is read from the diff: the element
    // removed, the text back to the idle one, numbers reset to constants, a counter up by one, a
    // list merged by the key the work held. The time it takes is a numeric field of the
    // document of the data the element (or a command) names, the one equal to the time seen or
    // within the bounds every ending shows. The text a work puts in is never the idle one, the
    // one most elements hold.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#RelationFinder
    // not ported: timerRules (kept as it is, an upgrade a timer covers only gets its start and its forces added)
    // not ported: __init__, findOnSymbol, findOnFields, executeOnSymbol, executeOnFields, _findRelationType, _checkEqualityRelation, _equalRelation, _sizeRelation (RelationFinder.kt ports the relation, equalRelation is called)
    // not ported: _generateAttributeValuesForSymbol, _getAllFieldsValues, _generateConcatData, _generateDataValues, _generateSizeValues, returns (the values are the fields of the calls and of the elements)
    private fun startClearing(snapshots: List<Snapshot>, calls: List<Call>, statics: List<Any?>, rules: List<Map<String, Any?>>, timers: List<Map<String, Any?>>, whens: Map<String, Map<String, Any?>>, names: Set<String>, skipped: Set<String> = emptySet()): Works {
        val lists = dataLists(statics)
        val moments = intervals(snapshots, calls)
        val held = snapshots.flatMap { s -> elementLists(s.state).entries }
        val notes = mutableListOf<String>()
        fun idleOf(list: String, field: String): String? =
            held.filter { it.key == list }.flatMap { it.value }.mapNotNull { valueAt(it, field) as? String }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        fun optime(call: Call?): Long? = (call?.fields?.get("optime") as? Number)?.toLong()

        // The starts the rules show: a command that sets a text to a constant and a time to $optime.
        val works = mutableListOf<Work>()
        for ((key, group) in rules.filter { it["op"] == "set" }.groupBy { r -> NAMING.matchEntire(r["path"].toString())?.let { m -> Triple(r["command"].toString(), m.groupValues[1], m.groupValues[2]) } }) {
            if (key == null) continue
            val inner = group.associate { r -> NAMING.matchEntire(r["path"].toString())!!.groupValues[3] to r["value"] }
            val since = inner.entries.firstOrNull { it.value == "\$optime" }?.key ?: continue
            for ((field, value) in inner) {
                if (value is String && !value.startsWith("$") && value != idleOf(key.second, field)) works.add(Work(key.first, key.second, key.third, field, value, since, whens[key.first]))
            }
        }

        // The endings: an element no longer in its list, or one whose text went back to the idle one.
        for (i in moments) {
            val after = elementLists(i.target.state)
            for ((list, old) in elementLists(i.source.state)) {
                val now = after[list] ?: continue
                for (source in old) {
                    val id = source["id"].toString()
                    val target = now.firstOrNull { it["id"].toString() == id }
                    val here = i.diffs.filter { it.path.startsWith("$list/#$id/") }
                    // The numbers the end reset to a constant name the element as well as its id.
                    val reset = here.filter { d -> d.operation == StateDiff.Operation.REPLACE && d.value is Number && (d.srcValue as? Number)?.toLong()?.let { it in 1..EPOCH && it + 1 != (d.value as Number).toLong() } == true }
                    val names = setOf(id) + reset.map { it.srcValue.toString() }
                    val named = i.between.filter { c -> c.xFields.values.any { it.toString() in names } }
                    val candidates = works.filter { it.list == list }
                    if (target == null) {
                        val started = named.lastOrNull { n -> candidates.any { it.command == n.name } }
                        val work = candidates.firstOrNull { it.command == started?.name } ?: candidates.firstOrNull { valueAt(source, it.field) == it.value } ?: continue
                        work.endings.add(Ending(i, list, source, null, started, named.lastOrNull { it !== started && it.name != work.command }, here))
                        continue
                    }
                    for (d in here.filter { it.operation == StateDiff.Operation.REPLACE && it.value is String && it.srcValue is String }) {
                        val field = d.path.removePrefix("$list/#$id/")
                        val was = d.srcValue as String
                        if (was == d.value || d.value != idleOf(list, field) || was == idleOf(list, field)) continue
                        var work = candidates.firstOrNull { it.field == field && it.value == was }
                        if (work == null) {
                            // No set rule starts it: the call whose time the element changed from.
                            @Suppress("UNCHECKED_CAST")
                            val leaves = getValues(source as Map<String, Any?>)
                            val stamp = here.firstOrNull { x -> x.operation == StateDiff.Operation.REPLACE && (x.srcValue as? Number)?.toLong()?.let { t -> t > EPOCH && calls.any { optime(it) == t } } == true } ?: continue
                            val begun = calls.filter { c -> optime(c) == (stamp.srcValue as Number).toLong() && c.time <= i.source.time }
                                .map { c -> c to c.xFields.values.count { x -> leaves.values.any { RelationFinder.equalRelation(x, it) } } }.filter { it.second > 0 }
                            val start = begun.maxByOrNull { it.second }?.takeIf { b -> begun.count { it.second == b.second } == 1 }?.first ?: continue
                            if (start.name in skipped) continue
                            // The command names the element by its id, else the element is of the kind the data gives the work.
                            val by = start.xFields.entries.firstOrNull { it.value.toString() == id }?.key
                            work = Work(start.name, list, by, field, was, stamp.path.removePrefix("$list/#$id/"), whens[start.name])
                            val key = GameState.naming(old, GameState.named(statics))?.first
                            if (by == null && key != null) work.article = "*$key=${source[key]}"
                            works.add(work)
                        }
                        work.endings.add(Ending(i, list, source, target, named.lastOrNull { it.name == work.command }, named.lastOrNull { it.name != work.command }, here))
                    }
                }
            }
        }

        // The timers and works learnt: each work with endings gets its time from the data.
        val built = mutableListOf<MutableMap<String, Any?>>()
        val decorated = timers.map { LinkedHashMap(it) }
        for (w in works.filter { it.endings.isNotEmpty() }) {
            fun idOf(e: Ending) = e.source["id"].toString()
            fun inside(e: Ending, path: String) = path.removePrefix("${e.list}/#${idOf(e)}/")
            // The time the work began: the element's own field when the login held it under way, else the call's.
            fun began(e: Ending): Long? =
                (valueAt(e.source, w.since!!) as? Number)?.takeIf { valueAt(e.source, w.field) == w.value }?.toLong() ?: optime(e.started)
            // The computed end the element holds while the work is under way, minus the start.
            fun finish(e: Ending): StateDiff.Diff? {
                val s = began(e) ?: return null
                return e.diffs.firstOrNull { d -> d.operation == StateDiff.Operation.REPLACE && inside(e, d.path) != w.since && (d.srcValue as? Number)?.toLong()?.let { it > s && it - s < EPOCH } == true }
            }
            // Whether a time is one the ending allows: the one seen, else one the element was still
            // under way after at the first login and over by the second, or not over when it was forced.
            fun fits(e: Ending, seconds: Long): Boolean {
                val s = began(e) ?: return true
                finish(e)?.let { return (it.srcValue as Number).toLong() - s == seconds }
                val under = valueAt(e.source, w.field) == w.value
                val forced = optime(e.finisher)
                return if (forced != null) s + seconds >= forced - TIME_SLACK else s + seconds in (if (under) e.interval.source.time / 1000 else 0L) - TIME_SLACK..e.interval.target.time / 1000 + TIME_SLACK
            }
            val label = "${w.command} puts ${w.list} ${w.field} in ${w.value}"
            val sample = w.endings.first()
            val ready = idleOf(w.list, w.field)

            // What the end does to the element.
            val finished = w.endings.firstOrNull { it.finisher == null } ?: sample
            val then = mutableListOf<Map<String, Any?>>()
            // A list merged by key: the entry added holds a leaf the element held, and a counter that
            // starts at the default plus one, seen in the ending that adds the entry.
            val merge = w.endings.firstNotNullOfOrNull { e ->
                @Suppress("UNCHECKED_CAST")
                val leaves = getValues(e.source as Map<String, Any?>)
                e.diffs.firstNotNullOfOrNull { d ->
                    val before = d.srcValue as? List<*> ?: return@firstNotNullOfOrNull null
                    val entry = (d.value as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { x -> before.none { StateDiff.same(it, x) } } ?: return@firstNotNullOfOrNull null
                    val match = entry.keys.map { it.toString() }.firstOrNull { k -> leaves.entries.any { (p, v) -> p != "id" && RelationFinder.equalRelation(entry[k], v) } } ?: return@firstNotNullOfOrNull null
                    val counter = entry.keys.map { it.toString() }.firstOrNull { k -> k != match && entry[k] is Number } ?: return@firstNotNullOfOrNull null
                    if (before.filterIsInstance<Map<*, *>>().any { StateDiff.same(it[match], entry[match]) }) return@firstNotNullOfOrNull null
                    val from = leaves.entries.first { (p, v) -> p != "id" && RelationFinder.equalRelation(entry[match], v) }.key
                    linkedMapOf<String, Any?>("op" to "merge", "path" to inside(e, d.path), "match" to match, "from" to from, "field" to counter, "default" to ((entry[counter] as Number).toInt() - 1))
                }
            }
            if (finished.target == null) {
                then.add(linkedMapOf("op" to "remove"))
            } else {
                merge?.let { then.add(it) }
                for (d in finished.diffs) {
                    val path = inside(finished, d.path)
                    val before = d.srcValue
                    val after = d.value
                    when {
                        after is List<*> -> Unit
                        d.operation == StateDiff.Operation.REMOVE -> then.add(linkedMapOf("op" to "remove", "path" to path))
                        after is Number && before is Number && after.toLong() == before.toLong() + 1 -> then.add(linkedMapOf("op" to "increment", "path" to path))
                        after is Number && (after.toLong() == optime(finished.finisher) || path == w.since) -> then.add(linkedMapOf("op" to "set", "path" to path, "value" to "\$end"))
                        after is Number || after is String -> then.add(linkedMapOf("op" to "set", "path" to path, "value" to after))
                    }
                }
            }

            // The time it takes, from the data: the document an element field names, the numeric
            // field every ending allows.
            val named = GameState.naming(elementLists(snapshots.last().state)[w.list].orEmpty(), GameState.named(statics))?.first
            // The document is the one the kind of the element names (its article), or the one a field of the start command names.
            val starter = calls.firstOrNull { it.name == w.command && optime(it) == began(sample) }
            @Suppress("UNCHECKED_CAST")
            val keys = getValues(sample.source as Map<String, Any?>).filter { (p, v) ->
                p != "id" && p != w.field && p != w.since && (v is String || (v is Number && v.toLong() <= EPOCH)) &&
                    (p == named || starter?.xFields?.values?.any { RelationFinder.equalRelation(it, v) } == true)
            }.keys.sortedBy { if (it == named) 0 else 1 }
            var chosen: Triple<String, List<Map<*, *>>, String>? = null
            search@ for (list in lists) {
                for (key in keys) {
                    val docs = w.endings.map { e -> valueAt(e.source, key)?.let { v -> list.firstOrNull { it["id"].toString() == v.toString() } } }
                    if (docs.any { it == null }) continue
                    // A document gives a time at its own level and one at each grade its list holds: as
                    // UnitUpgradeComponent.GetTotalSeconds reads the time of the unit's level.
                    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#GetTotalSeconds
                    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
                    // not ported: GetTotalSeconds, timerRules (startClearing reads the time of a work here, in the search below)
                    fun getTotalSeconds(d: Map<*, *>, f: String): List<Long> =
                        listOfNotNull((d[f] as? Number)?.toLong()) + d.values.filterIsInstance<List<*>>().flatMap { l -> l.filterIsInstance<Map<*, *>>().mapNotNull { (it[f] as? Number)?.toLong() } }
                    // The time at the document's own level first, a time at one of its grades when that is ambiguous.
                    val own = docs[0]!!.keys.map { it.toString() }.filter { f -> f != "id" && docs.indices.all { n -> (docs[n]!![f] as? Number)?.toLong()?.let { fits(w.endings[n], it) } == true } }
                    val fields = own.takeIf { it.size == 1 } ?: docs[0]!!.keys.map { it.toString() }.filter { f -> f != "id" && docs.indices.all { n -> getTotalSeconds(docs[n]!!, f).any { fits(w.endings[n], it) } } }
                    if (fields.size == 1) {
                        chosen = Triple(key, list, fields.single())
                        break@search
                    }
                }
            }
            val (key, list, field) = chosen ?: run {
                notes.add("  work left out: $label, no numeric field of a document of the data fits the time of its ${w.endings.size} endings")
                continue
            }
            // The grades of a document: a list whose documents hold the field and a number running from the default.
            fun gradesOf(d: Map<*, *>): Pair<List<Map<*, *>>, String>? {
                for (inner in d.values) {
                    // A grade may hold none (the last has no next one to take a time to, a unit's sixth is not researched): it is skipped.
                    val entries = (inner as? List<*>)?.filterIsInstance<Map<*, *>>()?.takeIf { it.isNotEmpty() && it.any { x -> x[field] is Number } } ?: continue
                    val gradeKey = entries.first().keys.firstOrNull { k -> (entries.first()[k] as? Number)?.toLong()?.let { first -> entries.withIndex().all { (n, x) -> (x[k] as? Number)?.toLong() == first + n } } == true } ?: continue
                    return entries to gradeKey.toString()
                }
                return null
            }
            val table = LinkedHashMap<String, Any?>()
            val graded = merge != null && w.endings.any { e ->
                val f = finish(e) ?: return@any false
                val d = list.firstOrNull { it["id"].toString() == valueAt(e.source, key).toString() } ?: return@any false
                (f.srcValue as Number).toLong() - began(e)!! == (d[field] as? Number)?.toLong()
            }
            for (other in listOf(list) + lists.filter { it !== list }) {
                for (d in other) {
                    val top = (d[field] as? Number)?.toInt() ?: continue
                    if (!graded) {
                        table.putIfAbsent(d["id"].toString(), top)
                        continue
                    }
                    // The time of the first grade is the document's own, each next grade's is its entry's.
                    // A document without grades has the time of its first one only.
                    val grades = gradesOf(d)
                    if (grades == null) {
                        table.putIfAbsent("${d["id"]},${(merge!!["default"] as Number).toLong()}", top)
                        continue
                    }
                    val (entries, gradeKey) = grades
                    table.putIfAbsent("${d["id"]},${(entries.first()[gradeKey] as Number).toLong() - 1}", top)
                    entries.forEach { x -> (x[field] as? Number)?.let { table.putIfAbsent("${d["id"]},${(x[gradeKey] as Number).toLong()}", it.toInt()) } }
                }
            }

            // The start: the text, the time, and what the command's own fields put in the element.
            val begin = began(sample)
            val sets = LinkedHashMap<String, Any?>()
            sets[w.field] = w.value
            sets[w.since!!] = "\$optime"
            val under = w.endings.firstOrNull { valueAt(it.source, w.field) == w.value }
            val caller = calls.firstOrNull { it.name == w.command && optime(it) == under?.let { e -> began(e) } }
            for (d in under?.diffs.orEmpty()) {
                val path = inside(under!!, d.path)
                val before = d.srcValue
                if (path in sets || before == null || d.operation != StateDiff.Operation.REPLACE) continue
                val field0 = caller?.xFields?.entries?.firstOrNull { RelationFinder.equalRelation(it.value, before) }?.key
                when {
                    before is Number && d === finish(under) -> sets[path] = "\$end"
                    field0 != null && d.value !is List<*> -> sets[path] = "$$field0"
                }
            }
            val start = linkedMapOf<String, Any?>("command" to w.command, "sets" to sets)
            w.element?.let { start["element"] = it }
            w.article?.let { start["at"] = it }

            // The commands that finished it before its time, and the field that names the element.
            val ends = w.endings.mapNotNull { e ->
                val f = e.finisher ?: return@mapNotNull null
                val direct = f.xFields.entries.firstOrNull { it.value.toString() == idOf(e) }?.key
                if (direct != null) return@mapNotNull linkedMapOf<String, Any?>("command" to f.name, "role" to "force", "element" to direct)
                val leaf = e.diffs.firstOrNull { d -> d.operation == StateDiff.Operation.REPLACE && f.xFields.values.any { RelationFinder.equalRelation(it, d.srcValue) } } ?: return@mapNotNull null
                linkedMapOf<String, Any?>("command" to f.name, "role" to "force", "element" to f.xFields.entries.first { RelationFinder.equalRelation(it.value, leaf.srcValue) }.key, "by" to inside(e, leaf.path))
            }.distinctBy { it["command"] }

            val timer = decorated.indexOfFirst { it["list"] == w.list && it["field"] == w.field && it["value"] == w.value }
            if (timer >= 0) {
                // An upgrade a timer covers: its start and its forces added, the rules already end it.
                decorated[timer]["start"] = linkedMapOf("command" to w.command, "element" to w.element)
                // A force also ends an upgrade the logins never showed under way: the first command that
                // named the element after the start, between the two logins that show its counter up
                // (UnitUpgradeComponent.SpeedUp finishes the upgrade the component holds at once).
                // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#SpeedUp
                // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#startClearing
                // not ported: SpeedUp, startClearing (the cost is the economy's, this only names the command)
                val counter = (decorated[timer]["then"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().firstOrNull { it["op"] == "increment" }?.get("path")?.toString()
                val first = (decorated[timer]["default"] as? Number)?.toLong() ?: 0L
                val hurried = if (w.element == null || counter == null) emptyList() else moments.flatMap { i ->
                    i.between.filter { it.name == w.command }.mapNotNull { s ->
                        val id = s.xFields[w.element]?.toString() ?: return@mapNotNull null
                        val f = i.between.firstOrNull { it.time > s.time && it.name != w.command && it.xFields.values.any { x -> x.toString() == id } } ?: return@mapNotNull null
                        val before = valueAt(i.source.state, "${w.list}/#$id")
                        val after = valueAt(i.target.state, "${w.list}/#$id")
                        val up = ((valueAt(after, counter) as? Number)?.toLong() ?: first) > ((valueAt(before, counter) as? Number)?.toLong() ?: first)
                        f.name.takeIf { before != null && after != null && up && valueAt(after, w.field) != w.value }
                    }
                }
                decorated[timer]["forced"] = (ends.map { it["command"] } + hurried).distinct()
                decorated[timer]["idle"] = linkedMapOf(w.field to ready)
                continue
            }
            val work = linkedMapOf<String, Any?>("list" to w.list, "field" to w.field, "value" to w.value, "idle" to ready, "since" to w.since, "start" to start, "key" to listOf(key), "time" to field, "durations" to table, "then" to then, "ends" to ends)
            if (graded) {
                work["grade"] = linkedMapOf("list" to merge!!["path"], "match" to merge["match"], "field" to merge["field"])
                work["default"] = merge["default"]
            }
            w.condition?.let { work["when"] = it }
            built.add(work)
            if (begin == null) notes.add("  work: $label, the start time is not known")
        }

        // The names the game knows that no recording shows, paired with the works by stem.
        notes.addAll(execute(built + decorated.filter { it["start"] != null }, names, calls.map { it.name }.toSet()))

        // The second base: a work learnt for one value of its when field holds on the part of the state named like another.
        val variants = built.flatMap { work ->
            val condition = (work["when"] as? Map<*, *>)?.takeIf { it.size == 1 } ?: return@flatMap emptyList()
            val (xField, xValue) = condition.entries.single()
            valuesOf(statics, xField.toString()).filter { it != xValue }.mapNotNull { value ->
                moved(work["list"].toString(), value, snapshots.last().state)?.let { path ->
                    LinkedHashMap(work).apply {
                        put("list", path)
                        put("when", mapOf(xField.toString() to value))
                    }
                }
            }
        }
        return Works(built + variants, decorated, notes)
    }

    // families: UCS gives every timed action a start, a SpeedUp and a Cancel (CancelConstructionCommand
    // cancels a construction or a clearing, SpeedUpClearingCommand and SpeedUpUpgradeUnitCommand finish
    // one at once). The game names them by stem: X, force_X, cancel_X, X_start, X_force. A name the
    // game's library holds that no recording shows, made of a role word and the stem of a work's
    // start command, is that work's force or cancel. The role word of a force is the one the
    // forces recorded add to their start's stem, the word of a cancel is UCS's. The element is named
    // by the field the recorded force names it with, else the start's. The stem is the start command
    // whole or without its first or last word (upgrade_action, unit_upgrade_start), the work whose
    // stem is whole wins, and a stem two works share is left out.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/SpeedUpClearingCommand.cs#Execute
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/CancelConstructionCommand.cs#Execute
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/SpeedUpUpgradeUnitCommand.cs#Execute
    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
    // not ported: SpeedUpClearingCommand, CancelConstructionCommand, SpeedUpUpgradeUnitCommand, br.ReadInt32WithEndian, level.GameObjectManager (a command is read from its BSON fields, the objects are the elements of the state)
    // not ported: go.ClassId (a work is told by its end: an element removed is an obstacle, any other a construction item)
    // not ported: timerRules (kept as it is, its timers are the bases here once startClearing gave them their start)
    private fun execute(bases: List<MutableMap<String, Any?>>, names: Set<String>, recorded: Set<String>): List<String> {
        val lines = mutableListOf<String>()
        fun stems(command: String): List<Pair<List<String>, Int>> {
            val words = command.split('_')
            return listOf(words to 0) + (if (words.size > 1) listOf(words.drop(1) to 1, words.dropLast(1) to 1) else emptyList())
        }
        fun startOf(go: Map<String, Any?>): String = (go["start"] as Map<*, *>)["command"].toString()
        fun forcesOf(go: Map<String, Any?>): List<Map<*, *>> =
            (go["ends"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().filter { it["role"] == "force" && it["deduced"] != true } + (go["forced"] as? List<*>).orEmpty().map { mapOf("command" to it) }
        // The role words: the one each recorded force adds to its start's stem, then UCS's cancel.
        val roles = LinkedHashMap<String, String>()
        for (go in bases) {
            for (force in forcesOf(go)) {
                val words = force["command"].toString().split('_')
                for ((stem, _) in stems(startOf(go))) {
                    if (words.size != stem.size + 1) continue
                    if (words.drop(1) == stem) roles[words.first()] = "force" else if (words.dropLast(1) == stem) roles[words.last()] = "force"
                }
            }
        }
        roles["cancel"] = "cancel"
        // A recorded force names the stem of its work too (force_construct, for the command that buys a building).
        // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/CancelConstructionCommand.cs#Execute
        // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
        // not ported: timerRules (kept as it is), execute (this function is the one above, stemsOf only adds the stems the recorded forces show)
        fun stemsOf(go: Map<String, Any?>): List<Pair<List<String>, Int>> = stems(startOf(go)) + forcesOf(go).mapNotNull { force ->
            val words = force["command"].toString().split('_')
            if (words.size > 1 && roles[words.first()] == "force") words.drop(1) to 0 else if (words.size > 1 && roles[words.last()] == "force") words.dropLast(1) to 0 else null
        }
        val taken = bases.flatMap { go -> (go["ends"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { it["command"].toString() } + startOf(go) }.toSet()
        for (name in names.filter { it !in recorded && it !in taken }.sorted()) {
            val words = name.split('_')
            for ((word, role) in roles) {
                val stem = if (words.size > 1 && words.first() == word) words.drop(1) else if (words.size > 1 && words.last() == word) words.dropLast(1) else continue
                val matches = bases.mapNotNull { go -> stemsOf(go).filter { it.first == stem }.minByOrNull { it.second }?.let { go to it.second } }
                val best = matches.filter { it.second == matches.minOf { m -> m.second } }
                if (best.size != 1) {
                    if (matches.isNotEmpty()) lines.add("  family left out: $name could be the $role of ${matches.joinToString { startOf(it.first) }}")
                    continue
                }
                val go = best.single().first
                // The field that names the element: the recorded force's, else the start's.
                val force = forcesOf(go).firstOrNull()
                val element = force?.get("element") ?: (go["start"] as Map<*, *>)["element"]
                if (element == null) {
                    lines.add("  family left out: $name, no field names the element of ${startOf(go)}")
                    continue
                }
                val end = linkedMapOf<String, Any?>("command" to name, "role" to role, "element" to element, "deduced" to true)
                force?.get("by")?.let { end["by"] = it }
                go["ends"] = (go["ends"] as? List<*>).orEmpty() + end
                // An element removed at its end is an obstacle, any other work a construction item.
                val obstacle = (go["then"] as? List<*>).orEmpty().any { it is Map<*, *> && it["op"] == "remove" && it["path"] == null }
                lines.add("  family (deduced): $name ${role}s the ${if (obstacle) "clearing" else "work"} of ${startOf(go)}")
            }
        }
        return lines
    }

    // carried fields: MoveBuildingCommand finds the object BuildingId names (go) and sets its
    // position to X and Y, the fields of the command that share their name with the object's. Here
    // a command whose field names an element of a list present in both logins (go != null) sets
    // each other field it carries that the element holds under the same name, learnt only when
    // it holds in every call of the command the logins show, as RelationFinder keeps a relation that
    // holds for every pair. A command never recorded gets the same, marked as deduced by family.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/MoveBuildingCommand.cs#Execute
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#RelationFinder
    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
    // not ported: MoveBuildingCommand, br.ReadInt32WithEndian, level.GameObjectManager, SetPositionXY (a command is read from its BSON fields, the position is two fields of the element)
    // not ported: __init__, findOnSymbol, findOnFields, executeOnSymbol, executeOnFields, _findRelationType, _checkEqualityRelation, _equalRelation, _sizeRelation (RelationFinder.kt ports the relation, executeOnFields is called)
    // not ported: _generateAttributeValuesForSymbol, _getAllFieldsValues, _generateConcatData, _generateDataValues, _generateSizeValues, returns (the values are the fields of the calls and of the elements)
    // not ported: timerRules (kept as it is, the carried fields are its twin for a command that sets no time)
    private fun execute(snapshots: List<Snapshot>, calls: List<Call>): List<Map<String, Any?>> {
        val moments = intervals(snapshots, calls)
        val results = mutableListOf<Map<String, Any?>>()
        val lists = snapshots.flatMap { elementLists(it.state).keys }.distinct()
        for ((name, group) in calls.groupBy { it.name }) {
            for (list in lists) {
                val ids = snapshots.flatMap { s -> elementLists(s.state)[list].orEmpty().map { it["id"].toString() } }.toSet()
                for (xField in group.flatMap { it.xFields.keys }.toSet().filter { x -> group.all { it.xFields[x]?.toString() in ids } }) {
                    // The last call naming each element between two logins, when the element is in both.
                    val seen = moments.flatMap { i ->
                        i.between.filter { it.name == name }.groupBy { it.xFields[xField].toString() }.mapNotNull { (id, same) ->
                            val go = elementLists(i.target.state)[list]?.firstOrNull { it["id"].toString() == id }
                            if (go == null || elementLists(i.source.state)[list]?.any { it["id"].toString() == id } != true) null else same.last() to go
                        }
                    }
                    if (seen.isEmpty()) continue
                    for (yField in seen.first().first.xFields.keys.filter { it != xField && it != "id" && seen.all { (_, go) -> go.containsKey(it) } }) {
                        if (RelationFinder.executeOnFields(seen.map { it.first.fields[yField] }, seen.map { it.second[yField] }, allowConstant = true)) {
                            results.add(linkedMapOf("command" to name, "op" to "set", "path" to "$list/#$$xField/$yField", "value" to "$$yField", "carried" to true))
                        }
                    }
                }
            }
        }
        return results
    }

    // The grade of a unit in an answer rule: the number an answer rule learnt as a constant
    // (the 1 of each unit a mission sends) is the grade of the unit the answer lists, when a work
    // merged a list by a key the unit's field shares the name of (the units the laboratory researched).
    // The element holding the list is the one the work starts at, the grade of a unit not there the
    // default. UnitUpgradeComponent reads it as GetUnitUpgradeLevel of the unit the lab upgrades.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#GetTotalSeconds
    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#answerRules
    // not ported: GetTotalSeconds (the time is the work's durations table, GameServer reads it), answerRules (called as it is, the grade is put in the lists it learnt)
    // not ported: CanStartUpgrading, FinishUpgrading, GetCurrentlyUpgradedUnit, GetRemainingSeconds, Load, Save, SpeedUp, StartUpgrading, Tick (GameServer ports them, this part only reads the grade)
    private fun getUnitUpgradeLevel(answers: List<Map<String, Any?>>, works: List<Map<String, Any?>>): List<Map<String, Any?>> = answers.map { answer ->
        val each = answer["each"] as? List<*> ?: return@map answer
        for (work in works) {
            val merge = (work["then"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { it["op"] == "merge" } ?: continue
            val at = (work["start"] as Map<*, *>)["at"] ?: continue
            val unit = each.indexOf("$" + merge["match"])
            val level = each.indexOfFirst { it is Number && it.toLong() == (merge["default"] as Number).toLong() }
            if (unit < 0 || level < 0) continue
            val grade = linkedMapOf("of" to merge["match"], "list" to "${work["list"]}/$at/${merge["path"]}", "match" to merge["match"], "field" to merge["field"], "default" to merge["default"])
            return@map LinkedHashMap(answer).apply { put("each", each.mapIndexed { n, x -> if (n == level) linkedMapOf("grade" to grade) else x }) }
        }
        answer
    }

    // The documents of a list, null when it is empty or holds anything else.
    private fun documents(value: Any?): List<Map<*, *>>? = (value as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is Map<*, *> } }?.map { it as Map<*, *> }

    // The lists of a state whose elements all hold an id (a game's buildings), by their path.
    private fun elementLists(state: Map<String, Any?>): Map<String, List<Map<*, *>>> = stateValues(state).entries
        .filter { (path, place) -> '#' !in path && place.pattern == path }
        .mapNotNull { (path, place) -> documents(place.value)?.takeIf { l -> l.all { it["id"] != null } }?.let { path to it } }.toMap()

    // The lists of documents a call sent, empty ones too, at any depth (attacker/units).
    private fun listsIn(value: Any?, path: String): List<Pair<String, List<Map<*, *>>>> = when (value) {
        is Map<*, *> -> value.entries.flatMap { (k, v) -> listsIn(v, if (path.isEmpty()) k.toString() else "$path/$k") }
        is List<*> -> if (value.all { it is Map<*, *> }) listOf(path to value.map { it as Map<*, *> }) else emptyList()
        else -> emptyList()
    }

    // production: units queued in an element (a barracks) leave the head of its queue
    // one after the other, each after the time of its unit, for the camps. Seen between
    // two logins in an element no call named: the count of the head slot went down by k
    // and a time went up by k times a gap, which is a field of the document of the data
    // whose id is the unit (perUnit), while the elements of one kind gained the k units
    // (the camps, every element with that kind). The commands come from the calls that
    // named a producer: the one whose fields held the unit and the units trained
    // (barracks and camps together) puts units in the queue, the one that emptied the
    // queue sends them all, the one that held the units left after a battle sets the
    // camps. Each field is found by equal values, as RelationFinder finds a relation.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#RelationFinder
    // not ported: timerRules (called as it is for the works that end a state, this one is its twin for the queues)
    // not ported: __init__, findOnSymbol, findOnFields, executeOnSymbol, executeOnFields, _findRelationType, _checkEqualityRelation, _equalRelation, _sizeRelation (RelationFinder.kt ports the relation, equalRelation is called)
    // not ported: _generateAttributeValuesForSymbol, _getAllFieldsValues, _generateConcatData, _generateDataValues, _generateSizeValues, returns (the values are the fields of the calls and of the elements)
    private fun productionRules(snapshots: List<Snapshot>, calls: List<Call>, statics: List<Any?>, rules: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val results = LinkedHashMap<String, MutableMap<String, Any?>>()
        val kinds = HashMap<String, String>()
        val named = GameState.named(statics)
        for (i in intervals(snapshots, calls)) {
            val before = elementLists(i.source.state)
            for ((list, elements) in elementLists(i.target.state)) {
                val old = before[list] ?: continue
                for (target in elements) {
                    val id = target["id"].toString()
                    val source = old.firstOrNull { it["id"].toString() == id } ?: continue
                    if (i.between.any { c -> c.xFields.values.any { it.toString() == id } }) continue
                    for ((queue, place) in stateValues(target)) {
                        val slots = documents(place.value) ?: continue
                        val was = documents(valueAt(source, queue)) ?: continue
                        if (slots.size != was.size || (1 until slots.size).any { k -> !StateDiff.same(slots[k], was[k]) }) continue
                        val head = slots[0]
                        val count = head.keys.firstOrNull { k -> ((was[0][k] as? Number)?.toLong() ?: 0L) > ((head[k] as? Number)?.toLong() ?: Long.MAX_VALUE) } ?: continue
                        val lost = (was[0][count] as Number).toLong() - (head[count] as Number).toLong()
                        // The time the head ends: a number over a billion that went up by k gaps.
                        val times = i.diffs.filter { d ->
                            val t2 = (d.value as? Number)?.toLong()
                            val t1 = (d.srcValue as? Number)?.toLong()
                            d.path.startsWith("$list/#$id/") && t1 != null && t2 != null && t1 > 1_000_000_000L && t2 > t1 && (t2 - t1) % lost == 0L
                        }
                        val end = times.singleOrNull() ?: continue
                        val duration = ((end.value as Number).toLong() - (end.srcValue as Number).toLong()) / lost
                        for ((unit, value) in head) {
                            if (unit == count || !StateDiff.same(was[0][unit], value)) continue
                            val (_, table) = perUnit(statics, value, duration) ?: continue
                            val held = { e: Map<*, *>? -> documents(valueAt(e, queue))?.filter { StateDiff.same(it[unit], value) }?.sumOf { (it[count] as? Number)?.toLong() ?: 0L } ?: 0L }
                            val gained = elements.filter { e -> e !== target && held(e) - held(old.firstOrNull { it["id"].toString() == e["id"].toString() }) == lost }
                            // The kind of the camps: the field every element holds the id of a document of the data in.
                            val key = GameState.naming(elements, named)?.first ?: continue
                            val camp = gained.firstOrNull()?.get(key) ?: continue
                            // The position: the numbers every element holds, a time (ctime) or an id left out.
                            val near = elements.first().keys.map { it.toString() }.filter { k -> k != "id" && k != key && elements.all { (it[k] as? Number)?.let { n -> n.toLong() <= 1_000_000_000L } == true } }
                            val base = "$list $queue"
                            results.putIfAbsent(base, linkedMapOf("list" to list, "queue" to queue, "unit" to unit.toString(), "count" to count.toString(), "finish" to end.path.removePrefix("$list/#$id/"), "durations" to table, "camp" to linkedMapOf("key" to key, "value" to camp, "near" to near)))
                            kinds.putIfAbsent(base, target[key].toString())
                        }
                    }
                }
            }
        }
        val learnt = mutableListOf<Map<String, Any?>>()
        for ((base, rule) in results) {
            val list = rule["list"] as String
            val queue = rule["queue"] as String
            val unit = rule["unit"] as String
            val count = rule["count"] as String
            val camp = rule["camp"] as Map<*, *>
            val key = camp["key"] as String
            val kind = kinds.getValue(base)
            fun held(e: Map<*, *>?, value: Any?): Long = documents(valueAt(e, queue))?.filter { StateDiff.same(it[unit], value) }?.sumOf { (it[count] as? Number)?.toLong() ?: 0L } ?: 0L
            val trains = LinkedHashMap<String, MutableList<Set<Triple<String, String, String>>>>()
            val forces = LinkedHashMap<String, MutableList<Set<String>>>()
            val lefts = LinkedHashMap<Pair<String, String>, MutableList<Pair<List<Map<*, *>>, Map<String, Long>>>>()
            for (i in intervals(snapshots, calls)) {
                val old = elementLists(i.source.state)[list].orEmpty()
                val elements = elementLists(i.target.state)[list] ?: continue
                val camps = elements.filter { it[key].toString() == camp["value"].toString() }
                val producers = elements.filter { it[key].toString() == kind }
                for (producer in producers) {
                    val id = producer["id"].toString()
                    val last = i.between.lastOrNull { c -> c.xFields.values.any { it.toString() == id } } ?: continue
                    val fields = last.xFields.filter { it.value.toString() == id }.keys
                    val slots = documents(valueAt(producer, queue))
                    if (slots == null) {
                        if (documents(valueAt(old.firstOrNull { it["id"].toString() == id }, queue)) != null) forces.getOrPut(last.name) { mutableListOf() }.add(fields)
                        continue
                    }
                    // The units trained: the barracks' and the camps' gain, together.
                    val combos = last.xFields.keys.filter { it !in fields }.flatMap { u ->
                        val value = last.fields[u]
                        if (slots.none { RelationFinder.equalRelation(value, it[unit]) }) return@flatMap emptyList()
                        val total = held(producer, value) + camps.sumOf { held(it, value) } - old.filter { it[key].toString() == camp["value"].toString() }.sumOf { held(it, value) }
                        last.xFields.filter { (a, x) -> a !in fields && a != u && RelationFinder.equalRelation(x, total) }.keys.flatMap { a -> fields.map { f -> Triple(f, u, a) } }
                    }.toSet()
                    trains.getOrPut(last.name) { mutableListOf() }.add(combos)
                }
                // The units left after a battle: what the camps hold once nothing is in production.
                if (producers.all { documents(valueAt(it, queue)) == null }) {
                    val merged = LinkedHashMap<String, Long>()
                    for (c in camps) documents(valueAt(c, queue)).orEmpty().forEach { s -> merged.merge(s[unit].toString(), (s[count] as? Number)?.toLong() ?: 0L, Long::plus) }
                    for (c in i.between.groupBy { it.name }.values.map { it.last() }) for ((path, docs) in listsIn(c.fields, "")) lefts.getOrPut(c.name to path) { mutableListOf() }.add(docs to merged)
                }
            }
            val (train, combo) = trains.entries.firstNotNullOfOrNull { (name, seen) -> seen.reduce { x, y -> x intersect y }.singleOrNull()?.let { name to it } } ?: continue
            val force = forces.entries.filter { (name, seen) -> name != train && seen.all { it.size == 1 } }.maxByOrNull { it.value.size }
            val field = force?.value?.first()?.single()
            // What the command that sends the queue sets in the producer: the constants the rules learnt for it.
            val idle = LinkedHashMap<String, Any?>()
            if (force != null) rules.filter { r -> r["command"] == force.key && r["op"] == "set" && (r["path"] as String).startsWith("$list/#$$field/") && !(r["value"] is String && (r["value"] as String).startsWith("$")) }
                .forEach { r -> idle[(r["path"] as String).removePrefix("$list/#$$field/")] = r["value"] }
            val left = lefts.entries.filter { (k, _) -> k.first != train && k.first != force?.key }.firstNotNullOfOrNull { (k, seen) ->
                val shown = seen.filter { (docs, _) -> docs.isNotEmpty() }
                if (shown.isEmpty() || seen.any { (docs, merged) -> docs.isEmpty() && merged.isNotEmpty() }) return@firstNotNullOfOrNull null
                val fields = shown.first().first.first().keys.map { it.toString() }
                val pairs = fields.flatMap { u -> fields.filter { it != u }.map { f -> u to f } }.filter { (u, f) ->
                    shown.all { (docs, merged) -> docs.groupBy { it[u].toString() }.mapValues { (_, g) -> g.sumOf { (it[f] as? Number)?.toLong() ?: 0L } } == merged }
                }
                pairs.singleOrNull()?.let { (u, f) -> linkedMapOf("command" to k.first, "path" to k.second, "unit" to u, "count" to f) }
            }
            val production = LinkedHashMap(rule)
            production["train"] = linkedMapOf("command" to train, "element" to combo.first, "unit" to combo.second, "count" to combo.third)
            if (force != null) production["force"] = linkedMapOf("command" to force.key, "element" to field, "idle" to idle)
            if (left != null) production["left"] = left
            learnt.add(production)
        }
        return learnt
    }

    // The duration of a unit in the game's data: the document whose id is the queued
    // unit, and in it the numeric field equal to the gap seen per unit. Every document
    // of the list gives its duration, keyed by its id, then those of the lists written
    // alike (the second base has its own file of units), an id already given kept.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#timerRules
    // not ported: timerRules (called as it is, the lookup it makes is lookup, perUnit is its twin for a document without grades)
    private fun perUnit(statics: List<Any?>, unit: Any?, duration: Long): Pair<String, Map<String, Any?>>? {
        val lists = mutableListOf<List<Map<*, *>>>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> node.values.forEach { walk(it) }
                is List<*> -> if (node.isNotEmpty() && node.all { it is Map<*, *> && it["id"] != null }) lists.add(node.map { it as Map<*, *> }) else node.forEach { walk(it) }
            }
        }
        statics.forEach { walk(it) }
        for (list in lists) {
            val document = list.firstOrNull { it["id"].toString() == unit.toString() } ?: continue
            val field = document.entries.firstOrNull { (k, v) -> k != "id" && v is Number && v.toLong() == duration }?.key?.toString() ?: continue
            val table = LinkedHashMap<String, Any?>()
            for (other in listOf(list) + lists.filter { o -> o !== list && o.any { d -> list.any { it["id"].toString() == d["id"].toString() } } }) {
                for (d in other) (d[field] as? Number)?.let { table.putIfAbsent(d["id"].toString(), it.toInt()) }
            }
            return field to table
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
                val paths = group.flatMap { c -> getValues(c.answer!!).filter { (p, v) -> p != command && RelationFinder.equalRelation(c.fields[xField], v) }.keys }.distinct()
                for (yField in paths) {
                    // The relation is checked on the samples whose answer has the path: one
                    // without it (a mission's answer of another shape) says nothing about it,
                    // and one that has it with another value drops this rule alone.
                    val held = group.filter { it.fields[xField] != null && valueAt(it.answer, yField) != null }
                    val xValues = held.map { it.fields[xField] }
                    if (RelationFinder.executeOnFields(xValues, held.map { valueAt(it.answer, yField) })) results.add(linkedMapOf("command" to name, "path" to yField, "value" to "$$xField"))
                }
            }
        }
        return results
    }

    // A list or a document of a state, where it lies and the same place written for
    // any element: a * in the pattern for each element of a list the path went through.
    private class Place(val pattern: String, val value: Any?)

    // Whether a value holds a number or a text somewhere: a bare [] or {} proves nothing.
    private fun filled(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.values.any { filled(it) }
        is List<*> -> value.any { filled(it) }
        else -> value != null
    }

    // The lists and documents of a state, with the paths valueAt reads: a list whose
    // elements all hold an id is walked by #id, any other by place.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    // not ported: valueAt (called as it is, on the paths this gives)
    private fun stateValues(value: Any?): Map<String, Place> {
        val out = LinkedHashMap<String, Place>()
        fun walk(v: Any?, path: String, pattern: String) {
            when (v) {
                is Map<*, *> -> {
                    if (path.isNotEmpty() && v.isNotEmpty()) out[path] = Place(pattern, v)
                    v.forEach { (k, x) -> walk(x, if (path.isEmpty()) k.toString() else "$path/$k", if (pattern.isEmpty()) k.toString() else "$pattern/$k") }
                }
                is List<*> -> {
                    if (path.isNotEmpty() && v.isNotEmpty()) out[path] = Place(pattern, v)
                    val byId = v.all { it is Map<*, *> && it["id"] != null }
                    v.forEachIndexed { i, x -> walk(x, "$path/" + if (byId) "#${(x as Map<*, *>)["id"]}" else "$i", "$pattern/*") }
                }
            }
        }
        walk(value, "", "")
        return out
    }

    // A path through an element of a camp, written for every camp: the element
    // #id of the list becomes *key=value, the elements of that kind. Null for any other path.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#answerRules
    // not ported: answerRules (called as it is, camped only names the place it reads)
    private fun camped(path: String, state: Map<String, Any?>, production: List<Map<String, Any?>>): String? {
        for (rule in production) {
            val list = rule["list"].toString()
            val camp = rule["camp"] as Map<*, *>
            if (!path.startsWith("$list/#")) continue
            val id = path.removePrefix("$list/#").substringBefore('/')
            val element = valueAt(state, "$list/#$id") as? Map<*, *> ?: continue
            if (element[camp["key"]].toString() == camp["value"].toString()) return "$list/*${camp["key"]}=${camp["value"]}" + path.removePrefix("$list/#$id")
        }
        return null
    }

    // observed: whether a rule's command was seen to change what the rule names: in
    // one of the intervals it was sent in, the value at the rule's path is not the
    // same in the login before and the login after (RelationFinder's x_values and
    // y_values, one pair per interval, here the pair must differ once). A command
    // whose rule rests on a value it left as it was (a read that names an id by
    // chance) changes nothing, so it does not make the samples after it unclean.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#L236-L260
    // not ported: concrete, intervals (called as they are), general, added (the rule's path is already general, no diff is read)
    // not ported: relation_fcts, _sizeRelation, _equalRelation (the pair is compared by StateDiff.same)
    private fun observed(rule: Map<String, Any?>, intervals: List<Interval>): Boolean = intervals.any { interval ->
        interval.between.any { call ->
            call.name == rule["command"] && concrete(rule["path"].toString(), call).let { path ->
                val xValues = valueAt(interval.source.state, path)
                val yValues = valueAt(interval.target.state, path)
                !StateDiff.same(xValues, yValues)
            }
        }
    }

    // answer rules read from the state: a path of a command's answer that is, in every
    // clean sample, a list or a document of the state (equal, at the deepest place
    // that is the only one), or a list of lists each made from a document of a list of
    // the state, one of its fields or one constant at each place. A sample is clean
    // when no call that changes the state lies between its login and the call: the
    // state it echoed is the login's (a call that only reads, a list of zones or
    // missions, leaves it as it is). The place may be the elements of a camp, every
    // one of them: the units of a mission are those of all the camps, not of one.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#answerRules
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    private fun answerRules(snapshots: List<Snapshot>, calls: List<Call>, command: String, changing: Set<String>, production: List<Map<String, Any?>> = emptyList()): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for ((name, group) in calls.filter { it.answer != null }.groupBy { it.name }) {
            val samples = group.mapNotNull { c ->
                val login = snapshots.lastOrNull { it.time <= c.time } ?: return@mapNotNull null
                if (calls.any { it !== c && it.name in changing && it.time > login.time && it.time <= c.time }) null else c.answer!! to login.state
            }
            if (samples.isEmpty()) continue
            val states = samples.map { (_, state) -> stateValues(state) }
            // The paths of the answers of every clean sample, in the order they are met.
            val answered = LinkedHashMap<String, Any?>()
            samples.forEach { (answer, _) -> getValues(answer, whole = true).forEach { (p, v) -> answered.putIfAbsent(p, v) } }
            val done = mutableListOf<String>()
            for ((yField, y) in answered) {
                if (yField == command || done.any { yField.startsWith("$it/") } || !filled(y) || (y !is Map<*, *> && y !is List<*>)) continue
                // The rule holds in every clean sample whose answer has the path, as the
                // relation is kept over the samples it is checked on: one of another shape
                // (a mission's answer without heroes) leaves it as it is.
                val held = samples.indices.filter { valueAt(samples[it].first, yField) != null }
                val clean = held.map { samples[it] }
                val places = held.map { states[it] }
                // The places of the state holding the value in every sample, the deepest alone.
                val paths = places.first().filter { (_, p) -> RelationFinder.equalRelation(p.value, y) }.keys
                    .filter { x -> RelationFinder.executeOnFields(clean.map { (_, state) -> valueAt(state, x) }, clean.map { (answer, _) -> valueAt(answer, yField) }, allowConstant = true) }
                val deepest = paths.maxOfOrNull { it.count { c -> c == '/' } }
                val xField = paths.filter { it.count { c -> c == '/' } == deepest }.singleOrNull()
                if (xField != null) {
                    results.add(linkedMapOf("command" to name, "path" to yField, "op" to "state", "from" to xField))
                    done.add(yField)
                    continue
                }
                // A list of lists, each made from a document of the lists at one place of the
                // state: a path, or a pattern with a * for each element of a list. A place is
                // better with more fields read, and the pattern before the path it covers.
                // A constant is the same in every answer the command gave, clean or not.
                if (y !is List<*> || !y.all { it is List<*> && it.isNotEmpty() && it.size == (y.first() as List<*>).size }) continue
                val lists = clean.map { (answer, _) -> (valueAt(answer, yField) as? List<*>).orEmpty().filterIsInstance<List<*>>() }
                val everything = group.flatMap { c -> (valueAt(c.answer, yField) as? List<*>).orEmpty().filterIsInstance<List<*>>() }
                val scored = mutableListOf<Triple<String, List<Any?>, Int>>()
                for (place in places.first().entries.filter { (_, p) -> p.value is List<*> && (p.value as List<*>).all { it is Map<*, *> } }.flatMap { (path, p) -> listOfNotNull(p.pattern, path, camped(path, clean.first().second, production)) }.distinct()) {
                    val documents = places.mapIndexed { k, ps -> ps.filter { (path, p) -> p.pattern == place || path == place || camped(path, clean[k].second, production) == place }.values.flatMap { (it.value as List<*>).filterIsInstance<Map<*, *>>() } }
                    if (documents.indices.any { documents[it].size != lists[it].size }) continue
                    val each = (0 until (y.first() as List<*>).size).map { j ->
                        val xValues = documents.flatten()
                        val yValues = lists.flatten().map { it[j] }
                        val constants = everything.map { it.getOrNull(j) }
                        val fields = xValues.flatMap { it.keys }.distinct().filter { f -> RelationFinder.executeOnFields(xValues.map { it[f] }, yValues, allowConstant = yValues.size == 1) }
                        when {
                            fields.size == 1 -> "$${fields.single()}"
                            fields.isEmpty() && constants.all { RelationFinder.equalRelation(constants.first(), it) } && !(constants.first() is String && (constants.first() as String).startsWith("$")) -> constants.first()
                            else -> return@map null
                        }
                    }
                    if (null in each) continue
                    scored.add(Triple(place, each, each.count { it is String && it.startsWith("$") } * 2 + if ('*' in place) 1 else 0))
                }
                val top = scored.filter { it.third == scored.maxOf { s -> s.third } }
                if (top.size == 1) {
                    results.add(linkedMapOf("command" to name, "path" to yField, "op" to "state", "from" to top.single().first, "each" to top.single().second))
                    done.add(yField)
                }
            }
        }
        return results
    }

    // refusals: a message of a command's answer named after the command's first
    // word, not the command itself (attack_failed for attack_random), holding
    // nothing but its name and numbers: the server's reasons to refuse. They were
    // its verdict on the client recorded (a changed APK, "Hacked Client" for
    // Jungle Heat, which the game answers with more than a message), so the
    // local server keeps the refusal and removes its reasons: the game shows
    // its plain refusal and stays where it was.
    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#remove
    // not ported: remove (the op is the phone's, GameServer's Patch applies it, the rule names it)
    // not ported: InPlaceApplyProcessor, result, setFlags, move, copy, test, add, replace, set, error (a refusal only loses its reasons)
    private fun refusalRules(calls: List<Call>, command: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for ((name, group) in calls.filter { it.answer != null }.groupBy { it.name }) {
            for (m in group.flatMap { messages(it.answer!!, command) }) {
                val refusal = m[command] as String
                val reasons = m.keys.filter { it != command }
                if (refusal == name || refusal.substringBefore('_') != name.substringBefore('_') || reasons.isEmpty() || !reasons.all { m[it] is Number }) continue
                reasons.forEach { k -> results.add(linkedMapOf("command" to name, "op" to "remove", "path" to "$refusal/$k")) }
            }
        }
        return results.distinct()
    }

    // progress of an answer: a command answered with a list of entries, each a
    // list with an id first ([1, 0, 500, 500]), whose entry changed between two
    // answers to one request. The entry is the one whose id a command sent in
    // between held in one field every time (attack_mission's missionId). Each
    // number of it that changed is the value of the one field of a command sent
    // in between that held it (attack_finish's starsCount), else the value it
    // took every time. The phone changes the entry when that command comes and
    // answers the next request with it.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    // not ported: the relations of size and of data (a value is the field's or the one seen)
    private fun progressRules(calls: List<Call>, command: String): List<Map<String, Any?>> {
        class Change(val path: String, val id: String, val numbers: Map<Int, Pair<Any?, Any?>>, val between: List<Call>)
        val changes = mutableListOf<Pair<String, Change>>()
        for ((name, group) in calls.filter { it.answer != null }.groupBy { it.name }) {
            for (same in group.groupBy { (it.fields - SKIPPED).toString() }.values) {
                for ((a, b) in same.zipWithNext()) {
                    val between = calls.subList(calls.indexOf(a) + 1, calls.indexOf(b))
                    for ((path, before) in a.answer!!) {
                        val after = b.answer!![path] as? List<*> ?: continue
                        if (before !is List<*> || command == path) continue
                        val old = before.filterIsInstance<List<*>>().filter { it.firstOrNull() is Number }.associateBy { it.first().toString() }
                        for (entry in after.filterIsInstance<List<*>>().filter { it.firstOrNull() is Number }) {
                            val was = old[entry.first().toString()] ?: continue
                            val numbers = entry.indices.filter { it > 0 && was.getOrNull(it).toString() != entry[it].toString() }.associateWith { was.getOrNull(it) to entry[it] }
                            if (numbers.isNotEmpty()) changes.add(name to Change(path, entry.first().toString(), numbers, between))
                        }
                    }
                }
            }
        }
        val results = mutableListOf<Map<String, Any?>>()
        for ((key, group) in changes.groupBy { (name, c) -> name to c.path }) {
            val seen = group.map { it.second }
            // The fields of the commands sent in between that held a value every time.
            fun holders(value: (Change) -> String, other: (Change) -> String?): List<Pair<String, String>> = seen.first().between.flatMap { c -> c.xFields.keys.map { c.name to it } }.distinct().filter { (name, field) ->
                seen.all { s -> s.between.any { c -> c.name == name && c.fields[field].toString() == value(s) && c.fields[field].toString() != other(s) } }
            }
            val (keyCommand, keyField) = holders({ it.id }, { null }).firstOrNull() ?: continue
            val values = linkedMapOf<String, Any?>()
            var trigger: String? = null
            for (index in seen.flatMap { it.numbers.keys }.toSet()) {
                val changed = seen.filter { index in it.numbers }
                if (changed.size < seen.size) continue
                val bound = holders({ it.numbers.getValue(index).second.toString() }, { it.numbers.getValue(index).first.toString() })
                val taken = changed.map { it.numbers.getValue(index).second }
                when {
                    bound.size == 1 -> {
                        values[index.toString()] = "$" + bound.single().second
                        trigger = trigger ?: bound.single().first
                    }
                    taken.all { it.toString() == taken.first().toString() } -> values[index.toString()] = taken.first()
                }
            }
            if (values.isEmpty()) continue
            results.add(linkedMapOf("command" to (trigger ?: keyCommand), "answer" to key.first, "path" to key.second, "key" to linkedMapOf("command" to keyCommand, "field" to keyField), "values" to values))
        }
        return results
    }

    // A local game server whose state holds every element its instances can add
    // (every hero of the data in the player's list): a new player starts with them.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#files
    // not ported: files (the learnt server is given back, written by files as any)
    // The game itself keeps a document locked while its conditions are not met (a
    // hero's level 35, a clan), so its data file in the APK goes without them: the
    // field of the documents whose instances are added that is named like a message
    // of the state, the conditions the server tracks ("restrictions"). The lists of
    // check values the game compares are left as they are, the game then reads the
    // file of the APK as its own.
    @Suppress("UNCHECKED_CAST")
    fun unlocked(learned: Learned, apk: Path? = null): Learned {
        val instances = (learned.rules["instances"] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>()
        return Learned(learned.origin, learned.rules, GameState.added(learned.state, instances), learned.lines, unlockedData(apk, learned, instances))
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#statics
    // not ported: statics (the recordings are not data files of the APK, a file is written back by its name)
    private fun unlockedData(apk: Path?, learned: Learned, instances: List<Map<String, Any?>>): Map<String, ByteArray> {
        if (apk == null || !Files.isRegularFile(apk) || instances.isEmpty()) return emptyMap()
        val ids = instances.flatMap { (it["entries"] as? List<*>).orEmpty() }.mapNotNull { (it as? Map<*, *>)?.get("id")?.toString() }.toSet()
        val names = (learned.state["messages"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get(learned.rules["command"]) as? String }.toSet()
        val out = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipFile(apk.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.startsWith("assets/") || !entry.name.endsWith(".json") || entry.size !in 2..8_000_000L) continue
                val text = zip.getInputStream(entry).use { String(it.readAllBytes(), Charsets.UTF_8) }
                val documents = runCatching { io.gutapk.tools.Json.parse(text.trimStart('﻿')) }.getOrNull() as? List<*> ?: continue
                var changed = false
                val edited = documents.map { d ->
                    if (d !is Map<*, *> || d["id"]?.toString() !in ids) return@map d
                    val kept = d.filterKeys { k -> !(k.toString() in names && d[k] is List<*>) }
                    if (kept.size != d.size) changed = true
                    kept
                }
                if (changed) out[entry.name] = StringBuilder().also { json(edited, it) }.toString().toByteArray(Charsets.UTF_8)
            }
        }
        return out
    }

    // A value written as JSON, org.json's writeValue on the maps, lists and texts Json.parse gives.
    // https://github.com/stleary/JSON-java/blob/master/src/main/java/org/json/JSONObject.java#quote
    // not ported: quote (its loop is the String branch below, written to a StringBuilder, not a Writer)
    // not ported: writeAsHex (a character outside ASCII is written as it is, the file is UTF-8)
    // not ported: JSONObject, JSONArray, JSONString, numberToString, doubleToString, stringToValue, testValidity, wrap, write, toString (the values are the parser's own)
    private fun json(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> {
                out.append('"')
                var b: Char
                var c = 0.toChar()
                for (i in value.indices) {
                    b = c
                    c = value[i]
                    when (c) {
                        '\\', '"' -> out.append('\\').append(c)
                        '/' -> {
                            if (b == '<') out.append('\\')
                            out.append(c)
                        }
                        '\b' -> out.append("\\b")
                        '\t' -> out.append("\\t")
                        '\n' -> out.append("\\n")
                        '\u000C' -> out.append("\\f")
                        '\r' -> out.append("\\r")
                        else -> if (c < ' ') out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0')) else out.append(c)
                    }
                }
                out.append('"')
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    json(k.toString(), out)
                    out.append(':')
                    json(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    json(v, out)
                }
                out.append(']')
            }
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            else -> out.append(value.toString())
        }
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
                results.add(LinkedHashMap(rule).apply {
                    put("path", path)
                    put("when", mapOf(xField to value))
                })
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
    // With whole, a non-empty document is a value too, before the values it holds.
    private fun getValues(value: Map<String, Any?>, whole: Boolean = false): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        fun walk(v: Any?, path: String) {
            if (v is Map<*, *>) {
                if (whole && v.isNotEmpty() && path.isNotEmpty()) out[path] = v
                v.forEach { (k, x) -> walk(x, if (path.isEmpty()) k.toString() else "$path/$k") }
            } else if (path.isNotEmpty()) out[path] = v
        }
        walk(value, "")
        return out
    }

    // The files the APK carries for the local server.
    fun files(learned: Learned): Map<String, ByteArray> = mapOf(RULES to Bson.encode(learned.rules), STATE to Bson.encode(learned.state))
}
