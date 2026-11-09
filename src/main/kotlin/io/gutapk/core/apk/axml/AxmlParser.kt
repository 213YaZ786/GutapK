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

import io.gutapk.core.apk.ApkFormatError
import io.gutapk.core.apk.axml.NodeVisitor.Companion.TYPE_INT_BOOLEAN
import io.gutapk.core.apk.axml.NodeVisitor.Companion.TYPE_STRING
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

// Ported from Sable/axml, AxmlParser.java, to Kotlin. Cited method by method:
// the whole class in one citation makes the guard's Java method pattern take
// more than its time limit on next, which holds a long commented block.
// The commented-out attribute loop of next is not ported: the parser reads the
// attributes lazily through attrs, as the live code does.

/**
 * a class to read android axml
 *
 * @author <a href="mailto:pxb1988@gmail.com">Panxiaobo</a>
 */
class AxmlParser(`in`: ByteBuffer) : ResConst {
    private var attributeCount = 0

    private var attrs: IntBuffer? = null

    private var classAttribute = 0
    private var fileSize = -1
    private var idAttribute = 0
    private var `in`: ByteBuffer = `in`.order(ByteOrder.LITTLE_ENDIAN)
    private var lineNumber = 0
    private var nameIdx = 0
    private var nsIdx = 0

    private var prefixIdx = 0

    private var resourceIds: IntArray? = null

    private var strings: Array<String>? = null

    private var styleAttribute = 0

    private var textIdx = 0

    constructor(data: ByteArray) : this(ByteBuffer.wrap(data))

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttrCount
    fun getAttrCount(): Int = attributeCount

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttributeCount
    fun getAttributeCount(): Int = attributeCount

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttrName
    fun getAttrName(i: Int): String? {
        val idx = attrs!!.get(i * 5 + 1)
        if (idx >= 0 && idx < strings!!.size) {
            return strings!![idx]
        }
        return null
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttrNs
    fun getAttrNs(i: Int): String? {
        val idx = attrs!!.get(i * 5 + 0)
        if (idx >= 0 && idx < strings!!.size) {
            return strings!![idx]
        }
        return null
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttrRawString
    internal fun getAttrRawString(i: Int): String? {
        val idx = attrs!!.get(i * 5 + 2)
        if (idx >= 0 && idx < strings!!.size) {
            return strings!![idx]
        }
        return null
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttrResId
    fun getAttrResId(i: Int): Int {
        if (resourceIds != null) {
            val idx = attrs!!.get(i * 5 + 1)
            if (idx >= 0 && idx < resourceIds!!.size) {
                return resourceIds!![idx]
            }
        }
        return -1
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttrType
    fun getAttrType(i: Int): Int = attrs!!.get(i * 5 + 3) shr 24

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getAttrValue
    fun getAttrValue(i: Int): Any? {
        val v = attrs!!.get(i * 5 + 4)

        if (i == idAttribute) {
            return ValueWrapper.wrapId(v, getAttrRawString(i))
        } else if (i == styleAttribute) {
            return ValueWrapper.wrapStyle(v, getAttrRawString(i))
        } else if (i == classAttribute) {
            return ValueWrapper.wrapClass(v, getAttrRawString(i))
        }

        return when (getAttrType(i)) {
            TYPE_STRING -> strings!![v]
            TYPE_INT_BOOLEAN -> v != 0
            else -> v
        }
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getLineNumber
    fun getLineNumber(): Int = lineNumber

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getName
    fun getName(): String? {
        if (nameIdx >= 0 && nameIdx < strings!!.size) {
            return strings!![nameIdx]
        }
        return null
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getNamespacePrefix
    fun getNamespacePrefix(): String? {
        if (prefixIdx >= 0 && prefixIdx < strings!!.size) {
            return strings!![prefixIdx]
        }
        return null
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#getNamespaceUri
    fun getNamespaceUri(): String? {
        if (nsIdx >= 0 && nsIdx < strings!!.size) {
            return strings!![nsIdx]
        }
        return null
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#L172-L177
    fun getText(): String? {
        if (textIdx >= 0 && textIdx < strings!!.size) {
            return strings!![textIdx]
        }
        return null
    }

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#L179-L211
    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlParser.java#L242-L290
    @Throws(IOException::class)
    fun next(): Int {
        if (fileSize < 0) {
            val type = `in`.getInt() and 0xFFFF
            if (type != ResConst.RES_XML_TYPE) {
                throw ApkFormatError(String.format("Resource type was 0x%04X instead of XML_TYPE (0x0003)", type))
            }
            fileSize = `in`.getInt()
            return START_FILE
        }
        var event = -1
        var p = `in`.position()
        while (p < fileSize) {
            val type = `in`.getInt() and 0xFFFF
            val size = `in`.getInt()
            when (type) {
                ResConst.RES_XML_START_ELEMENT_TYPE -> {
                    lineNumber = `in`.getInt()
                    `in`.getInt() /* skip, 0xFFFFFFFF */
                    nsIdx = `in`.getInt()
                    nameIdx = `in`.getInt()
                    val flag = `in`.getInt() // 0x00140014 ?
                    if (flag != 0x00140014) {
                        throw ApkFormatError("Unexpected attribute start and size 0x${flag.toString(16)} at $p")
                    }

                    attributeCount = `in`.getShort().toInt() and 0xFFFF
                    idAttribute = (`in`.getShort().toInt() and 0xFFFF) - 1
                    classAttribute = (`in`.getShort().toInt() and 0xFFFF) - 1
                    styleAttribute = (`in`.getShort().toInt() and 0xFFFF) - 1

                    attrs = `in`.asIntBuffer()
                    event = START_TAG
                }
                ResConst.RES_XML_END_ELEMENT_TYPE -> {
                    `in`.position(p + size)
                    event = END_TAG
                }
                ResConst.RES_XML_START_NAMESPACE_TYPE -> {
                    lineNumber = `in`.getInt()
                    `in`.getInt() /* 0xFFFFFFFF */
                    prefixIdx = `in`.getInt()
                    nsIdx = `in`.getInt()
                    event = START_NS
                }
                ResConst.RES_XML_END_NAMESPACE_TYPE -> {
                    `in`.position(p + size)
                    event = END_NS
                }
                ResConst.RES_STRING_POOL_TYPE -> {
                    strings = StringItems.read(`in`)
                    `in`.position(p + size)
                    p = `in`.position()
                    continue
                }
                ResConst.RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = size / 4 - 2
                    resourceIds = IntArray(count)
                    for (i in 0 until count) {
                        resourceIds!![i] = `in`.getInt()
                    }
                    `in`.position(p + size)
                    p = `in`.position()
                    continue
                }
                ResConst.RES_XML_CDATA_TYPE -> {
                    lineNumber = `in`.getInt()
                    `in`.getInt() /* 0xFFFFFFFF */
                    textIdx = `in`.getInt()

                    `in`.getInt() /* 00000008 00000000 */
                    `in`.getInt()

                    event = TEXT
                }
                else -> throw ApkFormatError("Unsupported type: $type")
            }
            `in`.position(p + size)
            return event
        }
        return END_FILE
    }

    companion object {
        const val END_FILE = 7
        const val END_NS = 5
        const val END_TAG = 3
        const val START_FILE = 1
        const val START_NS = 4
        const val START_TAG = 2
        const val TEXT = 6
    }
}
