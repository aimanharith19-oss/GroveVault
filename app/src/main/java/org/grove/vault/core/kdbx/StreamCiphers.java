package org.grove.vault.core.kdbx;

/** ChaCha20 (RFC 8439, 96-bit nonce) and Salsa20 (64-bit nonce) keystreams, as used by KeePass. */
final class StreamCiphers {
    private StreamCiphers() {}

    interface KeyStream {
        /** XORs the next bytes of keystream into data in place. */
        void xor(byte[] data, int off, int len);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    abstract static class Base implements KeyStream {
        final int[] state = new int[16];
        final int[] x = new int[16];
        final byte[] block = new byte[64];
        int pos = 64;

        abstract void core();

        abstract void increment();

        public void xor(byte[] data, int off, int len) {
            for (int i = 0; i < len; i++) {
                if (pos == 64) {
                    core();
                    for (int k = 0; k < 16; k++) {
                        int w = x[k] + state[k];
                        block[k * 4] = (byte) w;
                        block[k * 4 + 1] = (byte) (w >>> 8);
                        block[k * 4 + 2] = (byte) (w >>> 16);
                        block[k * 4 + 3] = (byte) (w >>> 24);
                    }
                    increment();
                    pos = 0;
                }
                data[off + i] ^= block[pos++];
            }
        }
    }

    static final class ChaCha20 extends Base {
        ChaCha20(byte[] key, byte[] nonce12) {
            state[0] = 0x61707865;
            state[1] = 0x3320646e;
            state[2] = 0x79622d32;
            state[3] = 0x6b206574;
            for (int i = 0; i < 8; i++) state[4 + i] = le32(key, i * 4);
            state[12] = 0;
            state[13] = le32(nonce12, 0);
            state[14] = le32(nonce12, 4);
            state[15] = le32(nonce12, 8);
        }

        void increment() {
            state[12]++;
        }

        private void qr(int a, int b, int c, int d) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
        }

        void core() {
            System.arraycopy(state, 0, x, 0, 16);
            for (int i = 0; i < 10; i++) {
                qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15);
                qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14);
            }
        }
    }

    static final class Salsa20 extends Base {
        Salsa20(byte[] key, byte[] nonce8) {
            state[0] = 0x61707865;
            state[5] = 0x3320646e;
            state[10] = 0x79622d32;
            state[15] = 0x6b206574;
            for (int i = 0; i < 4; i++) state[1 + i] = le32(key, i * 4);
            for (int i = 0; i < 4; i++) state[11 + i] = le32(key, 16 + i * 4);
            state[6] = le32(nonce8, 0);
            state[7] = le32(nonce8, 4);
            state[8] = 0;
            state[9] = 0;
        }

        void increment() {
            if (++state[8] == 0) state[9]++;
        }

        void core() {
            System.arraycopy(state, 0, x, 0, 16);
            for (int i = 0; i < 10; i++) {
                x[4] ^= Integer.rotateLeft(x[0] + x[12], 7);  x[8] ^= Integer.rotateLeft(x[4] + x[0], 9);
                x[12] ^= Integer.rotateLeft(x[8] + x[4], 13); x[0] ^= Integer.rotateLeft(x[12] + x[8], 18);
                x[9] ^= Integer.rotateLeft(x[5] + x[1], 7);   x[13] ^= Integer.rotateLeft(x[9] + x[5], 9);
                x[1] ^= Integer.rotateLeft(x[13] + x[9], 13); x[5] ^= Integer.rotateLeft(x[1] + x[13], 18);
                x[14] ^= Integer.rotateLeft(x[10] + x[6], 7); x[2] ^= Integer.rotateLeft(x[14] + x[10], 9);
                x[6] ^= Integer.rotateLeft(x[2] + x[14], 13); x[10] ^= Integer.rotateLeft(x[6] + x[2], 18);
                x[3] ^= Integer.rotateLeft(x[15] + x[11], 7); x[7] ^= Integer.rotateLeft(x[3] + x[15], 9);
                x[11] ^= Integer.rotateLeft(x[7] + x[3], 13); x[15] ^= Integer.rotateLeft(x[11] + x[7], 18);
                x[1] ^= Integer.rotateLeft(x[0] + x[3], 7);   x[2] ^= Integer.rotateLeft(x[1] + x[0], 9);
                x[3] ^= Integer.rotateLeft(x[2] + x[1], 13);  x[0] ^= Integer.rotateLeft(x[3] + x[2], 18);
                x[6] ^= Integer.rotateLeft(x[5] + x[4], 7);   x[7] ^= Integer.rotateLeft(x[6] + x[5], 9);
                x[4] ^= Integer.rotateLeft(x[7] + x[6], 13);  x[5] ^= Integer.rotateLeft(x[4] + x[7], 18);
                x[11] ^= Integer.rotateLeft(x[10] + x[9], 7); x[8] ^= Integer.rotateLeft(x[11] + x[10], 9);
                x[9] ^= Integer.rotateLeft(x[8] + x[11], 13); x[10] ^= Integer.rotateLeft(x[9] + x[8], 18);
                x[12] ^= Integer.rotateLeft(x[15] + x[14], 7); x[13] ^= Integer.rotateLeft(x[12] + x[15], 9);
                x[14] ^= Integer.rotateLeft(x[13] + x[12], 13); x[15] ^= Integer.rotateLeft(x[14] + x[13], 18);
            }
        }
    }
}
