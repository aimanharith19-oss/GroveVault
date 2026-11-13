package org.grove.desktop;

import org.grove.vault.core.PasswordTools;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Shared look, settings, clipboard and small dialogs for the desktop app. */
public final class Ux {
    private Ux() {}

    public static final Color ACCENT = new Color(0x2E7D5B);
    public static final Color WARN = new Color(0xB26A00);
    public static final Color DANGER = new Color(0xC62828);
    public static final Color SUB = new Color(0x5B6A61);
    public static final int[] CARD_COLORS = {
            0x2E7D5B, 0x1E88E5, 0x8E24AA, 0xE53935, 0xF4511E, 0x00897B,
            0x6D4C41, 0x3949AB, 0xC0CA33, 0x00ACC1, 0xD81B60, 0x546E7A};

    // ---------------------------------------------------------------- settings (%APPDATA%\GroveVault\settings.properties)

    private static final Properties props = new Properties();
    private static File settingsFile;

    public static File appDir() {
        String base = System.getenv("APPDATA");
        File d = base != null ? new File(base, "GroveVault") : new File(System.getProperty("user.home"), ".grovevault");
        d.mkdirs();
        return d;
    }

    public static void loadSettings() {
        settingsFile = new File(appDir(), "settings.properties");
        try (FileInputStream in = new FileInputStream(settingsFile)) {
            props.load(in);
        } catch (Exception ignored) {}
    }

    public static String get(String k, String def) {
        return props.getProperty(k, def);
    }

    public static int getInt(String k, int def) {
        try { return Integer.parseInt(props.getProperty(k)); } catch (Exception e) { return def; }
    }

    public static void put(String k, Object v) {
        if (v == null) props.remove(k);
        else props.setProperty(k, v.toString());
        try (FileOutputStream out = new FileOutputStream(settingsFile)) {
            props.store(out, "Grove Vault settings");
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- clipboard

    private static Timer clipTimer;

    /** Copies text and clears it after the configured delay if it's still on the clipboard. */
    public static void copy(Component parent, String label, String text) {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(new StringSelection(text), null);
        int secs = getInt("clip_secs", 30);
        if (clipTimer != null) clipTimer.stop();
        if (secs > 0) {
            clipTimer = new Timer(secs * 1000, e -> {
                try {
                    Object cur = cb.getData(DataFlavor.stringFlavor);
                    if (text.equals(cur)) cb.setContents(new StringSelection(""), null);
                } catch (Exception ignored) {}
            });
            clipTimer.setRepeats(false);
            clipTimer.start();
        }
        status(label + " copied" + (secs > 0 ? " · clipboard clears in " + secs + " s" : ""));
    }

    public static JLabel statusBar;

    public static void status(String s) {
        if (statusBar != null) statusBar.setText(" " + s);
    }

    // ---------------------------------------------------------------- dialogs

    public static char[] askPassword(Component parent, String title, String message) {
        JPasswordField pf = new JPasswordField(24);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel(message), BorderLayout.NORTH);
        p.add(pf, BorderLayout.CENTER);
        JOptionPane op = new JOptionPane(p, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog d = op.createDialog(parent, title);
        d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            public void windowGainedFocus(java.awt.event.WindowEvent e) { pf.requestFocusInWindow(); }
        });
        pf.addActionListener(e -> { op.setValue(JOptionPane.OK_OPTION); d.dispose(); });
        d.setVisible(true);
        Object v = op.getValue();
        if (v instanceof Integer && (Integer) v == JOptionPane.OK_OPTION) return pf.getPassword();
        return null;
    }

