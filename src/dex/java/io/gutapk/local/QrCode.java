package io.gutapk.local;

/*
 * A QR code for short texts, byte mode, error correction level M, versions 1
 * to 10 (up to 213 bytes): the invitation the host's phone shows, without a
 * library for one picture. GutapK's own encoder, in Java for the dex:
 * https://github.com/213YaZ786/GutapK/blob/10ee6c76cf7177086b07b3a34b0fe962b5b414a3/src/main/kotlin/io/gutapk/core/QrCode.kt#L7-L276
 */

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class QrCode {
    // Per version at level M: error correction codewords per block, blocks
    // of the first group and their data codewords, then the second group.
    // The tables of ISO/IEC 18004.
    private static final int[] EC = {10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[][] G1 = {{1, 16}, {1, 28}, {1, 44}, {2, 32}, {2, 43}, {4, 27}, {4, 31}, {2, 38}, {3, 36}, {4, 43}};
    private static final int[][] G2 = {{0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {2, 39}, {2, 37}, {1, 44}};
    private static final int[][] ALIGN = {
        {}, {6, 18}, {6, 22}, {6, 26}, {6, 30},
        {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
    };
    private static final int MAX_VERSION = 10;

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11d;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    public final int size;
    private final boolean[][] dark;

    private QrCode(int size, boolean[][] dark) {
        this.size = size;
        this.dark = dark;
    }

    public boolean isDark(int x, int y) {
        return dark[y][x];
    }

    private static int dataCodewords(int v) {
        return G1[v - 1][0] * G1[v - 1][1] + G2[v - 1][0] * G2[v - 1][1];
    }

    public static QrCode encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        for (int v = 1; v <= MAX_VERSION; v++) {
            if (4 + (v < 10 ? 8 : 16) + 8 * bytes.length <= 8 * dataCodewords(v)) {
                version = v;
                break;
            }
        }
        if (version == 0) throw new IllegalArgumentException("too long for a QR code of version 10");
        int[] data = dataBits(bytes, version);
        int[] all = interleave(data, version);
        return best(version, all);
    }

    private static int[] dataBits(byte[] bytes, int version) {
        int capacity = dataCodewords(version) * 8;
        List<Boolean> bits = new ArrayList<Boolean>();
        put(bits, 4, 4);
        put(bits, bytes.length, version < 10 ? 8 : 16);
        for (byte b : bytes) put(bits, b & 0xff, 8);
        put(bits, 0, Math.min(4, capacity - bits.size()));
        while (bits.size() % 8 != 0) bits.add(false);
        int pad = 0xec;
        while (bits.size() < capacity) {
            put(bits, pad, 8);
            pad ^= 0xec ^ 0x11;
        }
        int[] out = new int[bits.size() / 8];
        for (int i = 0; i < out.length; i++) {
            int acc = 0;
            for (int k = 0; k < 8; k++) acc = (acc << 1) | (bits.get(i * 8 + k) ? 1 : 0);
            out[i] = acc;
        }
        return out;
    }

    private static void put(List<Boolean> bits, int value, int count) {
        for (int i = count - 1; i >= 0; i--) bits.add(((value >>> i) & 1) == 1);
    }

    // Each block gets its Reed-Solomon codewords, then the blocks are read
    // column by column, data first, error correction after.
    private static int[] interleave(int[] data, int version) {
        int ec = EC[version - 1];
        List<int[]> blocks = new ArrayList<int[]>();
        int at = 0;
        for (int[] group : new int[][] {G1[version - 1], G2[version - 1]}) {
            for (int n = 0; n < group[0]; n++) {
                int[] block = new int[group[1]];
                System.arraycopy(data, at, block, 0, group[1]);
                blocks.add(block);
                at += group[1];
            }
        }
        int[] gen = generator(ec);
        List<int[]> ecBlocks = new ArrayList<int[]>();
        int longest = 0;
        for (int[] block : blocks) {
            ecBlocks.add(remainder(block, gen));
            longest = Math.max(longest, block.length);
        }
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < longest; i++) for (int[] block : blocks) if (i < block.length) out.add(block[i]);
        for (int i = 0; i < ec; i++) for (int[] block : ecBlocks) out.add(block[i]);
        int[] all = new int[out.size()];
        for (int i = 0; i < all.length; i++) all[i] = out.get(i);
        return all;
    }

    private static int mul(int a, int b) {
        return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
    }

    // The product of (x - a^i) for i below degree, highest term first, the
    // leading 1 left out.
    private static int[] generator(int degree) {
        int[] poly = {1};
        for (int i = 0; i < degree; i++) {
            int[] next = new int[poly.length + 1];
            for (int j = 0; j < poly.length; j++) {
                next[j] ^= poly[j];
                next[j + 1] ^= mul(poly[j], EXP[i]);
            }
            poly = next;
        }
        int[] gen = new int[poly.length - 1];
        System.arraycopy(poly, 1, gen, 0, gen.length);
        return gen;
    }

    private static int[] remainder(int[] data, int[] gen) {
        int[] r = new int[gen.length];
        for (int b : data) {
            int factor = b ^ r[0];
            for (int i = 0; i < r.length - 1; i++) r[i] = r[i + 1];
            r[r.length - 1] = 0;
            for (int i = 0; i < gen.length; i++) r[i] ^= mul(gen[i], factor);
        }
        return r;
    }

    // The eight masks are all drawn and scored, the lowest penalty wins.
    private static QrCode best(int version, int[] codewords) {
        int size = version * 4 + 17;
        boolean[][] chosen = null;
        int lowest = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] grid = new boolean[size][size];
            boolean[][] function = new boolean[size][size];
            drawFunction(version, size, grid, function);
            drawData(size, grid, function, codewords);
            applyMask(size, grid, function, mask);
            drawFormat(size, grid, function, mask);
            int p = penalty(size, grid);
            if (p < lowest) {
                lowest = p;
                chosen = grid;
            }
        }
        if (chosen == null) throw new IllegalStateException("no mask");
        return new QrCode(size, chosen);
    }

    private static void set(boolean[][] grid, boolean[][] function, int x, int y, boolean dark) {
        grid[y][x] = dark;
        function[y][x] = true;
    }

    private static void drawFunction(int version, int size, boolean[][] grid, boolean[][] function) {
        for (int i = 0; i < size; i++) {
            set(grid, function, 6, i, i % 2 == 0);
            set(grid, function, i, 6, i % 2 == 0);
        }
        for (int[] c : new int[][] {{3, 3}, {size - 4, 3}, {3, size - 4}}) {
            for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
                int x = c[0] + dx;
                int y = c[1] + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    int d = Math.max(Math.abs(dx), Math.abs(dy));
                    set(grid, function, x, y, d != 2 && d != 4);
                }
            }
        }
        int[] align = ALIGN[version - 1];
        for (int i = 0; i < align.length; i++) for (int j = 0; j < align.length; j++) {
            boolean corner = (i == 0 && j == 0) || (i == 0 && j == align.length - 1) || (i == align.length - 1 && j == 0);
            if (corner) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                set(grid, function, align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        // Reserved now, written once the mask is known.
        drawFormat(size, grid, function, 0);
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
            int bits = (version << 12) | rem;
            for (int i = 0; i < 18; i++) {
                boolean dark = ((bits >>> i) & 1) == 1;
                int a = size - 11 + i % 3;
                int b = i / 3;
                set(grid, function, a, b, dark);
                set(grid, function, b, a, dark);
            }
        }
    }

    // Level M is 00 in the two format bits.
    private static void drawFormat(int size, boolean[][] grid, boolean[][] function, int mask) {
        int data = mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(grid, function, 8, i, bit(bits, i));
        set(grid, function, 8, 7, bit(bits, 6));
        set(grid, function, 8, 8, bit(bits, 7));
        set(grid, function, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(grid, function, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(grid, function, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(grid, function, 8, size - 15 + i, bit(bits, i));
        set(grid, function, 8, size - 8, true);
    }

    private static boolean bit(int bits, int i) {
        return ((bits >>> i) & 1) == 1;
    }

    // Two columns at a time from the right, up then down, skipping the
    // timing column.
    private static void drawData(int size, boolean[][] grid, boolean[][] function, int[] codewords) {
        int i = 0;
        int right = size - 1;
        while (right >= 1) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < codewords.length * 8) {
                        grid[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) == 1;
                        i++;
                    }
                }
            }
            right -= 2;
        }
    }

    private static void applyMask(int size, boolean[][] grid, boolean[][] function, int mask) {
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (function[y][x]) continue;
            boolean flip;
            switch (mask) {
                case 0: flip = (x + y) % 2 == 0; break;
                case 1: flip = y % 2 == 0; break;
                case 2: flip = x % 3 == 0; break;
                case 3: flip = (x + y) % 3 == 0; break;
                case 4: flip = (x / 3 + y / 2) % 2 == 0; break;
                case 5: flip = x * y % 2 + x * y % 3 == 0; break;
                case 6: flip = (x * y % 2 + x * y % 3) % 2 == 0; break;
                default: flip = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
            }
            if (flip) grid[y][x] = !grid[y][x];
        }
    }

    // The standard's four penalty rules: runs, 2x2 boxes, finder-like
    // patterns and the balance of dark modules.
    private static int penalty(int size, boolean[][] g) {
        int total = 0;
        for (int pass = 0; pass <= 1; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? g[a][b] : g[b][a];
                    boolean prev = pass == 0 ? g[a][b - 1] : g[b - 1][a];
                    if (cur == prev) {
                        run++;
                    } else {
                        if (run >= 5) total += run - 2;
                        run = 1;
                    }
                }
                if (run >= 5) total += run - 2;
            }
        }
        for (int y = 0; y < size - 1; y++) for (int x = 0; x < size - 1; x++) {
            boolean c = g[y][x];
            if (c == g[y][x + 1] && c == g[y + 1][x] && c == g[y + 1][x + 1]) total += 3;
        }
        boolean[] pattern = {true, false, true, true, true, false, true};
        for (int pass = 0; pass <= 1; pass++) for (int a = 0; a < size; a++) for (int b = 0; b <= size - 7; b++) {
            boolean match = true;
            for (int k = 0; k < 7 && match; k++) match = at(g, pass, a, b + k) == pattern[k];
            if (match) {
                boolean before = true;
                for (int k = 1; k <= 4; k++) before &= b - k < 0 || !at(g, pass, a, b - k);
                boolean after = true;
                for (int k = 7; k <= 10; k++) after &= b + k >= size || !at(g, pass, a, b + k);
                if (before || after) total += 40;
            }
        }
        int darkCount = 0;
        for (boolean[] row : g) for (boolean cell : row) if (cell) darkCount++;
        int cells = size * size;
        int k = (Math.abs(darkCount * 20 - cells * 10) + cells - 1) / cells - 1;
        total += Math.max(0, k) * 10;
        return total;
    }

    private static boolean at(boolean[][] g, int pass, int a, int b) {
        return pass == 0 ? g[a][b] : g[b][a];
    }
}
