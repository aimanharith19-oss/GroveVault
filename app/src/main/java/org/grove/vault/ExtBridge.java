package org.grove.vault;

import android.content.Context;
import android.util.Base64;

import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Totp;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bridge for the GroveVault browser extension (Vivaldi and other Chromium browsers on this phone).
 *
 * Security:
 *  - listens on 127.0.0.1 only (not reachable from Wi-Fi or the internet), and only while paired;
 *  - every request and reply is AES-256-GCM encrypted with a key derived from the pairing code, which only the
 *    paired extension knows; anything else (another app on the phone probing the port) gets nothing;
 *  - requests carry a timestamp and a random nonce: old or repeated requests are refused (no replay);
 *  - passwords are only returned while the vault is unlocked, and "logins" only for the site the tab is on;
 *  - after 20 bad requests the bridge shuts itself off until a new code is made.
 */
public final class ExtBridge {
    private ExtBridge() {}

    public static final int PORT = 47920;
    private static final String KEY = "ext_code";
    private static final int MAX_BODY = 64 * 1024;
    private static volatile ServerSocket server;
    private static volatile int bad;
    private static final LinkedHashSet<String> seen = new LinkedHashSet<String>();

    public static String code(Context c) { return Prefs.p(c).getString(KEY, ""); }
    public static boolean paired(Context c) { return code(c).length() > 0; }

