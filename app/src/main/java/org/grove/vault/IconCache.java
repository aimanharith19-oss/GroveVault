package org.grove.vault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.grove.vault.core.Card;
import org.grove.vault.core.IconFinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Website icons for cards. Icons are downloaded straight from each website (only the domain name is used — no vault
 * data is ever sent, and no third-party icon service is involved), shrunk, and cached in private storage under
 * hashed file names. Cards linked to an installed Android app use that app's icon instead.
 */
public final class IconCache {
    private IconCache() {}

    private static final int SIZE = 256; // HD: sharp even on the big grid cards
    private static final long RETRY_FAILED_MS = 3L * 86400000L;
    private static final int MAX_PAGE = 400 * 1024, MAX_IMAGE = 768 * 1024;
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>((int) Math.min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Map<String, List<Ui.Cb<Bitmap>>> pending = new HashMap<String, List<Ui.Cb<Bitmap>>>();
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Key for a card's icon: "web:domain" or "app:package", or null if the card has neither. */
    public static String keyFor(Card c) {
        String d = IconFinder.domainFor(c);
        if (d.length() > 0 && IconFinder.isFetchable(d)) return "web:" + d;
        if (!c.apps.isEmpty()) return "app:" + c.apps.iterator().next();
        return null;
    }

    /** The icon if it's already in memory (no loading). */
    public static Bitmap peek(String key) {
        return key == null ? null : memory.get(key);
    }

    /** Delivers the icon on the main thread (immediately if cached). Never delivers null. */
    public static void load(final Context ctx, final String key, final Ui.Cb<Bitmap> cb) {
        if (key == null) return;
        Bitmap b = memory.get(key);
        if (b != null) {
            cb.on(b);
            return;
        }
        final Context app = ctx.getApplicationContext();
        if (key.startsWith("web:") && !Prefs.p(app).getBoolean("icons", true)) {
            // downloads off: still show icons already on disk
            Bitmap disk = readDisk(app, key);
            if (disk != null) {
                memory.put(key, disk);
                cb.on(disk);
            }
            return;
        }
        synchronized (pending) {
            List<Ui.Cb<Bitmap>> l = pending.get(key);
            if (l != null) {
                l.add(cb);
                return;
            }
            l = new ArrayList<Ui.Cb<Bitmap>>();
            l.add(cb);
            pending.put(key, l);
        }
        pool.execute(new Runnable() {
            public void run() {
                Bitmap r = null;
                try {
                    r = readDisk(app, key);
                    if (r == null) r = key.startsWith("app:") ? appIcon(app, key.substring(4)) : download(app, key.substring(4));
                } catch (Throwable ignored) {
                }
                final Bitmap fr = r;
                if (fr != null) memory.put(key, fr);
                final List<Ui.Cb<Bitmap>> cbs;
                synchronized (pending) {
                    cbs = pending.remove(key);
                }
                if (fr != null && cbs != null) {
                    main.post(new Runnable() {
                        public void run() {
                            for (Ui.Cb<Bitmap> c : cbs) c.on(fr);
                        }
                    });
                }
            }
        });
    }

    // ---------------------------------------------------------------- disk

    /** Pixel size of the last image fetchImage() decoded on this thread (to prefer sharp icons). */
    private static final ThreadLocal<Integer> LAST_SIZE = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() { return 0; }
    };

    private static File dir(Context c) {
        File d = new File(c.getNoBackupFilesDir(), "icons-hd"); // new folder: icons are fetched again in HD
        if (!d.exists()) {
            d.mkdirs();
            File old = new File(c.getNoBackupFilesDir(), "icons"); // the old low-resolution cache
            File[] fs = old.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            old.delete();
        }
        return d;
    }

