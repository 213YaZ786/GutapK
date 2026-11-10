package io.gutapk.core.offline

// Netzob's RelationFinder, which finds the fields of a protocol's messages
// whose values are bound to each other across every message seen: here the
// x field is a field a game's command sent, the y field a path of what the
// server answered or kept after it, and the relation is equality. A relation
// holds when it holds for every pair of values, and is dropped when either
// side never varies, since a constant proves nothing (Netzob's
// len(set(x_values)) == 1 check).
// https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
// https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnSymbol
// not ported: findOnSymbol, findOnFields, _generateAttributeValuesForSymbol, _findRelationType (the fields are given, one attribute each)
// not ported: _sizeRelation, ATTR_SIZE, REL_SIZE, REL_DATA (a game's state holds values, not sizes of other fields)
// not ported: uuid ids of the results, the logger (a result is returned, the caller reports it)
// Copyright (C) 2011-2017 Georges Bossert and Frédéric Guihéry, GPL-3.0-or-later.
object RelationFinder {
    const val REL_EQUALITY = "EqualityRelation"

    // Whether x_values and y_values, taken pair by pair, are always equal, and
    // vary: every pair passes _equalRelation and neither side is one value.
    // A single pair is a relation only when allowConstant is set, for a command
    // seen once, where nothing better can be known.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    fun executeOnFields(xValues: List<Any?>, yValues: List<Any?>, allowConstant: Boolean = false): Boolean {
        if (xValues.isEmpty() || xValues.size != yValues.size) return false
        var isRelation = true
        for (i in xValues.indices) {
            if (!equalRelation(xValues[i], yValues[i])) {
                isRelation = false
                break
            }
        }
        if (!isRelation) return false
        // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnSymbol
        val constant = xValues.map { it.toString() }.toSet().size == 1 || yValues.map { it.toString() }.toSet().size == 1
        return !constant || allowConstant
    }

    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#_equalRelation
    fun equalRelation(x: Any?, y: Any?): Boolean = x != null && StateDiff.same(x, y)
}
