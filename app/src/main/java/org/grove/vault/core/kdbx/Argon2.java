package org.grove.vault.core.kdbx;

/** Argon2 v1.3 (RFC 9106), types d / i / id. Single-threaded reference-style implementation. */
public final class Argon2 {
    public static final int TYPE_D = 0, TYPE_I = 1, TYPE_ID = 2;
    private static final int BLOCK_WORDS = 128;
    private static final int SYNC_POINTS = 4;

    private Argon2() {}

    public static byte[] hash(int type, byte[] password, byte[] salt, int iterations, int memoryKiB, int lanes,
                              int tagLen, byte[] secret, byte[] ad, int version) {
        if (secret == null) secret = new byte[0];
        if (ad == null) ad = new byte[0];
        Blake2b h0b = new Blake2b(64);
        h0b.updateInt(lanes);
        h0b.updateInt(tagLen);
        h0b.updateInt(memoryKiB);
        h0b.updateInt(iterations);
        h0b.updateInt(version);
        h0b.updateInt(type);
        h0b.updateInt(password.length);
        h0b.update(password);
        h0b.updateInt(salt.length);
        h0b.update(salt);
        h0b.updateInt(secret.length);
        h0b.update(secret);
        h0b.updateInt(ad.length);
        h0b.update(ad);
        byte[] h0 = h0b.digest();

        int mBlocks = Math.max(memoryKiB, 8 * lanes);
        int segLen = mBlocks / (lanes * SYNC_POINTS);
        int laneLen = segLen * SYNC_POINTS;
        int total = laneLen * lanes;
        long[][] mem = new long[total][];

        byte[] in = new byte[72];
        System.arraycopy(h0, 0, in, 0, 64);
        for (int l = 0; l < lanes; l++) {
            for (int j = 0; j < 2; j++) {
                putInt(in, 64, j);
                putInt(in, 68, l);
                mem[l * laneLen + j] = toWords(hPrime(1024, in));
            }
        }
        for (int i = 0; i < total; i++) if (mem[i] == null) mem[i] = new long[BLOCK_WORDS];

        long[] zero = new long[BLOCK_WORDS];
        long[] inputBlock = new long[BLOCK_WORDS];
        long[] addressBlock = new long[BLOCK_WORDS];
        long[] tmp = new long[BLOCK_WORDS];
        long[] r = new long[BLOCK_WORDS];

        for (int pass = 0; pass < iterations; pass++) {
            for (int slice = 0; slice < SYNC_POINTS; slice++) {
                for (int lane = 0; lane < lanes; lane++) {
                    boolean indep = type == TYPE_I || (type == TYPE_ID && pass == 0 && slice < SYNC_POINTS / 2);
                    if (indep) {
                        java.util.Arrays.fill(inputBlock, 0);
                        inputBlock[0] = pass;
                        inputBlock[1] = lane;
                        inputBlock[2] = slice;
                        inputBlock[3] = total;
                        inputBlock[4] = iterations;
                        inputBlock[5] = type;
                    }
                    int start = 0;
                    if (pass == 0 && slice == 0) {
                        start = 2;
                        if (indep) nextAddresses(addressBlock, inputBlock, zero, tmp, r);
                    }
                    int curr = lane * laneLen + slice * segLen + start;
                    int prev = (curr % laneLen == 0) ? curr + laneLen - 1 : curr - 1;
                    for (int i = start; i < segLen; i++, curr++, prev++) {
                        if (curr % laneLen == 1) prev = curr - 1;
                        long rand;
                        if (indep) {
                            if (i % BLOCK_WORDS == 0) nextAddresses(addressBlock, inputBlock, zero, tmp, r);
                            rand = addressBlock[i % BLOCK_WORDS];
                        } else rand = mem[prev][0];
                        int refLane = (int) ((rand >>> 32) % lanes);
                        if (pass == 0 && slice == 0) refLane = lane;
                        boolean sameLane = refLane == lane;
                        long area;
                        if (pass == 0) {
                            if (slice == 0) area = i - 1;
                            else if (sameLane) area = (long) slice * segLen + i - 1;
                            else area = (long) slice * segLen + (i == 0 ? -1 : 0);
                        } else {
                            if (sameLane) area = laneLen - segLen + i - 1;
                            else area = laneLen - segLen + (i == 0 ? -1 : 0);
                        }
                        long rel = rand & 0xffffffffL;
                        rel = (rel * rel) >>> 32;
                        rel = area - 1 - ((area * rel) >>> 32);
                        long startPos = 0;
                        if (pass != 0) startPos = (slice == SYNC_POINTS - 1) ? 0 : (long) (slice + 1) * segLen;
                        int refIndex = (int) ((startPos + rel) % laneLen);
                        long[] ref = mem[refLane * laneLen + refIndex];
                        fill(mem[prev], ref, mem[curr], pass != 0 && version != 0x10, tmp, r);
                    }
                }
            }
        }
        long[] c = mem[laneLen - 1].clone();
        for (int l = 1; l < lanes; l++) {
            long[] last = mem[l * laneLen + laneLen - 1];
            for (int k = 0; k < BLOCK_WORDS; k++) c[k] ^= last[k];
        }
        byte[] cb = new byte[1024];
        for (int k = 0; k < BLOCK_WORDS; k++) for (int b = 0; b < 8; b++) cb[k * 8 + b] = (byte) (c[k] >>> (8 * b));
        return hPrime(tagLen, cb);
    }

