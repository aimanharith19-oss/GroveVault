package org.grove.vault.core;

import org.grove.vault.core.kdbx.KeePassMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reads and writes SafeInCloud databases (SafeInCloud.db, encrypted) and SafeInCloud XML exports, for moving data
 * between SafeInCloud and Grove Vault. Implemented from the file layout; no SafeInCloud code is used.
 *
 * <pre>
 * .db layout:  u16 magic 0x0505 | u8 version (1 = password chars as bytes, 2 = UTF-8)
 *              | [u8 len] salt                      key  = PBKDF2-HMAC-SHA1(password, salt, 10000, 32)
 *              | [u8 len] iv
 *              | [u8 len] salt2
 *              | [u8 len] AES-256-CBC(key, iv, [len] innerIv [len] innerKey [len] PBKDF2-HMAC-SHA1(innerKey, salt2, 1000, 32))
 *              | AES-256-CBC(innerKey, innerIv, zlib(XML))
 * </pre>
 */
public final class SafeInCloud {
    private SafeInCloud() {}

    private static final int MAGIC = 0x0505;
    private static final int USER_ROUNDS = 10000;
    private static final int CHECK_ROUNDS = 1000;

    // ------------------------------------------------------------------ detection

    /** True for an encrypted SafeInCloud .db file. */
    public static boolean isDb(byte[] f) {
        return f != null && f.length > 40 && (f[0] & 0xff) == 0x05 && (f[1] & 0xff) == 0x05;
    }

    /** True for an unencrypted SafeInCloud XML export. */
    public static boolean isXml(byte[] f) {
        if (f == null || f.length < 10) return false;
        String head = new String(f, 0, Math.min(f.length, 400), StandardCharsets.UTF_8).trim();
        if (head.startsWith("﻿")) head = head.substring(1);
        return (head.startsWith("<?xml") || head.startsWith("<database")) && head.contains("<database");
    }

    // ------------------------------------------------------------------ crypto

    private static byte[] passwordBytes(String password, int version) {
        if (version == 1) {
            byte[] b = new byte[password.length()];
            for (int i = 0; i < b.length; i++) b[i] = (byte) password.charAt(i);
            return b;
        }
        return password.getBytes(StandardCharsets.UTF_8);
    }

