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
import io.gutapk.core.apk.axml.AxmlParser.Companion.END_FILE
import io.gutapk.core.apk.axml.AxmlParser.Companion.END_NS
import io.gutapk.core.apk.axml.AxmlParser.Companion.END_TAG
import io.gutapk.core.apk.axml.AxmlParser.Companion.START_FILE
import io.gutapk.core.apk.axml.AxmlParser.Companion.START_NS
import io.gutapk.core.apk.axml.AxmlParser.Companion.START_TAG
import io.gutapk.core.apk.axml.AxmlParser.Companion.TEXT
import java.io.IOException
import java.util.Stack

/**
 * a class to read android axml
 *
 * @author <a href="mailto:pxb1988@gmail.com">Panxiaobo</a>
 */
// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlReader.java#AxmlReader
class AxmlReader(data: ByteArray) {
    internal val parser: AxmlParser = AxmlParser(data)

    // https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/AxmlReader.java#AxmlReader
    @Throws(IOException::class)
    fun accept(av: AxmlVisitor) {
        val nvs = Stack<NodeVisitor>()
        var tos: NodeVisitor? = av
        while (true) {
            val type = parser.next()
            when (type) {
                START_FILE -> {}
                START_TAG -> {
                    nvs.push(tos)
                    tos = tos!!.child(parser.getNamespaceUri(), parser.getName())
                    if (tos != null) {
                        if (tos !== EMPTY_VISITOR) {
                            tos.line(parser.getLineNumber())
                            for (i in 0 until parser.getAttrCount()) {
                                tos.attr(
                                    parser.getAttrNs(i), parser.getAttrName(i), parser.getAttrResId(i),
                                    parser.getAttrType(i), parser.getAttrValue(i),
                                )
                            }
                        }
                    } else {
                        tos = EMPTY_VISITOR
                    }
                }
                END_TAG -> {
                    tos!!.end()
                    tos = nvs.pop()
                }
                START_NS -> av.ns(parser.getNamespacePrefix(), parser.getNamespaceUri(), parser.getLineNumber())
                END_NS -> {}
                TEXT -> tos!!.text(parser.getLineNumber(), parser.getText())
                END_FILE -> return
                // Sable logs a warning here with slf4j, which the app does not
                // carry. next never returns another type, so it is an error.
                else -> throw ApkFormatError("Unsupported tag: $type")
            }
        }
    }

    companion object {
        val EMPTY_VISITOR: NodeVisitor = object : NodeVisitor() {
            override fun child(ns: String?, name: String?): NodeVisitor = this
        }
    }
}
