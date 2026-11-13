import org.grove.vault.core.*;
import org.grove.vault.core.kdbx.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class CoreTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static byte[] fill(int n, int v) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) v);
        return b;
    }

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        String out = a[1];
        // Argon2 RFC 9106 vectors
        byte[] P = fill(32, 1), S = fill(16, 2), K = fill(8, 3), X = fill(12, 4);
        check(hex(Argon2.hash(Argon2.TYPE_D, P, S, 3, 32, 4, 32, K, X, 0x13)).equals("512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb"), "Argon2d RFC vector");
        check(hex(Argon2.hash(Argon2.TYPE_I, P, S, 3, 32, 4, 32, K, X, 0x13)).equals("c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8"), "Argon2i RFC vector");
        check(hex(Argon2.hash(Argon2.TYPE_ID, P, S, 3, 32, 4, 32, K, X, 0x13)).equals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"), "Argon2id RFC vector");

        // TOTP RFC 6238 vector
        Totp t = Totp.parse("otpauth://totp/x?secret=" + Totp.toBase32("12345678901234567890".getBytes()) + "&digits=8");
        check(t.code(59000L).equals("94287082"), "TOTP RFC 6238 SHA1 t=59");
        check(t.code(1111111109000L).equals("07081804"), "TOTP RFC 6238 SHA1 t=1111111109");

        // vault round trip
        Vault v = Templates.newVault();
        Card c = v.templates().get(0).instantiate();
        c.title = "Example <&> \"quoted\"";
        c.fields.get(0).value = "alice@example.com";
        c.fields.get(1).update("p4ss\nword ünïcode 🔑");
        c.fields.get(1).update("second");
        c.notes = "line1\nline2";
        c.labelIds.add(v.labels.get(0).id);
        c.attachments.add(new Attachment("a.bin", null, new byte[]{1, 2, 3, 0, (byte) 255}));
        c.apps.add("com.example.app");
        c.favorite = true;
        v.cards.add(c);
        VaultCrypto.Key key = VaultCrypto.newKey("master pw".toCharArray());
        long t0 = System.currentTimeMillis();
        byte[] enc = VaultCrypto.encryptVault(v, key);
        Vault v2 = VaultCrypto.decryptVault(enc, VaultCrypto.deriveFor(enc, "master pw".toCharArray()));
        System.out.println("  vault encrypt+derive ms: " + (System.currentTimeMillis() - t0) + " size " + enc.length);
        Card c2 = v2.findCard(c.id);
        check(c2 != null && c2.title.equals(c.title) && c2.notes.equals(c.notes) && c2.favorite, "vault round trip basic");
        check(c2.fields.get(1).value.equals("second") && c2.fields.get(1).history.get(0).value.equals("p4ss\nword ünïcode 🔑"), "vault round trip history/newlines/unicode");
        check(Arrays.equals(c2.attachments.get(0).data, c.attachments.get(0).data) && c2.apps.contains("com.example.app"), "vault attachments/apps");
        boolean wrong = false;
        try { VaultCrypto.decryptVault(enc, VaultCrypto.deriveFor(enc, "nope".toCharArray())); } catch (VaultCrypto.WrongPasswordException e) { wrong = true; }
        check(wrong, "wrong password rejected");

        // generator + strength
        PasswordTools.Options o = new PasswordTools.Options();
        String g = PasswordTools.generate(o);
        check(g.length() == 16 && PasswordTools.score(g) >= 3, "generator " + g + " score " + PasswordTools.score(g));
        o.passphrase = true;
        System.out.println("  passphrase: " + PasswordTools.generate(o));
        check(PasswordTools.isWeak("password1") && PasswordTools.isWeak("123456") && !PasswordTools.isWeak("Tr0ub4dor&3xQ!zz"), "strength");
        check(Audit.parseExpiry("12/25") > 0 && Audit.parseExpiry("2030-01-01") > 0 && Audit.parseExpiry("junk") < 0, "expiry parse");

        // CSV
        Vault cv = new Vault();
        int n = Csv.importInto(cv, "name,url,username,password,note\nGitHub,https://github.com,al,\"p,w\"\"x\",\"multi\nline\"\n");
        check(n == 1 && cv.cards.get(0).password().equals("p,w\"x") && cv.cards.get(0).notes.equals("multi\nline"), "CSV import");
        Vault cv2 = new Vault();
        check(Csv.importInto(cv2, Csv.export(v2)) == 1 && cv2.cards.get(0).password().equals("second"), "CSV export/import round trip");

        // KeePass import of pykeepass test corpus
        String[] keys = {null, "test3.key", "test4.key", "test4_hex.key", "test4_keyx.keyx"};
        String[] pws = {"password", null, ""};
        for (File f : new File(dir).listFiles()) {
            if (!f.getName().endsWith(".kdbx")) continue;
            byte[] data = Files.readAllBytes(f.toPath());
            String result = null;
            outer:
            for (String pw : pws) for (String k : keys) {
                try {
                    byte[] kf = k == null ? null : Files.readAllBytes(new File(dir, k).toPath());
                    Kdbx.Database db = Kdbx.read(data, pw, kf);
                    Vault iv = new Vault();
                    KeePassMapper.Result r = KeePassMapper.importInto(iv, db, true);
                    result = "entries=" + r.imported + " pw=" + pw + " key=" + k + " labels=" + iv.labels.size();
                    break outer;
                } catch (Exception e) {
                    if (!(e instanceof VaultCrypto.WrongPasswordException)) {
                        result = "ERR " + e;
                        if (e.getMessage() != null && e.getMessage().contains("Twofish")) break outer;
                    }
                }
            }
            System.out.println("  " + f.getName() + ": " + result);
        }

        // KeePass export (all variants) for pykeepass to verify
        for (int kdf = 0; kdf < 3; kdf++) for (int cc = 0; cc < 2; cc++) {
            Kdbx.Params p = new Kdbx.Params();
            p.kdf = kdf;
            p.chacha20 = cc == 1;
            p.argonMemoryKiB = 8 * 1024;
            p.aesRounds = 10000;
            long s = System.currentTimeMillis();
            byte[] kd = Kdbx.write(KeePassMapper.export(v2, true), "export pw", null, p);
            Files.write(new File(out, "grove_kdf" + kdf + "_c" + cc + ".kdbx").toPath(), kd);
            Vault back = new Vault();
            KeePassMapper.importInto(back, Kdbx.read(kd, "export pw", null), true);
            Card bc = null;
            for (Card x : back.cards) if (x.title.equals(c.title)) bc = x;
            check(bc != null && bc.password().equals("second") && bc.notes.equals(c.notes) && bc.favorite && bc.attachments.size() == 1
                    && bc.apps.contains("com.example.app"), "KDBX self round trip kdf=" + kdf + " chacha=" + cc + " (" + (System.currentTimeMillis() - s) + "ms)");
        }
        // with key file
        byte[] kfile = Files.readAllBytes(new File(dir, "test4.key").toPath());
        byte[] kd = Kdbx.write(KeePassMapper.export(v2, false), "export pw", kfile, null);
        Files.write(new File(out, "grove_keyfile.kdbx").toPath(), kd);
        check(Kdbx.read(kd, "export pw", kfile) != null, "KDBX with key file");
        // SafeInCloud self round trip (cross-tested separately against SafeInCloud's own cipher)
        byte[] sic = SafeInCloud.encrypt(SafeInCloud.export(v2, true), "sic pw ü");
        Vault sv = new Vault();
        SafeInCloud.importInto(sv, SafeInCloud.decrypt(sic, "sic pw ü"));
        Card sc = null;
        for (Card x : sv.cards) if (x.title.equals(c.title)) sc = x;
        check(SafeInCloud.isDb(sic) && sc != null && sc.password().equals("second") && sc.first(Field.PASSWORD).history.size() == 1
                && sc.favorite && sc.apps.contains("com.example.app") && sc.attachments.size() == 1, "SafeInCloud .db round trip");
        // Passkey: registration + assertion signature verifies with the registered public key
        Passkey pk = Passkey.generate("example.com", "alice", "Alice", new byte[]{9, 9});
        java.util.Map<String, Object> reg = Json.obj(Json.parse(pk.registrationResponse(Passkey.clientData("webauthn.create", "Y2hhbA", "https://example.com", null))));
        byte[] spki = Passkey.unb64u(Json.str(Json.obj(reg.get("response")), "publicKey"));
        byte[] cd = Passkey.clientData("webauthn.get", "Y2hhbDI", "https://example.com", null);
        java.util.Map<String, Object> as = Json.obj(Json.obj(Json.parse(Passkey.decode(pk.encode()).assertionResponse(cd, null))).get("response"));
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
        sig.initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(spki)));
        sig.update(Passkey.unb64u(Json.str(as, "authenticatorData")));
        sig.update(Passkey.sha256(cd));
        check(sig.verify(Passkey.unb64u(Json.str(as, "signature"))), "passkey assertion signature verifies");
        check(Passkey.originMatchesRp("https://m.example.com", "example.com") && !Passkey.originMatchesRp("https://example.com.evil.io", "example.com"), "passkey origin check");
        // ---- vault format v2: Argon2id envelope, key file, security keys ----
        final byte[] secretA = "yubikey-secret-A-0123".getBytes(), secretB = "yubikey-secret-B-4567".getBytes();
        byte[] chal = VaultCrypto.random(32);
        byte[] kf2 = "my key file contents".getBytes();
        java.util.List<byte[]> resp = new java.util.ArrayList<byte[]>();
        resp.add(simHmac(secretA, YubiKey.pad(chal)));
        resp.add(simHmac(secretB, YubiKey.pad(chal)));
        long tk = System.currentTimeMillis();
        VaultCrypto.Key k2 = VaultCrypto.create("pw2".toCharArray(), kf2, chal, resp, java.util.Arrays.asList("Key A", "Key B"));
        byte[] f2 = VaultCrypto.encryptVault(v2, k2);
        System.out.println("  v2 create (Argon2id 64 MiB) ms: " + (System.currentTimeMillis() - tk));
        VaultCrypto.Header h2 = VaultCrypto.readHeader(f2);
        check(h2.version == 2 && h2.keyFile && h2.securityKey() && h2.securityKeyLabels().equals(java.util.Arrays.asList("Key A", "Key B")), "v2 header");
        for (final byte[] sec : new byte[][]{secretA, secretB}) {
            VaultCrypto.Responder usb = new VaultCrypto.Responder() { public byte[] respond(byte[] c) throws Exception { return YubiKey.respondUsb(new SimUsb(sec), YubiKey.SLOT_2, c, null); } };
            VaultCrypto.Key u = VaultCrypto.unlock(f2, "pw2".toCharArray(), new VaultCrypto.Factors(kf2, usb));
            check(VaultCrypto.decryptVault(f2, u).findCard(c.id) != null && java.util.Arrays.equals(u.key, k2.key), "v2 unlock with security key over simulated USB");
        }
        VaultCrypto.Responder nfcA = new VaultCrypto.Responder() { public byte[] respond(byte[] c) throws Exception { return YubiKey.respondNfc(new SimNfc(secretA), YubiKey.SLOT_2, c); } };
        check(VaultCrypto.unlock(f2, "pw2".toCharArray(), new VaultCrypto.Factors(kf2, nfcA)) != null, "v2 unlock with security key over simulated NFC");
        check(failsWith(f2, "pw2", kf2, simResp("other secret".getBytes()), VaultCrypto.WrongPasswordException.class), "unknown security key rejected");
        check(failsWith(f2, "pw2", "wrong file".getBytes(), simResp(secretA), VaultCrypto.WrongPasswordException.class), "wrong key file rejected");
        check(failsWith(f2, "nope", kf2, simResp(secretA), VaultCrypto.WrongPasswordException.class), "wrong password rejected (v2)");
        check(failsWith(f2, "pw2", null, simResp(secretA), VaultCrypto.FactorRequiredException.class)
                && failsWith(f2, "pw2", kf2, null, VaultCrypto.FactorRequiredException.class), "missing factor detected");
        byte[] tampered = f2.clone();
        tampered[9] ^= 1; // argon ops
        check(failsAny(tampered, "pw2", kf2, simResp(secretA)), "tampered header rejected");
        byte[] tampered2 = f2.clone();
        tampered2[tampered2.length - 5] ^= 1;
        check(failsAny(tampered2, "pw2", kf2, simResp(secretA)), "tampered data rejected");
        byte[] bomb = f2.clone();
        bomb[5] = 0x7f; // absurd Argon2 memory
        check(failsAny(bomb, "pw2", kf2, simResp(secretA)), "absurd KDF parameters refused");
        // password-only v2 and legacy v1 still open
        VaultCrypto.Key kp = VaultCrypto.newKey("simple".toCharArray());
        byte[] fp = VaultCrypto.encryptVault(v2, kp);
        check(VaultCrypto.readHeader(fp).version == 2 && VaultCrypto.deriveFor(fp, "simple".toCharArray()) != null, "password-only v2");
        VaultCrypto.Key k1 = new VaultCrypto.Key(VaultCrypto.pbkdf2("legacy".toCharArray(), new byte[32], 210000), new byte[32], 210000);
        byte[] f1 = VaultCrypto.encryptVault(v2, k1);
        check(VaultCrypto.readHeader(f1).version == 1 && VaultCrypto.deriveFor(f1, "legacy".toCharArray()).isLegacy(), "legacy v1 vault still opens");
        check(VaultCrypto.keyFromRaw(f2, k2.key).header.length > 0 && VaultCrypto.sameKey(f2, VaultCrypto.keyFromRaw(f2, k2.key)), "quick-unlock key rebuild");
        check(YubiKey.crc16(new byte[]{0x31,0x32,0x33,0x34,0x35,0x36,0x37,0x38,0x39}, 0, 9) == 0x6F91, "YubiKey CRC-16 (ISO 13239) check value");
        System.out.println(fails == 0 ? "ALL PASSED" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }

    static byte[] simHmac(byte[] secret, byte[] msg) throws Exception {
        javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA1");
        m.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA1"));
        return m.doFinal(msg);
    }

    static VaultCrypto.Responder simResp(final byte[] secret) {
        return new VaultCrypto.Responder() { public byte[] respond(byte[] c) throws Exception { return simHmac(secret, YubiKey.pad(c)); } };
    }

    static boolean failsWith(byte[] f, String pw, byte[] kf, VaultCrypto.Responder r, Class<?> type) {
        try { VaultCrypto.unlock(f, pw.toCharArray(), new VaultCrypto.Factors(kf, r)); return false; } catch (Exception e) { return type.isInstance(e); }
    }

    static boolean failsAny(byte[] f, String pw, byte[] kf, VaultCrypto.Responder r) {
        try { VaultCrypto.decryptVault(f, VaultCrypto.unlock(f, pw.toCharArray(), new VaultCrypto.Factors(kf, r))); return false; } catch (Exception e) { return true; }
    }

    /** Simulates a YubiKey's OTP interface over HID feature reports. */
    static class SimUsb implements YubiKey.FeatureReports {
        final byte[] secret; final byte[] frame = new byte[70]; byte[] resp; int readSeq = 0; boolean pending;
        SimUsb(byte[] s) { secret = s; }
        public void set(byte[] r) throws java.io.IOException {
            int flags = r[7] & 0xff;
            if (flags == 0x8f) { pending = false; readSeq = 0; return; }
            int seq = flags & 0x1f;
            System.arraycopy(r, 0, frame, seq * 7, 7);
            if (seq == 9) {
                int crc = YubiKey.crc16(frame, 0, 64);
                if ((frame[65] & 0xff | (frame[66] & 0xff) << 8) != crc || (frame[64] & 0xff) != 0x38) throw new java.io.IOException("bad frame");
                try { byte[] h = simHmac(secret, java.util.Arrays.copyOf(frame, 64));
                    resp = new byte[28]; System.arraycopy(h, 0, resp, 0, 20);
                    int c = ~YubiKey.crc16(h, 0, 20) & 0xffff; resp[20] = (byte) c; resp[21] = (byte) (c >>> 8);
                } catch (Exception e) { throw new java.io.IOException(e); }
                pending = true; readSeq = 0;
                java.util.Arrays.fill(frame, (byte) 0);
            }
        }
        public byte[] get() {
            byte[] r = new byte[8];
            if (pending) {
                System.arraycopy(resp, readSeq * 7, r, 0, 7);
                r[7] = (byte) (0x40 | readSeq);
                readSeq = (readSeq + 1) % 4;
            }
            return r;
        }
        public void sleep(int ms) {}
    }

    static class SimNfc implements YubiKey.Apdu {
        final byte[] secret; boolean selected;
        SimNfc(byte[] s) { secret = s; }
        public byte[] transceive(byte[] c) throws java.io.IOException {
            if (c[1] == (byte) 0xA4) { selected = true; return new byte[]{0x05, 0x07, 0x00, (byte) 0x90, 0x00}; }
            if (!selected || c[1] != 0x01 || c[2] != 0x38 || c[4] != 64) return new byte[]{0x6A, (byte) 0x80};
            try { byte[] h = simHmac(secret, java.util.Arrays.copyOfRange(c, 5, 69)); byte[] r = java.util.Arrays.copyOf(h, 22); r[20] = (byte) 0x90; r[21] = 0; return r; }
            catch (Exception e) { throw new java.io.IOException(e); }
        }
    }
}
