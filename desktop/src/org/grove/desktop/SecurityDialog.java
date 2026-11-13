package org.grove.desktop;

import org.grove.vault.core.VaultCrypto;

import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.*;

/** Change master password, key file and security keys (YubiKey) on Windows. Always rotates the data key. */
public final class SecurityDialog {
    private SecurityDialog() {}

    public static void show(MainWindow w, DesktopVault dv) {
        VaultCrypto.Header h;
        try {
            h = VaultCrypto.readHeader(Files.readAllBytes(dv.file.toPath()));
        } catch (Exception e) {
            Ux.error(w, e.getMessage());
            return;
        }
        boolean kf = h.version == 2 && h.keyFile, sk = h.version == 2 && h.securityKey();
        String status = "<html><b>Your vault is unlocked with:</b><br>✅ Master password"
                + (h.version == 2 ? " (Argon2id, " + h.memoryKiB / 1024 + " MiB)" : " (older format, upgrades on next password unlock)") + "<br>"
                + (kf ? "🔑 Key file" : "Key file: not used") + "<br>"
                + (sk ? "🔐 Security key: " + String.join(", ", h.securityKeyLabels()) : "Security key: not used")
                + "<br><br>YubiKeys need HMAC-SHA1 challenge-response in slot 2 (YubiKey Manager → Applications → OTP → Long Touch → Challenge-response).</html>";
        JCheckBox quick = new JCheckBox("Let the PIN skip the key file and security key (less secure)", "1".equals(Ux.get("quick_with_factors", "0")));
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.add(new JLabel(status), BorderLayout.CENTER);
        p.add(quick, BorderLayout.SOUTH);
        Object[] opts = {"Change password, key file or keys…", "Close"};
        int r = JOptionPane.showOptionDialog(w, p, "Security key & key file", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[1]);
        Ux.put("quick_with_factors", quick.isSelected() ? "1" : "0");
        if (!PinUnlock.allowed()) PinUnlock.disable(dv.file);
        if (r == 0) wizard(w, dv);
    }

