package io.gutapk.local;

/*
 * The salts the host has seen, so a stream or a datagram recorded on the
 * network and sent again is refused. Two sets used as a ring buffer, each
 * holding half the entries: when the current one is full the other is
 * cleared and becomes current, so the oldest half is forgotten. Ported from
 * https://github.com/shadowsocks/shadowsocks-rust/blob/master/crates/shadowsocks/src/security/replay/ppbloom.rs#L30-L94
 * keeping its names, with exact sets in place of bloom filters: a phone
 * hosting friends sees far fewer salts than a public server, and a set has no
 * false positive to drop a friend's packet.
 *
 * Not ported: BF_NUM_ENTRIES_FOR_CLIENT, BF_ERROR_RATE_FOR_CLIENT, BF_ERROR_RATE_FOR_SERVER, ServerType, is_local, new_for_fp_rate, debug (only the host checks, and a set has no error rate)
 *
 * Copyright (c) 2014 Y. T. CHUNG, MIT License.
 */

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

public final class PingPongBloom {
    /** About 3 MB of salts on the host, half of them kept after a turn. */
    static final int BF_NUM_ENTRIES_FOR_SERVER = 20000;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final Set<ByteBuffer>[] blooms = new Set[] {new HashSet<ByteBuffer>(), new HashSet<ByteBuffer>()};
    private final int[] bloom_count = new int[2];
    private final int item_count;
    private int current;

    public PingPongBloom() {
        this(BF_NUM_ENTRIES_FOR_SERVER);
    }

    PingPongBloom(int item_count) {
        this.item_count = item_count / 2;
    }

    // Check if data in `buf` exist.
    //
    // Set into the current bloom filter if not exist.
    //
    // Return `true` if data exist in bloom filter.
    public synchronized boolean check_and_set(byte[] buf) {
        ByteBuffer data = ByteBuffer.wrap(buf.clone());
        for (Set<ByteBuffer> bloom : blooms) {
            if (bloom.contains(data)) {
                return true;
            }
        }
        if (bloom_count[current] >= item_count) {
            // Current bloom filter is full,
            // Create a new one and use that one as current.
            current = (current + 1) % 2;
            bloom_count[current] = 0;
            blooms[current].clear();
        }
        blooms[current].add(data);
        bloom_count[current] += 1;
        return false;
    }
}
