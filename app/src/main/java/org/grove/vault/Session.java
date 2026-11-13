package org.grove.vault;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.grove.vault.core.Card;
import org.grove.vault.core.Vault;
import org.grove.vault.core.VaultCrypto;

import java.util.ArrayList;
import java.util.List;

/** The unlocked vault lives here, in memory only, until the app locks. */
public final class Session {
    private Session() {}

    private static Vault vault;
    private static VaultCrypto.Key key;
    private static long backgroundSince = -1;
    private static final List<Runnable> listeners = new ArrayList<Runnable>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isUnlocked() {
        return vault != null && key != null;
    }

    public static Vault vault() {
        return vault;
    }

    public static VaultCrypto.Key key() {
        return key;
    }

    public static void unlock(Vault v, VaultCrypto.Key k) {
        vault = v;
        key = k;
        backgroundSince = -1;
    }

    public static void lock() {
        if (key != null) key.wipe();
        vault = null;
        key = null;
        notifyChanged();
    }

    /** Encrypts and writes the vault. Backups run in the background. */
    public static void save(final Context c) throws Exception {
        if (!isUnlocked()) throw new IllegalStateException("Locked");
        vault.touch();
        final byte[] data = VaultCrypto.encryptVault(vault, key);
        Store.write(c, data);
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                Store.autoBackup(app, data);
            }
        }).start();
        notifyChanged();
    }

    /** Saves and shows a toast if it fails. Returns success. */
    public static boolean saveOrToast(Context c) {
        try {
            save(c);
            return true;
        } catch (Exception e) {
            Ui.toast(c, "Could not save: " + e.getMessage());
            return false;
        }
    }

    public static void replace(Vault v, VaultCrypto.Key k) {
        if (key != null && key != k) key.wipe();
        vault = v;
        key = k;
        notifyChanged();
    }

    public static Card card(String id) {
        return vault == null ? null : vault.findCard(id);
    }

    // ---------------- auto-lock bookkeeping ----------------

    private static long pickerSince = 0;

    /** Our own file pickers send the app to the background; don't auto-lock for that (up to 10 minutes). */
    public static void pickerStarted() {
        pickerSince = System.currentTimeMillis();
    }

    public static void pickerFinished() {
        pickerSince = 0;
    }

    public static boolean pickerActive() {
        return pickerSince > 0 && System.currentTimeMillis() - pickerSince < 10 * 60 * 1000L;
    }

    public static void wentToBackground() {
        backgroundSince = System.currentTimeMillis();
    }

    public static void cameToForeground() {
        backgroundSince = -1;
    }

    /** Locks if the auto-lock timeout passed while the app was in the background. */
    public static void checkTimeout(Context c) {
        if (!isUnlocked() || backgroundSince < 0 || pickerActive()) return;
        int secs = Prefs.autoLockSeconds(c);
        if (secs < 0) return; // never
        if (System.currentTimeMillis() - backgroundSince >= secs * 1000L) lock();
    }

    public static void addListener(Runnable r) {
        listeners.add(r);
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    public static void notifyChanged() {
        main.post(new Runnable() {
            public void run() {
                for (Runnable r : new ArrayList<Runnable>(listeners)) r.run();
            }
        });
    }
}
