package org.grove.vault.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds logins that are really the same account (same username and same password, e.g. one card per website of a
 * single sign-on) and combines them into one card. Passkey-only cards (created before they could be attached to a
 * login) join the login with the same username on the same site.
 */
public final class Dedupe {
    private Dedupe() {}

    static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    /** Groups of 2+ cards that can be combined; the first card of each group is the one that will be kept. */
    public static List<List<Card>> groups(Vault v) {
        Map<String, List<Card>> by = new LinkedHashMap<String, List<Card>>();
        List<Card> passkeyOnly = new ArrayList<Card>();
        for (Card c : v.activeCards()) {
            if (c.template) continue;
            String pw = c.password();
            if (pw.length() == 0) {
                if (!c.passkeys().isEmpty()) passkeyOnly.add(c);
                continue;
            }
            String key = norm(c.login()) + "\u0000" + pw;
            List<Card> g = by.get(key);
            if (g == null) by.put(key, g = new ArrayList<Card>());
            g.add(c);
        }
        // passkey-only cards: attach to a login with the same username on the passkey's site
        for (Card pk : passkeyOnly) {
            String site = null;
            for (Passkey p : pk.passkeys()) { site = Card.baseDomain(p.rpId); break; }
            Card best = null;
            for (Card c : v.activeCards()) {
                if (c == pk || c.template || c.password().length() == 0) continue;
                if (!norm(c.login()).equals(norm(pk.login())) && pk.login().length() > 0) continue;
                for (Field f : c.fields)
                    if (Field.WEBSITE.equals(f.type) && f.value.length() > 0 && Card.baseDomain(Card.domainOf(f.value)).equals(site)) { best = c; break; }
                if (best != null) break;
            }
            if (best == null) continue;
            List<Card> g = by.get(norm(best.login()) + "\u0000" + best.password());
            if (g != null && !g.contains(pk)) g.add(pk);
        }
        List<List<Card>> out = new ArrayList<List<Card>>();
        for (List<Card> g : by.values()) {
            if (g.size() < 2) continue;
            // keep: favourite first, then the one with a password field and the most fields, then the newest
            Card keep = g.get(0);
            for (Card c : g) if (better(c, keep)) keep = c;
            g.remove(keep);
            g.add(0, keep);
            out.add(g);
        }
        return out;
    }

    private static boolean better(Card a, Card b) {
        if (a.favorite != b.favorite) return a.favorite;
        boolean ap = a.password().length() > 0, bp = b.password().length() > 0;
        if (ap != bp) return ap;
        if (a.fields.size() != b.fields.size()) return a.fields.size() > b.fields.size();
        return a.modified > b.modified;
    }

    /** Combines group[1..] into group[0]; the others go to the Trash (so it can be undone). Returns the kept card. */
    public static Card merge(List<Card> group) {
        Card keep = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            Card o = group.get(i);
            for (Field f : o.fields) {
                if (f.value.length() == 0) continue;
                if (Field.WEBSITE.equals(f.type)) {
                    if (!hasSite(keep, f.value)) keep.fields.add(new Field(nextName(keep, "Website"), Field.WEBSITE, f.value));
                } else if (Field.PASSKEY.equals(f.type) || Field.OTP.equals(f.type)) {
                    if (!hasValue(keep, f.type, f.value) && (Field.PASSKEY.equals(f.type) || keep.first(Field.OTP) == null)) keep.fields.add(f.copy());
                } else if (Field.LOGIN.equals(f.type) || Field.EMAIL.equals(f.type) || Field.PASSWORD.equals(f.type)) {
                    if (keep.first(f.type) == null && !hasValue(keep, null, f.value)) keep.fields.add(f.copy());
                } else if (!hasValue(keep, null, f.value)) {
                    keep.fields.add(f.copy());
                }
            }
            String n = o.notes == null ? "" : o.notes.trim();
            if (n.length() > 0 && !keep.notes.contains(n)) keep.notes = keep.notes.trim().length() == 0 ? n : keep.notes.trim() + "\n\n— " + o.title + " —\n" + n;
            keep.labelIds.addAll(o.labelIds);
            keep.apps.addAll(o.apps);
            for (Map.Entry<String, String> e : o.appSigs.entrySet()) if (!keep.appSigs.containsKey(e.getKey())) keep.appSigs.put(e.getKey(), e.getValue());
            keep.attachments.addAll(o.attachments);
            keep.favorite |= o.favorite;
            o.deleted = true;
            o.modified = System.currentTimeMillis();
        }
        keep.modified = System.currentTimeMillis();
        return keep;
    }

    private static boolean hasSite(Card c, String url) {
        String d = Card.domainOf(url);
        for (Field f : c.fields) if (Field.WEBSITE.equals(f.type) && Card.domainOf(f.value).equalsIgnoreCase(d)) return true;
        return false;
    }

    private static boolean hasValue(Card c, String type, String value) {
        for (Field f : c.fields) if ((type == null || type.equals(f.type)) && f.value.equals(value)) return true;
        return false;
    }

    private static String nextName(Card c, String base) {
        int n = 1;
        for (Field f : c.fields) if (f.name.startsWith(base)) n++;
        return n == 1 ? base : base + " " + n;
    }
}