    private static void nextAddresses(long[] address, long[] input, long[] zero, long[] tmp, long[] r) {
        input[6]++;
        fill(zero, input, address, false, tmp, r);
        fill(zero, address, address, false, tmp, r);
    }

    private static void fill(long[] prev, long[] ref, long[] next, boolean withXor, long[] tmp, long[] r) {
        for (int i = 0; i < BLOCK_WORDS; i++) r[i] = prev[i] ^ ref[i];
        System.arraycopy(r, 0, tmp, 0, BLOCK_WORDS);
        if (withXor) for (int i = 0; i < BLOCK_WORDS; i++) tmp[i] ^= next[i];
        for (int i = 0; i < 8; i++) {
            int b = 16 * i;
            p(r, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15);
        }
        for (int i = 0; i < 8; i++) {
            int b = 2 * i;
            p(r, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113);
        }
        for (int i = 0; i < BLOCK_WORDS; i++) next[i] = tmp[i] ^ r[i];
    }

    private static void p(long[] v, int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7,
                          int a8, int a9, int a10, int a11, int a12, int a13, int a14, int a15) {
        gb(v, a0, a4, a8, a12);
        gb(v, a1, a5, a9, a13);
        gb(v, a2, a6, a10, a14);
        gb(v, a3, a7, a11, a15);
        gb(v, a0, a5, a10, a15);
        gb(v, a1, a6, a11, a12);
        gb(v, a2, a7, a8, a13);
        gb(v, a3, a4, a9, a14);
    }

    private static long fbla(long x, long y) {
        return x + y + 2 * ((x & 0xffffffffL) * (y & 0xffffffffL));
    }

    private static void gb(long[] v, int a, int b, int c, int d) {
        v[a] = fbla(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = fbla(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = fbla(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = fbla(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    /** Variable-length hash H' from the Argon2 spec. */
    static byte[] hPrime(int outLen, byte[] in) {
        byte[] len = new byte[4];
        putInt(len, 0, outLen);
        if (outLen <= 64) return Blake2b.hash(outLen, len, in);
        byte[] out = new byte[outLen];
        byte[] v = Blake2b.hash(64, len, in);
        System.arraycopy(v, 0, out, 0, 32);
        int pos = 32;
        int rr = (outLen + 31) / 32 - 2;
        for (int i = 2; i <= rr; i++) {
            v = Blake2b.hash(64, v);
            System.arraycopy(v, 0, out, pos, 32);
            pos += 32;
        }
        v = Blake2b.hash(outLen - 32 * rr, v);
        System.arraycopy(v, 0, out, pos, v.length);
        return out;
    }

    private static long[] toWords(byte[] b) {
        long[] w = new long[BLOCK_WORDS];
        for (int i = 0; i < BLOCK_WORDS; i++) w[i] = Blake2b.le64(b, i * 8);
        return w;
    }

    private static void putInt(byte[] b, int o, int x) {
        b[o] = (byte) x;
        b[o + 1] = (byte) (x >>> 8);
        b[o + 2] = (byte) (x >>> 16);
        b[o + 3] = (byte) (x >>> 24);
    }
}
