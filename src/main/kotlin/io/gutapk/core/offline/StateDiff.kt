package io.gutapk.core.offline

// The differences between two states of a game as its server sent them, as
// zjsonpatch's JsonDiff finds them between two JSON trees: walk both, a value
// in the target only is an ADD, in the source only a REMOVE, a different
// value a REPLACE. A list of documents with an id (a game's buildings, its
// quests) is compared element by element by id, the path naming the element
// #id, as the game's own commands name a building by its id: zjsonpatch's
// longest common subsequence would name it by its index, which shifts.
// https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#generateDiffs
// https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#compareObjects
// not ported: getLCS, introduceCopyOperation, introduceMoveOperation, getUnchangedPart, DiffFlags, EMIT_TEST_OPERATIONS (no copy, move or test is learnt from a game)
// Copyright 2016 flipkart.com zjsonpatch, Apache License 2.0.
object StateDiff {
    enum class Operation { ADD, REMOVE, REPLACE }

    // One difference: the path from the root, segments joined by "/", the value
    // in the target and, for a REPLACE or a REMOVE, the value in the source.
    data class Diff(val operation: Operation, val path: String, val value: Any?, val srcValue: Any? = null)

    fun asDiffs(source: Any?, target: Any?): List<Diff> {
        val diffs = mutableListOf<Diff>()
        generateDiffs(diffs, "", source, target)
        return diffs
    }

    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#generateDiffs
    private fun generateDiffs(diffs: MutableList<Diff>, path: String, source: Any?, target: Any?) {
        if (same(source, target)) return
        if (source is List<*> && target is List<*> && byId(source) && byId(target)) {
            // both are lists of documents with an id
            compareArray(diffs, path, source, target)
        } else if (source is Map<*, *> && target is Map<*, *>) {
            // both are documents
            compareObjects(diffs, path, source, target)
        } else {
            // can be replaced
            diffs.add(Diff(Operation.REPLACE, path, target, source))
        }
    }

    private fun byId(list: List<*>): Boolean = list.all { it is Map<*, *> && it["id"] != null }

    // The elements of the source and of the target by id: an id in the source
    // only is removed, in the target only added, in both compared.
    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#compareArray
    // not ported: getLCS, lcsIdx, pos (elements are matched by id, not by order)
    private fun compareArray(diffs: MutableList<Diff>, path: String, source: List<*>, target: List<*>) {
        val srcNodes = source.associateBy { (it as Map<*, *>)["id"].toString() }
        val targetNodes = target.associateBy { (it as Map<*, *>)["id"].toString() }
        for ((id, srcNode) in srcNodes) {
            val currPath = "$path/#$id"
            val targetNode = targetNodes[id]
            if (targetNode == null) diffs.add(Diff(Operation.REMOVE, currPath, null, srcNode)) else generateDiffs(diffs, currPath, srcNode, targetNode)
        }
        for ((id, targetNode) in targetNodes) {
            if (id !in srcNodes) diffs.add(Diff(Operation.ADD, "$path/#$id", targetNode))
        }
    }

    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#compareObjects
    private fun compareObjects(diffs: MutableList<Diff>, path: String, source: Map<*, *>, target: Map<*, *>) {
        for (key in source.keys) {
            val currPath = if (path.isEmpty()) key.toString() else "$path/$key"
            if (!target.containsKey(key)) {
                // remove case
                diffs.add(Diff(Operation.REMOVE, currPath, null, source[key]))
                continue
            }
            generateDiffs(diffs, currPath, source[key], target[key])
        }
        for (key in target.keys) {
            if (!source.containsKey(key)) {
                // add case
                val currPath = if (path.isEmpty()) key.toString() else "$path/$key"
                diffs.add(Diff(Operation.ADD, currPath, target[key]))
            }
        }
    }

    // Two values of a game's state are the same when equal, whatever the width of
    // their number: the server writes 2 as an int32 here, an int64 there.
    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/AbstractJsonDiff.java#generateDiffs
    // not ported: NodeType.getNodeType (a value's Kotlin type says it)
    fun same(source: Any?, target: Any?): Boolean = when {
        source is Number && target is Number -> if (source is Double || target is Double) source.toDouble() == target.toDouble() else source.toLong() == target.toLong()
        source is Map<*, *> && target is Map<*, *> -> source.size == target.size && source.all { (k, v) -> target.containsKey(k) && same(v, target[k]) }
        source is List<*> && target is List<*> -> source.size == target.size && source.indices.all { same(source[it], target[it]) }
        source is ByteArray && target is ByteArray -> source.contentEquals(target)
        else -> source == target
    }
}
