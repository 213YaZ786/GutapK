package io.gutapk.local;

/*
 * The encryption of Local, Shadowsocks AEAD with AES-256-GCM: one side of a
 * stream is a random salt, then chunks of
 * [encrypted payload length][length tag][encrypted payload][payload tag],
 * sealed with a subkey HKDF_SHA1(key, salt, "ss-subkey") and a counting nonce
 * that goes up twice per chunk. A datagram is [salt][encrypted payload][tag]
 * with a zero nonce. The key is the one the host's QR code carries. Ported from
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/cipher/AbstractAEADCipher.java#L75-L99
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/cipher/AbstractAEADCipher.java#L149-L157
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/cipher/impl/AbstractBCAEADCipher.java#L84-L111
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/cipher/impl/AbstractBCAEADCipher.java#L119-L139
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/cipher/impl/AbstractBCAEADCipher.java#L227-L250
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/cipher/kdf/SSKDF.java#L78-L85
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/cipher/SSAEADCipher.java
 * and the HKDF they call,
 * https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/generators/HKDFBytesGenerator.java#L73-L120
 * with javax.crypto in place of BouncyCastle: Android carries AES-GCM and HMAC.
 *
 * Not ported: encryptWithAEAD, decryptChunk (a stream is read chunk by chunk with readFully, nothing is buffered across reads)
 * Not ported: encryptMessageInternal (inlined in encryptAll, its only caller)
 * Not ported: expandNext (inlined in aeadKey, which makes the one 32-byte output)
 * Not ported: tagSize, nonceSize, saltSize, keySize (one cipher: the constants DEFAULT_TAG_SIZE, DEFAULT_NONCE_SIZE and KEY_SIZE)
 * Not ported: evpBytesToKey, rc4md5Key, KeyInfo (the key is random bytes, not a password)
 * Not ported: reset, getEncryptSubKey, getDecryptSubKey, getZeroNonce, randomSize, encryptAll(byte[]), decryptAll(byte[]) (one Aead per direction, its key and nonce stay inside)
 * Not ported: init(DerivationParameters), getDigest, generateBytes, skipExtract (one HKDF call of 32 bytes)
 * Not ported: AESGCMCipher, AESAlgorithm, beforeInitializeKey, cipher, cipherParameters (one cipher, AES-256-GCM)
 *
 * Copyright 2020 xingty, Apache License 2.0.
 * Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc., MIT License.
 */

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Aead {
    public static final int CHUNK_SIZE_LEN = 2;
    public static final int CHUNK_SIZE_MASK = 0x3FFF;
    public static final int DEFAULT_NONCE_SIZE = 12;
    public static final int DEFAULT_TAG_SIZE = 16;
    /** AES-256: the key, the salt and the subkey are 32 bytes. */
    public static final int KEY_SIZE = 32;

    private static final byte[] SUB_KEY = "ss-subkey".getBytes(StandardCharsets.US_ASCII);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] sessionKey;
    private final byte[] nonce = new byte[DEFAULT_NONCE_SIZE];

    private Aead(byte[] sessionKey) {
        this.sessionKey = sessionKey;
    }

    /** A new random key, for the QR code. */
    public static byte[] newKey() {
        return randomBytes(KEY_SIZE);
    }

    static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * The sending side of a stream: writes the salt it seals with, and returns
     * the Aead that seals the chunks after it.
     */
    public static Aead encrypt(byte[] ssKey, OutputStream out) throws IOException, GeneralSecurityException {
        byte[] salt = randomBytes(KEY_SIZE);
        out.write(salt);
        return new Aead(aeadKey(ssKey, salt, KEY_SIZE));
    }

    /**
     * The receiving side of a stream: reads the salt, refused when seen
     * before, and returns the Aead that opens the chunks after it.
     */
    public static Aead decrypt(byte[] ssKey, DataInputStream in, PingPongBloom seen) throws IOException, GeneralSecurityException {
        byte[] salt = new byte[KEY_SIZE];
        in.readFully(salt);
        if (seen != null && seen.check_and_set(salt)) {
            throw new GeneralSecurityException("salt already seen, the stream is replayed");
        }
        return new Aead(aeadKey(ssKey, salt, KEY_SIZE));
    }

    /**
     * Writes [encrypted payload length][length tag][encrypted payload][payload tag],
     * split in chunks of at most CHUNK_SIZE_MASK bytes.
     */
    public void write(OutputStream out, byte[] plaintext, int offset, int len) throws IOException, GeneralSecurityException {
        int remain = len;
        while (remain > 0) {
            int consume = Math.min(remain, CHUNK_SIZE_MASK);
            out.write(encryptChunk(plaintext, offset, consume));
            offset += consume;
            remain -= consume;
        }
    }

    private byte[] encryptChunk(byte[] plaintext, int offset, int len) throws GeneralSecurityException {
        int tagSize = DEFAULT_TAG_SIZE;
        byte[] ciphertext = new byte[tagSize * 2 + len + CHUNK_SIZE_LEN];
        ByteBuffer buf = ByteBuffer.wrap(ciphertext);
        buf.putShort((short) len);
        // encrypt payload length
        int cPos = buf.position() - CHUNK_SIZE_LEN;
        encryptInternal(sessionKey, nonce, ciphertext, cPos, CHUNK_SIZE_LEN, ciphertext, cPos);
        buf.position(buf.position() + tagSize);
        incrementNonce(nonce);

        encryptInternal(sessionKey, nonce, plaintext, offset, len, ciphertext, buf.position());
        incrementNonce(nonce);
        return ciphertext;
    }

    /**
     * Reads one chunk and returns its payload, or null when the stream ends
     * between chunks. A chunk that does not open is an error: the stream was
     * not sealed with this key, or was tampered with.
     */
    public byte[] read(DataInputStream in) throws IOException, GeneralSecurityException {
        int tagSize = DEFAULT_TAG_SIZE;
        byte[] lenText = new byte[CHUNK_SIZE_LEN + tagSize];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        lenText[0] = (byte) first;
        in.readFully(lenText, 1, lenText.length - 1);
        int payloadLen = decryptChunkSize(lenText, sessionKey, nonce);
        incrementNonce(nonce);
        if (payloadLen <= 0 || payloadLen > CHUNK_SIZE_MASK) {
            throw new GeneralSecurityException("invalid payload length " + payloadLen);
        }
        byte[] chunkText = new byte[payloadLen + tagSize];
        in.readFully(chunkText);
        byte[] pt = decryptChunkPayload(chunkText, 0, chunkText.length, sessionKey, nonce);
        incrementNonce(nonce);
        return pt;
    }

    /** [salt][encrypted payload][tag], one datagram sealed with its own salt and a zero nonce. */
    public static byte[] encryptAll(byte[] ssKey, byte[] plaintext, int offset, int len) throws GeneralSecurityException {
        byte[] salt = randomBytes(KEY_SIZE);
        byte[] otk = aeadKey(ssKey, salt, KEY_SIZE);
        byte[] ciphertext = new byte[salt.length + len + DEFAULT_TAG_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(ciphertext);
        buf.put(salt);
        encryptInternal(otk, new byte[DEFAULT_NONCE_SIZE], plaintext, offset, len, ciphertext, buf.position());
        return ciphertext;
    }

    /** Opens a datagram of encryptAll; a salt seen before is refused. */
    public static byte[] decryptAll(byte[] ssKey, byte[] ciphertext, int offset, int len, PingPongBloom seen) throws GeneralSecurityException {
        if (len < KEY_SIZE + DEFAULT_TAG_SIZE) {
            throw new GeneralSecurityException("datagram too short");
        }
        byte[] salt = new byte[KEY_SIZE];
        System.arraycopy(ciphertext, offset, salt, 0, salt.length);
        byte[] otk = aeadKey(ssKey, salt, KEY_SIZE);
        byte[] plaintext = decryptChunkPayload(ciphertext, offset + salt.length, len - salt.length, otk, new byte[DEFAULT_NONCE_SIZE]);
        // Recorded once the datagram opened: anyone could send random salts.
        if (seen != null && seen.check_and_set(salt)) {
            throw new GeneralSecurityException("salt already seen, the datagram is replayed");
        }
        return plaintext;
    }

    private static void encryptInternal(byte[] key, byte[] nonce, byte[] in, int inOff, int inLen, byte[] out, int outOff) throws GeneralSecurityException {
        Cipher encoder = Cipher.getInstance(TRANSFORMATION);
        encoder.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(DEFAULT_TAG_SIZE * 8, nonce.clone()));
        encoder.doFinal(in, inOff, inLen, out, outOff);
    }

    private static int decryptChunkSize(byte[] ciphertext, byte[] key, byte[] nonce) throws GeneralSecurityException {
        byte[] p = decryptChunkPayload(ciphertext, 0, ciphertext.length, key, nonce);
        // convert bytes to unsigned short
        return ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
    }

    private static byte[] decryptChunkPayload(byte[] ciphertext, int offset, int len, byte[] key, byte[] nonce) throws GeneralSecurityException {
        Cipher decoder = Cipher.getInstance(TRANSFORMATION);
        decoder.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(DEFAULT_TAG_SIZE * 8, nonce.clone()));
        return decoder.doFinal(ciphertext, offset, len);
    }

    /** The nonce is incremented by one as if it were an unsigned little-endian integer. */
    static void incrementNonce(byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] += 1;
            // carry
            if (nonce[i] != 0) {
                break;
            }
        }
    }

    /** HKDF_SHA1(ssKey, salt, "ss-subkey"), keySize bytes. */
    static byte[] aeadKey(byte[] ssKey, byte[] salt, int keySize) throws GeneralSecurityException {
        return hkdf(ssKey, salt, SUB_KEY, keySize);
    }

    /**
     * HKDF with HMAC-SHA1 of RFC 5869, keySize bytes, the expand steps of
     * https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/generators/HKDFBytesGenerator.java#generateBytes
     * not ported: expandNext (inlined in the loop below, the one place it runs)
     * not ported: extractPRK (the method of this name below, which takes the Mac)
     */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int keySize) throws GeneralSecurityException {
        Mac hMacHash = Mac.getInstance(HMAC);
        int hashLen = hMacHash.getMacLength();
        hMacHash.init(new SecretKeySpec(extractPRK(hMacHash, salt, ikm), HMAC));
        byte[] key = new byte[keySize];
        byte[] currentT = new byte[0];
        int generatedBytes = 0;
        while (generatedBytes < keySize) {
            int n = generatedBytes / hashLen + 1;
            if (n >= 256) {
                throw new GeneralSecurityException("HKDF cannot generate more than 255 blocks of HashLen size");
            }
            // T(0) is empty
            hMacHash.update(currentT);
            hMacHash.update(info);
            hMacHash.update((byte) n);
            currentT = hMacHash.doFinal();
            int toCopy = Math.min(hashLen, keySize - generatedBytes);
            System.arraycopy(currentT, 0, key, generatedBytes, toCopy);
            generatedBytes += toCopy;
        }
        return key;
    }

    private static byte[] extractPRK(Mac hMacHash, byte[] salt, byte[] ikm) throws GeneralSecurityException {
        // RFC 5869 sec. 2.2: no salt is HashLen zero octets.
        hMacHash.init(new SecretKeySpec(salt != null ? salt : new byte[hMacHash.getMacLength()], HMAC));
        return hMacHash.doFinal(ikm);
    }
}
