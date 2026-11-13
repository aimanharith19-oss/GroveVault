package org.grove.vault.core.kdbx;

import org.grove.vault.core.VaultCrypto;
import org.grove.vault.core.VaultXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * KeePass database reader (KDBX 3.1 and 4.x) and writer (KDBX 4.0).
 * Ciphers: AES-256, ChaCha20. KDFs: AES-KDF, Argon2d, Argon2id. Optional key file.
 */
public final class Kdbx {
    private Kdbx() {}

    // ------------------------------------------------------------------ model

    public static class Entry {
        public String uuid = Base64.getEncoder().encodeToString(uuidBytes(UUID.randomUUID()));
        public final Map<String, String> strings = new LinkedHashMap<String, String>();
        public final Set<String> protectedKeys = new LinkedHashSet<String>();
        public final Map<String, byte[]> binaries = new LinkedHashMap<String, byte[]>();
        public String tags = "";
        public long created = System.currentTimeMillis();
        public long modified = created;
        public long expires = -1;
        public int iconId = 0;

        public String get(String key) {
            String s = strings.get(key);
            return s == null ? "" : s;
        }
    }

    public static class Group {
        public String uuid = Base64.getEncoder().encodeToString(uuidBytes(UUID.randomUUID()));
        public String name = "";
        public final List<Group> groups = new ArrayList<Group>();
        public final List<Entry> entries = new ArrayList<Entry>();
        public boolean recycleBin;
    }

    public static class Database {
        public String name = "Grove Vault export";
        public Group root = new Group();
        public String recycleBinUuid = "";
    }

    /** Export settings. */
    public static class Params {
        public boolean chacha20;
        /** 0 = Argon2d, 1 = Argon2id, 2 = AES-KDF */
        public int kdf = 0;
        public int argonMemoryKiB = 32 * 1024;
        public int argonIterations = 3;
        public int argonParallelism = 2;
        public long aesRounds = 600000;
    }

    public static class FormatException extends IOException {
        public FormatException(String m) { super(m); }
    }

    // ------------------------------------------------------------------ constants

    private static final int SIG1 = 0x9AA2D903, SIG2 = 0xB54BFB67;
    private static final byte[] CIPHER_AES = hex("31c1f2e6bf714350be5805216afc5aff");
    private static final byte[] CIPHER_CHACHA = hex("d6038a2b8b6f4cb5a524339a31dbb59a");
    private static final byte[] KDF_AES = hex("c9d9f39a628a4460bf740d08c18a4fea");
    private static final byte[] KDF_ARGON2D = hex("ef636ddf8c29444b91f7a9a403e30a0c");
    private static final byte[] KDF_ARGON2ID = hex("9e298b1956db4773b23dfc3ec6f0a1e6");
    private static final byte[] SALSA_NONCE = hex("e830094b97205d2a");
    private static final long EPOCH_OFFSET = 62135596800L; // seconds between 0001-01-01 and 1970-01-01

    // ------------------------------------------------------------------ keys

