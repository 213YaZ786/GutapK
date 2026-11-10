package io.gutapk.core.offline

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// BSON, the binary JSON a game's server and client may speak (Jungle Heat's
// commands and answers are BSON documents). mongo-java-server's decoder and
// encoder, read into plain Kotlin values: a document is a LinkedHashMap kept
// in order, an array a List, int32 an Int, int64 a Long, a double a Double,
// binary data a ByteArray. The PC side reads the recordings with it and
// writes the local server's files, the phone has the same port in Java
// (src/dex/java/io/gutapk/offline/Bson.java).
// https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeBson
// https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonEncoder.java#encodeDocument
// not ported: decodePattern, decodeObjectId, BsonTimestamp, Decimal128, MaxKey, MinKey, BsonJavaScript, LegacyUUID and UUID subtypes (no game recorded so far sends them, they are refused as unknown)
// not ported: Assert.isNull on a repeated field (the last value wins, as a map keeps it)
// https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java#validateNestingDepth
// not ported: MAX_BSON_OBJECT_SIZE (a recording is already on disk, its size is the file's)
// not ported: StreamReadConstraints builder, withMaxNestingDepth, getMaxNestingDepth, _constrainRef, _constructException and the other constraints (only the nesting depth overflows the stack, and its limit is one constant)
// Copyright (c) 2012-2024 Benedikt Waldvogel, BSD-3-Clause.
object Bson {
    private const val TERMINATING_BYTE: Byte = 0x00
    private const val TYPE_DOUBLE: Byte = 0x01
    private const val TYPE_UTF8_STRING: Byte = 0x02
    private const val TYPE_EMBEDDED_DOCUMENT: Byte = 0x03
    private const val TYPE_ARRAY: Byte = 0x04
    private const val TYPE_DATA: Byte = 0x05
    private const val TYPE_UNDEFINED: Byte = 0x06
    private const val TYPE_BOOLEAN: Byte = 0x08
    private const val TYPE_UTC_DATETIME: Byte = 0x09
    private const val TYPE_NULL: Byte = 0x0A
    private const val TYPE_INT32: Byte = 0x10
    private const val TYPE_INT64: Byte = 0x12
    private const val BINARY_SUBTYPE_GENERIC: Byte = 0x00

    // How deep documents and arrays may nest. A recording is a file the user may have got from anywhere,
    // and a deep one would overflow the stack, an Error that documents() does not catch. The deepest
    // nesting recorded is 9.
    const val DEFAULT_MAX_DEPTH = 1000
    private const val maxNestingDepth = DEFAULT_MAX_DEPTH

    /** The documents of a body one after the other, empty when it is not BSON. */
    fun documents(body: ByteArray): List<Map<String, Any?>> {
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<Map<String, Any?>>()
        return try {
            while (buffer.hasRemaining()) out.add(decodeBson(buffer))
            out
        } catch (e: RuntimeException) {
            emptyList()
        }
    }

    /** The one document a body holds, null when it is not exactly one BSON document. */
    fun document(body: ByteArray): Map<String, Any?>? = documents(body).singleOrNull()

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeBson
    fun decodeBson(buffer: ByteBuffer): LinkedHashMap<String, Any?> = decodeBson(buffer, 1)

    // Throws past the limit, IllegalArgumentException being a RuntimeException that documents() reads as "not BSON".
    // https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java#validateNestingDepth
    internal fun validateNestingDepth(depth: Int) {
        if (depth > maxNestingDepth) {
            throw IllegalArgumentException("Document nesting depth ($depth) exceeds the maximum allowed ($maxNestingDepth, from Bson.DEFAULT_MAX_DEPTH)")
        }
    }

