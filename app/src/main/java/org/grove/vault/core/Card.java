package org.grove.vault.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A vault entry (login, credit card, note, ...). Templates are cards with template=true. */
public class Card {
    public String id = Vault.newId();
    public String title = "";
    public String notes = "";
    public boolean template;
    public boolean favorite;
    public boolean deleted;
    /** Index into the UI colour palette. */
    public int color;
    /** Template-like icon name, e.g. "web", "card", "key". */
    public String icon = "key";
    public long created = System.currentTimeMillis();
    public long modified = created;
    public final List<Field> fields = new ArrayList<Field>();
    public final Set<String> labelIds = new LinkedHashSet<String>();
    public final List<Attachment> attachments = new ArrayList<Attachment>();
    /** Android package names this card is linked to for autofill. */
    public final Set<String> apps = new LinkedHashSet<String>();
    /** Signing-certificate SHA-256 pinned for each linked app (package -> "AA:BB:..."). */
    public final java.util.Map<String, String> appSigs = new java.util.LinkedHashMap<String, String>();

    /** Deep copy with a new id, used when creating a card from a template. */
    public Card instantiate() {
        Card c = new Card();
        c.title = template ? "" : title;
        c.notes = notes;
        c.color = color;
        c.icon = icon;
        for (Field f : fields) c.fields.add(new Field(f.name, f.type, template ? "" : f.value));
        c.labelIds.addAll(labelIds);
        return c;
    }

    /** Copy keeping the id, used as a working copy in the editor. */
    public Card copy() {
        Card c = new Card();
        c.id = id;
        c.title = title;
        c.notes = notes;
        c.template = template;
        c.favorite = favorite;
        c.deleted = deleted;
        c.color = color;
        c.icon = icon;
        c.created = created;
        c.modified = modified;
        for (Field f : fields) c.fields.add(f.copy());
        c.labelIds.addAll(labelIds);
        c.attachments.addAll(attachments);
        c.apps.addAll(apps);
        c.appSigs.putAll(appSigs);
        return c;
    }

    public Field first(String type) {
        for (Field f : fields) if (type.equals(f.type) && f.value.length() > 0) return f;
        return null;
    }

    public java.util.List<Passkey> passkeys() {
        java.util.List<Passkey> out = new ArrayList<Passkey>();
        for (Field f : fields) {
            if (!Field.PASSKEY.equals(f.type)) continue;
            Passkey p = Passkey.decode(f.value);
            if (p != null) out.add(p);
        }
        return out;
    }

    public String login() {
        Field f = first(Field.LOGIN);
        if (f == null) f = first(Field.EMAIL);
        return f == null ? "" : f.value;
    }

    public String password() {
        Field f = first(Field.PASSWORD);
        return f == null ? "" : f.value;
    }

    public String website() {
        Field f = first(Field.WEBSITE);
        return f == null ? "" : f.value;
    }

    /** Short line under the title in lists. */
    public String subtitle() {
        String l = login();
        if (l.length() > 0) return l;
        String w = website();
        if (w.length() > 0) return w;
        for (Field f : fields) if (!f.isSecret() && f.value.length() > 0) return f.value;
        return "";
    }

    public boolean matches(String query) {
        if (query == null || query.length() == 0) return true;
        String q = query.toLowerCase(Locale.ROOT);
        if (title.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (notes.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Field f : fields) {
            if (f.name.toLowerCase(Locale.ROOT).contains(q)) return true;
            if (!f.isSecret() && f.value.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /** Host part of a URL, without "www.", lowercase; "" if none. */
    public static String domainOf(String url) {
        if (url == null) return "";
        String u = url.trim().toLowerCase(Locale.ROOT);
        int s = u.indexOf("://");
        if (s >= 0) u = u.substring(s + 3);
        int e = 0;
        while (e < u.length() && "/?#:".indexOf(u.charAt(e)) < 0) e++;
        u = u.substring(0, e);
        int at = u.lastIndexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        if (u.startsWith("www.")) u = u.substring(4);
        return u;
    }

    /** Registrable-ish domain: last two labels (three for short second-level like co.uk / com.my). */
    public static String baseDomain(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        String sl = p[p.length - 2];
        int keep = (sl.length() <= 3 && p[p.length - 1].length() == 2) ? 3 : 2;
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, p.length - keep); i < p.length; i++) {
            if (b.length() > 0) b.append('.');
            b.append(p[i]);
        }
        return b.toString();
    }
}
