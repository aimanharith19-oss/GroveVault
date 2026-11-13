package org.grove.vault.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A WebAuthn passkey (discoverable credential, ES256 / P-256) stored inside a card as a field of type "passkey".
 * Builds the registration (attestation "none") and authentication responses expected by Android Credential Manager.
 */
public class Passkey {
    public String rpId = "";
    public String userName = "";
    public String displayName = "";
    public byte[] credentialId;
    public byte[] userHandle;
    /** PKCS#8 DER EC private key. */
    public byte[] privateKey;
    public long created = System.currentTimeMillis();

    /** Grove Vault's authenticator id (random, constant). */
    public static final byte[] AAGUID = hex("6a1e5c2b9d0f4e7a8c3b1d5f7e9a0b2c");
    private static final int FLAG_UP = 0x01, FLAG_UV = 0x04, FLAG_BE = 0x08, FLAG_BS = 0x10, FLAG_AT = 0x40;

    // ---------------------------------------------------------------- storage

    public String encode() {
        return "rp=" + rpId + "\nuser=" + userName + "\ndisplay=" + displayName
                + "\ncredId=" + b64u(credentialId) + "\nuserHandle=" + b64u(userHandle)
                + "\nkey=" + Base64.getEncoder().encodeToString(privateKey) + "\ncreated=" + created;
    }