    private static String hash(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format("%02x", h[i] & 0xff));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(key.hashCode());
        }
    }

    private static Bitmap readDisk(Context c, String key) {
        File f = new File(dir(c), hash(key) + ".png");
        if (!f.exists()) return null;
        return BitmapFactory.decodeFile(f.getPath());
    }

    private static void writeDisk(Context c, String key, Bitmap b) {
        try {
            FileOutputStream o = new FileOutputStream(new File(dir(c), hash(key) + ".png"));
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            o.close();
        } catch (IOException ignored) {}
    }

    private static boolean recentlyFailed(Context c, String key) {
        File f = new File(dir(c), hash(key) + ".fail");
        return f.exists() && System.currentTimeMillis() - f.lastModified() < RETRY_FAILED_MS;
    }

    private static void markFailed(Context c, String key) {
        try {
            File f = new File(dir(c), hash(key) + ".fail");
            new FileOutputStream(f).close();
            f.setLastModified(System.currentTimeMillis());
        } catch (IOException ignored) {}
    }

    public static void clear(Context c) {
        memory.evictAll();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    public static int cachedCount(Context c) {
        File[] fs = dir(c).listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".png")) n++;
        return n;
    }

    // ---------------------------------------------------------------- app icons

    private static Bitmap appIcon(Context c, String pkg) {
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, SIZE, SIZE);
            d.draw(cv);
            return b; // not stored on disk: apps update their icons
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- download

    private static Bitmap download(Context c, String domain) {
        String key = "web:" + domain;
        if (recentlyFailed(c, key)) return null;
        List<String> hosts = new ArrayList<String>();
        hosts.add(domain);
        String base = Card.baseDomain(domain);
        if (!base.equals(domain)) hosts.add(base);
        if (!domain.startsWith("www.")) hosts.add("www." + base);
        for (String host : hosts) {
            String[] page = fetchPage("https://" + host + "/");
            List<String> cands = IconFinder.candidates(page != null ? page[0] : "https://" + host + "/", page != null ? page[1] : null);
            int tries = 0, bestSize = 0;
            Bitmap best = null;
            for (String u : cands) {
                if (tries++ >= 6) break;
                Bitmap b = fetchImage(u);
                if (b == null) continue;
                int sz = LAST_SIZE.get();
                if (sz >= 144) { // big enough to look sharp: take it
                    writeDisk(c, key, b);
                    return b;
                }
                if (sz > bestSize) { best = b; bestSize = sz; } // keep looking for a sharper one
            }
            if (best != null) {
                writeDisk(c, key, best);
                return best;
            }
        }
        markFailed(c, key);
        return null;
    }

    /** GETs a URL following up to 5 redirects, https only. Returns {finalUrl} with an open connection, or null. */
    private static HttpURLConnection open(String url) throws IOException {
        for (int i = 0; i < 6; i++) {
            if (!url.startsWith("https://")) return null;
            if (!safeHost(new URL(url).getHost())) return null;
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setUseCaches(false);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GroveVault/1.2 (icon fetcher)");
            h.setRequestProperty("Accept-Language", "en");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                if (loc == null) return null;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                h.disconnect();
                return null;
            }
            return h;
        }
        return null;
    }

    /** Only public internet hosts: never local network, loopback or link-local addresses (even via redirects or DNS). */
    private static boolean safeHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (!IconFinder.isFetchable(h)) return false;
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(h)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress())
                    return false;
                byte[] b = a.getAddress();
                if (b.length == 4 && ((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64)) return false; // carrier-grade NAT
                if (b.length == 16 && ((b[0] & 0xfe) == 0xfc)) return false; // IPv6 unique local
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] read(HttpURLConnection h, int max) throws IOException {
        InputStream in = h.getInputStream();
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, Math.min(n, max - bo.size()));
                if (bo.size() >= max) break;
            }
            return bo.toByteArray();
        } finally {
            in.close();
            h.disconnect();
        }
    }

    private static String[] fetchPage(String url) {
        try {
            HttpURLConnection h = open(url);
            if (h == null) return null;
            String ct = String.valueOf(h.getContentType()).toLowerCase();
            String finalUrl = h.getURL().toString();
            if (!ct.contains("html")) {
                h.disconnect();
                return null;
            }
            return new String[]{finalUrl, new String(read(h, MAX_PAGE), StandardCharsets.UTF_8)};
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fetchImage(String url) {
        try {
            HttpURLConnection h = open(url);
            if (h == null) return null;
            byte[] data = read(h, MAX_IMAGE);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth < 16 || o.outHeight < 16) return null;
            LAST_SIZE.set(Math.min(o.outWidth, o.outHeight));
            int sample = 1;
            while (o.outWidth / (sample * 2) >= SIZE && o.outHeight / (sample * 2) >= SIZE) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            float scale = Math.min((float) SIZE / src.getWidth(), (float) SIZE / src.getHeight());
            int w = Math.round(src.getWidth() * scale), hh = Math.round(src.getHeight() * scale);
            int x = (SIZE - w) / 2, y = (SIZE - hh) / 2;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
            cv.drawBitmap(src, null, new Rect(x, y, x + w, y + hh), p);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }
}
