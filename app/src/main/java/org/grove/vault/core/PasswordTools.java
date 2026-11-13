package org.grove.vault.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Password generator, passphrase generator and offline strength estimate. */
public final class PasswordTools {
    private PasswordTools() {}

    private static final SecureRandom RNG = new SecureRandom();
    public static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    public static final String DIGITS = "0123456789";
    public static final String SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/~";
    public static final String AMBIGUOUS = "Il1O0o|`'\"";

    public static class Options {
        public int length = 16;
        public boolean upper = true, lower = true, digits = true, symbols = true;
        public boolean avoidAmbiguous = true;
        public boolean passphrase;
        public int words = 5;
        public String separator = "-";
        public boolean capitalize = true;
        public boolean addNumber = true;
    }

    public static String generate(Options o) {
        if (o.passphrase) return passphrase(o);
        List<String> sets = new ArrayList<String>();
        if (o.upper) sets.add(UPPER);
        if (o.lower) sets.add(LOWER);
        if (o.digits) sets.add(DIGITS);
        if (o.symbols) sets.add(SYMBOLS);
        if (sets.isEmpty()) sets.add(LOWER);
        if (o.avoidAmbiguous) {
            for (int i = 0; i < sets.size(); i++) sets.set(i, strip(sets.get(i), AMBIGUOUS));
        }
        StringBuilder all = new StringBuilder();
        for (String s : sets) all.append(s);
        int len = Math.max(o.length, sets.size());
        char[] out = new char[len];
        // guarantee one char from each set, then fill and shuffle
        for (int i = 0; i < sets.size(); i++) out[i] = pick(sets.get(i));
        for (int i = sets.size(); i < len; i++) out[i] = pick(all.toString());
        for (int i = len - 1; i > 0; i--) {
            int j = RNG.nextInt(i + 1);
            char t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return new String(out);
    }

    private static String passphrase(Options o) {
        StringBuilder b = new StringBuilder();
        int numberAt = o.addNumber ? RNG.nextInt(Math.max(1, o.words)) : -1;
        for (int i = 0; i < Math.max(2, o.words); i++) {
            if (i > 0) b.append(o.separator);
            String w = Words.LIST[RNG.nextInt(Words.LIST.length)];
            if (o.capitalize) w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            b.append(w);
            if (i == numberAt) b.append(RNG.nextInt(10));
        }
        return b.toString();
    }

    private static char pick(String s) {
        return s.charAt(RNG.nextInt(s.length()));
    }

    private static String strip(String s, String remove) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (remove.indexOf(c) < 0) b.append(c);
        return b.toString();
    }

    // ---------------- strength ----------------

    private static final Set<String> COMMON = new HashSet<String>();
    static {
        String[] c = {"password", "123456", "12345678", "qwerty", "abc123", "111111", "123123", "letmein", "welcome", "monkey",
                "dragon", "football", "iloveyou", "admin", "login", "princess", "sunshine", "master", "shadow", "superman",
                "passw0rd", "trustno1", "baseball", "starwars", "whatever", "freedom", "hello", "charlie", "qwertyuiop",
                "1q2w3e4r", "zaq12wsx", "asdfgh", "000000", "654321", "michael", "jordan", "computer", "secret", "google"};
        for (String s : c) COMMON.add(s);
    }

    /** Estimated entropy in bits, after penalties for common patterns. */
    public static double entropy(String p) {
        if (p == null || p.length() == 0) return 0;
        String low = p.toLowerCase(Locale.ROOT);
        String deleet = low.replace('0', 'o').replace('1', 'l').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('@', 'a').replace('$', 's');
        for (String c : COMMON) {
            if (low.equals(c) || deleet.equals(c)) return 4;
        }
        int pool = 0;
        boolean u = false, l = false, d = false, s = false, other = false;
        for (char ch : p.toCharArray()) {
            if (Character.isUpperCase(ch)) u = true;
            else if (Character.isLowerCase(ch)) l = true;
            else if (Character.isDigit(ch)) d = true;
            else if (ch < 128) s = true;
            else other = true;
        }
        if (u) pool += 26;
        if (l) pool += 26;
        if (d) pool += 10;
        if (s) pool += 33;
        if (other) pool += 100;
        // count effective length: repeated and sequential runs count less
        double eff = 0;
        Map<Character, Integer> seen = new HashMap<Character, Integer>();
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            double w = 1.0;
            if (i > 0) {
                int diff = ch - p.charAt(i - 1);
                if (diff == 0) w = 0.2;
                else if (diff == 1 || diff == -1) w = 0.4;
            }
            Integer n = seen.get(ch);
            if (n != null) w *= Math.max(0.3, 1.0 - 0.2 * n);
            seen.put(ch, n == null ? 1 : n + 1);
            eff += w;
        }
        double bits = eff * (Math.log(pool) / Math.log(2));
        for (String c : COMMON) {
            if (c.length() >= 5 && (low.contains(c) || deleet.contains(c))) bits -= c.length() * 3;
        }
        if (low.matches(".*(19|20)\\d\\d.*")) bits -= 6;
        return Math.max(0, bits);
    }

    /** 0 = very weak, 1 = weak, 2 = fair, 3 = good, 4 = strong. */
    public static int score(String p) {
        double e = entropy(p);
        if (e < 28) return 0;
        if (e < 40) return 1;
        if (e < 60) return 2;
        if (e < 80) return 3;
        return 4;
    }

    public static final String[] SCORE_NAMES = {"Very weak", "Weak", "Fair", "Good", "Strong"};

    public static boolean isWeak(String p) {
        return p != null && p.length() > 0 && score(p) <= 1;
    }
}
