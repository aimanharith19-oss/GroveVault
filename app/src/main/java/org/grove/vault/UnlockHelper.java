package org.grove.vault;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.grove.vault.core.VaultCrypto;

/** Key-file memory and a reusable "unlock this .grove file" flow (password → key file → security key). */
public final class UnlockHelper {
    private UnlockHelper() {}

    // ---------------------------------------------------------------- key file location (never its contents)

    public static void rememberKeyFile(Context c, Uri uri) {
        try {
            c.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        Prefs.put(c, "keyfile_uri", uri.toString());
        Prefs.put(c, "keyfile_name", displayName(c, uri));
    }

    public static void forgetKeyFile(Context c) {
        Prefs.put(c, "keyfile_uri", null);
        Prefs.put(c, "keyfile_name", null);
    }

    public static String keyFileName(Context c) {
        return Prefs.p(c).getString("keyfile_name", null);
    }

    /** Contents of the remembered key file, or null if none / no longer readable. */
    public static byte[] readKeyFile(Context c) {
        String u = Prefs.p(c).getString("keyfile_uri", null);
        if (u == null) return null;
        try {
            byte[] b = Store.readUri(c, Uri.parse(u));
            return b.length == 0 ? null : b;
        } catch (Exception e) {
            return null;
        }
    }

    public static String displayName(Context c, Uri uri) {
        try {
            Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) return cur.getString(0);
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignored) {}
        return "key file";
    }

    /** Records whether the open vault needs extra factors (used to decide if fingerprint/PIN may skip them). */
    public static void recordFactors(Context c, byte[] file) {
        try {
            VaultCrypto.Header h = VaultCrypto.readHeader(file);
            boolean f = h.version == 2 && (h.keyFile || h.securityKey());
            Prefs.put(c, "has_factors", f);
            if (f && !Prefs.p(c).getBoolean("quick_with_factors", false)) {
                KeyGuard.bioDisable(c);
                KeyGuard.pinDisable(c);
            }
        } catch (Exception ignored) {}
    }

    public static boolean quickUnlockAllowed(Context c) {
        return !Prefs.p(c).getBoolean("has_factors", false) || Prefs.p(c).getBoolean("quick_with_factors", false);
    }

    // ---------------------------------------------------------------- full unlock of a file

    /** Asks for everything the file needs and unlocks it. Password may be null (then it's asked). */
    public static void unlockFile(final BaseActivity a, final byte[] file, final String title, final String password,
                                  final Ui.Cb<VaultCrypto.Key> ok, final Ui.Cb<Exception> fail) {
        if (password == null) {
            Ui.prompt(a, title, "Master password", null, Ui.PASSWORD_INPUT, new Ui.Cb<String>() {
                public void on(String p) { unlockFile(a, file, title, p, ok, fail); }
            });
            return;
        }
        final VaultCrypto.Header h;
        try {
            h = VaultCrypto.readHeader(file);
        } catch (Exception e) {
            fail.on(e);
            return;
        }
        if (h.version == 2 && h.keyFile) {
            byte[] kf = readKeyFile(a);
            if (kf == null) {
                Ui.toast(a, "Choose this vault's key file");
                a.pickDocument(new Ui.Cb<Uri>() {
                    public void on(Uri u) {
                        try {
                            byte[] b = Store.readUri(a, u);
                            withKeyFile(a, file, h, password, b, ok, fail);
                        } catch (Exception e) {
                            fail.on(e);
                        }
                    }
                });
                return;
            }
            withKeyFile(a, file, h, password, kf, ok, fail);
        } else withKeyFile(a, file, h, password, null, ok, fail);
    }

    private static void withKeyFile(final Activity a, final byte[] file, VaultCrypto.Header h, final String password, final byte[] kf,
                                    final Ui.Cb<VaultCrypto.Key> ok, final Ui.Cb<Exception> fail) {
        boolean needsKey = h.version == 2 && h.securityKey() && !hasPasswordSlot(h);
        if (!needsKey) {
            run(a, file, password, kf, null, ok, fail);
            return;
        }
        SecurityKeyReader.request(a, "Security key", h.challenge, new SecurityKeyReader.Callback() {
            public void onResponse(byte[] r, String via) { run(a, file, password, kf, r, ok, fail); }
            public void onError(String m) { fail.on(new Exception(m)); }
        });
    }

    private static boolean hasPasswordSlot(VaultCrypto.Header h) {
        for (VaultCrypto.Slot s : h.slots) if (s.type == 1) return true;
        return false;
    }

    private static void run(Activity a, final byte[] file, final String password, final byte[] kf, final byte[] response,
                            final Ui.Cb<VaultCrypto.Key> ok, final Ui.Cb<Exception> fail) {
        Ui.bg(a, "Unlocking…", new Ui.Task<VaultCrypto.Key>() {
            public VaultCrypto.Key run() throws Exception {
                return VaultCrypto.unlock(file, password.toCharArray(), new VaultCrypto.Factors(kf, response == null ? null : fixed(response)));
            }
        }, ok, fail);
    }

    public static VaultCrypto.Responder fixed(final byte[] response) {
        return new VaultCrypto.Responder() {
            public byte[] respond(byte[] challenge) { return response; }
        };
    }
}
