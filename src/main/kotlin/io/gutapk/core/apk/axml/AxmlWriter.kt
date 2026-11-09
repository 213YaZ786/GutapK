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

import io.gutapk.core.apk.axml.ResConst.Companion.RES_STRING_POOL_TYPE
import io.gutapk.core.apk.axml.ResConst.Companion.RES_XML_CDATA_TYPE
import io.gutapk.core.apk.axml.ResConst.Companion.RES_XML_END_ELEMENT_TYPE
import io.gutapk.core.apk.axml.ResConst.Companion.RES_XML_END_NAMESPACE_TYPE
import io.gutapk.core.apk.axml.ResConst.Companion.RES_XML_RESOURCE_MAP_TYPE
import io.gutapk.core.apk.axml.ResConst.Companion.RES_XML_START_ELEMENT_TYPE
import io.gutapk.core.apk.axml.ResConst.Companion.RES_XML_START_NAMESPACE_TYPE
import io.gutapk.core.apk.axml.ResConst.Companion.RES_XML_TYPE
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Stack
import java.util.TreeSet

/**
 * a class to write android axml
 *
 * @author <a href="mailto:pxb1988@gmail.com">Panxiaobo</a>
 */
// Rebuilds the string pool and the resource map of a binary XML, and keeps the
// attributes of every element sorted by resource id: Android looks an
// attribute up by binary search on its id (AOSP's BackTrackingAttributeFinder).
// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlWriter.java#AxmlWriter
class AxmlWriter : AxmlVisitor() {
    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlWriter.java#AxmlWriter
    internal class Attr(
        @JvmField var ns: StringItem?,
        @JvmField var name: StringItem?,
        @JvmField var resourceId: Int,
    ) {
        @JvmField var index = 0
        @JvmField var type = 0
        @JvmField var value: Any? = null
        @JvmField var raw: StringItem? = null

        fun prepare(axmlWriter: AxmlWriter) {
            ns = axmlWriter.updateNs(ns)
            if (this.name != null) {
                if (resourceId != -1) {
                    this.name = axmlWriter.updateWithResourceId(this.name!!, this.resourceId)
                } else {
                    this.name = axmlWriter.update(this.name)
                }
            }
            if (value is StringItem) {
                value = axmlWriter.update(value as StringItem)
            }
            if (raw != null) {
                raw = axmlWriter.update(raw)
            }
        }
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlWriter.java#AxmlWriter
    internal class NodeImpl(ns: String?, name: String?) : NodeVisitor(null as NodeVisitor?) {
        private val attrs: MutableSet<Attr> = TreeSet(ATTR_CMP)
        private val children: MutableList<NodeImpl> = ArrayList()
        private var line = 0
        private var name: StringItem?
        private var ns: StringItem?
        private var text: StringItem? = null
        private var textLineNumber = 0
        var id: Attr? = null
        var style: Attr? = null
        var clz: Attr? = null

        init {
            this.ns = if (ns == null) null else StringItem(ns)
            this.name = if (name == null) null else StringItem(name)
        }

        override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, value: Any?) {
            if (name == null) {
                throw RuntimeException("name can't be null")
            }
            val a = Attr(if (ns == null) null else StringItem(ns), StringItem(name), resourceId)
            a.type = type

            if (value is ValueWrapper) {
                val valueWrapper = value
                if (valueWrapper.raw != null) {
                    a.raw = StringItem(valueWrapper.raw)
                }
                a.value = valueWrapper.ref
                when (valueWrapper.type) {
                    ValueWrapper.CLASS -> clz = a
                    ValueWrapper.ID -> id = a
                    ValueWrapper.STYLE -> style = a
                }
            } else if (type == TYPE_STRING) {
                val raw = StringItem(value as String?)
                a.raw = raw
                a.value = raw
            } else {
                a.raw = null
                a.value = value
            }

            attrs.add(a)
        }

        override fun child(ns: String?, name: String?): NodeVisitor {
            val child = NodeImpl(ns, name)
            this.children.add(child)
            return child
        }

        override fun end() {
        }

        override fun line(ln: Int) {
            this.line = ln
        }

        @Throws(IOException::class)
        fun prepare(axmlWriter: AxmlWriter): Int {
            ns = axmlWriter.updateNs(ns)
            name = axmlWriter.update(name)

            var attrIndex = 0
            for (attr in attrs) {
                attr.index = attrIndex++
                attr.prepare(axmlWriter)
            }

            text = axmlWriter.update(text)
            var size = 24 + 36 + attrs.size * 20 // 24 for end tag,36+x*20 for
            // start tag
            for (child in children) {
                size += child.prepare(axmlWriter)
            }
            if (text != null) {
                size += 28
            }
            return size
        }

        override fun text(ln: Int, value: String?) {
            this.text = StringItem(value)
            this.textLineNumber = ln
        }

        @Throws(IOException::class)
        fun write(out: ByteBuffer) {
            // start tag
            out.putInt(RES_XML_START_ELEMENT_TYPE or (0x0010 shl 16))
            out.putInt(36 + attrs.size * 20)
            out.putInt(line)
            out.putInt(-0x1)
            out.putInt(if (ns != null) this.ns!!.index else -1)
            out.putInt(name!!.index)
            out.putInt(0x00140014) // TODO
            out.putShort(this.attrs.size.toShort())
            out.putShort((if (id == null) 0 else id!!.index + 1).toShort())
            out.putShort((if (clz == null) 0 else clz!!.index + 1).toShort())
            out.putShort((if (style == null) 0 else style!!.index + 1).toShort())
            for (attr in attrs) {
                out.putInt(if (attr.ns == null) -1 else attr.ns!!.index)
                out.putInt(attr.name!!.index)
                out.putInt(if (attr.raw != null) attr.raw!!.index else -1)
                out.putInt((attr.type shl 24) or 0x000008)
                val v = attr.value
                if (v is Item) {
                    (attr.value as Item).writeout(out)
                } else if (v is Boolean) {
                    out.putInt(if (true == v) -1 else 0)
                } else if (v is Float) {
                    out.putInt(java.lang.Float.floatToIntBits(v))
                } else {
                    out.putInt(attr.value as Int)
                }
            }

            if (this.text != null) {
                out.putInt(RES_XML_CDATA_TYPE or (0x0010 shl 16))
                out.putInt(28)
                out.putInt(textLineNumber)
                out.putInt(-0x1)
                out.putInt(text!!.index)
                out.putInt(0x00000008)
                out.putInt(0x00000000)
            }

            // children
            for (child in children) {
                child.write(out)
            }

            // end tag
            out.putInt(RES_XML_END_ELEMENT_TYPE or (0x0010 shl 16))
            out.putInt(24)
            out.putInt(-1)
            out.putInt(-0x1)
            out.putInt(if (ns != null) this.ns!!.index else -1)
            out.putInt(name!!.index)
        }
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlWriter.java#AxmlWriter
    internal class Ns(
        @JvmField var prefix: StringItem?,
        @JvmField var uri: StringItem?,
        @JvmField var ln: Int,
    )

    private val firsts: MutableList<NodeImpl> = ArrayList(3)

    // Insertion order, not a hash order, so the same tree gives the same bytes.
    private val nses: MutableMap<String, Ns?> = LinkedHashMap()

    private var otherString: MutableList<StringItem>? = ArrayList()

    private val resourceId2Str: MutableMap<String, StringItem> = HashMap()

    private val resourceIds: MutableList<Int> = ArrayList()

    private var resourceString: MutableList<StringItem>? = ArrayList()

    private val stringItems = StringItems()

    // TODO add style support
    // private List<StringItem> styleItems = new ArrayList();

    override fun child(ns: String?, name: String?): NodeVisitor {
        val first = NodeImpl(ns, name)
        this.firsts.add(first)
        return first
    }

    override fun end() {
    }

    override fun ns(prefix: String?, uri: String?, ln: Int) {
        nses[uri!!] = Ns(if (prefix == null) null else StringItem(prefix), StringItem(uri), ln)
    }

    @Throws(IOException::class)
    private fun prepare(): Int {
        var size = 0

        for (first in firsts) {
            size += first.prepare(this)
        }
        run {
            var a = 0
            for (e in nses.entries) {
                var ns = e.value
                if (ns == null) {
                    ns = Ns(null, StringItem(e.key), 0)
                    e.setValue(ns)
                }
                if (ns.prefix == null) {
                    ns.prefix = StringItem(String.format("axml_auto_%02d", a++))
                }
                ns.prefix = update(ns.prefix)
                ns.uri = update(ns.uri)
            }
        }

        size += nses.size * 24 * 2

        this.stringItems.addAll(resourceString!!)
        resourceString = null
        this.stringItems.addAll(otherString!!)
        otherString = null
        this.stringItems.prepare()
        var stringSize = this.stringItems.getSize()
        if (stringSize % 4 != 0) {
            stringSize += 4 - stringSize % 4
        }
        size += 8 + stringSize
        size += 8 + resourceIds.size * 4
        return size
    }

    @Throws(IOException::class)
    fun toByteArray(): ByteArray {
        val size = 8 + prepare()
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        out.putInt(RES_XML_TYPE or (0x0008 shl 16))
        out.putInt(size)

        val stringSize = this.stringItems.getSize()
        var padding = 0
        if (stringSize % 4 != 0) {
            padding = 4 - stringSize % 4
        }
        out.putInt(RES_STRING_POOL_TYPE or (0x001C shl 16))
        out.putInt(stringSize + padding + 8)
        this.stringItems.write(out)
        out.put(ByteArray(padding))

        out.putInt(RES_XML_RESOURCE_MAP_TYPE or (0x0008 shl 16))
        out.putInt(8 + this.resourceIds.size * 4)
        for (i in resourceIds) {
            out.putInt(i)
        }

        val stack = Stack<Ns>()
        for (e in this.nses.entries) {
            val ns = e.value!!
            stack.push(ns)
            out.putInt(RES_XML_START_NAMESPACE_TYPE or (0x0010 shl 16))
            out.putInt(24)
            out.putInt(-1)
            out.putInt(-0x1)
            out.putInt(ns.prefix!!.index)
            out.putInt(ns.uri!!.index)
        }

        for (first in firsts) {
            first.write(out)
        }

        while (stack.size > 0) {
            val ns = stack.pop()
            out.putInt(RES_XML_END_NAMESPACE_TYPE or (0x0010 shl 16))
            out.putInt(24)
            out.putInt(ns.ln)
            out.putInt(-0x1)
            out.putInt(ns.prefix!!.index)
            out.putInt(ns.uri!!.index)
        }
        return out.array()
    }

    internal fun update(item: StringItem?): StringItem? {
        if (item == null) {
            return null
        }
        val i = this.otherString!!.indexOf(item)
        if (i < 0) {
            val copy = StringItem(item.data)
            this.otherString!!.add(copy)
            return copy
        } else {
            return this.otherString!![i]
        }
    }

    internal fun updateNs(item: StringItem?): StringItem? {
        if (item == null) {
            return null
        }
        val ns = item.data!!
        if (!this.nses.containsKey(ns)) {
            this.nses[ns] = null
        }
        return update(item)
    }

    internal fun updateWithResourceId(name: StringItem, resourceId: Int): StringItem {
        val key = name.data + resourceId
        val item = this.resourceId2Str[key]
        if (item != null) {
            return item
        } else {
            val copy = StringItem(name.data)
            resourceIds.add(resourceId)
            resourceString!!.add(copy)
            resourceId2Str[key] = copy
            return copy
        }
    }

    companion object {
        // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlWriter.java#AxmlWriter
        internal val ATTR_CMP: Comparator<Attr> = object : Comparator<Attr> {
            override fun compare(a: Attr, b: Attr): Int {
                var x = a.resourceId - b.resourceId
                if (x == 0) {
                    x = a.name!!.data!!.compareTo(b.name!!.data!!)
                    if (x == 0) {
                        val aNsIsnull = a.ns == null
                        val bNsIsnull = b.ns == null
                        if (aNsIsnull) {
                            x = if (bNsIsnull) {
                                0
                            } else {
                                -1
                            }
                        } else {
                            x = if (bNsIsnull) {
                                1
                            } else {
                                a.ns!!.data!!.compareTo(b.ns!!.data!!)
                            }
                        }
                    }
                }
                return x
            }
        }
    }
}
