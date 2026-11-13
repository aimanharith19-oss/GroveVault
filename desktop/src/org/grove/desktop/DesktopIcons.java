package org.grove.desktop;

import org.grove.vault.core.Card;
import org.grove.vault.core.IconFinder;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Website icons for the Windows app, same rules as Android: fetched straight from the card's website over HTTPS,
 * only public hosts, cached under hashed names in %APPDATA%\GroveVault\icons.
 */
public final class DesktopIcons {
    private DesktopIcons() {}

    private static final int SIZE = 96;
    private static final Map<String, Image> memory = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, List<Consumer<Image>>> pending = new HashMap<>();
    private static final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "icons");
        t.setDaemon(true);
        return t;
    });

    public static boolean enabled() {
        return "1".equals(Ux.get("icons", "1"));
    }

    public static Image cached(Card c) {
        String d = IconFinder.domainFor(c);
        return d.isEmpty() ? null : memory.get(d);
    }

    /** Calls back on the Swing thread when the card's icon is available (never with null). */
    public static void load(Card c, Consumer<Image> cb) {
        String d = IconFinder.domainFor(c);
        if (d.isEmpty() || !IconFinder.isFetchable(d)) return;
        Image m = memory.get(d);
        if (m != null) {
            cb.accept(m);
            return;
        }
        synchronized (pending) {
            List<Consumer<Image>> l = pending.get(d);
            if (l != null) { l.add(cb); return; }
            l = new ArrayList<>();
            l.add(cb);
            pending.put(d, l);
        }
        pool.execute(() -> {
            Image img = null;
            try {
                img = readDisk(d);
                if (img == null && enabled()) img = download(d);
            } catch (Throwable ignored) {}
            List<Consumer<Image>> cbs;
            synchronized (pending) { cbs = pending.remove(d); }
            if (img != null) {
                memory.put(d, img);
                Image fi = img;
                SwingUtilities.invokeLater(() -> { for (Consumer<Image> x : cbs) x.accept(fi); });
            }
        });
    }

    private static File dir() {
        File d = new File(Ux.appDir(), "icons");
        d.mkdirs();
        return d;
    }

    private static String hash(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("web:" + s).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format("%02x", h[i] & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static Image readDisk(String d) {
        try {
            File f = new File(dir(), hash(d) + ".png");
            return f.exists() ? ImageIO.read(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear() {
        memory.clear();
        File[] fs = dir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static Image download(String domain) throws Exception {
        File fail = new File(dir(), hash(domain) + ".fail");
        if (fail.exists() && System.currentTimeMillis() - fail.lastModified() < 3L * 86400000L) return null;
        List<String> hosts = new ArrayList<>();
        hosts.add(domain);
        String base = Card.baseDomain(domain);
        if (!base.equals(domain)) hosts.add(base);
        if (!domain.startsWith("www.")) hosts.add("www." + base);
        for (String host : hosts) {
            String[] page = fetchPage("https://" + host + "/");
            List<String> c = IconFinder.candidates(page != null ? page[0] : "https://" + host + "/", page != null ? page[1] : null);
            int tries = 0;
            for (String u : c) {
                if (tries++ >= 5) break;
                BufferedImage img = fetchImage(u);
                if (img != null) {
                    ImageIO.write(img, "png", new File(dir(), hash(domain) + ".png"));
                    return img;
                }
            }
        }
        fail.createNewFile();
        fail.setLastModified(System.currentTimeMillis());
        return null;
    }

    static boolean safeHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (!IconFinder.isFetchable(h)) return false;
        try {
            for (InetAddress a : InetAddress.getAllByName(h)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return false;
                byte[] b = a.getAddress();
                if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) return false;
                if (b.length == 16 && (b[0] & 0xfe) == 0xfc) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 6; i++) {
            if (!url.startsWith("https://") || !safeHost(new URL(url).getHost())) return null;
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0) GroveVault/1.3 (icon fetcher)");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                if (loc == null) return null;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) { h.disconnect(); return null; }
            return h;
        }
        return null;
    }

    private static byte[] read(HttpURLConnection h, int max) throws Exception {
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    private static String[] fetchPage(String url) {
        try {
            HttpURLConnection h = open(url);
            if (h == null) return null;
            String ct = String.valueOf(h.getContentType()).toLowerCase();
            String fin = h.getURL().toString();
            if (!ct.contains("html")) { h.disconnect(); return null; }
            return new String[]{fin, new String(read(h, 400 * 1024), StandardCharsets.UTF_8)};
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage fetchImage(String url) {
        try {
            HttpURLConnection h = open(url);
            if (h == null) return null;
            byte[] data = read(h, 768 * 1024);
            BufferedImage img = decode(data);
            if (img == null || img.getWidth() < 16) return null;
            return scale(img);
        } catch (Throwable e) {
            return null;
        }
    }

    /** PNG/JPEG/GIF/BMP via ImageIO, plus Windows .ico (largest PNG or 32-bit BMP entry). */
    static BufferedImage decode(byte[] data) throws Exception {
        if (data.length > 6 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0) return decodeIco(data);
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    static BufferedImage decodeIco(byte[] d) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int count = b.getShort(4) & 0xffff;
        int best = -1, bestSize = 0;
        for (int i = 0; i < count && 6 + 16 * i + 16 <= d.length; i++) {
            int w = d[6 + 16 * i] & 0xff;
            if (w == 0) w = 256;
            if (w > bestSize) { bestSize = w; best = i; }
        }
        if (best < 0) return null;
        int size = b.getInt(6 + 16 * best + 8), off = b.getInt(6 + 16 * best + 12);
        if (off < 0 || size <= 0 || off + size > d.length) return null;
        byte[] e = Arrays.copyOfRange(d, off, off + size);
        if (e.length > 8 && (e[0] & 0xff) == 0x89 && e[1] == 'P') return ImageIO.read(new ByteArrayInputStream(e));
        // BITMAPINFOHEADER entry: height is doubled (XOR + AND masks); handle 32-bit BGRA
        ByteBuffer h = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN);
        int hdr = h.getInt(0), w = h.getInt(4), hh = h.getInt(8) / 2, bpp = h.getShort(14);
        if (bpp != 32 || w <= 0 || hh <= 0 || w > 512 || hh > 512) return null;
        BufferedImage img = new BufferedImage(w, hh, BufferedImage.TYPE_INT_ARGB);
        int p = hdr;
        for (int y = hh - 1; y >= 0; y--)
            for (int x = 0; x < w; x++, p += 4) {
                if (p + 3 >= e.length) return img;
                int bl = e[p] & 0xff, g = e[p + 1] & 0xff, r = e[p + 2] & 0xff, a = e[p + 3] & 0xff;
                img.setRGB(x, y, a << 24 | r << 16 | g << 8 | bl);
            }
        return img;
    }

    private static BufferedImage scale(BufferedImage src) {
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = Math.min((double) SIZE / src.getWidth(), (double) SIZE / src.getHeight());
        int w = (int) Math.round(src.getWidth() * s), h = (int) Math.round(src.getHeight() * s);
        g.drawImage(src, (SIZE - w) / 2, (SIZE - h) / 2, w, h, null);
        g.dispose();
        return out;
    }
}
