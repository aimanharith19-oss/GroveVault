package org.grove.vault.core;

import java.net.URLDecoder;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 time-based one-time passwords. Accepts otpauth:// URIs, plain base32 secrets and KeePass "seed|settings". */
public class Totp {
    public byte[] secret;
    public int digits = 6;
    public int period = 30;
    public String algorithm = "SHA1";
    public boolean steam;
    public String issuer = "";
    public String account = "";

    public static Totp parse(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() == 0) return null;
        Totp t = new Totp();
        try {
            if (s.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
                String rest = s.substring(10);
                int q = rest.indexOf('?');
                String path = q >= 0 ? rest.substring(0, q) : rest;
                int slash = path.indexOf('/');
                if (slash >= 0) {
                    String label = URLDecoder.decode(path.substring(slash + 1), "UTF-8");
                    int colon = label.indexOf(':');
                    if (colon >= 0) {
                        t.issuer = label.substring(0, colon).trim();
                        t.account = label.substring(colon + 1).trim();
                    } else t.account = label;
                }
                if (q >= 0) {
                    for (String kv : rest.substring(q + 1).split("&")) {
                        int eq = kv.indexOf('=');
                        if (eq < 0) continue;
                        String k = kv.substring(0, eq).toLowerCase(Locale.ROOT);
                        String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                        if (k.equals("secret")) t.secret = base32(v);
                        else if (k.equals("digits")) t.digits = Integer.parseInt(v);
                        else if (k.equals("period")) t.period = Integer.parseInt(v);
                        else if (k.equals("algorithm")) t.algorithm = v.toUpperCase(Locale.ROOT);
                        else if (k.equals("issuer")) t.issuer = v;
                        else if (k.equals("encoder") && v.equalsIgnoreCase("steam")) t.steam = true;
                    }
                }
            } else {
                t.secret = base32(s);
            }
        } catch (Exception e) {
            return null;
        }
        if (t.secret == null || t.secret.length == 0) return null;
        if (t.digits < 4 || t.digits > 10) t.digits = 6;
        if (t.period <= 0) t.period = 30;
        if (t.steam) t.digits = 5;
        return t;
    }

    public String code(long timeMillis) {
        long counter = (timeMillis / 1000L) / period;
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        try {
            String alg = "HmacSHA1";
            if (algorithm.equals("SHA256")) alg = "HmacSHA256";
            else if (algorithm.equals("SHA512")) alg = "HmacSHA512";
            Mac mac = Mac.getInstance(alg);
            mac.init(new SecretKeySpec(secret, "RAW"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            if (steam) {
                String chars = "23456789BCDFGHJKMNPQRTVWXY";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    sb.append(chars.charAt(bin % chars.length()));
                    bin /= chars.length();
                }
                return sb.toString();
            }
            long mod = 1;
            for (int i = 0; i < digits; i++) mod *= 10;
            String c = String.valueOf(bin % mod);
            while (c.length() < digits) c = "0" + c;
            return c;
        } catch (Exception e) {
            return "------";
        }
    }

    public int secondsLeft(long timeMillis) {
        return (int) (period - ((timeMillis / 1000L) % period));
    }

    public static byte[] base32(String s) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        s = s.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0, bits = 0, idx = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = a.indexOf(s.charAt(i));
            if (v < 0) throw new IllegalArgumentException("bad base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }

    public static String toBase32(byte[] b) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : b) {
            buffer = (buffer << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(a.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(a.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    /** Builds an otpauth URI (used when importing KeePass "TOTP Seed" fields). */
    public static String uri(String base32Secret, int period, int digits, String algorithm) {
        StringBuilder b = new StringBuilder("otpauth://totp/Imported?secret=").append(base32Secret.replace(" ", ""));
        if (period != 30) b.append("&period=").append(period);
        if (digits != 6) b.append("&digits=").append(digits);
        if (algorithm != null && !algorithm.equalsIgnoreCase("SHA1")) b.append("&algorithm=").append(algorithm.toUpperCase(Locale.ROOT));
        return b.toString();
    }
}