    public static String newCode(Context c) {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20; i++) { // 100 bits
            if (i > 0 && i % 5 == 0) b.append('-');
            b.append(abc.charAt(r.nextInt(abc.length())));
        }
        stop();
        Prefs.put(c, KEY, b.toString());
        bad = 0;
        start(c);
        return b.toString();
    }

    public static void unlink(Context c) {
        Prefs.put(c, KEY, null);
        stop();
    }

    static byte[] keyFor(String code) throws Exception {
        String norm = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return MessageDigest.getInstance("SHA-256").digest(("GroveVault-ext-v1|" + norm).getBytes(StandardCharsets.UTF_8));
    }

    public static synchronized void start(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (server != null || !paired(c)) return;
        try {
            final ServerSocket ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            server = ss;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    while (!ss.isClosed()) {
                        try {
                            Socket s = ss.accept();
                            try {
                                s.setSoTimeout(5000);
                                handle(c, s);
                            } finally {
                                s.close();
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }, "grove-ext");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            server = null;
        }
    }

    public static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    private static void handle(Context c, Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int prev3 = 0, ch;
        while ((ch = in.read()) >= 0) {
            head.write(ch);
            prev3 = (prev3 << 8 | ch) & 0xFFFFFFFF;
            if (prev3 == 0x0D0A0D0A) break;
            if (head.size() > 8192) return;
        }
        String h = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        String first = h.split("\r\n", 2)[0];
        OutputStream out = s.getOutputStream();
        if (first.startsWith("OPTIONS")) { reply(out, 204, ""); return; }
        if (!first.startsWith("POST /v1 ")) { reply(out, 404, ""); return; }
        int len = 0;
        for (String line : h.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        if (len <= 0 || len > MAX_BODY) { reply(out, 413, ""); return; }
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(body, got, len - got);
            if (n < 0) return;
            got += n;
        }
        byte[] key = keyFor(code(c));
        JSONObject req;
        try {
            JSONObject env = new JSONObject(new String(body, StandardCharsets.UTF_8));
            req = new JSONObject(new String(open(key, env), StandardCharsets.UTF_8));
            long t = req.getLong("t");
            String nonce = req.getString("n");
            if (Math.abs(System.currentTimeMillis() - t) > 120000) throw new SecurityException("stale");
            synchronized (seen) {
                if (!seen.add(nonce)) throw new SecurityException("replay");
                if (seen.size() > 512) seen.remove(seen.iterator().next());
            }
        } catch (Exception e) {
            if (++bad >= 20) stop(); // someone is guessing: switch off until a new code is made
            reply(out, 403, "");
            return;
        }
        JSONObject res = answer(c, req);
        res.put("n", req.getString("n")); // binds the reply to this request
        reply(out, 200, seal(key, res.toString().getBytes(StandardCharsets.UTF_8)).toString());
    }

    private static JSONObject answer(Context c, JSONObject req) throws Exception {
        JSONObject r = new JSONObject();
        String op = req.optString("op");
        r.put("ok", true);
        r.put("locked", !Session.isUnlocked());
        if ("ping".equals(op) || !Session.isUnlocked()) return r;
        JSONArray items = new JSONArray();
        if ("logins".equals(op)) {
            for (Card card : forUrl(req.optString("url"))) items.put(item(card, true));
        } else if ("search".equals(op)) {
            String q = req.optString("q").toLowerCase(Locale.ROOT).trim();
            int n = 0;
            for (Card card : Session.vault().activeCards()) {
                if (card.template || card.login().length() + card.password().length() == 0) continue;
                String hay = (card.title + " " + card.login() + " " + card.website()).toLowerCase(Locale.ROOT);
                if (q.length() > 0 && !hay.contains(q)) continue;
                items.put(item(card, false));
                if (++n >= 50) break;
            }
        } else if ("get".equals(op)) {
            Card card = Session.card(req.optString("id"));
            if (card != null && !card.deleted) items.put(item(card, true));
        }
        r.put("items", items);
        return r;
    }

    /** Cards whose website matches the tab's site (same host, or same registered domain). */
    static List<Card> forUrl(String url) {
        List<Card> out = new ArrayList<Card>();
        String host = Card.domainOf(url);
        if (host == null || host.length() == 0) return out;
        String base = Card.baseDomain(host);
        List<Card> exact = new ArrayList<Card>();
        for (Card c : Session.vault().activeCards()) {
            if (c.template) continue;
            int score = 0;
            for (Field f : c.fields) {
                if (!Field.WEBSITE.equals(f.type) || f.value.length() == 0) continue;
                String d = Card.domainOf(f.value);
                if (d.equals(host)) score = 2;
                else if (score == 0 && Card.baseDomain(d).equals(base)) score = 1;
            }
            if (score == 2) exact.add(c);
            else if (score == 1) out.add(c);
        }
        exact.addAll(out);
        return exact;
    }

    private static JSONObject item(Card c, boolean secrets) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", c.id);
        o.put("title", c.title);
        o.put("user", c.login());
        o.put("url", c.website());
        if (secrets) {
            o.put("pass", c.password());
            for (Field f : c.fields)
                if (Field.OTP.equals(f.type) && f.value.length() > 0) {
                    Totp t = Totp.parse(f.value);
                    if (t != null) o.put("otp", t.code(System.currentTimeMillis()));
                    break;
                }
        }
        return o;
    }

    // ------------------------------------------------------------ envelope: {"iv": b64, "ct": b64}, AES-256-GCM
    private static final byte[] AAD = "gv1".getBytes(StandardCharsets.UTF_8);

    static byte[] open(byte[] key, JSONObject env) throws Exception {
        byte[] iv = Base64.decode(env.getString("iv"), Base64.NO_WRAP);
        byte[] ct = Base64.decode(env.getString("ct"), Base64.NO_WRAP);
        if (iv.length != 12) throw new SecurityException("iv");
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        ci.updateAAD(AAD);
        return ci.doFinal(ct);
    }

    static JSONObject seal(byte[] key, byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        ci.updateAAD(AAD);
        JSONObject o = new JSONObject();
        o.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        o.put("ct", Base64.encodeToString(ci.doFinal(plain), Base64.NO_WRAP));
        return o;
    }

    private static void reply(OutputStream out, int code, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: content-type\r\nAccess-Control-Allow-Methods: POST\r\n"
                + "Access-Control-Allow-Private-Network: true\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }
}
