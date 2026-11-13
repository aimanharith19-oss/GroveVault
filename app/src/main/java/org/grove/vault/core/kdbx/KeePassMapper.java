package org.grove.vault.core.kdbx;

import org.grove.vault.core.Attachment;
import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Label;
import org.grove.vault.core.Passkey;
import org.grove.vault.core.Totp;
import org.grove.vault.core.Vault;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Converts between KeePass entries/groups and Grove cards/labels. */
public final class KeePassMapper {
    private KeePassMapper() {}

    public static class Result {
        public int imported;
        public int skippedRecycleBin;
    }

    /** Imports all entries into the vault. Groups (by path) and tags become labels. */
    public static Result importInto(Vault v, Kdbx.Database db, boolean includeRecycleBin) {
        Result r = new Result();
        walk(v, db.root, "", r, includeRecycleBin, true);
        return r;
    }

    private static void walk(Vault v, Kdbx.Group g, String path, Result r, boolean includeBin, boolean isRoot) {
        if (g.recycleBin && !includeBin) {
            r.skippedRecycleBin += count(g);
            return;
        }
        String here = isRoot ? "" : (path.length() == 0 ? g.name : path + "/" + g.name);
        for (Kdbx.Entry e : g.entries) {
            Card c = toCard(v, e);
            if (here.length() > 0) c.labelIds.add(v.labelNamed(here).id);
            if (g.recycleBin) c.deleted = true;
            v.cards.add(c);
            r.imported++;
        }
        for (Kdbx.Group child : g.groups) walk(v, child, here, r, includeBin, false);
    }

    private static int count(Kdbx.Group g) {
        int n = g.entries.size();
        for (Kdbx.Group c : g.groups) n += count(c);
        return n;
    }

    private static final String[] STANDARD = {"Title", "UserName", "Password", "URL", "Notes"};

    private static boolean isStandard(String k) {
        for (String s : STANDARD) if (s.equals(k)) return true;
        return false;
    }