    /** Composite key: SHA256(SHA256(password) || keyfileKey). */
    public static byte[] compositeKey(String password, byte[] keyFile) throws GeneralSecurityException, IOException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        if (password != null) md.update(sha256(password.getBytes(StandardCharsets.UTF_8)));
        if (keyFile != null) md.update(keyFileKey(keyFile));
        return md.digest();
    }

    /** Key file formats: XML v1/v2, 32 raw bytes, 64 hex chars, otherwise SHA-256 of the whole file. */
    static byte[] keyFileKey(byte[] f) throws GeneralSecurityException {
        String s = new String(f, StandardCharsets.UTF_8).trim();
        if (s.startsWith("<?xml") || s.startsWith("<KeyFile")) {
            try {
                Document d = VaultXml.builder().parse(new ByteArrayInputStream(f));
                Element data = (Element) d.getElementsByTagName("Data").item(0);
                NodeList ver = d.getElementsByTagName("Version");
                String version = ver.getLength() > 0 ? ver.item(0).getTextContent().trim() : "1.0";
                String text = data.getTextContent().replaceAll("\\s", "");
                if (version.startsWith("2")) return hex(text);
                return Base64.getDecoder().decode(text);
            } catch (Exception ignored) {
                // fall through to hashing
            }
        }
        if (f.length == 32) return f;
        if (f.length == 64 && s.matches("[0-9a-fA-F]{64}")) return hex(s);
        return sha256(f);
    }

    // ------------------------------------------------------------------ reading

    public static Database read(byte[] file, String password, byte[] keyFile) throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (file.length < 12 || bb.getInt() != SIG1) throw new FormatException("Not a KeePass file");
        int sig2 = bb.getInt();
        if (sig2 == 0xB54BFB65) throw new FormatException("KeePass 1.x (.kdb) files are not supported. Open it in KeePass 2 and save as .kdbx first.");
        if (sig2 != SIG2) throw new FormatException("Not a KeePass 2 file");
        int version = bb.getInt();
        int major = version >>> 16;
        if (major < 3 || major > 4) throw new FormatException("Unsupported KDBX version " + major);
        boolean v4 = major == 4;

        byte[] cipherId = null, masterSeed = null, iv = null, transformSeed = null, protectedStreamKey = null, streamStart = null;
        long transformRounds = 0;
        int compression = 0, innerId = 0;
        Map<String, Object> kdfParams = null;
        while (true) {
            int id = bb.get() & 0xff;
            int size = v4 ? bb.getInt() : (bb.getShort() & 0xffff);
            byte[] data = new byte[size];
            bb.get(data);
            if (id == 0) break;
            switch (id) {
                case 2: cipherId = data; break;
                case 3: compression = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(); break;
                case 4: masterSeed = data; break;
                case 5: transformSeed = data; break;
                case 6: transformRounds = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong(); break;
                case 7: iv = data; break;
                case 8: protectedStreamKey = data; break;
                case 9: streamStart = data; break;
                case 10: innerId = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(); break;
                case 11: kdfParams = readVariantDict(data); break;
                default: break;
            }
        }
        int headerEnd = bb.position();
        byte[] composite = compositeKey(password, keyFile);
        byte[] transformed;
        if (v4) {
            if (kdfParams == null) throw new FormatException("Missing KDF parameters");
            transformed = runKdf(kdfParams, composite);
        } else {
            if (transformRounds < 1 || transformRounds > 2000000000L) throw new FormatException("Unsupported key transformation rounds");
            transformed = aesKdf(composite, transformSeed, transformRounds);
        }
        byte[] cipherKey = sha256(concat(masterSeed, transformed));

        byte[] payload;
        if (v4) {
            byte[] headerHash = Arrays.copyOfRange(file, headerEnd, headerEnd + 32);
            if (!MessageDigest.isEqual(headerHash, sha256(Arrays.copyOfRange(file, 0, headerEnd))))
                throw new FormatException("File header is corrupted");
            byte[] hmacBase = sha512(concat(masterSeed, transformed, new byte[]{1}));
            byte[] headerHmac = Arrays.copyOfRange(file, headerEnd + 32, headerEnd + 64);
            if (!MessageDigest.isEqual(headerHmac, hmac(blockKey(hmacBase, -1L), Arrays.copyOfRange(file, 0, headerEnd))))
                throw new VaultCrypto.WrongPasswordException();
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            bb.position(headerEnd + 64);
            long index = 0;
            while (true) {
                byte[] mac = new byte[32];
                bb.get(mac);
                int size = bb.getInt();
                byte[] data = new byte[size];
                bb.get(data);
                ByteBuffer msg = ByteBuffer.allocate(12 + size).order(ByteOrder.LITTLE_ENDIAN);
                msg.putLong(index).putInt(size).put(data);
                if (!MessageDigest.isEqual(mac, hmac(blockKey(hmacBase, index), msg.array())))
                    throw new FormatException("Data block " + index + " is corrupted");
                if (size == 0) break;
                enc.write(data);
                index++;
            }
            payload = decrypt(cipherId, cipherKey, iv, enc.toByteArray());
            if (compression == 1) payload = VaultCrypto.gunzip(payload);
        } else {
            byte[] dec;
            try {
                dec = decrypt(cipherId, cipherKey, iv, Arrays.copyOfRange(file, headerEnd, file.length));
            } catch (GeneralSecurityException e) {
                throw new VaultCrypto.WrongPasswordException();
            }
            if (!Arrays.equals(Arrays.copyOfRange(dec, 0, 32), streamStart)) throw new VaultCrypto.WrongPasswordException();
            ByteBuffer hb = ByteBuffer.wrap(dec, 32, dec.length - 32).order(ByteOrder.LITTLE_ENDIAN);
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            while (true) {
                hb.getInt();
                byte[] hash = new byte[32];
                hb.get(hash);
                int size = hb.getInt();
                if (size == 0) break;
                byte[] data = new byte[size];
                hb.get(data);
                if (!MessageDigest.isEqual(hash, sha256(data))) throw new FormatException("Data block is corrupted");
                plain.write(data);
            }
            payload = plain.toByteArray();
            if (compression == 1) payload = VaultCrypto.gunzip(payload);
        }

        List<byte[]> innerBinaries = new ArrayList<byte[]>();
        byte[] xml;
        StreamCiphers.KeyStream inner;
        if (v4) {
            ByteBuffer ib = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            byte[] innerKey = null;
            while (true) {
                int id = ib.get() & 0xff;
                int size = ib.getInt();
                byte[] data = new byte[size];
                ib.get(data);
                if (id == 0) break;
                if (id == 1) innerId = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
                else if (id == 2) innerKey = data;
                else if (id == 3) innerBinaries.add(Arrays.copyOfRange(data, 1, data.length));
            }
            xml = Arrays.copyOfRange(payload, ib.position(), payload.length);
            inner = innerStream(innerId, innerKey);
        } else {
            xml = payload;
            inner = innerStream(innerId, protectedStreamKey);
        }
        return parseXml(xml, inner, innerBinaries, v4);
    }

    private static StreamCiphers.KeyStream innerStream(int id, byte[] key) throws GeneralSecurityException, FormatException {
        if (id == 3) {
            byte[] h = sha512(key);
            return new StreamCiphers.ChaCha20(Arrays.copyOfRange(h, 0, 32), Arrays.copyOfRange(h, 32, 44));
        } else if (id == 2) {
            return new StreamCiphers.Salsa20(sha256(key), SALSA_NONCE);
        } else if (id == 0) {
            return null;
        }
        throw new FormatException("Unsupported inner stream cipher " + id);
    }

    private static byte[] runKdf(Map<String, Object> p, byte[] composite) throws Exception {
        byte[] uuid = (byte[]) p.get("$UUID");
        if (Arrays.equals(uuid, KDF_AES)) {
            long rounds = (Long) p.get("R");
            if (rounds < 1 || rounds > 2000000000L) throw new FormatException("Unsupported AES-KDF rounds");
            return aesKdf(composite, (byte[]) p.get("S"), rounds);
        }
        boolean d = Arrays.equals(uuid, KDF_ARGON2D);
        boolean id = Arrays.equals(uuid, KDF_ARGON2ID);
        if (!d && !id) throw new FormatException("Unsupported key derivation function");
        byte[] salt = (byte[]) p.get("S");
        long mem = ((Number) p.get("M")).longValue();
        long it = ((Number) p.get("I")).longValue();
        int par = ((Number) p.get("P")).intValue();
        int ver = p.containsKey("V") ? ((Number) p.get("V")).intValue() : 0x13;
        if (mem / 1024 > 1024L * 1024 || mem < 8 * 1024) throw new FormatException("Argon2 memory setting is too large for this device");
        if (it < 1 || it > 10000 || par < 1 || par > 64) throw new FormatException("Unsupported Argon2 settings");
        return Argon2.hash(d ? Argon2.TYPE_D : Argon2.TYPE_ID, composite, salt, (int) it, (int) (mem / 1024), par, 32,
                (byte[]) p.get("K"), (byte[]) p.get("A"), ver);
    }

    private static byte[] aesKdf(byte[] key, byte[] seed, long rounds) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(seed, "AES"));
        byte[] a = key.clone(), b = new byte[32];
        for (long i = 0; i < rounds; i++) {
            c.update(a, 0, 32, b, 0);
            byte[] t = a;
            a = b;
            b = t;
        }
        return sha256(a);
    }

    private static byte[] decrypt(byte[] cipherId, byte[] key, byte[] iv, byte[] data) throws GeneralSecurityException, FormatException {
        if (Arrays.equals(cipherId, CIPHER_AES)) {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } else if (Arrays.equals(cipherId, CIPHER_CHACHA)) {
            byte[] out = data.clone();
            new StreamCiphers.ChaCha20(key, iv).xor(out, 0, out.length);
            return out;
        }
        throw new FormatException("This database uses an encryption algorithm Grove Vault does not support (Twofish?). Change it to AES or ChaCha20 in KeePass first.");
    }

    // ------------------------------------------------------------------ XML

    private static Element child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    private static String text(Element e, String tag) {
        Element c = child(e, tag);
        return c == null ? "" : c.getTextContent();
    }

    private static Database parseXml(byte[] xml, StreamCiphers.KeyStream inner, List<byte[]> innerBinaries, boolean v4) throws Exception {
        Document d = VaultXml.builder().parse(new ByteArrayInputStream(xml));
        // Decrypt protected values in document order (required: the keystream is sequential).
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if ("True".equalsIgnoreCase(e.getAttribute("Protected")) && inner != null) {
                byte[] b = Base64.getDecoder().decode(e.getTextContent().trim());
                inner.xor(b, 0, b.length);
                boolean isString = e.getTagName().equals("Value") && e.getParentNode() instanceof Element
                        && ((Element) e.getParentNode()).getTagName().equals("String");
                e.setTextContent(isString ? new String(b, StandardCharsets.UTF_8) : Base64.getEncoder().encodeToString(b));
                e.removeAttribute("Protected");
                e.setAttribute("WasProtected", "True");
            }
        }
        Element file = d.getDocumentElement();
        Element meta = child(file, "Meta");
        Database db = new Database();
        List<byte[]> binaries = new ArrayList<byte[]>(innerBinaries);
        if (meta != null) {
            db.name = text(meta, "DatabaseName");
            db.recycleBinUuid = "True".equalsIgnoreCase(text(meta, "RecycleBinEnabled")) || text(meta, "RecycleBinEnabled").length() == 0
                    ? text(meta, "RecycleBinUUID") : "";
            Element bins = child(meta, "Binaries");
            if (bins != null && !v4) {
                Map<Integer, byte[]> byId = new LinkedHashMap<Integer, byte[]>();
                for (Node n = bins.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element)) continue;
                    Element b = (Element) n;
                    byte[] data = Base64.getMimeDecoder().decode(b.getTextContent().trim());
                    if ("True".equalsIgnoreCase(b.getAttribute("Compressed")) && data.length > 0) data = VaultCrypto.gunzip(data);
                    byId.put(Integer.parseInt(b.getAttribute("ID")), data);
                }
                for (int i = 0; i < byId.size(); i++) binaries.add(byId.get(i));
            }
        }
        Element root = child(file, "Root");
        Element g = root == null ? null : child(root, "Group");
        if (g != null) db.root = parseGroup(g, binaries, v4, db.recycleBinUuid);
        return db;
    }

    private static Group parseGroup(Element e, List<byte[]> binaries, boolean v4, String recycleUuid) {
        Group g = new Group();
        g.uuid = text(e, "UUID");
        g.name = text(e, "Name");
        g.recycleBin = recycleUuid.length() > 0 && recycleUuid.equals(g.uuid);
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element c = (Element) n;
            if (c.getTagName().equals("Group")) g.groups.add(parseGroup(c, binaries, v4, recycleUuid));
            else if (c.getTagName().equals("Entry")) g.entries.add(parseEntry(c, binaries, v4));
        }
        return g;
    }

    private static Entry parseEntry(Element e, List<byte[]> binaries, boolean v4) {
        Entry en = new Entry();
        en.uuid = text(e, "UUID");
        en.tags = text(e, "Tags");
        try { en.iconId = Integer.parseInt(text(e, "IconID")); } catch (Exception ignored) {}
        Element times = child(e, "Times");
        if (times != null) {
            en.created = parseTime(text(times, "CreationTime"), v4);
            en.modified = parseTime(text(times, "LastModificationTime"), v4);
            if ("True".equalsIgnoreCase(text(times, "Expires"))) en.expires = parseTime(text(times, "ExpiryTime"), v4);
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element c = (Element) n;
            if (c.getTagName().equals("String")) {
                String key = text(c, "Key");
                Element val = child(c, "Value");
                en.strings.put(key, val == null ? "" : val.getTextContent());
                if (val != null && "True".equalsIgnoreCase(val.getAttribute("WasProtected"))) en.protectedKeys.add(key);
            } else if (c.getTagName().equals("Binary")) {
                String key = text(c, "Key");
                Element val = child(c, "Value");
                if (val == null) continue;
                if (val.hasAttribute("Ref")) {
                    int ref = Integer.parseInt(val.getAttribute("Ref"));
                    if (ref >= 0 && ref < binaries.size() && binaries.get(ref) != null) en.binaries.put(key, binaries.get(ref));
                } else {
                    en.binaries.put(key, Base64.getMimeDecoder().decode(val.getTextContent().trim()));
                }
            }
        }
        return en;
    }

    private static long parseTime(String s, boolean v4) {
        s = s.trim();
        if (s.length() == 0) return System.currentTimeMillis();
        try {
            if (!s.contains("-") && !s.contains(":")) {
                byte[] b = Base64.getDecoder().decode(s);
                long secs = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
                return (secs - EPOCH_OFFSET) * 1000L;
            }
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, Math.min(19, s.length()))).getTime();
        } catch (Exception ex) {
            return System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------ writing

    public static byte[] write(Database db, String password, byte[] keyFile, Params p) throws Exception {
        if (p == null) p = new Params();
        byte[] masterSeed = VaultCrypto.random(32);
        byte[] iv = VaultCrypto.random(p.chacha20 ? 12 : 16);
        byte[] innerKey = VaultCrypto.random(64);

        Map<String, Object> kdf = new LinkedHashMap<String, Object>();
        if (p.kdf == 2) {
            kdf.put("$UUID", KDF_AES);
            kdf.put("R", Long.valueOf(p.aesRounds));
            kdf.put("S", VaultCrypto.random(32));
        } else {
            kdf.put("$UUID", p.kdf == 1 ? KDF_ARGON2ID : KDF_ARGON2D);
            kdf.put("S", VaultCrypto.random(32));
            kdf.put("P", Integer.valueOf(p.argonParallelism));
            kdf.put("M", Long.valueOf(p.argonMemoryKiB * 1024L));
            kdf.put("I", Long.valueOf(p.argonIterations));
            kdf.put("V", Integer.valueOf(0x13));
        }

        ByteArrayOutputStream h = new ByteArrayOutputStream();
        writeInt(h, SIG1);
        writeInt(h, SIG2);
        writeInt(h, 0x00040000);
        headerField(h, 2, p.chacha20 ? CIPHER_CHACHA : CIPHER_AES);
        headerField(h, 3, le32(1));
        headerField(h, 4, masterSeed);
        headerField(h, 7, iv);
        headerField(h, 11, writeVariantDict(kdf));
        headerField(h, 0, new byte[]{13, 10, 13, 10});
        byte[] header = h.toByteArray();

        byte[] transformed = runKdf(kdf, compositeKey(password, keyFile));
        byte[] cipherKey = sha256(concat(masterSeed, transformed));
        byte[] hmacBase = sha512(concat(masterSeed, transformed, new byte[]{1}));

        // inner header + XML
        List<byte[]> binaries = new ArrayList<byte[]>();
        byte[] h2 = sha512(innerKey);
        StreamCiphers.KeyStream inner = new StreamCiphers.ChaCha20(Arrays.copyOfRange(h2, 0, 32), Arrays.copyOfRange(h2, 32, 44));
        byte[] xml = buildXml(db, inner, binaries);
        ByteArrayOutputStream ih = new ByteArrayOutputStream();
        innerField(ih, 1, le32(3));
        innerField(ih, 2, innerKey);
        for (byte[] b : binaries) innerField(ih, 3, concat(new byte[]{0}, b));
        innerField(ih, 0, new byte[0]);
        ih.write(xml);
        byte[] payload = VaultCrypto.gzip(ih.toByteArray());

        byte[] enc;
        if (p.chacha20) {
            enc = payload.clone();
            new StreamCiphers.ChaCha20(cipherKey, iv).xor(enc, 0, enc.length);
        } else {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));
            enc = c.doFinal(payload);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(sha256(header));
        out.write(hmac(blockKey(hmacBase, -1L), header));
        int blockSize = 1024 * 1024;
        long index = 0;
        for (int off = 0; ; off += blockSize) {
            int size = Math.max(0, Math.min(blockSize, enc.length - off));
            byte[] data = Arrays.copyOfRange(enc, Math.min(off, enc.length), Math.min(off, enc.length) + size);
            ByteBuffer msg = ByteBuffer.allocate(12 + size).order(ByteOrder.LITTLE_ENDIAN);
            msg.putLong(index).putInt(size).put(data);
            out.write(hmac(blockKey(hmacBase, index), msg.array()));
            out.write(le32(size));
            out.write(data);
            index++;
            if (size == 0) break;
        }
        return out.toByteArray();
    }

    private static byte[] buildXml(Database db, StreamCiphers.KeyStream inner, List<byte[]> binaries) throws Exception {
        Document d = VaultXml.builder().newDocument();
        d.setXmlStandalone(true);
        Element file = d.createElement("KeePassFile");
        d.appendChild(file);
        Element meta = add(d, file, "Meta", null);
        add(d, meta, "Generator", "Grove Vault");
        add(d, meta, "DatabaseName", db.name);
        add(d, meta, "DatabaseNameChanged", time(System.currentTimeMillis()));
        add(d, meta, "DefaultUserName", "");
        add(d, meta, "MaintenanceHistoryDays", "365");
        Element mp = add(d, meta, "MemoryProtection", null);
        add(d, mp, "ProtectTitle", "False");
        add(d, mp, "ProtectUserName", "False");
        add(d, mp, "ProtectPassword", "True");
        add(d, mp, "ProtectURL", "False");
        add(d, mp, "ProtectNotes", "False");
        add(d, meta, "RecycleBinEnabled", "True");
        add(d, meta, "RecycleBinUUID", db.recycleBinUuid.length() > 0 ? db.recycleBinUuid : Base64.getEncoder().encodeToString(new byte[16]));
        add(d, meta, "HistoryMaxItems", "10");
        add(d, meta, "HistoryMaxSize", "6291456");
        Element root = add(d, file, "Root", null);
        writeGroup(d, root, db.root, inner, binaries);
        add(d, root, "DeletedObjects", null);
        return VaultXml.toBytes(d, true);
    }

    private static void writeGroup(Document d, Element parent, Group g, StreamCiphers.KeyStream inner, List<byte[]> binaries) {
        Element e = add(d, parent, "Group", null);
        add(d, e, "UUID", g.uuid);
        add(d, e, "Name", g.name);
        add(d, e, "Notes", "");
        add(d, e, "IconID", g.recycleBin ? "43" : "48");
        writeTimes(d, e, System.currentTimeMillis(), System.currentTimeMillis(), -1);
        add(d, e, "IsExpanded", "True");
        for (Entry en : g.entries) {
            Element x = add(d, e, "Entry", null);
            add(d, x, "UUID", en.uuid);
            add(d, x, "IconID", String.valueOf(en.iconId));
            add(d, x, "Tags", en.tags);
            writeTimes(d, x, en.created, en.modified, en.expires);
            for (Map.Entry<String, String> s : en.strings.entrySet()) {
                Element se = add(d, x, "String", null);
                add(d, se, "Key", s.getKey());
                Element v = d.createElement("Value");
                if (en.protectedKeys.contains(s.getKey())) {
                    byte[] b = s.getValue().getBytes(StandardCharsets.UTF_8);
                    inner.xor(b, 0, b.length);
                    v.setAttribute("Protected", "True");
                    v.setTextContent(Base64.getEncoder().encodeToString(b));
                } else v.setTextContent(s.getValue());
                se.appendChild(v);
            }
            for (Map.Entry<String, byte[]> b : en.binaries.entrySet()) {
                Element be = add(d, x, "Binary", null);
                add(d, be, "Key", b.getKey());
                Element v = d.createElement("Value");
                v.setAttribute("Ref", String.valueOf(binaries.size()));
                binaries.add(b.getValue());
                be.appendChild(v);
            }
        }
        for (Group c : g.groups) writeGroup(d, e, c, inner, binaries);
    }

    private static void writeTimes(Document d, Element parent, long created, long modified, long expires) {
        Element t = add(d, parent, "Times", null);
        add(d, t, "CreationTime", time(created));
        add(d, t, "LastModificationTime", time(modified));
        add(d, t, "LastAccessTime", time(modified));
        add(d, t, "ExpiryTime", time(expires > 0 ? expires : modified));
        add(d, t, "Expires", expires > 0 ? "True" : "False");
        add(d, t, "UsageCount", "0");
        add(d, t, "LocationChanged", time(modified));
    }

    private static String time(long millis) {
        long secs = millis / 1000L + EPOCH_OFFSET;
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(secs).array());
    }

    private static Element add(Document d, Element parent, String tag, String text) {
        Element e = d.createElement(tag);
        if (text != null) e.setTextContent(text);
        parent.appendChild(e);
        return e;
    }

    // ------------------------------------------------------------------ variant dictionary

    static Map<String, Object> readVariantDict(byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        bb.getShort();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        while (bb.hasRemaining()) {
            int type = bb.get() & 0xff;
            if (type == 0) break;
            byte[] k = new byte[bb.getInt()];
            bb.get(k);
            byte[] v = new byte[bb.getInt()];
            bb.get(v);
            ByteBuffer vb = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
            Object val;
            switch (type) {
                case 0x04: val = Long.valueOf(vb.getInt() & 0xffffffffL); break;
                case 0x05: val = Long.valueOf(vb.getLong()); break;
                case 0x08: val = Boolean.valueOf(v[0] != 0); break;
                case 0x0C: val = Integer.valueOf(vb.getInt()); break;
                case 0x0D: val = Long.valueOf(vb.getLong()); break;
                case 0x18: val = new String(v, StandardCharsets.UTF_8); break;
                default: val = v; break;
            }
            m.put(new String(k, StandardCharsets.UTF_8), val);
        }
        return m;
    }

    static byte[] writeVariantDict(Map<String, Object> m) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x00);
        o.write(0x01);
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            byte[] vb;
            int type;
            if (v instanceof Integer) { type = 0x04; vb = le32((Integer) v); }
            else if (v instanceof Long) { type = 0x05; vb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong((Long) v).array(); }
            else if (v instanceof Boolean) { type = 0x08; vb = new byte[]{(byte) (((Boolean) v) ? 1 : 0)}; }
            else if (v instanceof String) { type = 0x18; vb = ((String) v).getBytes(StandardCharsets.UTF_8); }
            else { type = 0x42; vb = (byte[]) v; }
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            o.write(type);
            o.write(le32(k.length));
            o.write(k);
            o.write(le32(vb.length));
            o.write(vb);
        }
        o.write(0);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    private static void headerField(ByteArrayOutputStream o, int id, byte[] data) throws IOException {
        o.write(id);
        o.write(le32(data.length));
        o.write(data);
    }

    private static void innerField(ByteArrayOutputStream o, int id, byte[] data) throws IOException {
        headerField(o, id, data);
    }

    private static void writeInt(ByteArrayOutputStream o, int x) throws IOException {
        o.write(le32(x));
    }

    private static byte[] le32(int x) {
        return new byte[]{(byte) x, (byte) (x >>> 8), (byte) (x >>> 16), (byte) (x >>> 24)};
    }

    private static byte[] blockKey(byte[] base, long index) throws GeneralSecurityException {
        byte[] idx = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(index).array();
        return sha512(concat(idx, base));
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    static byte[] sha256(byte[] b) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    static byte[] sha512(byte[] b) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-512").digest(b);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static byte[] uuidBytes(UUID u) {
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }

    /** True if the bytes look like a KeePass database. */
    public static boolean isKdbx(byte[] f) {
        return f != null && f.length > 8 && ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).getInt() == SIG1;
    }
}