    /** Asks for a new password twice. */
    public static char[] askNewPassword(Component parent, String title, int minLength) {
        JPasswordField a = new JPasswordField(24), b = new JPasswordField(24);
        JLabel strength = new JLabel(" ");
        a.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void upd() {
                String s = new String(a.getPassword());
                if (s.isEmpty()) { strength.setText(" "); return; }
                int sc = PasswordTools.score(s);
                strength.setText(PasswordTools.SCORE_NAMES[sc] + " · ~" + (int) PasswordTools.entropy(s) + " bits");
                strength.setForeground(sc <= 1 ? DANGER : sc == 2 ? WARN : ACCENT);
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        });
        JPanel p = form(new String[]{"Password", "", "Repeat"}, new JComponent[]{a, strength, b});
        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            char[] x = a.getPassword(), y = b.getPassword();
            if (x.length < minLength) error(parent, "Use at least " + minLength + " characters.");
            else if (!java.util.Arrays.equals(x, y)) error(parent, "The passwords don't match.");
            else return x;
        }
    }

    public static JPanel form(String[] labels, JComponent[] fields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labels.length; i++) {
            c.gridx = 0; c.gridy = i; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            p.add(new JLabel(labels[i]), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            p.add(fields[i], c);
        }
        return p;
    }

    public static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Grove Vault", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Grove Vault", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String msg) {
        return JOptionPane.showConfirmDialog(parent, msg, "Grove Vault", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    public static JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.addActionListener(l);
        b.setFocusable(false);
        return b;
    }

    public static JComponent pad(JComponent c, int px) {
        c.setBorder(new EmptyBorder(px, px, px, px));
        return c;
    }

    /** Coloured circle with the card's first letter. */
    public static Icon avatar(String title, int color, int size) {
        return new Icon() {
            public void paintIcon(Component comp, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(new Color(CARD_COLORS[Math.abs(color) % CARD_COLORS.length]));
                g.fillOval(x, y, size, size);
                String t = title == null || title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase();
                g.setColor(Color.WHITE);
                g.setFont(g.getFont().deriveFont(Font.BOLD, size * 0.45f));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(t, x + (size - fm.stringWidth(t)) / 2, y + (size - fm.getHeight()) / 2 + fm.getAscent());
                g.dispose();
            }
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
        };
    }

    /** A website icon on a white circle. */
    public static Icon roundIcon(Image img, int size) {
        return new Icon() {
            public void paintIcon(Component comp, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setColor(Color.WHITE);
                g.fillOval(x, y, size, size);
                g.setColor(new Color(0, 0, 0, 40));
                g.drawOval(x, y, size - 1, size - 1);
                int pad = Math.round(size * 0.17f);
                g.setClip(new java.awt.geom.Ellipse2D.Float(x, y, size, size));
                g.drawImage(img, x + pad, y + pad, size - 2 * pad, size - 2 * pad, null);
                g.dispose();
            }
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
        };
    }

    /** App icon (three trees on a shield) drawn at any size. */
    public static Image appIcon(int size) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 108.0, size / 108.0);
        g.setColor(new Color(0x1F4D3A));
        g.fillRoundRect(4, 4, 100, 100, 28, 28);
        g.setColor(new Color(0xE8F5EC));
        Polygon shield = new Polygon();
        double[][] s = {{54, 22}, {80, 31}, {80, 52}, {72, 72}, {54, 86}, {36, 72}, {28, 52}, {28, 31}};
        for (double[] pt : s) shield.addPoint((int) pt[0], (int) pt[1]);
        g.fillPolygon(shield);
        g.setColor(new Color(0x5DBB8A));
        g.fillPolygon(new int[]{42, 48, 45, 50, 34, 39, 36}, new int[]{44, 56, 56, 64, 64, 56, 56}, 7);
        g.fillPolygon(new int[]{66, 72, 69, 74, 58, 63, 60}, new int[]{44, 56, 56, 64, 64, 56, 56}, 7);
        g.setColor(new Color(0x2E7D5B));
        g.fillPolygon(new int[]{54, 62, 58, 64, 57, 57, 51, 51, 44, 50, 46}, new int[]{36, 50, 50, 60, 60, 70, 70, 60, 60, 50, 50}, 11);
        g.dispose();
        return img;
    }
}
