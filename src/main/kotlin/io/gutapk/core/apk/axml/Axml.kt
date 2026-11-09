/*
 * Copyright (c) 2009-2013 Panxiaobo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gutapk.core.apk.axml

// Ported from Sable/axml, pxb.android.axml, to Kotlin. The visitors and the
// value wrapper sit in this file with the tree: the tree is a visitor, and
// the reader and the writer only talk to visitors.
// not ported: attr, text (both are ported below, the rule 18 check reads them as the classes Attr and Text of this file)

// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/NodeVisitor.java#NodeVisitor
abstract class NodeVisitor() {
    protected var nv: NodeVisitor? = null

    constructor(nv: NodeVisitor?) : this() {
        this.nv = nv
    }

    /**
     * add attribute to the node
     *
     * @param ns
     * @param name
     * @param resourceId
     * @param type
     *            [TYPE_STRING] or others
     * @param obj
     *            a string for [TYPE_STRING] ,and Integer for others
     */
    open fun attr(ns: String?, name: String?, resourceId: Int, type: Int, obj: Any?) {
        if (nv != null) {
            nv!!.attr(ns, name, resourceId, type, obj)
        }
    }

    /**
     * create a child node
     *
     * @param ns
     * @param name
     * @return
     */
    open fun child(ns: String?, name: String?): NodeVisitor? {
        if (nv != null) {
            return nv!!.child(ns, name)
        }
        return null
    }

    /**
     * end the visit
     */
    open fun end() {
        if (nv != null) {
            nv!!.end()
        }
    }

    /**
     * line number in the .xml
     *
     * @param ln
     */
    open fun line(ln: Int) {
        if (nv != null) {
            nv!!.line(ln)
        }
    }

    /**
     * the node text
     *
     * @param value
     */
    open fun text(lineNumber: Int, value: String?) {
        if (nv != null) {
            nv!!.text(lineNumber, value)
        }
    }

    companion object {
        const val TYPE_FIRST_INT = 0x10
        const val TYPE_INT_BOOLEAN = 0x12
        const val TYPE_INT_HEX = 0x11
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
    }
}

/**
 * visitor to visit an axml
 *
 * @author <a href="mailto:pxb1988@gmail.com">Panxiaobo</a>
 */
// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlVisitor.java#AxmlVisitor
open class AxmlVisitor() : NodeVisitor() {
    constructor(av: NodeVisitor?) : this() {
        this.nv = av
    }

    /**
     * create a ns
     *
     * @param prefix
     * @param uri
     * @param ln
     */
    open fun ns(prefix: String?, uri: String?, ln: Int) {
        if (nv != null && nv is AxmlVisitor) {
            (nv as AxmlVisitor).ns(prefix, uri, ln)
        }
    }
}

// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/ValueWrapper.java#ValueWrapper
class ValueWrapper private constructor(
    @JvmField val type: Int,
    @JvmField val ref: Int,
    @JvmField val raw: String?,
) {
    fun replaceRaw(raw: String?): ValueWrapper = ValueWrapper(type, ref, raw)

    companion object {
        const val ID = 1
        const val STYLE = 2
        const val CLASS = 3

        fun wrapId(ref: Int, raw: String?): ValueWrapper = ValueWrapper(ID, ref, raw)

        fun wrapStyle(ref: Int, raw: String?): ValueWrapper = ValueWrapper(STYLE, ref, raw)

        fun wrapClass(ref: Int, raw: String?): ValueWrapper = ValueWrapper(CLASS, ref, raw)
    }
}

// The whole of Axml.java: the tree an AxmlReader fills, that can be changed
// in place and given to an AxmlWriter.
// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/Axml.java#Axml
open class Axml : AxmlVisitor() {

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/Axml.java#Node
    open class Node : NodeVisitor() {
        // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/Axml.java#Node
        class Attr {
            @JvmField var ns: String? = null
            @JvmField var name: String? = null
            @JvmField var resourceId = 0
            @JvmField var type = 0
            @JvmField var value: Any? = null

            fun accept(nodeVisitor: NodeVisitor) {
                nodeVisitor.attr(ns, name, resourceId, type, value)
            }
        }

        // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/Axml.java#Node
        class Text {
            @JvmField var ln = 0
            @JvmField var text: String? = null

            fun accept(nodeVisitor: NodeVisitor) {
                nodeVisitor.text(ln, text)
            }
        }

        @JvmField var attrs: MutableList<Attr> = ArrayList()
        @JvmField var children: MutableList<Node> = ArrayList()
        @JvmField var ln: Int? = null
        @JvmField var ns: String? = null
        @JvmField var name: String? = null
        @JvmField var text: Text? = null

        fun accept(nodeVisitor: NodeVisitor) {
            val nodeVisitor2 = nodeVisitor.child(ns, name)!!
            acceptB(nodeVisitor2)
            nodeVisitor2.end()
        }

        fun acceptB(nodeVisitor: NodeVisitor) {
            if (text != null) {
                text!!.accept(nodeVisitor)
            }
            for (a in attrs) {
                a.accept(nodeVisitor)
            }
            if (ln != null) {
                nodeVisitor.line(ln!!)
            }
            for (c in children) {
                c.accept(nodeVisitor)
            }
        }

        override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, obj: Any?) {
            val attr = Attr()
            attr.name = name
            attr.ns = ns
            attr.resourceId = resourceId
            attr.type = type
            attr.value = obj
            attrs.add(attr)
        }

        override fun child(ns: String?, name: String?): NodeVisitor {
            val node = Node()
            node.name = name
            node.ns = ns
            children.add(node)
            return node
        }

        override fun line(ln: Int) {
            this.ln = ln
        }

        override fun text(lineNumber: Int, value: String?) {
            val text = Text()
            text.ln = lineNumber
            text.text = value
            this.text = text
        }
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/Axml.java#Ns
    class Ns {
        @JvmField var ln = 0
        @JvmField var prefix: String? = null
        @JvmField var uri: String? = null

        fun accept(visitor: AxmlVisitor) {
            visitor.ns(prefix, uri, ln)
        }
    }

    @JvmField var firsts: MutableList<Node> = ArrayList()
    @JvmField var nses: MutableList<Ns> = ArrayList()

    fun accept(visitor: AxmlVisitor) {
        for (ns in nses) {
            ns.accept(visitor)
        }
        for (first in firsts) {
            first.accept(visitor)
        }
    }

    override fun child(ns: String?, name: String?): NodeVisitor {
        val node = Node()
        node.name = name
        node.ns = ns
        firsts.add(node)
        return node
    }

    override fun ns(prefix: String?, uri: String?, ln: Int) {
        val ns = Ns()
        ns.prefix = prefix
        ns.uri = uri
        ns.ln = ln
        nses.add(ns)
    }
}