    /** PBKDF2 with HMAC-SHA1 over raw key bytes (works for an empty password too). */
    static byte[] pbkdf2Sha1(byte[] pw, byte[] salt, int rounds, int len) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        // HMAC pads short keys with zeros, so an empty key is equivalent to a single zero byte.
        mac.init(new SecretKeySpec(pw.length == 0 ? new byte[1] : pw, "HmacSHA1"));
        byte[] out = new byte[len];
        int blocks = (len + 19) / 20;
        for (int i = 1; i <= blocks; i++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int r = 1; r < rounds; r++) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, (i - 1) * 20, Math.min(20, len - (i - 1) * 20));
        }
        return out;
    }

    private static byte[] readArray(DataInputStream in) throws IOException {
        int n = in.readByte();
        if (n < 0) n += 256;
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    private static void writeArray(DataOutputStream out, byte[] b) throws IOException {
        out.writeByte(b.length);
        out.write(b);
    }

    private static Cipher aes(int mode, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c;
    }

    /** Decrypts a .db file to its XML. Throws {@link VaultCrypto.WrongPasswordException} for a wrong password. */
    public static byte[] decrypt(byte[] file, String password) throws IOException, GeneralSecurityException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        if (in.readUnsignedShort() != MAGIC) throw new IOException("Not a SafeInCloud database");
        int version = in.readUnsignedByte();
        byte[] salt = readArray(in);
        byte[] key = pbkdf2Sha1(passwordBytes(password, version), salt, USER_ROUNDS, 32);
        byte[] iv = readArray(in);
        byte[] salt2 = readArray(in);
        byte[] blob = readArray(in);
        byte[] inner;
        try {
            inner = aes(Cipher.DECRYPT_MODE, key, iv).doFinal(blob);
        } catch (BadPaddingException e) {
            throw new VaultCrypto.WrongPasswordException();
        }
        DataInputStream ii = new DataInputStream(new ByteArrayInputStream(inner));
        byte[] innerIv, innerKey, check;
        try {
            innerIv = readArray(ii);
            innerKey = readArray(ii);
            check = readArray(ii);
        } catch (IOException e) {
            throw new VaultCrypto.WrongPasswordException();
        }
        if (innerKey.length != 32 || !Arrays.equals(check, pbkdf2Sha1(innerKey, salt2, CHECK_ROUNDS, 32)))
            throw new VaultCrypto.WrongPasswordException();
        int headerLen = file.length - in.available();
        byte[] compressed = aes(Cipher.DECRYPT_MODE, innerKey, innerIv).doFinal(file, headerLen, file.length - headerLen);
        return inflate(compressed);
    }

    /** Encrypts XML into a .db file SafeInCloud can open. */
    public static byte[] encrypt(byte[] xml, String password) throws IOException, GeneralSecurityException {
        boolean ascii = true;
        for (int i = 0; i < password.length(); i++) if (password.charAt(i) > 0x7f) ascii = false;
        int version = ascii ? 1 : 2;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeShort(MAGIC);
        out.writeByte(version);
        byte[] salt = VaultCrypto.random(64);
        byte[] key = pbkdf2Sha1(passwordBytes(password, version), salt, USER_ROUNDS, 32);
        writeArray(out, salt);
        byte[] iv = VaultCrypto.random(16);
        writeArray(out, iv);
        byte[] innerKey = VaultCrypto.random(32);
        byte[] salt2 = VaultCrypto.random(64);
        writeArray(out, salt2);
        byte[] innerIv = VaultCrypto.random(16);
        ByteArrayOutputStream ib = new ByteArrayOutputStream();
        DataOutputStream io = new DataOutputStream(ib);
        writeArray(io, innerIv);
        writeArray(io, innerKey);
        writeArray(io, pbkdf2Sha1(innerKey, salt2, CHECK_ROUNDS, 32));
        io.flush();
        writeArray(out, aes(Cipher.ENCRYPT_MODE, key, iv).doFinal(ib.toByteArray()));
        out.write(aes(Cipher.ENCRYPT_MODE, innerKey, innerIv).doFinal(deflate(xml)));
        out.flush();
        return bo.toByteArray();
    }

    static byte[] inflate(byte[] b) throws IOException {
        return VaultCrypto.readLimited(new InflaterInputStream(new ByteArrayInputStream(b)), 512 * 1024 * 1024);
    }

    static byte[] deflate(byte[] b) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DeflaterOutputStream d = new DeflaterOutputStream(bo);
        d.write(b);
        d.close();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ names used by built-in templates

    private static final Map<String, String> NAMES = new HashMap<String, String>();
    static {
        String[] kv = {
                "account_field", "Account", "admin_password_field", "Admin password", "bank_account_template", "Bank Account",
                "bank_field", "Bank", "birthday_field", "Birthday", "blocking_field", "Blocking", "busines_label", "Business",
                "class_field", "Class", "code_field", "Code", "code_template", "Code", "credit_card_template", "Credit Card",
                "custom_template", "Custom", "cvv_field", "CVV", "dns_field", "DNS", "driving_license_template", "Driving License",
                "email_account_template", "Email Account", "email_field", "Email", "expires_field", "Expires", "holder_field", "Holder",
                "iban_field", "IBAN", "id_passport_template", "ID/Passport", "insurance_template", "Insurance",
                "internet_provider_template", "Internet Provider", "ip_address_field", "IP address", "issued_field", "Issued",
                "key_field", "Key", "login_field", "Login", "login_password_template", "Login/Password", "membership_template", "Membership",
                "name_field", "Name", "network_field", "Network", "number_field", "Number", "one_time_password_field", "One-time password (2FA)",
                "owner_field", "Owner", "password_field", "Password", "phone_field", "Phone", "pin_field", "PIN", "private_label", "Private",
                "protocol_field", "Protocol", "samples_label", "Samples", "social_security_template", "Social Security",
                "software_license_template", "Software License", "swift_field", "SWIFT", "type_field", "Type", "url_field", "Website",
                "web_account_template", "Web Account", "web_accounts_label", "Web Accounts", "wifi_password_field", "Wi-Fi password",
                "wifi_router_template", "Wi-Fi Router"};
        for (int i = 0; i + 1 < kv.length; i += 2) NAMES.put(kv[i], kv[i + 1]);
    }

    /** Resolves "@string/login_field" style names to readable text. */
    static String name(String s) {
        if (s == null) return "";
        if (!s.startsWith("@string/")) return s;
        String key = s.substring(8);
        String n = NAMES.get(key);
        if (n != null) return n;
        String t = key.replaceAll("_(field|template|label)$", "").replace('_', ' ');
        return t.length() == 0 ? s : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ------------------------------------------------------------------ type / icon / colour mapping

    private static String fieldType(String t) {
        if (t == null) return Field.TEXT;
        if (t.equals("login")) return Field.LOGIN;
        if (t.equals("password")) return Field.PASSWORD;
        if (t.equals("website")) return Field.WEBSITE;
        if (t.equals("one_time_password")) return Field.OTP;
        if (t.equals("number")) return Field.NUMBER;
        if (t.equals("pin")) return Field.PIN;
        if (t.equals("phone")) return Field.PHONE;
        if (t.equals("date")) return Field.DATE;
        if (t.equals("expiry")) return Field.EXPIRY;
        if (t.equals("email")) return Field.EMAIL;
        if (t.equals("secret")) return Field.SECRET;
        return Field.TEXT;
    }

    private static String sicType(Field f) {
        String t = f.type;
        if (Field.OTP.equals(t)) return "one_time_password";
        if (Field.MULTILINE.equals(t)) return "text";
        if (Field.PASSKEY.equals(t)) return "secret";
        for (String s : new String[]{Field.LOGIN, Field.PASSWORD, Field.WEBSITE, Field.NUMBER, Field.PIN, Field.PHONE, Field.DATE, Field.EXPIRY, Field.EMAIL, Field.SECRET})
            if (s.equals(t)) return t;
        return "text";
    }

    private static final String[][] SYMBOLS = {
            {"credit_card", "card"}, {"web_site", "web"}, {"email", "email"}, {"key", "key"}, {"lock", "lock"}, {"id", "id"},
            {"insurance", "shield"}, {"membership", "star"}, {"bank", "bank"}, {"car", "car"}, {"wifi", "wifi"}, {"router", "wifi"},
            {"software", "code"}, {"computer", "server"}, {"server", "server"}, {"phone", "phone"}, {"home", "home"}, {"note", "note"},
            {"game", "game"}, {"shop", "cart"}, {"plane", "plane"}, {"heart", "heart"}};

    private static String icon(String symbol, boolean hasWebsite) {
        if (symbol != null) {
            String s = symbol.toLowerCase(Locale.ROOT);
            for (String[] m : SYMBOLS) if (s.equals(m[0]) || s.contains(m[0])) return m[1];
        }
        return hasWebsite ? "web" : "key";
    }

    private static String symbol(String icon) {
        for (String[] m : SYMBOLS) if (m[1].equals(icon)) return m[0];
        return "key";
    }

    private static final String[][] COLOR_NAMES = {{"gray", "11"}, {"grey", "11"}, {"blue", "1"}, {"green", "0"}, {"red", "3"},
            {"orange", "4"}, {"purple", "2"}, {"violet", "2"}, {"yellow", "8"}, {"brown", "6"}, {"teal", "5"}, {"cyan", "9"}, {"pink", "10"}, {"indigo", "7"}};
    /** Must match Ui.CARD_COLORS order. */
    private static final int[] PALETTE = {0x2E7D5B, 0x1E88E5, 0x8E24AA, 0xE53935, 0xF4511E, 0x00897B, 0x6D4C41, 0x3949AB, 0xC0CA33, 0x00ACC1, 0xD81B60, 0x546E7A};

    private static int color(String c) {
        if (c == null || c.length() == 0) return 11;
        String s = c.toLowerCase(Locale.ROOT);
        for (String[] m : COLOR_NAMES) if (s.equals(m[0])) return Integer.parseInt(m[1]);
        try {
            long v = Long.decode(s.startsWith("#") ? "0x" + s.substring(1) : s);
            int r = (int) (v >> 16) & 0xff, g = (int) (v >> 8) & 0xff, b = (int) v & 0xff;
            int best = 11;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < PALETTE.length; i++) {
                int pr = PALETTE[i] >> 16 & 0xff, pg = PALETTE[i] >> 8 & 0xff, pb = PALETTE[i] & 0xff;
                long d = (long) (r - pr) * (r - pr) + (long) (g - pg) * (g - pg) + (long) (b - pb) * (b - pb);
                if (d < bestD) { bestD = d; best = i; }
            }
            return best;
        } catch (Exception e) {
            return 11;
        }
    }

    // ------------------------------------------------------------------ history JSON {"<millis>":"old value", ...}

    static List<Field.HistoryEntry> parseHistory(String json) {
        List<Field.HistoryEntry> out = new ArrayList<Field.HistoryEntry>();
        if (json == null) return out;
        int i = json.indexOf('{');
        if (i < 0) return out;
        i++;
        try {
            while (i < json.length()) {
                while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
                if (i >= json.length() || json.charAt(i) == '}') break;
                StringBuilder k = new StringBuilder();
                i = readJsonString(json, i, k);
                while (json.charAt(i) != ':') i++;
                i++;
                while (Character.isWhitespace(json.charAt(i))) i++;
                StringBuilder v = new StringBuilder();
                i = readJsonString(json, i, v);
                long t;
                try { t = Long.parseLong(k.toString()); } catch (NumberFormatException e) { t = 0; }
                if (v.length() > 0) out.add(new Field.HistoryEntry(v.toString(), t));
            }
        } catch (RuntimeException ignored) {
            // keep whatever parsed
        }
        java.util.Collections.sort(out, new java.util.Comparator<Field.HistoryEntry>() {
            public int compare(Field.HistoryEntry a, Field.HistoryEntry b) { return Long.compare(b.time, a.time); }
        });
        return out;
    }

    private static int readJsonString(String s, int i, StringBuilder out) {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("bad json");
        i++;
        while (s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\') {
                char e = s.charAt(++i);
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u': out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                    default: out.append(e);
                }
            } else out.append(c);
            i++;
        }
        return i + 1;
    }

    static String historyJson(List<Field.HistoryEntry> h) {
        StringBuilder b = new StringBuilder("{");
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        for (Field.HistoryEntry e : h) {
            long t = e.time;
            while (seen.contains(t)) t++;
            seen.add(t);
            if (b.length() > 1) b.append(',');
            b.append('"').append(t).append("\":\"");
            for (char c : e.value.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c == '\n') b.append("\\n");
                else if (c == '\r') b.append("\\r");
                else if (c == '\t') b.append("\\t");
                else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                else b.append(c);
            }
            b.append('"');
        }
        return b.append('}').toString();
    }

    // ------------------------------------------------------------------ import

    public static class Result {
        public int cards, templates, trashed, skippedTemplates, attachments;
    }

    private static Element child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    private static long stamp(Element e, String attr, long def) {
        try { return Long.parseLong(e.getAttribute(attr)); } catch (Exception x) { return def; }
    }

    /** Adds the cards, templates and labels from SafeInCloud XML to the vault. */
    public static Result importInto(Vault v, byte[] xml) throws Exception {
        Document d = VaultXml.builder().parse(new ByteArrayInputStream(xml));
        Element root = d.getDocumentElement();
        if (!"database".equals(root.getTagName())) throw new IOException("Not a SafeInCloud database");
        Result r = new Result();
        Map<String, String> labels = new HashMap<String, String>();
        Label archived = null;
        long now = System.currentTimeMillis();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            if (e.getTagName().equals("label")) {
                String nm = name(e.getAttribute("name")).trim();
                if (nm.length() == 0) nm = "Label " + e.getAttribute("id");
                labels.put(e.getAttribute("id").trim(), v.labelNamed(nm).id);
            }
        }
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element) || !((Element) n).getTagName().equals("card")) continue;
            Element e = (Element) n;
            Card c = new Card();
            c.title = name(e.getAttribute("title")).trim();
            c.template = "true".equalsIgnoreCase(e.getAttribute("template"));
            c.deleted = "true".equalsIgnoreCase(e.getAttribute("deleted"));
            c.favorite = "true".equalsIgnoreCase(e.getAttribute("star"));
            c.color = color(e.getAttribute("color"));
            c.modified = stamp(e, "time_stamp", now);
            c.created = stamp(e, "first_stamp", c.modified);
            boolean website = false;
            for (Node k = e.getFirstChild(); k != null; k = k.getNextSibling()) {
                if (!(k instanceof Element)) continue;
                Element ke = (Element) k;
                String tag = ke.getTagName();
                if (tag.equals("field")) {
                    String type = ke.getAttribute("type");
                    String value = ke.getTextContent();
                    if (value == null) value = "";
                    if (type.equals("application")) {
                        if (value.trim().length() > 0) c.apps.add(value.trim());
                        continue;
                    }
                    Field f = new Field(name(ke.getAttribute("name")), fieldType(type), value);
                    if (f.name.startsWith("Passkey") && value.contains("credId=") && Passkey.decode(value) != null) f.type = Field.PASSKEY;
                    if (Field.TEXT.equals(f.type) && value.contains("\n")) f.type = Field.MULTILINE;
                    if (Field.WEBSITE.equals(f.type) && value.length() > 0) website = true;
                    f.history.addAll(parseHistory(ke.getAttribute("history")));
                    c.fields.add(f);
                } else if (tag.equals("notes")) {
                    c.notes = ke.getTextContent();
                } else if (tag.equals("label_id")) {
                    String id = labels.get(ke.getTextContent().trim());
                    if (id != null) c.labelIds.add(id);
                } else if (tag.equals("image")) {
                    byte[] data = Base64.getMimeDecoder().decode(ke.getTextContent().trim());
                    boolean png = data.length > 4 && (data[0] & 0xff) == 0x89 && data[1] == 'P';
                    c.attachments.add(new Attachment("image-" + (c.attachments.size() + 1) + (png ? ".png" : ".jpg"), png ? "image/png" : "image/jpeg", data));
                    r.attachments++;
                } else if (tag.equals("file")) {
                    byte[] data = inflate(Base64.getMimeDecoder().decode(ke.getTextContent().trim()));
                    String fname = ke.getAttribute("name");
                    if (fname.length() == 0) fname = "file-" + (c.attachments.size() + 1);
                    c.attachments.add(new Attachment(fname, KeePassMapper.mimeFor(fname), data));
                    r.attachments++;
                }
            }
            c.icon = icon(e.getAttribute("symbol"), website);
            if ("true".equalsIgnoreCase(e.getAttribute("archived"))) {
                if (archived == null) archived = v.labelNamed("Archived");
                c.labelIds.add(archived.id);
            }
            if (c.template) {
                boolean dup = false;
                for (Card t : v.templates()) if (t.title.equalsIgnoreCase(c.title)) dup = true;
                if (dup) { r.skippedTemplates++; continue; }
                r.templates++;
            } else if (c.deleted) r.trashed++;
            else r.cards++;
            if (c.title.length() == 0) c.title = c.template ? "Template" : "Untitled";
            v.cards.add(c);
        }
        return r;
    }

    // ------------------------------------------------------------------ export

    /** Builds SafeInCloud XML from the vault (cards, templates, labels, trash, history, attachments). */
    public static byte[] export(Vault v, boolean includeTrash) throws Exception {
        Document d = VaultXml.builder().newDocument();
        Element root = d.createElement("database");
        d.appendChild(root);
        Map<String, Integer> labelIds = new LinkedHashMap<String, Integer>();
        int nextId = 1;
        for (Label l : v.labels) {
            Element e = d.createElement("label");
            e.setAttribute("name", l.name);
            e.setAttribute("id", String.valueOf(nextId));
            labelIds.put(l.id, nextId++);
            root.appendChild(e);
        }
        int cardId = Math.max(1000, nextId);
        for (Card c : v.cards) {
            if (c.deleted && !includeTrash) continue;
            Element e = d.createElement("card");
            e.setAttribute("title", c.title);
            e.setAttribute("id", String.valueOf(cardId++));
            e.setAttribute("symbol", symbol(c.icon));
            int rgb = PALETTE[Math.abs(c.color) % PALETTE.length];
            e.setAttribute("color", String.format(Locale.ROOT, "0xff%06x", rgb));
            boolean login = c.login().length() > 0 || c.password().length() > 0 || c.template;
            e.setAttribute("autofill", login ? "on" : "off");
            if (c.template) e.setAttribute("template", "true");
            if (c.favorite) e.setAttribute("star", "true");
            if (c.deleted) e.setAttribute("deleted", "true");
            e.setAttribute("first_stamp", String.valueOf(c.created));
            e.setAttribute("time_stamp", String.valueOf(c.modified));
            for (Field f : c.fields) {
                Element fe = d.createElement("field");
                fe.setAttribute("name", f.name);
                fe.setAttribute("type", sicType(f));
                if (!f.history.isEmpty()) fe.setAttribute("history", historyJson(f.history));
                if (f.value.length() > 0) fe.setTextContent(f.value);
                e.appendChild(fe);
            }
            for (String app : c.apps) {
                Element fe = d.createElement("field");
                fe.setAttribute("name", "Application");
                fe.setAttribute("type", "application");
                fe.setTextContent(app);
                e.appendChild(fe);
            }
            if (c.notes.length() > 0) {
                Element ne = d.createElement("notes");
                ne.setTextContent(c.notes);
                e.appendChild(ne);
            }
            for (String id : c.labelIds) {
                Integer li = labelIds.get(id);
                if (li == null) continue;
                Element le = d.createElement("label_id");
                le.setTextContent(String.valueOf(li));
                e.appendChild(le);
            }
            for (Attachment a : c.attachments) {
                if (a.isImage()) {
                    Element ie = d.createElement("image");
                    ie.setTextContent(Base64.getEncoder().encodeToString(a.data));
                    e.appendChild(ie);
                } else {
                    Element fe = d.createElement("file");
                    fe.setAttribute("name", a.name);
                    fe.setTextContent(Base64.getEncoder().encodeToString(deflate(a.data)));
                    e.appendChild(fe);
                }
            }
            root.appendChild(e);
        }
        return VaultXml.toBytes(d, true);
    }
}
