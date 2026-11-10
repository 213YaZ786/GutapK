package io.gutapk.offline;

/*
 * BSON, the binary JSON a game's server and client may speak (Jungle Heat's
 * commands and answers are BSON documents). mongo-java-server's decoder and
 * encoder, read into plain Java values: a document is a LinkedHashMap kept in
 * order, an array an ArrayList, int32 an Integer, int64 a Long, a double a
 * Double, binary data a byte[], a date a java.util.Date. Netty's ByteBuf is a
 * java.nio.ByteBuffer set little-endian: reads go straight to it, writes go
 * through Out, which grows the way a ByteBuf does. The PC side has the same
 * port in Kotlin (core/offline/Bson.kt).
 *
 * https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonDecoder.java#decodeBson
 * https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/bson/BsonEncoder.java#encodeDocument
 * https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/main/java/de/bwaldvogel/mongo/wire/BsonConstants.java
 * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java#validateNestingDepth
 *
 * Not ported: decodePattern, decodeObjectId, BsonTimestamp, Decimal128, MaxKey, MinKey, BsonJavaScript, LegacyUUID and UUID subtypes (no game recorded so far sends them, they are refused as unknown and the body is then not handled)
 * Not ported: Assert.isNull on a repeated field (the last value wins, as a map keeps it)
 * Not ported: Instant for TYPE_UTC_DATETIME (java.time needs Android 8, a Date holds the same milliseconds)
 * Not ported: Missing, Document (a LinkedHashMap is the document)
 * Not ported: StreamReadConstraints builder, withMaxNestingDepth, getMaxNestingDepth, _constrainRef, _constructException and the other constraints (number, string, name length, token count: only the nesting depth overflows the stack, and its limit is one constant)
 *
 * Copyright (c) 2012-2024 Benedikt Waldvogel, BSD-3-Clause.
 */

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Bson {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    static final int MAX_BSON_OBJECT_SIZE = 16 * 1024 * 1024;

    /**
     * How deep documents and arrays may nest. Any phone app can write to the game port, and about
     * 70 KB of nested documents would otherwise overflow the stack, an Error no caller catches.
     * The deepest nesting recorded is 9.
     */
    public static final int DEFAULT_MAX_DEPTH = 1000;

    private static final int maxNestingDepth = DEFAULT_MAX_DEPTH;

    static final byte TERMINATING_BYTE = 0x00;
    static final byte TYPE_DOUBLE = 0x01;
    static final byte TYPE_UTF8_STRING = 0x02;
    static final byte TYPE_EMBEDDED_DOCUMENT = 0x03;
    static final byte TYPE_ARRAY = 0x04;
    static final byte TYPE_DATA = 0x05;
    static final byte TYPE_UNDEFINED = 0x06;
    static final byte TYPE_BOOLEAN = 0x08;
    static final byte TYPE_UTC_DATETIME = 0x09;
    static final byte TYPE_NULL = 0x0A;
    static final byte TYPE_INT32 = 0x10;
    static final byte TYPE_INT64 = 0x12;

    static final byte BOOLEAN_VALUE_FALSE = 0x00;
    static final byte BOOLEAN_VALUE_TRUE = 0x01;

    static final byte STRING_TERMINATION = 0x00;

    static final byte BINARY_SUBTYPE_GENERIC = 0x00;
    static final byte BINARY_SUBTYPE_USER_DEFINED = (byte) 0x80;

    private Bson() {
    }

    /** The one document a body holds. Anything else, a short body, a type not ported or bytes left after it, is an IllegalArgumentException. */
    public static LinkedHashMap<String, Object> decode(byte[] body) {
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        try {
            LinkedHashMap<String, Object> document = decodeBson(buffer);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Bytes left after the document: " + buffer.remaining());
            }
            return document;
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("Too few bytes to read");
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Bad length: " + e.getMessage());
        } catch (NegativeArraySizeException e) {
            throw new IllegalArgumentException("Bad length: " + e.getMessage());
        }
    }

    public static LinkedHashMap<String, Object> decodeBson(ByteBuffer buffer) {
        return decodeBson(buffer, 1);
    }

    /**
     * Throws past the limit, IllegalArgumentException being what callers read as "not BSON".
     * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java#validateNestingDepth
     */
    static void validateNestingDepth(int depth) {
        if (depth > maxNestingDepth) {
            throw new IllegalArgumentException("Document nesting depth (" + depth + ") exceeds the maximum allowed (" + maxNestingDepth + ", from Bson.DEFAULT_MAX_DEPTH)");
        }
    }

    private static LinkedHashMap<String, Object> decodeBson(ByteBuffer buffer, int depth) {
        validateNestingDepth(depth);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        final int totalObjectLength = buffer.getInt();
        final int length = totalObjectLength - 4;
        if (buffer.remaining() < length) {
            throw new IllegalArgumentException("Too few bytes to read: " + buffer.remaining() + ". Expected: " + length);
        }
        if (length > MAX_BSON_OBJECT_SIZE) {
            throw new IllegalArgumentException("BSON object too large: " + length + " bytes");
        }
        if (length < 1) {
            throw new IllegalArgumentException("BSON object too short: " + totalObjectLength + " bytes");
        }

        LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
        int start = buffer.position();
        while (buffer.position() - start < length) {
            byte type = buffer.get();
            if (type == TERMINATING_BYTE) {
                return document;
            }
            String name = decodeCString(buffer);
            Object value = decodeValue(type, buffer, depth);
            document.put(name, value);
        }
        throw new IllegalArgumentException("illegal BSON object. Terminating byte not found. totalObjectLength = " + totalObjectLength);
    }

    public static Object decodeValue(byte type, ByteBuffer buffer) {
        return decodeValue(type, buffer, 1);
    }

    /** The depth is that of the document holding the value. */
    private static Object decodeValue(byte type, ByteBuffer buffer, int depth) {
        switch (type) {
            case TYPE_DOUBLE:
                return Double.valueOf(Double.longBitsToDouble(buffer.getLong()));
            case TYPE_UTF8_STRING:
                return decodeString(buffer);
            case TYPE_EMBEDDED_DOCUMENT:
                return decodeBson(buffer, depth + 1);
            case TYPE_ARRAY:
                return decodeArray(buffer, depth + 1);
            case TYPE_DATA:
                return decodeBinary(buffer);
            case TYPE_UNDEFINED:
            case TYPE_NULL:
                return null;
            case TYPE_BOOLEAN:
                return decodeBoolean(buffer);
            case TYPE_UTC_DATETIME:
                return new Date(buffer.getLong());
            case TYPE_INT32:
                return Integer.valueOf(buffer.getInt());
            case TYPE_INT64:
                return Long.valueOf(buffer.getLong());
            default:
                throw new IllegalArgumentException("unknown type: 0x" + Integer.toHexString(type & 0xFF));
        }
    }

    private static List<Object> decodeArray(ByteBuffer buffer, int depth) {
        List<Object> array = new ArrayList<Object>();
        LinkedHashMap<String, Object> arrayObject = decodeBson(buffer, depth);
        for (String key : arrayObject.keySet()) {
            array.add(arrayObject.get(key));
        }
        return array;
    }

    private static String decodeString(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 1 || length > buffer.remaining()) {
            throw new IllegalArgumentException("Illegal string length: " + length);
        }
        byte[] data = new byte[length - 1];
        buffer.get(data);
        String value = new String(data, UTF_8);
        byte trail = buffer.get();
        if (trail != STRING_TERMINATION) {
            throw new IllegalArgumentException("Unexpected trailing byte: " + trail);
        }
        return value;
    }

    // default visibility for unit test
    public static String decodeCString(ByteBuffer buffer) {
        // ByteBuf.bytesBefore: the bytes up to the first terminator, from the reader index.
        int length = -1;
        for (int i = buffer.position(); i < buffer.limit(); i++) {
            if (buffer.get(i) == STRING_TERMINATION) {
                length = i - buffer.position();
                break;
            }
        }
        if (length < 0)
            throw new IllegalArgumentException("string termination not found");

        byte[] data = new byte[length];
        buffer.get(data);
        // ByteBuf.skipBytes(length + 1): the terminator.
        buffer.get();
        return new String(data, UTF_8);
    }

    private static Object decodeBinary(ByteBuffer buffer) {
        int length = buffer.getInt();
        int subtype = buffer.get();
        switch (subtype) {
            case BINARY_SUBTYPE_GENERIC:
            case BINARY_SUBTYPE_USER_DEFINED: {
                if (length < 0 || length > buffer.remaining()) {
                    throw new IllegalArgumentException("Illegal length: " + length);
                }
                byte[] data = new byte[length];
                buffer.get(data);
                return data;
            }
            default:
                throw new IllegalArgumentException("Unknown subtype: " + subtype);
        }
    }

    private static Object decodeBoolean(ByteBuffer buffer) {
        byte value = buffer.get();
        switch (value) {
            case BOOLEAN_VALUE_FALSE:
                return Boolean.FALSE;
            case BOOLEAN_VALUE_TRUE:
                return Boolean.TRUE;
            default:
                throw new IllegalArgumentException("Illegal boolean value:" + value);
        }
    }

    /** The document as BSON bytes. */
    public static byte[] encode(Map<String, ?> document) {
        Out out = new Out();
        encodeDocument(document, out);
        byte[] bytes = new byte[out.writerIndex()];
        System.arraycopy(out.buffer.array(), 0, bytes, 0, bytes.length);
        return bytes;
    }

    /**
     * The part of Netty's ByteBuf the encoder writes with: little-endian
     * writes, a writer index that can be moved back to fill in a length, and
     * a capacity that grows when a write would not fit.
     * Not ported: every other method of ByteBuf (only these writes are used)
     */
    static final class Out {
        ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);

        private void ensure(int more) {
            if (buffer.remaining() < more) {
                ByteBuffer bigger = ByteBuffer.allocate(Math.max(buffer.capacity() * 2, buffer.position() + more)).order(ByteOrder.LITTLE_ENDIAN);
                bigger.put(buffer.array(), 0, buffer.position());
                buffer = bigger;
            }
        }

        int writerIndex() {
            return buffer.position();
        }

        void writerIndex(int index) {
            buffer.position(index);
        }

        void writeByte(byte value) {
            ensure(1);
            buffer.put(value);
        }

        void writeBytes(byte[] value) {
            ensure(value.length);
            buffer.put(value);
        }

        void writeIntLE(int value) {
            ensure(4);
            buffer.putInt(value);
        }

        void writeLongLE(long value) {
            ensure(8);
            buffer.putLong(value);
        }
    }

    public static void encodeDocument(Map<String, ?> document, Out out) {
        int indexBefore = out.writerIndex();
        out.writeIntLE(0); // total number of bytes will be written later

        for (String key : document.keySet()) {
            encodeValue(key, document.get(key), out);
        }

        out.writeByte(TERMINATING_BYTE);
        int indexAfter = out.writerIndex();
        out.writerIndex(indexBefore);
        out.writeIntLE(indexAfter - indexBefore);
        out.writerIndex(indexAfter);
    }

    private static void encodeCString(String data, Out buffer) {
        byte[] bytes = data.getBytes(UTF_8);
        buffer.writeBytes(bytes);
        buffer.writeByte(STRING_TERMINATION);
    }

    private static void encodeString(String data, Out buffer) {
        byte[] bytes = data.getBytes(UTF_8);
        buffer.writeIntLE(bytes.length + 1);
        buffer.writeBytes(bytes);
        buffer.writeByte(STRING_TERMINATION);
    }

    private static void encodeValue(String key, Object value, Out buffer) {
        byte type = determineType(value);
        buffer.writeByte(type);
        encodeCString(key, buffer);
        encodeValue(type, value, buffer);
    }

    @SuppressWarnings("unchecked")
    public static void encodeValue(byte type, Object value, Out buffer) {
        switch (type) {
            case TYPE_DOUBLE:
                buffer.writeLongLE(Double.doubleToRawLongBits(((Double) value).doubleValue()));
                break;
            case TYPE_UTF8_STRING:
                encodeString(value.toString(), buffer);
                break;
            case TYPE_EMBEDDED_DOCUMENT:
                Map<String, ?> valueAsMap = (Map<String, ?>) value;
                encodeDocument(valueAsMap, buffer);
                break;
            case TYPE_ARRAY:
                LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
                List<?> array = collectionToList(value);
                for (int i = 0; i < array.size(); i++) {
                    document.put(String.valueOf(i), array.get(i));
                }
                encodeDocument(document, buffer);
                break;
            case TYPE_DATA:
                if (value instanceof byte[]) {
                    byte[] data = (byte[]) value;
                    buffer.writeIntLE(data.length);
                    buffer.writeByte(BINARY_SUBTYPE_GENERIC);
                    buffer.writeBytes(data);
                } else {
                    throw new IllegalArgumentException("Unknown data: " + value.getClass());
                }
                break;
            case TYPE_BOOLEAN:
                if (((Boolean) value).booleanValue()) {
                    buffer.writeByte(BOOLEAN_VALUE_TRUE);
                } else {
                    buffer.writeByte(BOOLEAN_VALUE_FALSE);
                }
                break;
            case TYPE_UTC_DATETIME:
                Date date = (Date) value;
                buffer.writeLongLE(date.getTime());
                break;
            case TYPE_INT32:
                buffer.writeIntLE(((Integer) value).intValue());
                break;
            case TYPE_INT64:
                buffer.writeLongLE(((Long) value).longValue());
                break;
            case TYPE_UNDEFINED:
            case TYPE_NULL:
                // empty
                break;
            default:
                throw new IllegalArgumentException("unknown type: " + value.getClass());
        }
    }

    public static byte determineType(Object value) {
        if (value == null) {
            return TYPE_NULL;
        } else if (value instanceof Map) {
            return TYPE_EMBEDDED_DOCUMENT;
        } else if (value instanceof Integer) {
            return TYPE_INT32;
        } else if (value instanceof Long) {
            return TYPE_INT64;
        } else if (value instanceof Double) {
            return TYPE_DOUBLE;
        } else if (value instanceof String) {
            return TYPE_UTF8_STRING;
        } else if (value instanceof Boolean) {
            return TYPE_BOOLEAN;
        } else if (value instanceof byte[]) {
            return TYPE_DATA;
        } else if (value instanceof Collection<?>) {
            return TYPE_ARRAY;
        } else if (value instanceof Date) {
            return TYPE_UTC_DATETIME;
        } else {
            throw new IllegalArgumentException("Unknown type: " + value.getClass());
        }
    }

    private static List<?> collectionToList(Object value) {
        if (value instanceof List<?>) {
            return (List<?>) value;
        } else {
            return new ArrayList<Object>((Collection<?>) value);
        }
    }

}