    private fun decodeBson(buffer: ByteBuffer, depth: Int): LinkedHashMap<String, Any?> {
        validateNestingDepth(depth)
        val totalObjectLength = buffer.int
        val length = totalObjectLength - 4
        if (length < 1 || buffer.remaining() < length) {
            throw IllegalArgumentException("Too few bytes to read: " + buffer.remaining() + ". Expected: " + length)
        }
        val document = LinkedHashMap<String, Any?>()
        val start = buffer.position()
        while (buffer.position() - start < length) {
            val type = buffer.get()
            if (type == TERMINATING_BYTE) {
                return document
            }
            val name = decodeCString(buffer)
            document[name] = decodeValue(type, buffer, depth)
        }
        throw IllegalArgumentException("illegal BSON object. Terminating byte not found. totalObjectLength = $totalObjectLength")
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeValue
    // The depth is that of the document holding the value.
    private fun decodeValue(type: Byte, buffer: ByteBuffer, depth: Int): Any? = when (type) {
        TYPE_DOUBLE -> java.lang.Double.longBitsToDouble(buffer.long)
        TYPE_UTF8_STRING -> decodeString(buffer)
        TYPE_EMBEDDED_DOCUMENT -> decodeBson(buffer, depth + 1)
        TYPE_ARRAY -> decodeArray(buffer, depth + 1)
        TYPE_DATA -> decodeBinary(buffer)
        TYPE_UNDEFINED, TYPE_NULL -> null
        TYPE_BOOLEAN -> decodeBoolean(buffer)
        TYPE_UTC_DATETIME -> buffer.long
        TYPE_INT32 -> buffer.int
        TYPE_INT64 -> buffer.long
        else -> throw IllegalArgumentException("unknown type: 0x" + Integer.toHexString(type.toInt()))
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeArray
    private fun decodeArray(buffer: ByteBuffer, depth: Int): List<Any?> {
        val arrayObject = decodeBson(buffer, depth)
        return arrayObject.keys.mapTo(ArrayList()) { arrayObject[it] }
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeString
    private fun decodeString(buffer: ByteBuffer): String {
        val length = buffer.int
        if (length < 1 || length > buffer.remaining()) throw IllegalArgumentException("Illegal string length: $length")
        val data = ByteArray(length - 1)
        buffer.get(data)
        val value = String(data, Charsets.UTF_8)
        val trail = buffer.get()
        if (trail != TERMINATING_BYTE) {
            throw IllegalArgumentException("Unexpected trailing byte: $trail")
        }
        return value
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeCString
    private fun decodeCString(buffer: ByteBuffer): String {
        val start = buffer.position()
        var end = start
        while (end < buffer.limit() && buffer.get(end) != TERMINATING_BYTE) end++
        if (end == buffer.limit()) throw IllegalArgumentException("string termination not found")
        val bytes = ByteArray(end - start)
        buffer.get(bytes)
        buffer.get()
        return String(bytes, Charsets.UTF_8)
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeBinary
    private fun decodeBinary(buffer: ByteBuffer): ByteArray {
        val length = buffer.int
        val subtype = buffer.get()
        if (length < 0 || length > buffer.remaining()) throw IllegalArgumentException("Illegal length: $length")
        if (subtype != BINARY_SUBTYPE_GENERIC && subtype != 0x80.toByte()) throw IllegalArgumentException("Unknown subtype: $subtype")
        return ByteArray(length).also { buffer.get(it) }
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeBoolean
    private fun decodeBoolean(buffer: ByteBuffer): Boolean = when (val value = buffer.get().toInt()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Illegal boolean value:$value")
    }

    /** A document as BSON bytes. */
    fun encode(document: Map<String, Any?>): ByteArray = ByteArrayOutputStream().also { encodeDocument(document, it) }.toByteArray()

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonEncoder.java#encodeDocument
    // The total length is known once the fields are written: they go to a buffer of their own first,
    // where the netty original writes a zero and comes back to it.
    fun encodeDocument(document: Map<String, Any?>, out: ByteArrayOutputStream) {
        val buffer = ByteArrayOutputStream()
        for (key in document.keys) {
            encodeValue(key, document[key], buffer)
        }
        buffer.write(TERMINATING_BYTE.toInt())
        writeIntLE(out, buffer.size() + 4)
        buffer.writeTo(out)
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonEncoder.java#encodeCString
    private fun encodeCString(data: String, buffer: ByteArrayOutputStream) {
        buffer.write(data.toByteArray(Charsets.UTF_8))
        buffer.write(TERMINATING_BYTE.toInt())
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonEncoder.java#encodeString
    private fun encodeString(data: String, buffer: ByteArrayOutputStream) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        writeIntLE(buffer, bytes.size + 1)
        buffer.write(bytes)
        buffer.write(TERMINATING_BYTE.toInt())
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonEncoder.java#encodeValue
    private fun encodeValue(key: String, value: Any?, buffer: ByteArrayOutputStream) {
        val type = determineType(value)
        buffer.write(type.toInt())
        encodeCString(key, buffer)
        when (type) {
            TYPE_DOUBLE -> writeLongLE(buffer, java.lang.Double.doubleToRawLongBits((value as Number).toDouble()))
            TYPE_UTF8_STRING -> encodeString(value.toString(), buffer)
            TYPE_EMBEDDED_DOCUMENT -> {
                @Suppress("UNCHECKED_CAST")
                encodeDocument(value as Map<String, Any?>, buffer)
            }
            TYPE_ARRAY -> {
                val document = LinkedHashMap<String, Any?>()
                (value as Collection<*>).forEachIndexed { i, item -> document[i.toString()] = item }
                encodeDocument(document, buffer)
            }
            TYPE_DATA -> {
                val data = value as ByteArray
                writeIntLE(buffer, data.size)
                buffer.write(BINARY_SUBTYPE_GENERIC.toInt())
                buffer.write(data)
            }
            TYPE_BOOLEAN -> buffer.write(if (value as Boolean) 1 else 0)
            TYPE_INT32 -> writeIntLE(buffer, (value as Number).toInt())
            TYPE_INT64 -> writeLongLE(buffer, value as Long)
            TYPE_NULL -> Unit
        }
    }

    // https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonEncoder.java#determineType
    // not ported: ObjectId, Decimal128, Instant, BsonTimestamp, BsonRegularExpression, MaxKey, MinKey, BsonJavaScript, Missing (never decoded above)
    private fun determineType(value: Any?): Byte = when (value) {
        null -> TYPE_NULL
        is Map<*, *> -> TYPE_EMBEDDED_DOCUMENT
        is Int, is Short, is Byte -> TYPE_INT32
        is Long -> TYPE_INT64
        is Double, is Float -> TYPE_DOUBLE
        is String -> TYPE_UTF8_STRING
        is Boolean -> TYPE_BOOLEAN
        is ByteArray -> TYPE_DATA
        is Collection<*> -> TYPE_ARRAY
        else -> throw IllegalArgumentException("Unknown type: " + value.javaClass)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun writeLongLE(out: ByteArrayOutputStream, value: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }
}
