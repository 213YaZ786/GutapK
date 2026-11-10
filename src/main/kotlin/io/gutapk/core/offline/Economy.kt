package io.gutapk.core.offline

// The economy of a game, learnt as the other rules are, from the recordings and
// the game's data files: what each command adds to the player's resources and
// takes from them. The game computes what its mines produce itself, from the
// time of their last collect, and tells the server how much it collected: the
// server has to keep the totals only.
//
// The totals are read at each checkpoint: a login (the wallet, and the storages,
// elements holding a number named <anything>_<resource>) or an answer that holds
// a number for every resource (the wallet an attack shows). Between two
// checkpoints, each command could have done one of a few things, all read from
// the request and the data: added one of its numbers to the resource of the
// producer it names (a collect), taken the price of the data document one of
// its fields names (times another of its numbers), taken the price of the next
// grade of the element it names, taken one of its numbers from a resource of the
// wallet, added the resources a field of it lists. The relations kept are those
// that bring the totals closest to the checkpoints, as RelationFinder keeps the
// relations that hold: chosen one or two at a time (a cost and the collect that
// hides it), while the error goes down.
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#incrementRules
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#intervals
// https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
// not ported: intervals (the checkpoints are logins and answers here, Interval below holds totals, not diffs)
// not ported: valueAt (called from ServerRules as it is)
// not ported: values (the totals are summed by resource, no path of a diff is read)
// not ported: executeOnSymbol, findOnSymbol, findOnFields, _findRelationType, _sizeRelation, _generateAttributeValuesForSymbol, _getAllFieldsValues, _generateConcatData, _generateDataValues, _generateSizeValues (the relations are between a request and totals, not between fields of one message)
object Economy {
    // A number above this is a time, not an amount.
    private const val TIME = 1_000_000_000L

    // One relation a call may follow and what it does to the totals, by resource.
    private class Effect(val relation: Map<String, Any?>, val deltas: Map<String, Long>)

    // An element list of the state: its path, the field naming its document in the
    // data and the documents by id.
    private class Kind(val path: String, val key: String, val documents: Map<String, Map<*, *>>)

    // The totals between two checkpoints, and what each call between them may have done.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#intervals
    private class Interval(val source: Map<String, Long>, val target: Map<String, Long>, val between: List<List<Effect>>)

    // The resource a field holds: the longest resource its name ends with, after an underscore.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    private fun suffix(key: String, resources: Collection<String>): String? = resources.filter { key.endsWith("_$it") }.maxByOrNull { it.length }

