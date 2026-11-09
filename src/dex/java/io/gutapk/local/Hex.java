package io.gutapk.local;

/*
 * The key of an invitation as text for the link, lowercase hexadecimal, and
 * back. android.util.Base64 is not on the JVM the tests run on, and
 * java.util.Base64 came with Android 8. Ported from
 * https://github.com/apache/commons-codec/blob/master/src/main/java/org/apache/commons/codec/binary/Hex.java#L80-L116
 * https://github.com/apache/commons-codec/blob/master/src/main/java/org/apache/commons/codec/binary/Hex.java#L215-L222
 * https://github.com/apache/commons-codec/blob/master/src/main/java/org/apache/commons/codec/binary/Hex.java#L388-L400
 *
 * Not ported: DIGITS_UPPER, encodeHex(ByteBuffer), decode, encode, getCharset, Hex(Charset) (one alphabet, one string form)
 * Not ported: DecoderException (IllegalArgumentException: a bad link is the caller's to report)
 *
 * Licensed to the Apache Software Foundation (ASF), Apache License 2.0.
 */
public final class Hex {
    private static final char[] DIGITS_LOWER = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    private Hex() {
    }

    public static String encodeHexString(final byte[] data) {
        final char[] out = new char[data.length << 1];
        // two characters form the hex value.
        for (int i = 0, j = 0; i < data.length; i++) {
            out[j++] = DIGITS_LOWER[(0xF0 & data[i]) >>> 4];
            out[j++] = DIGITS_LOWER[0x0F & data[i]];
        }
        return new String(out);
    }

    public static byte[] decodeHex(final String text) {
        final char[] data = text.toCharArray();
        final int len = data.length;
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("Odd number of characters " + len + ".");
        }
        final byte[] out = new byte[len >> 1];
        // two characters form the hex value.
        for (int i = 0, j = 0; j < len; i++) {
            int f = toDigit(data[j], j) << 4;
            j++;
            f |= toDigit(data[j], j);
            j++;
            out[i] = (byte) (f & 0xFF);
        }
        return out;
    }

    private static int toDigit(final char ch, final int index) {
        final int digit;
        if (ch >= '0' && ch <= '9') {
            digit = ch - '0';
        } else if (ch >= 'A' && ch <= 'F') {
            digit = ch - 'A' + 10;
        } else if (ch >= 'a' && ch <= 'f') {
            digit = ch - 'a' + 10;
        } else {
            digit = -1;
        }
        if (digit == -1) {
            throw new IllegalArgumentException("Illegal hexadecimal character at index " + index + ".");
        }
        return digit;
    }
}
