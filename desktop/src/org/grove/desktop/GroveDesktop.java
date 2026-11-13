package org.grove.desktop;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Grove Vault for Windows: start screen (open / create a vault) and app entry point. */
public final class GroveDesktop {
    public static final String VERSION = "1.3.0";

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        Ux.loadSettings();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            File f = args.length > 0 ? new File(args[0]) : null;
            start(f);
        });
    }

    public static List<Image> icons() {
        List<Image> l = new ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) l.add(Ux.appIcon(s));
        return l;
    }

    /** Shows the start window. If a file is given (e.g. double-clicked .grove), asks to unlock it straight away. */
    public static void start(File preselect) {
        JFrame f = new JFrame("Grove Vault");
        f.setIconImages(icons());
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(28, 40, 28, 40));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.insets = new Insets(6, 0, 6, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel logo = new JLabel(new ImageIcon(Ux.appIcon(88)));
        p.add(logo, c);
        JLabel name = new JLabel("Grove Vault", SwingConstants.CENTER);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 26f));
        p.add(name, c);
        JLabel sub = new JLabel("<html><center>Offline password manager. Opens .grove vaults and backups made by the<br>"
                + "Grove Vault Android app, and imports SafeInCloud, KeePass and CSV.</center></html>", SwingConstants.CENTER);
        sub.setForeground(Ux.SUB);
        p.add(sub, c);
        String recent = Ux.get("recent", null);
        if (recent != null && new File(recent).exists()) {
            JButton r = new JButton("Unlock " + new File(recent).getName());
            r.setFont(r.getFont().deriveFont(Font.BOLD, 15f));
            r.addActionListener(e -> unlock(f, new File(recent)));
            p.add(r, c);
            JLabel path = new JLabel(recent, SwingConstants.CENTER);
            path.setForeground(Ux.SUB);
            path.setFont(path.getFont().deriveFont(11f));
            p.add(path, c);
        }
        p.add(Ux.button("Open a vault file…", e -> {
            JFileChooser ch = chooser();
            if (ch.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) unlock(f, ch.getSelectedFile());
        }), c);
        p.add(Ux.button("Create a new vault…", e -> create(f)), c);
        JLabel tip = new JLabel("<html><center>New here? Create a vault, then use Import to bring in SafeInCloud, KeePass or CSV data.</center></html>", SwingConstants.CENTER);
        tip.setForeground(Ux.SUB);
        tip.setFont(tip.getFont().deriveFont(11f));
        p.add(tip, c);
        f.setContentPane(p);
        f.pack();
        f.setMinimumSize(new Dimension(520, f.getHeight()));
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        if (preselect != null && preselect.exists()) SwingUtilities.invokeLater(() -> unlock(f, preselect));
    }

    static JFileChooser chooser() {
        String recent = Ux.get("recent", null);
        JFileChooser ch = new JFileChooser(recent == null ? null : new File(recent).getParentFile());
        ch.setFileFilter(new FileNameExtensionFilter("Grove vault (*.grove)", "grove"));
        return ch;
    }

    static void unlock(JFrame owner, File file) {
        // PIN quick unlock (Windows, DPAPI-protected) if set up for this vault
        if (PinUnlock.enabled(file)) {
            while (PinUnlock.enabled(file)) {
                JPasswordField pf = new JPasswordField(12);
                Object[] opts = {"Unlock", "Use master password", "Cancel"};
                JPanel p = new JPanel(new BorderLayout(0, 6));
                p.add(new JLabel("PIN for " + file.getName()), BorderLayout.NORTH);
                p.add(pf, BorderLayout.CENTER);
                int r = JOptionPane.showOptionDialog(owner, p, "Unlock", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
                if (r == 2 || r < 0) return;
                if (r == 1) break;
                try {
                    byte[] dk = PinUnlock.open(file, new String(pf.getPassword()));
                    byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                    opened(owner, file, DesktopVault.open(file, org.grove.vault.core.VaultCrypto.keyFromRaw(data, dk), null));
                    return;
                } catch (org.grove.vault.core.VaultCrypto.WrongPasswordException e) {
                    Ux.error(owner, PinUnlock.enabled(file) ? "Wrong PIN. " + PinUnlock.triesLeft(file) + " tries left." : "Too many wrong PINs. Use your master password.");
                } catch (Exception e) {
                    PinUnlock.disable(file);
                    Ux.error(owner, "PIN unlock no longer works for this vault (" + e.getMessage() + "). Use your master password.");
                    break;
                }
            }
        }
        while (true) {
            long wait = Long.parseLong(Ux.get("lockout_until", "0")) - System.currentTimeMillis();
            if (wait > 0) {
                Ux.error(owner, "Too many wrong attempts. Try again in " + (wait / 1000 + 1) + " seconds.");
                return;
            }
            try {
                byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                DesktopUnlock.Result r = DesktopUnlock.unlock(owner, data, "Unlock " + file.getName());
                if (r == null) return;
                Ux.put("failed", 0);
                Ux.put("lockout_until", 0);
                boolean legacy = r.key.isLegacy();
                DesktopVault v = DesktopVault.open(file, r.key, r.password);
                java.util.Arrays.fill(r.password, '\0');
                if (legacy) Ux.info(owner, "Your vault was upgraded to stronger encryption (Argon2id).");
                opened(owner, file, v);
                return;
            } catch (org.grove.vault.core.VaultCrypto.WrongPasswordException e) {
                int fails = Ux.getInt("failed", 0) + 1;
                Ux.put("failed", fails);
                if (fails >= 3) Ux.put("lockout_until", System.currentTimeMillis() + Math.min(300, 5L << Math.min(10, fails - 3)) * 1000);
                int limit = Ux.getInt("erase_after", 0);
                if (limit > 0 && fails >= limit) {
                    file.delete();
                    new File(file.getPath() + ".bak").delete();
                    Ux.put("failed", 0);
                    PinUnlock.disable(file);
                    Ux.error(owner, "Too many wrong attempts. The vault file on this PC was erased. Restore it from a backup.");
                    owner.dispose();
                    start(null);
                    return;
                }
                Ux.error(owner, e.getMessage() + "." + (limit > 0 ? " " + (limit - fails) + " attempts left before the vault is erased." : ""));
            } catch (Exception e) {
                Ux.error(owner, "Can't open this file: " + e.getMessage());
                return;
            }
        }
    }

    private static void opened(JFrame owner, File file, DesktopVault v) {
        Ux.put("recent", file.getAbsolutePath());
        owner.dispose();
        new MainWindow(v).setVisible(true);
    }

    static void create(JFrame owner) {
        JFileChooser ch = chooser();
        ch.setSelectedFile(new File(ch.getCurrentDirectory(), "vault.grove"));
        if (ch.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        File file = ch.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".grove")) file = new File(file.getPath() + ".grove");
        if (file.exists() && !Ux.confirm(owner, file.getName() + " already exists. Replace it with a new, empty vault?")) return;
        char[] pw = Ux.askNewPassword(owner, "Choose a master password", 8);
        if (pw == null) return;
        try {
            DesktopVault v = DesktopVault.create(file, pw);
            Ux.put("recent", file.getAbsolutePath());
            owner.dispose();
            new MainWindow(v).setVisible(true);
            Ux.info(null, "Vault created. Remember the master password: it can't be recovered.");
        } catch (Exception e) {
            Ux.error(owner, "Couldn't create the vault: " + e.getMessage());
        }
    }
}