    // A map of resource names to numbers: a price, a wallet, a gain.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    private fun priced(value: Any?, resources: Collection<String>): Map<String, Long>? {
        val map = value as? Map<*, *> ?: return null
        if (map.isEmpty() || !map.all { (k, v) -> k.toString() in resources && v is Number }) return null
        return map.entries.associate { (k, v) -> k.toString() to (v as Number).toLong() }
    }

    // The grades of a data document: its list of documents whose one number runs
    // 2, 3, 4..., and that number's field. Null when it has none.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    private fun gradesOf(document: Map<*, *>): Pair<String, String>? {
        for ((listKey, inner) in document) {
            val grades = (inner as? List<*>)?.filterIsInstance<Map<*, *>>()?.takeIf { it.isNotEmpty() } ?: continue
            val gradeKey = grades.first().keys.firstOrNull { k -> grades.mapIndexed { index, g -> (g[k] as? Number)?.toLong() == index + 2L }.all { it } } ?: continue
            return listKey.toString() to gradeKey.toString()
        }
        return null
    }

    // The numbers named <anything>_<resource> of a data document at a grade: its own,
    // then those of each of its grades up to that one.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    private fun held(document: Map<*, *>, grade: Long, resources: Collection<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val grades = gradesOf(document)
        fun walk(v: Any?) {
            when (v) {
                is Map<*, *> -> v.forEach { (k, x) -> if (x is Number && suffix(k.toString(), resources) != null) out[k.toString()] = x.toLong() else walk(x) }
                is List<*> -> v.forEach { walk(it) }
            }
        }
        document.forEach { (k, x) -> if (k != grades?.first) walk(x) }
        if (grades != null) {
            for (g in (document[grades.first] as List<*>).filterIsInstance<Map<*, *>>()) {
                if (((g[grades.second] as? Number)?.toLong() ?: continue) <= grade) walk(g)
            }
        }
        return out
    }

    // The price of a data document at a grade: its own at the first, its grade's after.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    private fun price(document: Map<*, *>, key: String, grade: Long, resources: Collection<String>): Map<String, Long>? {
        if (grade <= 1) return priced(document[key], resources)
        val grades = gradesOf(document) ?: return null
        val inner = (document[grades.first] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull { (it[grades.second] as? Number)?.toLong() == grade } ?: return null
        return priced(inner[key], resources)
    }

    // The element lists of a state, each named by a list of the data.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    private fun kinds(state: Map<String, Any?>, lists: List<List<Map<*, *>>>): List<Kind> {
        val out = mutableListOf<Kind>()
        fun walk(v: Any?, path: String) {
            when (v) {
                is Map<*, *> -> v.forEach { (k, x) -> walk(x, "$path/$k") }
                is List<*> -> {
                    if (v.isEmpty() || !v.all { it is Map<*, *> && it["id"] != null }) return
                    val elements = v.map { it as Map<*, *> }
                    val (key, names) = GameState.naming(elements, lists) ?: return
                    val list = lists.firstOrNull { l -> l.associate { it["id"].toString() to it["name"] } == names } ?: return
                    out.add(Kind(path, key, list.associateBy { it["id"].toString() }))
                }
            }
        }
        state.forEach { (name, message) -> (message as? Map<*, *>)?.forEach { (k, x) -> if (x !is String) walk(x, "$name/$k") } }
        return out
    }

    // The wallet: the map of the state, out of its lists, of resources to numbers.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    private fun walletOf(state: Map<String, Any?>, resources: Collection<String>): String? {
        var best: Pair<String, Int>? = null
        fun walk(v: Any?, path: String) {
            if (v !is Map<*, *>) return
            val wallet = priced(v, resources)
            if (wallet != null && wallet.size > (best?.second ?: 0)) best = path to wallet.size
            v.forEach { (k, x) -> walk(x, "$path/$k") }
        }
        state.forEach { (name, message) -> (message as? Map<*, *>)?.forEach { (k, x) -> walk(x, "$name/$k") } }
        return best?.first
    }

    // The totals of a state: each resource of the wallet, plus the storages, the
    // elements holding it under one name only (a producer holds a rate and what it
    // made, the game counts that itself).
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#valueAt
    private fun totals(state: Map<String, Any?>, kinds: List<Kind>, wallet: String?, resources: Collection<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        priced(wallet?.let { ServerRules.valueAt(state, it) }, resources)?.let { out.putAll(it) }
        for (kind in kinds) {
            for (element in (ServerRules.valueAt(state, kind.path) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
                val document = kind.documents[element[kind.key].toString()] ?: continue
                val names = held(document, 1, resources).keys.groupBy { suffix(it, resources)!! }
                fun walk(v: Any?) {
                    when (v) {
                        is Map<*, *> -> v.forEach { (k, x) ->
                            val r = suffix(k.toString(), resources)
                            if (x is Number && r != null && names[r]?.size == 1) out[r] = (out[r] ?: 0) + x.toLong() else walk(x)
                        }
                        is List<*> -> v.forEach { walk(it) }
                    }
                }
                walk(element)
            }
        }
        return out
    }

    // The totals an answer shows: a map holding a number for most of the resources
    // the last checkpoint had a total of (an attack shows the attacker's wallet).
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#getValues
    private fun findTotals(value: Any?, wanted: Collection<String>, resources: Collection<String>): Map<String, Long>? {
        if (wanted.isEmpty()) return null
        when (value) {
            is Map<*, *> -> {
                priced(value, resources)?.takeIf { it.keys.count { k -> k in wanted } * 2 > wanted.size }?.let { out -> return out.filterKeys { it in wanted } }
                for (x in value.values) findTotals(x, wanted, resources)?.let { return it }
            }
            is List<*> -> for (x in value) findTotals(x, wanted, resources)?.let { return it }
        }
        return null
    }

    // Learns the economy: the relations kept and the data they read, ready for the phone.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/core/offline/ServerRules.kt#incrementRules
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    fun learn(
        snapshots: List<Pair<Long, Map<String, Any?>>>,
        calls: List<ServerRules.Call>,
        statics: List<Any?>,
        timers: List<Map<String, Any?>>,
        whens: Map<String, Map<String, Any?>>,
        rules: List<Map<String, Any?>> = emptyList(),
        moves: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?>? {
        val latest = snapshots.lastOrNull()?.second ?: return null
        val lists = GameState.named(statics)
        val resources = GameState.resources(statics)
        val kinds = kinds(latest, lists)
        val wallet = walletOf(latest, resources)
        // The grade of an element: the number a timer ends with, else the one a command increments.
        val increment = rules.firstOrNull { it["op"] == "increment" && "/#$" in it["path"].toString() }
        val gradeField = (timers.firstOrNull()?.get("key") as? List<*>)?.getOrNull(1) as? String ?: increment?.get("path")?.toString()?.substringAfterLast('/')
        val gradeDefault = ((timers.firstOrNull()?.get("default") ?: increment?.get("default")) as? Number)?.toLong() ?: 1L
        fun gradeOf(element: Map<*, *>): Long = gradeField?.let { (element[it] as? Number)?.toLong() } ?: gradeDefault
        val walletResources = priced(wallet?.let { ServerRules.valueAt(latest, it) }, resources)?.keys.orEmpty()
        // The fields of a command whose every value names an element of the state,
        // at one login or another (a percentage of 100 is not the element 100).
        val elementIds = snapshots.flatMap { (_, s) -> kinds.flatMap { k -> (ServerRules.valueAt(s, k.path) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { it["id"].toString() } } }.toSet()
        val naming = calls.groupBy { it.name }.mapValues { (_, group) ->
            group.flatMap { it.xFields.keys }.toSet().filter { xField -> group.all { it.fields[xField]?.toString() in elementIds } }.toSet()
        }
        // The fields of a command whose every value is the id of a document of one
        // data list, and that names no element.
        val ids = calls.groupBy { it.name }.mapValues { (name, group) ->
            group.flatMap { it.xFields.keys }.toSet().filter { it !in naming[name].orEmpty() }.associateWith { xField ->
                val xValues = group.map { it.fields[xField] }
                lists.indexOfFirst { list ->
                    xValues.all { x -> x is Number && x.toLong() < TIME && list.any { d -> d["id"].toString() == x.toString() && d.values.any { priced(it, resources) != null } } }
                }
            }.filterValues { it >= 0 }
        }

        // What a call may have done, read against the state of the last login.
        fun effects(call: ServerRules.Call, state: Map<String, Any?>): List<Effect> {
            val results = mutableListOf<Effect>()
            fun relation(vararg pairs: Pair<String, Any?>) = linkedMapOf<String, Any?>("command" to call.name).apply { pairs.forEach { (k, v) -> put(k, v) } }
            val numbers = call.xFields.filter { (_, v) -> v is Number && v.toLong() < TIME }
            for (kind in kinds) {
                val elements = (ServerRules.valueAt(state, kind.path) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
                for ((xField, x) in numbers) {
                    if (xField !in naming[call.name].orEmpty()) continue
                    val element = elements.firstOrNull { it["id"].toString() == x.toString() } ?: continue
                    val document = kind.documents[element[kind.key].toString()] ?: continue
                    val names = held(document, gradeOf(element), resources).keys.groupBy { suffix(it, resources)!! }
                    for ((r, keys) in names) {
                        if (keys.size < 2) continue
                        for ((yField, y) in numbers) {
                            if (yField != xField) results.add(Effect(relation("op" to "collect", "element" to xField, "field" to yField, "list" to kind.path), mapOf(r to (y as Number).toLong())))
                        }
                    }
                    val grades = gradesOf(document) ?: continue
                    for (key in (document[grades.first] as List<*>).filterIsInstance<Map<*, *>>().flatMap { g -> g.keys.filter { priced(g[it], resources) != null } }.toSet()) {
                        val cost = price(document, key.toString(), gradeOf(element) + 1, resources) ?: continue
                        results.add(Effect(relation("op" to "payGrade", "element" to xField, "price" to key.toString(), "list" to kind.path), cost.mapValues { -it.value }))
                    }
                }
            }
            for ((xField, index) in ids[call.name].orEmpty()) {
                val document = lists[index].firstOrNull { it["id"].toString() == call.fields[xField].toString() } ?: continue
                for (key in document.keys.filter { priced(document[it], resources) != null }) {
                    val cost = priced(document[key], resources)!!
                    results.add(Effect(relation("op" to "pay", "field" to xField, "price" to key.toString(), "data" to index), cost.mapValues { -it.value }))
                    for ((yField, y) in numbers) {
                        if (yField != xField) results.add(Effect(relation("op" to "pay", "field" to xField, "price" to key.toString(), "data" to index, "times" to yField), cost.mapValues { -it.value * (y as Number).toLong() }))
                    }
                }
            }
            for ((xField, x) in numbers) {
                for (r in walletResources) results.add(Effect(relation("op" to "spend", "field" to xField, "resource" to r), mapOf(r to -(x as Number).toLong())))
            }
            for ((key, value) in call.fields) {
                priced(value, resources)?.let { results.add(Effect(relation("op" to "gain", "field" to key), it)) }
            }
            return results
        }

        // The checkpoints and the calls between, in the order they were answered:
        // a call whose answer shows the totals closes an interval before its own effects.
        val intervals = mutableListOf<Interval>()
        val events = (snapshots.map { it.first to (it.second as Any) } + calls.map { it.time to (it as Any) })
            .sortedWith(compareBy<Pair<Long, Any>> { it.first }.thenBy { if (it.second is ServerRules.Call) 0 else 1 })
        var state: Map<String, Any?>? = null
        var source: Map<String, Long>? = null
        var between = mutableListOf<List<Effect>>()
        for ((_, event) in events) {
            val call = event as? ServerRules.Call
            @Suppress("UNCHECKED_CAST")
            val login = event as? Map<String, Any?>
            val target = if (call != null) findTotals(call.answer, source?.keys.orEmpty(), resources) else totals(login!!, kinds, wallet, resources)
            if (target != null) {
                source?.let { s -> intervals.add(Interval(s, target, between)) }
                source = target
                between = mutableListOf()
            }
            if (call != null) state?.let { between.add(effects(call, it)) } else state = login
        }

        fun error(chosen: Set<Map<String, Any?>>): Long = intervals.sumOf { i ->
            val predicted = HashMap(i.source)
            for (effects in i.between) for (e in effects) if (e.relation in chosen) e.deltas.forEach { (r, d) -> predicted[r] = (predicted[r] ?: 0) + d }
            i.target.entries.sumOf { (r, v) -> kotlin.math.abs((predicted[r] ?: 0) - v) }
        }
        val candidates = intervals.flatMap { i -> i.between.flatten().map { it.relation } }.toSet().toList()
        val chosen = LinkedHashSet<Map<String, Any?>>()
        var best = error(chosen)
        while (true) {
            val rest = candidates.filter { it !in chosen }
            var found: Pair<Long, List<Map<String, Any?>>>? = null
            for ((index, x) in rest.withIndex()) {
                val one = error(chosen + x)
                if (one < (found?.first ?: best)) found = one to listOf(x)
                for (y in rest.subList(index + 1, rest.size)) {
                    val two = error(chosen + x + y)
                    if (two < (found?.first ?: best)) found = two to listOf(x, y)
                }
            }
            val (lower, relations) = found ?: break
            chosen.addAll(relations)
            best = lower
        }
        if (chosen.isEmpty()) return null

        // The data the phone reads: the prices, the producers and the storages'
        // capacities, by article and grade.
        // The effects learnt, then the same on the lists the rules' variants were moved
        // to (the second base's), for requests holding the variant's value.
        val learnt = chosen.map { it to whens[it["command"]] }
        val variants = moves.flatMap { move ->
            learnt.mapNotNull { (relation, condition) ->
                val target = move["when"] as Map<*, *>
                if (condition == null || condition.keys != target.keys) return@mapNotNull null
                val list = relation["list"] ?: return@mapNotNull relation to target.entries.associate { it.key.toString() to it.value }
                if (list != move["from"]) null else LinkedHashMap(relation).apply { put("list", move["to"]) } to target.entries.associate { it.key.toString() to it.value }
            }
        }
        val effects = (learnt + variants).map { (relation, condition) ->
            val out = LinkedHashMap<String, Any?>(relation)
            condition?.let { out["when"] = it }
            val kind = kinds.firstOrNull { it.path == relation["list"] }
            when (relation["op"]) {
                "pay" -> {
                    // Its list first, then the others written alike (the second base's buildings).
                    val first = relation["data"] as Int
                    out.remove("data")
                    out["prices"] = LinkedHashMap<String, Any?>().apply {
                        (listOf(lists[first]) + lists.filterIndexed { i, _ -> i != first }).flatten().forEach { d -> priced(d[relation["price"]], resources)?.let { putIfAbsent(d["id"].toString(), it) } }
                    }
                }
                "payGrade" -> if (kind != null) {
                    out["key"] = kind.key
                    out["grade"] = gradeField
                    out["default"] = gradeDefault.toInt()
                    out["prices"] = kind.documents.flatMap { (id, d) ->
                        val top = 1L + ((gradesOf(d)?.let { (d[it.first] as List<*>).size }) ?: 0)
                        (2L..top).mapNotNull { g -> price(d, relation["price"] as String, g, resources)?.let { "$id,$g" to it } }
                    }.toMap()
                }
                "collect" -> if (kind != null) {
                    out["key"] = kind.key
                    out["producers"] = kind.documents.mapNotNull { (id, d) ->
                        val names = held(d, Long.MAX_VALUE, resources).keys.groupBy { suffix(it, resources)!! }.filterValues { it.size >= 2 }
                        if (names.isEmpty()) null else id to names.values.flatten()
                    }.toMap()
                    (timers.firstOrNull()?.get("since") as? String)?.let { out["since"] = it }
                }
            }
            out
        }.distinct()
        val storages = kinds.map { kind ->
            linkedMapOf<String, Any?>(
                "list" to kind.path, "key" to kind.key, "grade" to gradeField, "default" to gradeDefault.toInt(),
                "capacities" to kind.documents.flatMap { (id, d) ->
                    val top = 1L + ((gradesOf(d)?.let { (d[it.first] as List<*>).size }) ?: 0)
                    (1L..top).mapNotNull { g ->
                        val names = held(d, g, resources)
                        names.filterKeys { k -> names.keys.count { suffix(it, resources) == suffix(k, resources) } == 1 }.takeIf { it.isNotEmpty() }?.let { "$id,$g" to it }
                    }
                }.toMap(),
            )
        }.filter { (it["capacities"] as Map<*, *>).isNotEmpty() }
        return linkedMapOf("resources" to resources.toList(), "wallet" to wallet, "storages" to storages, "effects" to effects, "error" to best)
    }
}
