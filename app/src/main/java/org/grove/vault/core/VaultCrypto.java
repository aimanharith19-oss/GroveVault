package org.grove.vault.core;

import org.grove.vault.core.kdbx.Argon2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Vault file format (".grove").
 *
 * <pre>
 * Version 2 (current) — envelope encryption:
 *   "GROV" | u8 2 | u32 argonMemKiB | u32 argonOps | u8 argonLanes | 32 salt | u8 flags (bit0 = key file required)
 *   | u8 n | n-byte security-key challenge | u8 slotCount
 *   | slots: u8 type (1 = password, 2 = password + security key) | u8 len | label (UTF-8) | 12 iv | u8 len | AES-GCM(KEK, dataKey)
 *   | 12 iv | AES-256-GCM(dataKey, gzip(xml))
 *
 *   base = Argon2id(password, salt)                                  (memory-hard)
 *   base = HMAC-SHA256(base, "grove/keyfile" || SHA-256(key file))   (if a key file is required)
 *   KEK  = HMAC-SHA256(base, "grove/password")                        (type 1)
 *   KEK  = HMAC-SHA256(base, "grove/yubikey" || HMAC-SHA1 response)   (type 2, YubiKey challenge-response)
 *   Each slot's AAD is the fixed header + slot type + label; the payload's AAD is everything before its IV,
 *   so no header byte can be changed without detection. When a security key is required, there is no type-1 slot.
 *
 * Version 1 (read and written for vaults that haven't been upgraded yet):
 *   "GROV" | u8 1 | u8 1 | i32 iterations | 32 salt | 12 iv | AES-256-GCM(PBKDF2-SHA256(password), gzip(xml))
 * </pre>
 */
public final class VaultCrypto {
    private VaultCrypto() {}

    public static final byte[] MAGIC = {'G', 'R', 'O', 'V'};
    public static final int DEFAULT_ITERATIONS = 210000; // v1 only
    public static final int ARGON_MEMORY_KIB = 64 * 1024, ARGON_OPS = 4, ARGON_LANES = 2;
    private static final int MAX_PLAINTEXT = 512 * 1024 * 1024;
    private static final SecureRandom RNG = new SecureRandom();

    public static class WrongPasswordException extends GeneralSecurityException {
        public WrongPasswordException() { super("Wrong password"); }
        public WrongPasswordException(String m) { super(m); }
    }

    /** Thrown when the vault needs a key file or security key that wasn't supplied. */
    public static class FactorRequiredException extends GeneralSecurityException {
        public final boolean keyFile, securityKey;
        public FactorRequiredException(boolean keyFile, boolean securityKey) {
            super(keyFile && securityKey ? "This vault needs its key file and security key" : keyFile ? "This vault needs its key file" : "This vault needs its security key");
            this.keyFile = keyFile;
            this.securityKey = securityKey;
        }
    }

    /** YubiKey-style HMAC-SHA1 challenge-response. */
    public interface Responder {
        byte[] respond(byte[] challenge) throws Exception;
    }

    /** Extra unlock factors. */
    public static class Factors {
        public byte[] keyFile;
        public Responder securityKey;

        public Factors(byte[] keyFile, Responder securityKey) {
            this.keyFile = keyFile;
            this.securityKey = securityKey;
        }
    }

    /** The unlocked data key plus what's needed to save the vault again. */
    public static class Key {
        public final byte[] key;
        public final byte[] salt;
        public final int iterations;
        public final int version;
        /** v2: the header (magic … slots) reused on every save. */
        public final byte[] header;

        public Key(byte[] key, byte[] salt, int iterations) {
            this(key, salt, iterations, 1, null);
        }

        Key(byte[] key, byte[] salt, int iterations, int version, byte[] header) {
            this.key = key;
            this.salt = salt;
            this.iterations = iterations;
            this.version = version;
            this.header = header;
        }

        public void wipe() { Arrays.fill(key, (byte) 0); }

        public boolean isLegacy() { return version == 1; }
    }

    public static class Slot {
        public int type;
        public String label = "";
        byte[] iv, wrapped;
    }

    public static class Header {
        public int version;
        public int iterations, memoryKiB, ops, lanes;
        public byte[] salt;
        public boolean keyFile;
        public byte[] challenge = new byte[0];
        public final List<Slot> slots = new ArrayList<Slot>();
        int fixedLen, headerLen;

        public boolean securityKey() {
            for (Slot s : slots) if (s.type == 2) return true;
            return false;
        }

        public List<String> securityKeyLabels() {
            List<String> l = new ArrayList<String>();
            for (Slot s : slots) if (s.type == 2) l.add(s.label);
            return l;
        }
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean isVaultFile(byte[] file) {
        return file != null && file.length > 4 && file[0] == 'G' && file[1] == 'R' && file[2] == 'O' && file[3] == 'V';
    }

    // ---------------------------------------------------------------- header

    public static Header readHeader(byte[] file) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        byte[] m = new byte[4];
        in.readFully(m);
        if (!Arrays.equals(m, MAGIC)) throw new IOException("Not a Grove vault file");
        Header h = new Header();
        h.version = in.readUnsignedByte();
        if (h.version == 1) {
            if (in.readUnsignedByte() != 1) throw new IOException("Unsupported key derivation");
            h.iterations = in.readInt();
            if (h.iterations < 1000 || h.iterations > 20000000) throw new IOException("Invalid vault parameters");
            h.salt = new byte[32];
            in.readFully(h.salt);
            h.fixedLen = h.headerLen = 4 + 1 + 1 + 4 + 32;
            return h;
        }
        if (h.version != 2) throw new IOException("This vault was made by a newer Grove Vault (format " + h.version + "). Please update the app.");
        h.memoryKiB = in.readInt();
        h.ops = in.readInt();
        h.lanes = in.readUnsignedByte();
        // Refuse absurd parameters (a tampered file could otherwise exhaust memory or hang the app).
        if (h.memoryKiB < 8 * 1024 || h.memoryKiB > 1024 * 1024 || h.ops < 1 || h.ops > 100 || h.lanes < 1 || h.lanes > 16)
            throw new IOException("Invalid vault parameters");
        h.salt = new byte[32];
        in.readFully(h.salt);
        int flags = in.readUnsignedByte();
        h.keyFile = (flags & 1) != 0;
        h.challenge = new byte[in.readUnsignedByte()];
        in.readFully(h.challenge);
        h.fixedLen = file.length - in.available();
        int n = in.readUnsignedByte();
        if (n == 0) throw new IOException("Damaged vault header");
        for (int i = 0; i < n; i++) {
            Slot s = new Slot();
            s.type = in.readUnsignedByte();
            byte[] l = new byte[in.readUnsignedByte()];
            in.readFully(l);
            s.label = new String(l, StandardCharsets.UTF_8);
            s.iv = new byte[12];
            in.readFully(s.iv);
            s.wrapped = new byte[in.readUnsignedByte()];
            in.readFully(s.wrapped);
            h.slots.add(s);
        }
        h.headerLen = file.length - in.available();
        return h;
    }

    // ---------------------------------------------------------------- key derivation

    private static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) m.update(p);
        return m.doFinal();
    }

    private static byte[] base(char[] password, Header h, byte[] keyFile) throws GeneralSecurityException {
        byte[] pw = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] b = Argon2.hash(Argon2.TYPE_ID, pw, h.salt, h.ops, h.memoryKiB, h.lanes, 32, null, null, 0x13);
            if (h.keyFile) {
                byte[] kf = MessageDigest.getInstance("SHA-256").digest(keyFile);
                byte[] nb = hmac(b, "grove/keyfile".getBytes(StandardCharsets.UTF_8), kf);
                Arrays.fill(b, (byte) 0);
                b = nb;
            }
            return b;
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
    }

    private static byte[] slotAad(byte[] fixed, Slot s) {
        byte[] l = s.label.getBytes(StandardCharsets.UTF_8);
        byte[] a = new byte[fixed.length + 1 + l.length];
        System.arraycopy(fixed, 0, a, 0, fixed.length);
        a[fixed.length] = (byte) s.type;
        System.arraycopy(l, 0, a, fixed.length + 1, l.length);
        return a;
    }

    /** Unlocks any vault version. Factors may be null for password-only vaults. */
    public static Key unlock(byte[] file, char[] password, Factors f) throws GeneralSecurityException, IOException {
        Header h = readHeader(file);
        if (h.version == 1) {
            Key k = new Key(pbkdf2(password, h.salt, h.iterations), h.salt, h.iterations, 1, null);
            decrypt(file, k); // verifies the password
            return k;
        }
        boolean needKf = h.keyFile && (f == null || f.keyFile == null);
        boolean needSk = !hasType(h, 1) && (f == null || f.securityKey == null);
        if (needKf || needSk) throw new FactorRequiredException(needKf, needSk);
        byte[] fixed = Arrays.copyOfRange(file, 0, h.fixedLen);
        byte[] b = base(password, h, f == null ? null : f.keyFile);
        try {
            byte[] response = null;
            for (Slot s : h.slots) {
                byte[] kek;
                if (s.type == 1) kek = hmac(b, "grove/password".getBytes(StandardCharsets.UTF_8));
                else if (s.type == 2) {
                    if (f == null || f.securityKey == null) continue;
                    if (response == null) {
                        try {
                            response = f.securityKey.respond(h.challenge);
                        } catch (GeneralSecurityException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new GeneralSecurityException("Security key: " + e.getMessage(), e);
                        }
                    }
                    kek = hmac(b, "grove/yubikey".getBytes(StandardCharsets.UTF_8), response);
                } else continue;
                try {
                    byte[] dk = gcm(Cipher.DECRYPT_MODE, kek, s.iv, slotAad(fixed, s), s.wrapped);
                    Key k = new Key(dk, h.salt, 0, 2, Arrays.copyOfRange(file, 0, h.headerLen));
                    decrypt(file, k);
                    return k;
                } catch (AEADBadTagException ignored) {
                    // try the next slot
                } finally {
                    Arrays.fill(kek, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(b, (byte) 0);
        }
        if (h.securityKey() && !hasType(h, 1))
            throw new WrongPasswordException("Wrong password, key file or security key");
        throw new WrongPasswordException(h.keyFile ? "Wrong password or key file" : "Wrong password");
    }

    private static boolean hasType(Header h, int t) {
        for (Slot s : h.slots) if (s.type == t) return true;
        return false;
    }

    /** Password-only convenience (and for v1 files). */
    public static Key deriveFor(byte[] file, char[] password) throws GeneralSecurityException, IOException {
        return unlock(file, password, null);
    }

    /** Rebuilds a key from a data key unwrapped by biometrics / PIN. */
    public static Key keyFromRaw(byte[] file, byte[] dataKey) throws IOException {
        Header h = readHeader(file);
        if (h.version == 1) return new Key(dataKey, h.salt, h.iterations, 1, null);
        return new Key(dataKey, h.salt, 0, 2, Arrays.copyOfRange(file, 0, h.headerLen));
    }

    /** A new password-only vault key. */
    public static Key newKey(char[] password) throws GeneralSecurityException, IOException {
        return create(password, null, null, null, null);
    }

    /**
     * Creates a fresh data key and header. With security keys, pass the challenge plus one response and label per key;
     * the vault then cannot be opened without one of them.
     */
    public static Key create(char[] password, byte[] keyFile, byte[] challenge, List<byte[]> responses, List<String> labels)
            throws GeneralSecurityException, IOException {
        boolean sk = responses != null && !responses.isEmpty();
        Header h = new Header();
        h.version = 2;
        h.memoryKiB = ARGON_MEMORY_KIB;
        h.ops = ARGON_OPS;
        h.lanes = ARGON_LANES;
        h.salt = random(32);
        h.keyFile = keyFile != null;
        h.challenge = sk ? challenge : new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.write(MAGIC);
        o.writeByte(2);
        o.writeInt(h.memoryKiB);
        o.writeInt(h.ops);
        o.writeByte(h.lanes);
        o.write(h.salt);
        o.writeByte(h.keyFile ? 1 : 0);
        o.writeByte(h.challenge.length);
        o.write(h.challenge);
        o.flush();
        byte[] fixed = bo.toByteArray();
        byte[] dk = random(32);
        byte[] b = base(password, h, keyFile);
        try {
            List<Slot> slots = new ArrayList<Slot>();
            if (!sk) {
                Slot s = new Slot();
                s.type = 1;
                slots.add(s);
            } else {
                for (int i = 0; i < responses.size(); i++) {
                    Slot s = new Slot();
                    s.type = 2;
                    s.label = labels != null && i < labels.size() && labels.get(i) != null ? labels.get(i) : "Security key " + (i + 1);
                    if (s.label.getBytes(StandardCharsets.UTF_8).length > 120) s.label = s.label.substring(0, 60);
                    slots.add(s);
                }
            }
            o.writeByte(slots.size());
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                byte[] kek = s.type == 1 ? hmac(b, "grove/password".getBytes(StandardCharsets.UTF_8))
                        : hmac(b, "grove/yubikey".getBytes(StandardCharsets.UTF_8), responses.get(i));
                s.iv = random(12);
                s.wrapped = gcm(Cipher.ENCRYPT_MODE, kek, s.iv, slotAad(fixed, s), dk);
                Arrays.fill(kek, (byte) 0);
                byte[] l = s.label.getBytes(StandardCharsets.UTF_8);
                o.writeByte(s.type);
                o.writeByte(l.length);
                o.write(l);
                o.write(s.iv);
                o.writeByte(s.wrapped.length);
                o.write(s.wrapped);
            }
            o.flush();
            return new Key(dk, h.salt, 0, 2, bo.toByteArray());
        } finally {
            Arrays.fill(b, (byte) 0);
        }
    }

    // ---------------------------------------------------------------- payload

    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] aad, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad);
        return c.doFinal(data);
    }

    public static byte[] encrypt(byte[] plain, Key key) throws GeneralSecurityException, IOException {
        byte[] header;
        if (key.version == 1) {
            ByteArrayOutputStream hdr = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(hdr);
            out.write(MAGIC);
            out.writeByte(1);
            out.writeByte(1);
            out.writeInt(key.iterations);
            out.write(key.salt);
            out.flush();
            header = hdr.toByteArray();
        } else header = key.header;
        byte[] iv = random(12);
        byte[] aad = new byte[header.length + 12];
        System.arraycopy(header, 0, aad, 0, header.length);
        System.arraycopy(iv, 0, aad, header.length, 12);
        // v1 authenticated the IV as part of the header; v2 keeps the same layout (header || iv) as AAD.
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, key.key, iv, aad, gzip(plain));
        byte[] res = new byte[aad.length + ct.length];
        System.arraycopy(aad, 0, res, 0, aad.length);
        System.arraycopy(ct, 0, res, aad.length, ct.length);
        return res;
    }

    public static byte[] decrypt(byte[] file, Key key) throws GeneralSecurityException, IOException {
        Header h = readHeader(file);
        int hlen = h.headerLen + 12;
        if (file.length < hlen + 16) throw new IOException("Damaged vault file");
        byte[] aad = Arrays.copyOfRange(file, 0, hlen);
        byte[] iv = Arrays.copyOfRange(file, hlen - 12, hlen);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad);
            return gunzip(c.doFinal(file, hlen, file.length - hlen));
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException();
        }
    }

    public static byte[] encryptVault(Vault v, Key key) throws Exception {
        return encrypt(VaultXml.write(v), key);
    }

    public static Vault decryptVault(byte[] file, Key key) throws Exception {
        return VaultXml.read(decrypt(file, key));
    }

    /** True if this file can be decrypted with the given (already unlocked) key: same vault, same data key. */
    public static boolean sameKey(byte[] file, Key key) {
        try {
            Header h = readHeader(file);
            if (!Arrays.equals(h.salt, key.salt)) return false;
            decrypt(file, key);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- small AES-GCM helper for wrapping keys (PIN / biometrics) ----

    public static byte[] seal(byte[] plain, byte[] key) throws GeneralSecurityException {
        byte[] iv = random(12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    public static byte[] open(byte[] sealed, byte[] key) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        try {
            return c.doFinal(sealed, 12, sealed.length - 12);
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException();
        }
    }

    public static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(bo);
        g.write(b);
        g.close();
        return bo.toByteArray();
    }

    public static byte[] gunzip(byte[] b) throws IOException {
        return readLimited(new GZIPInputStream(new ByteArrayInputStream(b)), MAX_PLAINTEXT);
    }

    /** Reads a stream fully but refuses more than max bytes (protects against decompression bombs). */
    public static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) throw new IOException("File is too large or damaged");
        }
        return bo.toByteArray();
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
