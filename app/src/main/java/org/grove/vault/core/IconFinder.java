package org.grove.vault.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds a website's icon URLs in its home page HTML (apple-touch-icon, icon, manifest-free fallbacks). */
public final class IconFinder {
    private IconFinder() {}

    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    /** The domain whose icon a card should show: its first website, else a passkey's site. "" if none. */
    public static String domainFor(Card c) {
        for (Field f : c.fields) {
            if (Field.WEBSITE.equals(f.type) && f.value.trim().length() > 0) {
                String d = Card.domainOf(f.value);
                if (d.contains(".")) return d;
            }
        }
        for (Passkey p : c.passkeys()) if (p.rpId.contains(".")) return p.rpId.toLowerCase(Locale.ROOT);
        return "";
    }

    /** Only plain host names (letters, digits, dots, dashes) are ever fetched. */
    public static boolean isFetchable(String domain) {
        return domain != null && domain.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
                && !domain.endsWith(".local") && !domain.equals("localhost") && !domain.matches("[0-9.]+");
    }

    /** Candidate icon URLs from the page, best first; always ends with /favicon.ico. */
    public static List<String> candidates(String pageUrl, String html) {
        final List<String[]> found = new ArrayList<String[]>(); // {url, score}
        if (html != null) {
            int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
            String h = head > 0 ? html.substring(0, head) : html;
            Matcher m = LINK.matcher(h);
            while (m.find()) {
                String rel = "", href = null, sizes = "", type = "";
                Matcher a = ATTR.matcher(m.group());
                while (a.find()) {
                    String k = a.group(1).toLowerCase(Locale.ROOT);
                    String v = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5);
                    v = unescape(v.trim());
                    if (k.equals("rel")) rel = v.toLowerCase(Locale.ROOT);
                    else if (k.equals("href")) href = v;
                    else if (k.equals("sizes")) sizes = v.toLowerCase(Locale.ROOT);
                    else if (k.equals("type")) type = v.toLowerCase(Locale.ROOT);
                }
                if (href == null || href.length() == 0 || href.startsWith("data:")) continue;
                if (!(rel.contains("icon"))) continue;
                if (type.contains("svg") || href.toLowerCase(Locale.ROOT).endsWith(".svg")) continue; // can't decode SVG
                if (rel.contains("mask-icon")) continue;
                int size = 0;
                Matcher s = Pattern.compile("(\\d+)x(\\d+)").matcher(sizes);
                while (s.find()) size = Math.max(size, Integer.parseInt(s.group(1)));
                if (size == 0) size = rel.contains("apple-touch-icon") ? 180 : 32;
                // prefer around 64-192 px; huge icons are wasteful, tiny ones look blurry
                int score = size >= 64 && size <= 256 ? 1000 - Math.abs(size - 128) : size > 256 ? 500 - size / 10 : size;
                String abs = resolve(pageUrl, href);
                if (abs != null) found.add(new String[]{abs, String.valueOf(score)});
            }
        }
        Collections.sort(found, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return Integer.parseInt(b[1]) - Integer.parseInt(a[1]); }
        });
        List<String> out = new ArrayList<String>();
        for (String[] f : found) if (!out.contains(f[0])) out.add(f[0]);
        String fav = resolve(pageUrl, "/favicon.ico");
        if (fav != null && !out.contains(fav)) out.add(fav);
        return out;
    }

    static String resolve(String base, String href) {
        try {
            URI u = new URI(base).resolve(href.replace(" ", "%20"));
            String s = u.getScheme();
            if (s == null || !s.equalsIgnoreCase("https")) return null; // never fetch over plain http
            return u.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#x2F;", "/").replace("&#47;", "/").replace("&quot;", "\"");
    }
}