    private static void wizard(MainWindow w, DesktopVault dv) {
        // 1. confirm it's you, with the current unlock method
        DesktopUnlock.Result cur;
        try {
            cur = DesktopUnlock.unlock(w, Files.readAllBytes(dv.file.toPath()), "Confirm it's you");
            if (cur == null) return;
            if (!Arrays.equals(cur.key.key, dv.key.key)) {
                Ux.error(w, "That doesn't unlock this vault.");
                return;
            }
            cur.key.wipe();
        } catch (VaultCrypto.WrongPasswordException e) {
            Ux.error(w, e.getMessage());
            return;
        } catch (Exception e) {
            Ux.error(w, e.getMessage());
            return;
        }
        // 2. choose the new method
        JPasswordField np = new JPasswordField(24), np2 = new JPasswordField(24);
        JCheckBox useKf = new JCheckBox("Require a key file");
        JRadioButton kfNew = new JRadioButton("Create a new key file", true), kfOld = new JRadioButton("Use an existing file");
        ButtonGroup g = new ButtonGroup();
        g.add(kfNew);
        g.add(kfOld);
        JPanel kfp = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        kfp.add(kfNew);
        kfp.add(kfOld);
        JCheckBox useSk = new JCheckBox("Require a security key (YubiKey over USB)");
        try {
            VaultCrypto.Header h = VaultCrypto.readHeader(Files.readAllBytes(dv.file.toPath()));
            useKf.setSelected(h.version == 2 && h.keyFile);
            useSk.setSelected(h.version == 2 && h.securityKey());
        } catch (Exception ignored) {}
        if (!WinHelper.available()) {
            useSk.setEnabled(false);
            useSk.setText(useSk.getText() + " — Windows only");
        }
        JPanel form = Ux.form(new String[]{"New password", "Repeat", "", "", "", ""},
                new JComponent[]{np, np2, new JLabel("(leave empty to keep the current password)"), useKf, kfp, useSk});
        if (JOptionPane.showConfirmDialog(w, form, "New unlock method", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        char[] password = cur.password;
        if (np.getPassword().length > 0) {
            if (np.getPassword().length < 8) { Ux.error(w, "Use at least 8 characters."); return; }
            if (!Arrays.equals(np.getPassword(), np2.getPassword())) { Ux.error(w, "The new passwords don't match."); return; }
            password = np.getPassword();
        }
        // 3. key file
        byte[] keyFile = null;
        if (useKf.isSelected()) {
            JFileChooser ch = new JFileChooser();
            try {
                if (kfNew.isSelected()) {
                    ch.setSelectedFile(new File("grove-vault.key"));
                    if (ch.showSaveDialog(w) != JFileChooser.APPROVE_OPTION) return;
                    keyFile = VaultCrypto.random(64);
                    Files.write(ch.getSelectedFile().toPath(), keyFile);
                    if (!Arrays.equals(Files.readAllBytes(ch.getSelectedFile().toPath()), keyFile)) throw new Exception("The key file couldn't be saved correctly");
                } else {
                    if (ch.showOpenDialog(w) != JFileChooser.APPROVE_OPTION) return;
                    keyFile = Files.readAllBytes(ch.getSelectedFile().toPath());
                    if (keyFile.length < 16) { Ux.error(w, "Choose a file of at least 16 bytes."); return; }
                }
                Ux.put("keyfile_path", ch.getSelectedFile().getAbsolutePath());
            } catch (Exception e) {
                Ux.error(w, e.getMessage());
                return;
            }
        }
        // 4. security keys
        byte[] challenge = null;
        List<byte[]> responses = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (useSk.isSelected()) {
            challenge = VaultCrypto.random(32);
            while (true) {
                Ux.info(w, "Plug in security key " + (responses.size() + 1) + " and click OK. Touch it if it blinks.");
                byte[] r = DesktopUnlock.securityKey(w, challenge);
                if (r != null) {
                    boolean dup = false;
                    for (byte[] e : responses) if (Arrays.equals(e, r)) dup = true;
                    if (dup) Ux.error(w, "That key (or one with the same secret) is already added.");
                    else {
                        String name = JOptionPane.showInputDialog(w, "Name this key:", "YubiKey " + (responses.size() + 1));
                        responses.add(r);
                        labels.add(name == null || name.trim().isEmpty() ? "YubiKey " + responses.size() : name.trim());
                    }
                }
                if (responses.isEmpty()) {
                    if (!Ux.confirm(w, "No security key added yet. Try again?")) return;
                    continue;
                }
                int more = JOptionPane.showConfirmDialog(w, responses.size() + " key(s) added. Add another (backup) key?\nIf you lose your only key, the vault can't be opened.",
                        "Security keys", JOptionPane.YES_NO_OPTION);
                if (more != JOptionPane.YES_OPTION) break;
            }
        }
        // 5. re-encrypt with a fresh data key
        try {
            w.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            VaultCrypto.Key k = VaultCrypto.create(password, keyFile, challenge, responses.isEmpty() ? null : responses, labels);
            dv.changeKey(k);
            Ux.put("has_factors", keyFile != null || !responses.isEmpty() ? "1" : "0");
            if (keyFile == null) Ux.put("keyfile_path", null);
        } catch (Exception e) {
            Ux.error(w, "Not changed: " + e.getMessage());
            return;
        } finally {
            w.setCursor(Cursor.getDefaultCursor());
            Arrays.fill(password, '\0');
        }
        StringBuilder m = new StringBuilder("Saved. The vault now needs: master password");
        if (keyFile != null) m.append(" + key file");
        if (!responses.isEmpty()) m.append(" + one of ").append(responses.size()).append(" security key(s)");
        m.append(".\n\nOlder backups still open with the old method. Keep a copy of the key file and a backup YubiKey somewhere safe.");
        Ux.info(w, m.toString());
    }
}
