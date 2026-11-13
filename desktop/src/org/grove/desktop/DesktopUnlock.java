package org.grove.desktop;

import org.grove.vault.core.VaultCrypto;

import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Password + key file + security key dialog for unlocking a .grove file (Windows/desktop). */
public final class DesktopUnlock {
    private DesktopUnlock() {}

    /** Result of a successful unlock (password kept only long enough to upgrade old vaults). */
    public static final class Result {
        public final VaultCrypto.Key key;
        public final char[] password;
        Result(VaultCrypto.Key k, char[] p) { key = k; password = p; }
    }

    /** Asks for whatever the file needs. Returns null if cancelled. Throws WrongPasswordException etc. */
    public static Result unlock(Component parent, byte[] file, String title) throws Exception {
        VaultCrypto.Header h = VaultCrypto.readHeader(file);
        boolean needKf = h.version == 2 && h.keyFile;
        boolean pwSlot = false;
        for (VaultCrypto.Slot s : h.slots) if (s.type == 1) pwSlot = true;
        boolean needSk = h.version == 2 && h.securityKey() && !pwSlot;

        JPasswordField pw = new JPasswordField(26);
        JTextField kf = new JTextField(Ux.get("keyfile_path", ""), 26);
        JButton browse = Ux.button("Browse…", e -> {
            JFileChooser ch = new JFileChooser(kf.getText().isEmpty() ? null : new File(kf.getText()).getParentFile());
            if (ch.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) kf.setText(ch.getSelectedFile().getPath());
        });
        JCheckBox remember = new JCheckBox("Remember the key file location", !Ux.get("keyfile_path", "").isEmpty());
        JPanel kp = new JPanel(new BorderLayout(4, 0));
        kp.add(kf, BorderLayout.CENTER);
        kp.add(browse, BorderLayout.EAST);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<JComponent> fields = new java.util.ArrayList<>();
        labels.add("Master password");
        fields.add(pw);
        if (needKf) {
            labels.add("Key file");
            fields.add(kp);
            labels.add("");
            fields.add(remember);
        }
        if (needSk) {
            labels.add("");
            JLabel l = new JLabel("🔐 Security key needed: " + String.join(" or ", h.securityKeyLabels()) + ". Plug it in.");
            fields.add(l);
        }
        JPanel p = Ux.form(labels.toArray(new String[0]), fields.toArray(new JComponent[0]));
        JOptionPane op = new JOptionPane(p, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog d = op.createDialog(parent, title);
        d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            public void windowGainedFocus(java.awt.event.WindowEvent e) { pw.requestFocusInWindow(); }
        });
        pw.addActionListener(e -> { op.setValue(JOptionPane.OK_OPTION); d.dispose(); });
        d.setVisible(true);
        Object v = op.getValue();
        if (!(v instanceof Integer) || (Integer) v != JOptionPane.OK_OPTION) return null;
        char[] password = pw.getPassword();
        byte[] keyFile = null;
        if (needKf) {
            File f = new File(kf.getText().trim());
            if (!f.isFile()) throw new Exception("Choose the vault's key file.");
            keyFile = Files.readAllBytes(f.toPath());
            Ux.put("keyfile_path", remember.isSelected() ? f.getAbsolutePath() : null);
        }
        byte[] response = needSk ? securityKey(parent, h.challenge) : null;
        if (needSk && response == null) return null;
        Window w = SwingUtilities.getWindowAncestor(parent);
        if (w != null) w.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            final byte[] r = response;
            VaultCrypto.Key k = VaultCrypto.unlock(file, password, new VaultCrypto.Factors(keyFile, r == null ? null : c -> r));
            Ux.put("has_factors", needKf || needSk ? "1" : "0");
            return new Result(k, password);
        } finally {
            if (w != null) w.setCursor(Cursor.getDefaultCursor());
        }
    }

    /** Runs the YubiKey challenge with a small "touch your key" window. Returns null if cancelled/failed. */
    public static byte[] securityKey(Component parent, byte[] challenge) {
        while (true) {
            Window owner = parent == null ? null : (parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent));
            JDialog d = new JDialog(owner, "Security key", Dialog.ModalityType.APPLICATION_MODAL);
            JLabel msg = new JLabel("<html>🔐 Plug in your YubiKey.<br>If it blinks, touch it.</html>");
            msg.setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 24));
            JButton cancel = new JButton("Cancel");
            JPanel b = new JPanel();
            b.add(cancel);
            d.getContentPane().add(msg, BorderLayout.CENTER);
            d.getContentPane().add(b, BorderLayout.SOUTH);
            d.pack();
            d.setLocationRelativeTo(owner);
            AtomicReference<byte[]> res = new AtomicReference<>();
            AtomicReference<String> err = new AtomicReference<>();
            boolean[] cancelled = {false};
            cancel.addActionListener(e -> { cancelled[0] = true; d.dispose(); });
            Thread t = new Thread(() -> {
                try {
                    res.set(WinHelper.yubikey(challenge, s -> SwingUtilities.invokeLater(() -> msg.setText("<html>🔐 " + s + "</html>"))));
                } catch (Exception e) {
                    err.set(e.getMessage());
                }
                SwingUtilities.invokeLater(d::dispose);
            });
            t.setDaemon(true);
            t.start();
            d.setVisible(true);
            if (cancelled[0]) return null;
            if (res.get() != null) return res.get();
            int r = JOptionPane.showConfirmDialog(parent, (err.get() == null ? "The key didn't answer." : err.get()) + "\n\nTry again?", "Security key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
        }
    }
}