    static Card toCard(Vault v, Kdbx.Entry e) {
        Card c = new Card();
        c.title = e.get("Title");
        if (c.title.length() == 0) c.title = Card.domainOf(e.get("URL"));
        if (c.title.length() == 0) c.title = "Untitled";
        c.created = e.created;
        c.modified = e.modified;
        c.icon = e.get("URL").length() > 0 ? "web" : "key";
        c.color = Math.abs(c.title.hashCode()) % 12;
        String user = e.get("UserName");
        c.fields.add(new Field("Login", user.contains("@") ? Field.EMAIL : Field.LOGIN, user));
        c.fields.add(new Field("Password", Field.PASSWORD, e.get("Password")));
        if (e.get("URL").length() > 0) c.fields.add(new Field("Website", Field.WEBSITE, e.get("URL")));

        String otp = e.get("otp");
        String seed = e.get("TOTP Seed");
        String handled = null;
        if (otp.length() > 0) {
            c.fields.add(new Field("One-time password", Field.OTP, otp));
            handled = "otp";
        } else if (seed.length() > 0) {
            int period = 30, digits = 6;
            String settings = e.get("TOTP Settings");
            if (settings.contains(";")) {
                try {
                    String[] p = settings.split(";");
                    period = Integer.parseInt(p[0].trim());
                    if (p[1].trim().equalsIgnoreCase("S")) digits = 5;
                    else digits = Integer.parseInt(p[1].trim());
                } catch (Exception ignored) {}
            }
            c.fields.add(new Field("One-time password", Field.OTP, Totp.uri(seed, period, digits, "SHA1")));
            handled = "TOTP Seed";
        }
        // KeePass 2.47+ TimeOtp-Secret-Base32 style
        String kp = e.get("TimeOtp-Secret-Base32");
        if (handled == null && kp.length() > 0) {
            int period = 30, digits = 6;
            try { if (e.get("TimeOtp-Period").length() > 0) period = Integer.parseInt(e.get("TimeOtp-Period")); } catch (Exception ignored) {}
            try { if (e.get("TimeOtp-Length").length() > 0) digits = Integer.parseInt(e.get("TimeOtp-Length")); } catch (Exception ignored) {}
            String alg = e.get("TimeOtp-Algorithm").replace("HMAC-", "");
            c.fields.add(new Field("One-time password", Field.OTP, Totp.uri(kp, period, digits, alg.length() > 0 ? alg : "SHA1")));
            handled = "TimeOtp-";
        }
        for (Map.Entry<String, String> s : e.strings.entrySet()) {
            String k = s.getKey();
            if (isStandard(k)) continue;
            if (handled != null && (k.equals(handled) || k.startsWith(handled) || (handled.equals("TOTP Seed") && k.equals("TOTP Settings"))))
                continue;
            if (k.startsWith("TimeOtp-") && "TimeOtp-".equals(handled)) continue;
            if (k.startsWith("KPEX_") || k.startsWith("KP2A_") || k.startsWith("_etm_")) continue;
            String type = e.protectedKeys.contains(k) ? Field.SECRET : guessType(k, s.getValue());
            c.fields.add(new Field(k, type, s.getValue()));
        }
        if (e.get("KPEX_PASSKEY_PRIVATE_KEY_PEM").length() > 0 && e.get("KPEX_PASSKEY_CREDENTIAL_ID").length() > 0) {
            try {
                Passkey p = new Passkey();
                p.rpId = e.get("KPEX_PASSKEY_RELYING_PARTY");
                p.userName = e.get("KPEX_PASSKEY_USERNAME");
                p.credentialId = Passkey.unb64u(e.get("KPEX_PASSKEY_CREDENTIAL_ID"));
                p.userHandle = e.get("KPEX_PASSKEY_USER_HANDLE").length() > 0 ? Passkey.unb64u(e.get("KPEX_PASSKEY_USER_HANDLE")) : new byte[0];
                p.privateKey = Passkey.pemToDer(e.get("KPEX_PASSKEY_PRIVATE_KEY_PEM"));
                p.created = e.created;
                if (p.rpId.length() > 0) c.fields.add(p.toField());
            } catch (Exception ignored) {
                // malformed passkey: keep the rest of the entry
            }
        }
        if (e.expires > 0) {
            c.fields.add(new Field("Expires", Field.EXPIRY, new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(e.expires))));
        }
        c.notes = e.get("Notes");
        for (Map.Entry<String, byte[]> b : e.binaries.entrySet()) {
            c.attachments.add(new Attachment(b.getKey(), mimeFor(b.getKey()), b.getValue()));
        }
        if (e.tags.length() > 0) {
            for (String t : e.tags.split("[,;]")) {
                String tag = t.trim();
                if (tag.length() == 0) continue;
                if (tag.equalsIgnoreCase("Favorite") || tag.equalsIgnoreCase("Favourite")) c.favorite = true;
                else if (tag.equalsIgnoreCase("Passkey") && e.get("KPEX_PASSKEY_CREDENTIAL_ID").length() > 0) continue;
                else c.labelIds.add(v.labelNamed(tag).id);
            }
        }
        // KeePassXC / KeePass2Android store Android app links as "AndroidApp" or "KP2A_URL" strings
        for (Map.Entry<String, String> s : e.strings.entrySet()) {
            if (s.getValue().startsWith("androidapp://")) c.apps.add(s.getValue().substring(13));
        }
        return c;
    }

    private static String guessType(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("pin")) return Field.PIN;
        if (k.contains("password") || k.contains("passwd")) return Field.PASSWORD;
        if (k.contains("email")) return Field.EMAIL;
        if (k.contains("phone") || k.contains("tel")) return Field.PHONE;
        if (k.contains("url") || value.startsWith("http://") || value.startsWith("https://")) return Field.WEBSITE;
        if (value.contains("\n")) return Field.MULTILINE;
        return Field.TEXT;
    }

    public static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".key") || n.endsWith(".pem")) return "text/plain";
        return "application/octet-stream";
    }

    /** Builds a KeePass database from the vault. Each card goes into the group of its first label; all labels become tags. */
    public static Kdbx.Database export(Vault v, boolean includeTrash) {
        Kdbx.Database db = new Kdbx.Database();
        db.name = "Grove Vault";
        db.root.name = "Grove Vault";
        Map<String, Kdbx.Group> groups = new LinkedHashMap<String, Kdbx.Group>();
        Kdbx.Group bin = null;
        for (Card c : v.cards) {
            if (c.template) continue;
            if (c.deleted && !includeTrash) continue;
            Kdbx.Entry e = fromCard(v, c);
            java.util.List<org.grove.vault.core.Passkey> pks = c.passkeys();
            Kdbx.Group target = db.root;
            if (c.deleted) {
                if (bin == null) {
                    bin = new Kdbx.Group();
                    bin.name = "Recycle Bin";
                    bin.recycleBin = true;
                    db.recycleBinUuid = bin.uuid;
                }
                target = bin;
            } else if (!c.labelIds.isEmpty()) {
                Label l = v.findLabel(c.labelIds.iterator().next());
                if (l != null) target = groupFor(db.root, groups, l.name);
            }
            target.entries.add(e);
            // KeePassXC stores one passkey per entry; extra passkeys get their own entries.
            for (int i = 0; i < pks.size(); i++) {
                Kdbx.Entry pe = i == 0 ? e : fromCard(v, c);
                if (i > 0) {
                    pe.strings.put("Title", c.title + " (passkey " + (i + 1) + ")");
                    target.entries.add(pe);
                }
                putPasskey(pe, pks.get(i));
            }
        }
        if (bin != null) db.root.groups.add(bin);
        return db;
    }

    private static Kdbx.Group groupFor(Kdbx.Group root, Map<String, Kdbx.Group> cache, String path) {
        Kdbx.Group g = cache.get(path);
        if (g != null) return g;
        int slash = path.lastIndexOf('/');
        Kdbx.Group parent = slash > 0 ? groupFor(root, cache, path.substring(0, slash)) : root;
        g = new Kdbx.Group();
        g.name = slash > 0 ? path.substring(slash + 1) : path;
        parent.groups.add(g);
        cache.put(path, g);
        return g;
    }

    private static void putPasskey(Kdbx.Entry e, org.grove.vault.core.Passkey p) {
        e.strings.put("KPEX_PASSKEY_CREDENTIAL_ID", org.grove.vault.core.Passkey.b64u(p.credentialId));
        e.strings.put("KPEX_PASSKEY_PRIVATE_KEY_PEM", p.privateKeyPem());
        e.strings.put("KPEX_PASSKEY_RELYING_PARTY", p.rpId);
        e.strings.put("KPEX_PASSKEY_USERNAME", p.userName);
        e.strings.put("KPEX_PASSKEY_USER_HANDLE", org.grove.vault.core.Passkey.b64u(p.userHandle));
        e.protectedKeys.add("KPEX_PASSKEY_PRIVATE_KEY_PEM");
        e.protectedKeys.add("KPEX_PASSKEY_USER_HANDLE");
        if (!(";" + e.tags + ";").contains(";Passkey;")) e.tags = e.tags.length() == 0 ? "Passkey" : e.tags + ";Passkey";
    }

    static Kdbx.Entry fromCard(Vault v, Card c) {
        Kdbx.Entry e = new Kdbx.Entry();
        e.created = c.created;
        e.modified = c.modified;
        e.strings.put("Title", c.title);
        Field login = c.first(Field.LOGIN);
        if (login == null) login = c.first(Field.EMAIL);
        Field pass = c.first(Field.PASSWORD);
        Field url = c.first(Field.WEBSITE);
        Field otp = c.first(Field.OTP);
        e.strings.put("UserName", login == null ? "" : login.value);
        e.strings.put("Password", pass == null ? "" : pass.value);
        e.protectedKeys.add("Password");
        e.strings.put("URL", url == null ? "" : url.value);
        e.strings.put("Notes", c.notes);
        if (otp != null) {
            String val = otp.value.startsWith("otpauth://") ? otp.value : Totp.uri(otp.value, 30, 6, "SHA1");
            e.strings.put("otp", val);
            e.protectedKeys.add("otp");
        }
        for (Field f : c.fields) {
            if (f == login || f == pass || f == url || f == otp || Field.PASSKEY.equals(f.type)) continue;
            if (f.value.length() == 0) continue;
            String key = f.name;
            int n = 2;
            while (e.strings.containsKey(key)) key = f.name + " (" + (n++) + ")";
            e.strings.put(key, f.value);
            if (f.isSecret()) e.protectedKeys.add(key);
        }
        int i = 0;
        for (String app : c.apps) {
            e.strings.put(i == 0 ? "AndroidApp" : "AndroidApp_" + i, "androidapp://" + app);
            i++;
        }
        StringBuilder tags = new StringBuilder();
        for (String id : c.labelIds) {
            Label l = v.findLabel(id);
            if (l == null) continue;
            if (tags.length() > 0) tags.append(';');
            tags.append(l.name);
        }
        if (c.favorite) {
            if (tags.length() > 0) tags.append(';');
            tags.append("Favorite");
        }
        e.tags = tags.toString();
        for (Attachment a : c.attachments) {
            String key = a.name;
            int n = 2;
            while (e.binaries.containsKey(key)) key = (n++) + "_" + a.name;
            e.binaries.put(key, a.data);
        }
        e.iconId = url != null ? 1 : 0;
        return e;
    }
}
