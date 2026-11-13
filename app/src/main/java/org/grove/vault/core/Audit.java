package org.grove.vault.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Security audit: weak passwords, reused passwords and expiring cards. */
public final class Audit {
    private Audit() {}

    public static boolean hasWeakPassword(Card c) {
        for (Field f : c.fields) if (f.isPasswordLike() && PasswordTools.isWeak(f.value)) return true;
        return false;
    }

    /** password -> cards using it, only entries with 2+ cards. */
    public static Map<String, List<Card>> reused(List<Card> cards) {
        Map<String, List<Card>> m = new HashMap<String, List<Card>>();
        for (Card c : cards) {
            for (Field f : c.fields) {
                if (!f.isPasswordLike() || f.value.length() == 0) continue;
                List<Card> l = m.get(f.value);
                if (l == null) m.put(f.value, l = new ArrayList<Card>());
                if (!l.contains(c)) l.add(c);
            }
        }
        Map<String, List<Card>> out = new HashMap<String, List<Card>>();
        for (Map.Entry<String, List<Card>> e : m.entrySet()) if (e.getValue().size() > 1) out.put(e.getKey(), e.getValue());
        return out;
    }

    public static boolean isReused(Card c, Map<String, List<Card>> reused) {
        for (Field f : c.fields) if (f.isPasswordLike() && reused.containsKey(f.value)) return true;
        return false;
    }

    private static final Pattern MY = Pattern.compile("^\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{2}|\\d{4})\\s*$");
    private static final Pattern YMD = Pattern.compile("^\\s*(\\d{4})-(\\d{1,2})(?:-(\\d{1,2}))?\\s*$");
    private static final Pattern DMY = Pattern.compile("^\\s*(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\s*$");

    /** Parses an expiry value; returns end-of-validity time in millis, or -1. */
    public static long parseExpiry(String s) {
        if (s == null) return -1;
        Calendar cal = Calendar.getInstance();
        cal.clear();
        Matcher m = MY.matcher(s);
        if (m.matches()) {
            int mon = Integer.parseInt(m.group(1));
            int yr = Integer.parseInt(m.group(2));
            if (yr < 100) yr += 2000;
            if (mon < 1 || mon > 12) return -1;
            cal.set(yr, mon - 1, 1);
            cal.add(Calendar.MONTH, 1); // valid through end of month
            return cal.getTimeInMillis() - 1;
        }
        m = YMD.matcher(s);
        if (m.matches()) {
            int yr = Integer.parseInt(m.group(1)), mon = Integer.parseInt(m.group(2));
            if (m.group(3) == null) {
                cal.set(yr, mon - 1, 1);
                cal.add(Calendar.MONTH, 1);
            } else {
                cal.set(yr, mon - 1, Integer.parseInt(m.group(3)));
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
            return cal.getTimeInMillis() - 1;
        }
        m = DMY.matcher(s);
        if (m.matches()) {
            cal.set(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(1)));
            cal.add(Calendar.DAY_OF_MONTH, 1);
            return cal.getTimeInMillis() - 1;
        }
        return -1;
    }

    /** Earliest expiry on the card, or -1 if none. */
    public static long expiry(Card c) {
        long best = -1;
        for (Field f : c.fields) {
            if (!Field.EXPIRY.equals(f.type)) continue;
            long t = parseExpiry(f.value);
            if (t > 0 && (best < 0 || t < best)) best = t;
        }
        return best;
    }

    /** True if the card expires within `days` days or has already expired. */
    public static boolean isExpiring(Card c, int days, long now) {
        long t = expiry(c);
        return t > 0 && t < now + days * 86400000L;
    }

    public static boolean isExpired(Card c, long now) {
        long t = expiry(c);
        return t > 0 && t < now;
    }
}
