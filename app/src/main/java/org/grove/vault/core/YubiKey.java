package org.grove.vault.core;

import java.io.IOException;
import java.util.Arrays;

/**
 * YubiKey (and compatible keys) HMAC-SHA1 challenge-response, slot 1 or 2, as used by KeePassXC / KeePassDX.
 * Works over NFC (ISO 7816 APDUs to the OTP applet) and USB (8-byte HID feature reports).
 * The 32-byte challenge is padded to 64 bytes PKCS#7-style so keys programmed with or without "variable length"
 * give a stable answer on every transport.
 */
public final class YubiKey {
    private YubiKey() {}

    public static final int SLOT_1 = 0x30, SLOT_2 = 0x38;
    private static final byte[] OTP_AID = {(byte) 0xA0, 0x00, 0x00, 0x05, 0x27, 0x20, 0x01, 0x01};

    public static byte[] pad(byte[] challenge) {
        if (challenge.length >= 64) return Arrays.copyOf(challenge, 64);
        byte[] p = Arrays.copyOf(challenge, 64);
        int padByte = 64 - challenge.length;
        for (int i = challenge.length; i < 64; i++) p[i] = (byte) padByte;
        return p;
    }

    // ---------------------------------------------------------------- NFC

    public interface Apdu {
        byte[] transceive(byte[] command) throws IOException;
    }

    public static byte[] selectCommand() {
        byte[] c = new byte[5 + OTP_AID.length];
        c[0] = 0x00; c[1] = (byte) 0xA4; c[2] = 0x04; c[3] = 0x00; c[4] = (byte) OTP_AID.length;
        System.arraycopy(OTP_AID, 0, c, 5, OTP_AID.length);
        return c;
    }

    public static byte[] challengeCommand(int slot, byte[] challenge) {
        byte[] p = pad(challenge);
        byte[] c = new byte[5 + 64];
        c[0] = 0x00; c[1] = 0x01; c[2] = (byte) slot; c[3] = 0x00; c[4] = 64;
        System.arraycopy(p, 0, c, 5, 64);
        return c;
    }

    private static void checkSw(byte[] r, String what) throws IOException {
        if (r == null || r.length < 2) throw new IOException(what + ": no answer");
        int sw = ((r[r.length - 2] & 0xff) << 8) | (r[r.length - 1] & 0xff);
        if (sw != 0x9000) {
            if (sw == 0x6A82) throw new IOException("This key has no OTP application");
            if (sw == 0x6985 || sw == 0x6A80 || sw == 0x6B00)
                throw new IOException("Challenge-response isn't set up in slot 2 of this key (program it with YubiKey Manager: Applications → OTP → Long touch (Slot 2) → Configure → Challenge-response)");
            throw new IOException(what + " failed (" + Integer.toHexString(sw) + ")");
        }
    }

    public static byte[] respondNfc(Apdu card, int slot, byte[] challenge) throws IOException {
        checkSw(card.transceive(selectCommand()), "Select");
        byte[] r = card.transceive(challengeCommand(slot, challenge));
        checkSw(r, "Challenge");
        if (r.length < 22) throw new IOException("Short answer from the key");
        return Arrays.copyOf(r, 20);
    }

    // ---------------------------------------------------------------- USB HID

    public interface FeatureReports {
        void set(byte[] report8) throws IOException;
        byte[] get() throws IOException;
        void sleep(int ms);
    }

    private static final int SLOT_WRITE_FLAG = 0x80, RESP_PENDING_FLAG = 0x40, RESP_TIMEOUT_WAIT_FLAG = 0x20;
    private static final int SEQUENCE_MASK = 0x1f, DUMMY_REPORT_WRITE = 0x8f;

    public static int crc16(byte[] b, int off, int len) {
        int crc = 0xffff;
        for (int i = off; i < off + len; i++) {
            crc ^= b[i] & 0xff;
            for (int j = 0; j < 8; j++) {
                int lsb = crc & 1;
                crc >>>= 1;
                if (lsb != 0) crc ^= 0x8408;
            }
        }
        return crc & 0xffff;
    }

    /** 70-byte frame: 64 payload, slot, crc16 (LE), 3 filler. */
    public static byte[] frame(int slot, byte[] payload64) {
        byte[] f = new byte[70];
        System.arraycopy(payload64, 0, f, 0, 64);
        f[64] = (byte) slot;
        int crc = crc16(payload64, 0, 64);
        f[65] = (byte) crc;
        f[66] = (byte) (crc >>> 8);
        return f;
    }

    private static byte[] waitFor(FeatureReports d, int mask, boolean set, int timeoutMs, Runnable touchHint) throws IOException {
        int waited = 0;
        boolean hinted = false;
        while (waited < timeoutMs) {
            byte[] r = d.get();
            if (set ? (r[7] & mask) == mask : (r[7] & mask) == 0) return r;
            if (!hinted && (r[7] & RESP_TIMEOUT_WAIT_FLAG) != 0 && touchHint != null) {
                hinted = true;
                touchHint.run();
            }
            d.sleep(20);
            waited += 20;
        }
        throw new IOException(set ? "No answer from the key (touch it if it's blinking)" : "The key is busy");
    }

    public static byte[] respondUsb(FeatureReports d, int slot, byte[] challenge, Runnable touchHint) throws IOException {
        byte[] f = frame(slot, pad(challenge));
        // write the frame in 7-byte chunks; all-zero middle chunks are skipped
        for (int seq = 0, off = 0; off < f.length; seq++, off += 7) {
            boolean zero = true;
            for (int i = 0; i < 7; i++) if (f[off + i] != 0) zero = false;
            if (zero && seq > 0 && off + 7 < f.length) continue;
            byte[] rep = new byte[8];
            System.arraycopy(f, off, rep, 0, 7);
            rep[7] = (byte) (SLOT_WRITE_FLAG | seq);
            waitFor(d, SLOT_WRITE_FLAG, false, 1500, null);
            d.set(rep);
        }
        // read the response (20-byte HMAC + 2-byte CRC), allowing up to 15 s for a touch
        // the report that signals "response pending" already carries the first 7 bytes
        byte[] first = waitFor(d, RESP_PENDING_FLAG, true, 15000, touchHint);
        byte[] out = new byte[28];
        System.arraycopy(first, 0, out, 0, 7);
        int got = 7;
        while (got < 22) {
            byte[] r = d.get();
            if ((r[7] & RESP_PENDING_FLAG) == 0) break;
            int seq = r[7] & SEQUENCE_MASK;
            if (seq == 0 && got > 0) break;
            System.arraycopy(r, 0, out, got, 7);
            got += 7;
        }
        byte[] reset = new byte[8];
        reset[7] = (byte) DUMMY_REPORT_WRITE;
        d.set(reset);
        if (got < 22) throw new IOException("Incomplete answer from the key");
        if (crc16(out, 0, 22) != 0xf0b8) throw new IOException("Corrupted answer from the key");
        return Arrays.copyOf(out, 20);
    }
}