    public static Passkey decode(String s) {
        if (s == null) return null;
        Passkey p = new Passkey();
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            try {
                if (k.equals("rp")) p.rpId = v;
                else if (k.equals("user")) p.userName = v;
                else if (k.equals("display")) p.displayName = v;
                else if (k.equals("credId")) p.credentialId = unb64u(v);
                else if (k.equals("userHandle")) p.userHandle = unb64u(v);
                else if (k.equals("key")) p.privateKey = Base64.getMimeDecoder().decode(v);
                else if (k.equals("created")) p.created = Long.parseLong(v);
            } catch (Exception ignored) {}
        }
        if (p.credentialId == null || p.privateKey == null || p.rpId.length() == 0) return null;
        if (p.userHandle == null) p.userHandle = new byte[0];
        return p;
    }

    public Field toField() {
        return new Field("Passkey", Field.PASSKEY, encode());
    }

    /** PEM form used by KeePassXC (KPEX_PASSKEY_PRIVATE_KEY_PEM). */
    public String privateKeyPem() {
        String b = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(privateKey);
        return "-----BEGIN PRIVATE KEY-----\n" + b + "\n-----END PRIVATE KEY-----";
    }

    public static byte[] pemToDer(String pem) {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    // ---------------------------------------------------------------- creation

    public static Passkey generate(String rpId, String userName, String displayName, byte[] userHandle) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        Passkey p = new Passkey();
        p.rpId = rpId;
        p.userName = userName == null ? "" : userName;
        p.displayName = displayName == null ? "" : displayName;
        p.userHandle = userHandle == null ? new byte[0] : userHandle;
        p.credentialId = VaultCrypto.random(16);
        p.privateKey = kp.getPrivate().getEncoded();
        p.publicKeySpki = kp.getPublic().getEncoded();
        p.pub = (ECPublicKey) kp.getPublic();
        return p;
    }

    private transient byte[] publicKeySpki;
    private transient ECPublicKey pub;

    private static byte[] unsigned32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        if (b.length > 32) System.arraycopy(b, b.length - 32, out, 0, 32);
        else System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    private byte[] authData(boolean attested) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        o.write(FLAG_UP | FLAG_UV | FLAG_BE | FLAG_BS | (attested ? FLAG_AT : 0));
        o.write(new byte[4]); // sign count 0 (synced passkeys don't use counters)
        if (attested) {
            o.write(AAGUID);
            o.write(credentialId.length >> 8);
            o.write(credentialId.length & 0xff);
            o.write(credentialId);
            // COSE_Key {1: 2 (EC2), 3: -7 (ES256), -1: 1 (P-256), -2: x, -3: y}
            Cbor c = new Cbor();
            c.mapHeader(5);
            c.integer(1); c.integer(2);
            c.integer(3); c.integer(-7);
            c.integer(-1); c.integer(1);
            c.integer(-2); c.bytes(unsigned32(pub.getW().getAffineX()));
            c.integer(-3); c.bytes(unsigned32(pub.getW().getAffineY()));
            o.write(c.toBytes());
        }
        return o.toByteArray();
    }

    /** Registration response JSON. clientDataJson may be null when the browser supplied only the hash. */
    public String registrationResponse(byte[] clientDataJson) throws Exception {
        byte[] ad = authData(true);
        Cbor att = new Cbor();
        att.mapHeader(3);
        att.text("fmt"); att.text("none");
        att.text("attStmt"); att.mapHeader(0);
        att.text("authData"); att.bytes(ad);
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("clientDataJSON", b64u(clientDataJson == null ? placeholderClientData() : clientDataJson));
        resp.put("attestationObject", b64u(att.toBytes()));
        resp.put("authenticatorData", b64u(ad));
        List<Object> transports = new ArrayList<Object>();
        transports.add("internal");
        transports.add("hybrid");
        resp.put("transports", transports);
        resp.put("publicKeyAlgorithm", Long.valueOf(-7));
        resp.put("publicKey", b64u(publicKeySpki));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", b64u(credentialId));
        out.put("rawId", b64u(credentialId));
        out.put("type", "public-key");
        out.put("authenticatorAttachment", "platform");
        out.put("response", resp);
        Map<String, Object> ext = new LinkedHashMap<String, Object>();
        Map<String, Object> credProps = new LinkedHashMap<String, Object>();
        credProps.put("rk", Boolean.TRUE);
        ext.put("credProps", credProps);
        out.put("clientExtensionResults", ext);
        return Json.write(out);
    }

    /** Authentication (assertion) response JSON, signing authenticatorData || clientDataHash. */
    public String assertionResponse(byte[] clientDataJson, byte[] clientDataHash) throws Exception {
        byte[] ad = authData(false);
        if (clientDataHash == null) clientDataHash = sha256(clientDataJson);
        PrivateKey k = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateKey));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k);
        s.update(ad);
        s.update(clientDataHash);
        byte[] sig = s.sign();
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("clientDataJSON", b64u(clientDataJson == null ? placeholderClientData() : clientDataJson));
        resp.put("authenticatorData", b64u(ad));
        resp.put("signature", b64u(sig));
        resp.put("userHandle", b64u(userHandle));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", b64u(credentialId));
        out.put("rawId", b64u(credentialId));
        out.put("type", "public-key");
        out.put("authenticatorAttachment", "platform");
        out.put("response", resp);
        out.put("clientExtensionResults", new LinkedHashMap<String, Object>());
        return Json.write(out);
    }

    /** When a browser computes the client data itself, it replaces this value. */
    private static byte[] placeholderClientData() {
        return "{}".getBytes(StandardCharsets.UTF_8);
    }

    /** clientDataJSON for calls from native apps (origin "android:apk-key-hash:..."). */
    public static byte[] clientData(String type, String challengeB64u, String origin, String packageName) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("challenge", challengeB64u);
        m.put("origin", origin);
        if (packageName != null) m.put("androidPackageName", packageName);
        return Json.write(m).getBytes(StandardCharsets.UTF_8);
    }

    /** True if the web origin (https://host[:port]) belongs to rpId (same host or a subdomain). */
    public static boolean originMatchesRp(String origin, String rpId) {
        if (origin == null || rpId == null || rpId.length() == 0) return false;
        String o = origin.toLowerCase(Locale.ROOT);
        if (!o.startsWith("https://")) return false;
        String host = o.substring(8);
        int end = 0;
        while (end < host.length() && host.charAt(end) != '/' && host.charAt(end) != ':') end++;
        host = host.substring(0, end);
        String rp = rpId.toLowerCase(Locale.ROOT);
        return host.equals(rp) || host.endsWith("." + rp);
    }

    // ---------------------------------------------------------------- helpers

    public static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b == null ? new byte[0] : b);
    }

    public static byte[] unb64u(String s) {
        String t = s.trim().replace('+', '-').replace('/', '_').replace("=", "");
        return Base64.getUrlDecoder().decode(t);
    }

    public static byte[] sha256(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** Minimal CBOR encoder (major types 0,1,2,3,5) for WebAuthn structures. */
    static final class Cbor {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        private void head(int major, long v) {
            int m = major << 5;
            if (v < 24) o.write(m | (int) v);
            else if (v < 256) { o.write(m | 24); o.write((int) v); }
            else if (v < 65536) { o.write(m | 25); o.write((int) (v >> 8)); o.write((int) v & 0xff); }
            else { o.write(m | 26); for (int s = 24; s >= 0; s -= 8) o.write((int) (v >> s) & 0xff); }
        }

        void integer(long v) {
            if (v >= 0) head(0, v);
            else head(1, -1 - v);
        }

        void bytes(byte[] b) {
            head(2, b.length);
            o.write(b, 0, b.length);
        }

        void text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            head(3, b.length);
            o.write(b, 0, b.length);
        }

        void mapHeader(int n) {
            head(5, n);
        }

        byte[] toBytes() {
            return o.toByteArray();
        }
    }
}
