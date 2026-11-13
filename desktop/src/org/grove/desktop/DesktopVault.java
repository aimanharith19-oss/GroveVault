package org.grove.desktop;

import org.grove.vault.core.Templates;
import org.grove.vault.core.Vault;
import org.grove.vault.core.VaultCrypto;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

/**
 * The open vault file on the PC. Saving re-reads the file first and merges changes made elsewhere
 * (for example by the phone through a synced Google Drive / OneDrive / Dropbox folder).
 */
public final class DesktopVault {
    public final File file;
    public Vault vault;
    public VaultCrypto.Key key;
    private long loadedMtime;
    private long loadedSize;

    private DesktopVault(File file) {
        this.file = file;
    }

    public static DesktopVault create(File file, char[] password) throws Exception {
        DesktopVault d = new DesktopVault(file);
        d.key = VaultCrypto.newKey(password);
        d.vault = Templates.newVault();
        d.write();
        return d;
    }

    /** Opens with an already unlocked key; upgrades legacy (v1) vaults when the password is known. */
    public static DesktopVault open(File file, VaultCrypto.Key key, char[] passwordForUpgrade) throws Exception {
        DesktopVault d = new DesktopVault(file);
        byte[] data = Files.readAllBytes(file.toPath());
        d.vault = VaultCrypto.decryptVault(data, key);
        d.key = key;
        d.stamp();
        if (key.isLegacy() && passwordForUpgrade != null) {
            d.key = VaultCrypto.newKey(passwordForUpgrade);
            key.wipe();
            d.vault.touch();
            d.write();
            PinUnlock.disable(file);
        }
        return d;
    }

    private void stamp() {
        loadedMtime = file.lastModified();
        loadedSize = file.length();
    }

    public boolean changedOnDisk() {
        return file.exists() && (file.lastModified() != loadedMtime || file.length() != loadedSize);
    }

    /** Merges the file's current content. Returns {added, updated}, or null if it now needs a different unlock. */
    public int[] mergeFromDisk() throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        if (!VaultCrypto.sameKey(data, key)) return null;
        int[] r = vault.mergeFrom(VaultCrypto.decryptVault(data, key));
        stamp();
        return r;
    }

    /** Merges using the key the file now needs (after the password/unlock method was changed elsewhere). */
    public int[] mergeFromDisk(VaultCrypto.Key k) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        int[] r = vault.mergeFrom(VaultCrypto.decryptVault(data, k));
        key = k;
        stamp();
        PinUnlock.disable(file);
        return r;
    }

    public void save() throws Exception {
        if (changedOnDisk()) {
            int[] r = mergeFromDisk();
            if (r == null) throw new IOException("The vault file was changed elsewhere with a different unlock method. Use File → Reload from disk.");
        }
        vault.touch();
        write();
    }

    private void write() throws Exception {
        byte[] data = VaultCrypto.encryptVault(vault, key);
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), data);
        if (file.exists()) {
            try {
                Files.copy(file.toPath(), new File(file.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        stamp();
        autoBackup(data);
    }

    /** Replaces the key (new password / key file / security keys). The data key is always fresh. */
    public void changeKey(VaultCrypto.Key k) throws Exception {
        VaultCrypto.Key old = key;
        key = k;
        vault.touch();
        write();
        if (old != null && old != k) old.wipe();
        PinUnlock.disable(file);
    }

    /** Daily copy into the chosen backup folder, keeping the newest N. */
    private void autoBackup(byte[] data) {
        String dir = Ux.get("backup_dir", null);
        if (dir == null) return;
        File d = new File(dir);
        if (!d.isDirectory()) return;
        long last = 0;
        try { last = Long.parseLong(Ux.get("backup_last", "0")); } catch (Exception ignored) {}
        if (System.currentTimeMillis() - last < 23 * 3600 * 1000L) return;
        try {
            String name = "grove-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".grove";
            Files.write(new File(d, name).toPath(), data);
            Ux.put("backup_last", System.currentTimeMillis());
            File[] all = d.listFiles((x, n) -> n.startsWith("grove-backup-") && n.endsWith(".grove"));
            if (all != null) {
                Arrays.sort(all, (a, b) -> b.getName().compareTo(a.getName()));
                int keep = Ux.getInt("backup_keep", 10);
                for (int i = keep; i < all.length; i++) all[i].delete();
            }
        } catch (Exception e) {
            Ux.status("Automatic backup failed: " + e.getMessage());
        }
    }

    public void lock() {
        if (key != null) key.wipe();
        key = null;
        vault = null;
    }
}
