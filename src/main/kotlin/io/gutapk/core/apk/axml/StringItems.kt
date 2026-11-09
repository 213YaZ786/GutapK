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

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// Ported from Sable/axml, pxb.android, to Kotlin. Sable's Item, StringItem and
// ResConst sit in this file with StringItems, the only code that uses them
// besides the writer and the parser.

// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/Item.java#Item
interface Item {
    /**
     * Writes this item into a given bytebuffer
     * @param out the bytebuffer
     */
    fun writeout(out: ByteBuffer)
}

// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/ResConst.java#ResConst
interface ResConst {
    companion object {
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_TABLE_TYPE = 0x0002
        const val RES_TABLE_PACKAGE_TYPE = 0x0200
        const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202
        const val RES_TABLE_TYPE_TYPE = 0x0201

        const val RES_XML_TYPE = 0x0003
        const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        const val RES_XML_END_NAMESPACE_TYPE = 0x0101
        const val RES_XML_END_ELEMENT_TYPE = 0x0103
        const val RES_XML_START_NAMESPACE_TYPE = 0x0100
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val RES_XML_CDATA_TYPE = 0x0104
    }
}

// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/StringItem.java#StringItem
class StringItem() : Item {
    var data: String? = null
    var dataOffset = 0
    var index = 0

    constructor(data: String?) : this() {
        this.data = data
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null) {
            return false
        }
        if (javaClass != other.javaClass) {
            return false
        }
        val that = other as StringItem
        if (data == null) {
            if (that.data != null) {
                return false
            }
        } else if (data != that.data) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + (if (data == null) 0 else data.hashCode())
        return result
    }

    override fun toString(): String = String.format("S%04d %s", index, data)

    override fun writeout(out: ByteBuffer) {
        out.putInt(index)
    }
}

// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/StringItems.java#StringItems
class StringItems : ArrayList<StringItem>() {
    var stringData: ByteArray = ByteArray(0)

    // Another JVM name: Kotlin's list already has a getSize, the size property.
    @JvmName("byteSize")
    fun getSize(): Int = 5 * 4 + this.size * 4 + stringData.size + 0 // TODO

    @Throws(java.io.IOException::class)
    fun prepare() {
        for (s in this) {
            if (s.data!!.length > 0x7FFF) {
                useUTF8 = false
            }
        }
        val baos = ByteArrayOutputStream()
        var i = 0
        var offset = 0
        baos.reset()
        val map = HashMap<String, Int>()
        for (item in this) {
            item.index = i++
            val stringData = item.data!!
            val of = map[stringData]
            if (of != null) {
                item.dataOffset = of
            } else {
                item.dataOffset = offset
                map[stringData] = offset
                if (useUTF8) {
                    val length = stringData.length
                    val data = stringData.toByteArray(Charsets.UTF_8)
                    val u8lenght = data.size

                    if (length > 0x7F) {
                        offset++
                        baos.write((length shr 8) or 0x80)
                    }
                    baos.write(length)

                    if (u8lenght > 0x7F) {
                        offset++
                        baos.write((u8lenght shr 8) or 0x80)
                    }
                    baos.write(u8lenght)
                    baos.write(data)
                    baos.write(0)
                    offset += 3 + u8lenght
                } else {
                    val length = stringData.length
                    val data = stringData.toByteArray(Charsets.UTF_16LE)
                    if (length > 0x7FFF) {
                        val x = (length shr 16) or 0x8000
                        baos.write(x)
                        baos.write(x shr 8)
                        offset += 2
                    }
                    baos.write(length)
                    baos.write(length shr 8)
                    baos.write(data)
                    baos.write(0)
                    baos.write(0)
                    offset += 4 + data.size
                }
            }
        }
        // TODO
        this.stringData = baos.toByteArray()
    }

    private var useUTF8 = true

    @Throws(java.io.IOException::class)
    fun write(out: ByteBuffer) {
        out.putInt(this.size)
        out.putInt(0) // TODO style count
        out.putInt(if (useUTF8) UTF8_FLAG else 0)
        out.putInt(7 * 4 + this.size * 4)
        out.putInt(0)
        for (item in this) {
            out.putInt(item.dataOffset)
        }
        out.put(stringData)
        // TODO
    }

    companion object {
        private const val UTF8_FLAG = 0x00000100

        @Throws(java.io.IOException::class)
        fun read(`in`: ByteBuffer): Array<String> {
            val trunkOffset = `in`.position() - 8
            val stringCount = `in`.getInt()
            val styleOffsetCount = `in`.getInt()
            val flags = `in`.getInt()
            val stringDataOffset = `in`.getInt()
            val stylesOffset = `in`.getInt()
            val offsets = IntArray(stringCount)
            val strings = arrayOfNulls<String>(stringCount)
            for (i in 0 until stringCount) {
                offsets[i] = `in`.getInt()
            }

            val base = trunkOffset + stringDataOffset
            for (i in offsets.indices) {
                `in`.position(base + offsets[i])
                val s: String

                if (0 != (flags and UTF8_FLAG)) {
                    u8length(`in`) // ignored
                    val u8len = u8length(`in`)
                    val start = `in`.position()
                    var blength = u8len
                    while (`in`.get(start + blength).toInt() != 0) {
                        blength++
                    }
                    s = String(`in`.array(), start, blength, Charsets.UTF_8)
                } else {
                    val length = u16length(`in`)
                    s = String(`in`.array(), `in`.position(), length * 2, Charsets.UTF_16LE)
                }
                strings[i] = s
            }
            @Suppress("UNCHECKED_CAST")
            return strings as Array<String>
        }

        internal fun u16length(`in`: ByteBuffer): Int {
            var length = `in`.getShort().toInt() and 0xFFFF
            if (length > 0x7FFF) {
                length = ((length and 0x7FFF) shl 8) or (`in`.getShort().toInt() and 0xFFFF)
            }
            return length
        }

        internal fun u8length(`in`: ByteBuffer): Int {
            var len = `in`.get().toInt() and 0xFF
            if ((len and 0x80) != 0) {
                len = ((len and 0x7F) shl 8) or (`in`.get().toInt() and 0xFF)
            }
            return len
        }
    }
}
