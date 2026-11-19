package org.grove.vault;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.grove.vault.core.PasswordTools;
import org.grove.vault.core.Templates;
import org.grove.vault.core.Vault;
import org.grove.vault.core.VaultCrypto;

import javax.crypto.Cipher;

/** First-run setup (create master password / restore backup) and the unlock screen (password, biometrics, PIN). */
public class LockActivity extends Activity {
    public static final String EXTRA_RETURN = "return";
    private static final int REQ_RESTORE = 1;

    private EditText pw, pw2;
    private TextView strength, error;
    private boolean pinMode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        pinMode = Prefs.pinEnabled(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.isUnlocked()) {
            done();
            return;
        }
        build();
    }

    private boolean returning() {
        return getIntent().getBooleanExtra(EXTRA_RETURN, false);
    }

    private void done() {
        if (returning() || getCallingActivity() != null) {
            setResult(RESULT_OK);
            finish();
            overridePendingTransition(0, 0);
        } else {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (getCallingActivity() != null) {
            setResult(RESULT_CANCELED);
            finish();
        } else if (returning()) {
            // leave the app instead of revealing the screen behind
            moveTaskToBack(true);
        } else super.onBackPressed();
    }

    private void build() {
        LinearLayout root = Ui.screen(this);
        LinearLayout c = Ui.vertical(this);
        int pad = Ui.dp(this, 24);
        c.setPadding(pad, Ui.dp(this, 36), pad, pad);
        root.addView(Ui.scroll(this, c), Ui.lp(Ui.MATCH, Ui.MATCH));

        // Windows 11 sign-in: centred app tile, name and a short line of text above the password box
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(pad, Ui.dp(this, 56), pad, pad);
        TextView logo = Ui.text(this, "\uD83C\uDF33", 44, 0xFFFFFFFF, false);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 48)));
        c.addView(logo, Ui.lp(Ui.dp(this, 96), Ui.dp(this, 96)));
        TextView name = Ui.text(this, "GroveVault", 28, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 2));
        c.addView(name);
        TextView hint = Ui.text(this, Store.exists(this) ? "Enter your master password" : "Create your vault", 14, Ui.SUB, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, Ui.dp(this, 16));
        c.addView(hint);

        error = Ui.text(this, "", 14, Ui.DANGER, false);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, Ui.dp(this, 8), 0, 0);

        if (!Store.exists(this)) buildSetup(c);
        else if (pinMode && Prefs.pinEnabled(this) && UnlockHelper.quickUnlockAllowed(this)) buildPin(c);
        else buildUnlock(c);
        if (Store.exists(this) && Prefs.bioEnabled(this) && KeyGuard.bioSupported() && !bioTried && UnlockHelper.quickUnlockAllowed(this)) {
            bioTried = true;
            c.post(new Runnable() {
                public void run() {
                    biometric();
                }
            });
        }
    }

    private boolean bioTried;

    // ---------------------------------------------------------------- setup

    private void buildSetup(LinearLayout c) {
        TextView sub = Ui.text(this, "Create a master password. It encrypts everything in your vault.", 15, Ui.SUB, false);
        sub.setGravity(Gravity.CENTER);
        c.addView(sub, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 20));
        pw = Ui.edit(this, "Master password", Ui.PASSWORD_INPUT);
        c.addView(pw, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        pw2 = Ui.edit(this, "Repeat master password", Ui.PASSWORD_INPUT);
        c.addView(pw2, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 6));
        strength = Ui.text(this, " ", 13, Ui.SUB, false);
        c.addView(strength, Ui.margins(this, Ui.MATCH, Ui.WRAP, 4, 0, 0, 0));
        pw.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) {
                showStrength(strength, s.toString());
            }
        });
        TextView warn = Ui.text(this, "⚠️  Remember this password. It can't be reset or recovered, and without it your data can't be opened.", 13, Ui.WARN, false);
        c.addView(warn, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        c.addView(error);
        c.addView(Ui.button(this, "Create vault", new View.OnClickListener() {
            public void onClick(View v) {
                create();
            }
        }), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 20, 0, 0));
        c.addView(Ui.flatButton(this, "Restore from a Grove backup", new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_RESTORE);
            }
        }), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        TextView tip = Ui.text(this, "You can import from SafeInCloud, KeePass, Chrome, Bitwarden and other apps once your vault is created.", 13, Ui.SUB, false);
        tip.setGravity(Gravity.CENTER);
        c.addView(tip, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        Ui.showKeyboard(pw);
    }

    static void showStrength(TextView t, String p) {
        if (p.length() == 0) {
            t.setText(" ");
            return;
        }
        int s = PasswordTools.score(p);
        int[] cols = {Ui.DANGER, Ui.DANGER, Ui.WARN, Ui.ACCENT, Ui.ACCENT};
        String bars = "";
        for (int i = 0; i < 5; i++) bars += i <= s ? "▰" : "▱";
        t.setText(bars + "  " + PasswordTools.SCORE_NAMES[s] + " · ~" + (int) PasswordTools.entropy(p) + " bits");
        t.setTextColor(cols[s]);
    }

    private void create() {
        final String a = pw.getText().toString(), b2 = pw2.getText().toString();
        if (a.length() < 8) {
            error.setText("Use at least 8 characters. A few random words is best.");
            return;
        }
        if (!a.equals(b2)) {
            error.setText("The passwords don't match.");
            return;
        }
        Ui.hideKeyboard(pw);
        Ui.bg(this, "Creating your vault…", new Ui.Task<VaultCrypto.Key>() {
            public VaultCrypto.Key run() throws Exception {
                return VaultCrypto.newKey(a.toCharArray());
            }
        }, new Ui.Cb<VaultCrypto.Key>() {
            public void on(VaultCrypto.Key k) {
                Vault v = Templates.newVault();
                Session.unlock(v, k);
                if (Session.saveOrToast(LockActivity.this)) {
                    Prefs.put(LockActivity.this, "failed", 0);
                    done();
                }
            }
        }, null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_RESTORE && res == RESULT_OK && data != null) restoreFrom(data.getData());
        if (req == REQ_KEYFILE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                UnlockHelper.rememberKeyFile(this, data.getData());
                if (fFile != null) {
                    try {
                        fKeyFile = Store.readUri(this, data.getData());
                        nextFactor();
                    } catch (Exception e) {
                        Ui.message(this, "Key file", String.valueOf(e.getMessage()));
                    }
                } else build();
            } else fFile = null;
        }
    }

    private void restoreFrom(final Uri uri) {
        final byte[] file;
        try {
            file = Store.readUri(this, uri);
        } catch (Exception e) {
            Ui.message(this, "Can't open file", String.valueOf(e.getMessage()));
            return;
        }
        if (!VaultCrypto.isVaultFile(file)) {
            Ui.message(this, "Not a Grove backup", "This file isn't a Grove Vault backup. To bring in a SafeInCloud, KeePass or CSV file, create a vault first, then use ⋮ → Backup & import.");
            return;
        }
        Ui.prompt(this, "Backup password", "Master password of the backup", null, Ui.PASSWORD_INPUT, new Ui.Cb<String>() {
            public void on(String p) {
                startFactors(file, p, true);
            }
        });
    }

    // ---------------------------------------------------------------- unlock

    private void buildUnlock(LinearLayout c) {
        TextView sub = Ui.text(this, "Enter your master password", 15, Ui.SUB, false);
        sub.setGravity(Gravity.CENTER);
        c.addView(sub, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 20));
        pw = Ui.edit(this, "Master password", Ui.PASSWORD_INPUT);
        pw.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pw.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                unlockWithPassword();
                return true;
            }
        });
        LinearLayout row = Ui.horizontal(this);
        row.addView(pw, Ui.lpw(1));
        row.addView(Ui.iconButton(this, "👁", new View.OnClickListener() {
            public void onClick(View v) {
                boolean hidden = (pw.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
                pw.setInputType(hidden ? Ui.VISIBLE_PASSWORD_INPUT : Ui.PASSWORD_INPUT);
                pw.setSelection(pw.length());
            }
        }));
        c.addView(row);
        try {
            VaultCrypto.Header h = VaultCrypto.readHeader(Store.read(this));
            if (h.version == 2 && h.keyFile) {
                String n = UnlockHelper.keyFileName(this);
                c.addView(Ui.flatButton(this, "🔑  Key file: " + (n == null || UnlockHelper.readKeyFile(this) == null ? "choose…" : n), new View.OnClickListener() {
                    public void onClick(View v) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        fFile = null;
                        startActivityForResult(i, REQ_KEYFILE);
                    }
                }), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
            }
            if (h.version == 2 && h.securityKey()) {
                TextView t = Ui.text(this, "🔐  Security key needed: " + android.text.TextUtils.join(" or ", h.securityKeyLabels()), 13, Ui.SUB, false);
                t.setGravity(Gravity.CENTER);
                c.addView(t, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
            }
        } catch (Exception ignored) {}
        c.addView(error);
        int left = Prefs.eraseAfter(this) - Prefs.failedAttempts(this);
        if (Prefs.eraseAfter(this) > 0 && Prefs.failedAttempts(this) > 0) {
            error.setText("Wrong password. " + left + " attempts left before the vault is erased.");
        }
        c.addView(Ui.button(this, "Unlock", new View.OnClickListener() {
            public void onClick(View v) {
                unlockWithPassword();
            }
        }), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 20, 0, 0));
        if (Prefs.bioEnabled(this) && KeyGuard.bioSupported() && UnlockHelper.quickUnlockAllowed(this)) {
            c.addView(Ui.flatButton(this, "👆  Unlock with biometrics", new View.OnClickListener() {
                public void onClick(View v) {
                    biometric();
                }
            }), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        }
        if (!Prefs.bioEnabled(this) || bioTried) Ui.showKeyboard(pw);
        if (Prefs.pinEnabled(this) && UnlockHelper.quickUnlockAllowed(this)) {
            c.addView(Ui.flatButton(this, "Use PIN", new View.OnClickListener() {
                public void onClick(View v) {
                    pinMode = true;
                    build();
                }
            }));
        }
    }

    private void unlockWithPassword() {
        final String p = pw.getText().toString();
        if (p.length() == 0) return;
        long wait = Prefs.p(this).getLong("lockout_until", 0) - System.currentTimeMillis();
        if (wait > 0) {
            error.setText("Too many wrong attempts. Try again in " + (wait / 1000 + 1) + " s.");
            return;
        }
        Ui.hideKeyboard(pw);
        try {
            startFactors(Store.read(this), p, false);
        } catch (Exception e) {
            Ui.message(this, "Can't open vault", String.valueOf(e.getMessage()));
        }
    }

    // ---- password → key file → security key → unlock (used for unlocking and for restoring a backup)

    private static final int REQ_KEYFILE = 2;
    private byte[] fFile, fKeyFile, fResponse;
    private String fPassword;
    private boolean fRestore;

    private void startFactors(byte[] file, String password, boolean restore) {
        fFile = file;
        fPassword = password;
        fRestore = restore;
        fKeyFile = null;
        fResponse = null;
        nextFactor();
    }

    private void nextFactor() {
        final VaultCrypto.Header h;
        try {
            h = VaultCrypto.readHeader(fFile);
        } catch (Exception e) {
            Ui.message(this, "Can't open vault", String.valueOf(e.getMessage()));
            return;
        }
        if (h.version == 2 && h.keyFile && fKeyFile == null) {
            fKeyFile = UnlockHelper.readKeyFile(this);
            if (fKeyFile == null) {
                Ui.toast(this, "Choose your key file");
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_KEYFILE);
                return;
            }
        }
        boolean needsKey = h.version == 2 && h.securityKey() && !h.securityKeyLabels().isEmpty() && fResponse == null;
        boolean passwordSlot = false;
        for (VaultCrypto.Slot sl : h.slots) if (sl.type == 1) passwordSlot = true;
        if (needsKey && !passwordSlot) {
            SecurityKeyReader.request(this, "Security key", h.challenge, new SecurityKeyReader.Callback() {
                public void onResponse(byte[] r, String via) {
                    fResponse = r;
                    nextFactor();
                }
                public void onError(String m) {
                    if (!"Cancelled".equals(m)) Ui.message(LockActivity.this, "Security key", m);
                }
            });
            return;
        }
        final byte[] file = fFile, kf = fKeyFile, resp = fResponse;
        final String p = fPassword;
        final boolean restore = fRestore;
        Ui.bg(this, "Unlocking…", new Ui.Task<Object[]>() {
            public Object[] run() throws Exception {
                VaultCrypto.Key k = VaultCrypto.unlock(file, p.toCharArray(), new VaultCrypto.Factors(kf, resp == null ? null : UnlockHelper.fixed(resp)));
                Vault v = VaultCrypto.decryptVault(file, k);
                boolean migrated = false;
                if (k.isLegacy()) {
                    // upgrade old PBKDF2 vaults to the Argon2id envelope format
                    VaultCrypto.Key nk = VaultCrypto.newKey(p.toCharArray());
                    k.wipe();
                    k = nk;
                    migrated = true;
                }
                if (restore) Store.write(LockActivity.this, file);
                return new Object[]{v, k, migrated};
            }
        }, new Ui.Cb<Object[]>() {
            public void on(Object[] r) {
                fPassword = null;
                if (restore) {
                    Session.unlock((Vault) r[0], (VaultCrypto.Key) r[1]);
                    if ((Boolean) r[2]) Session.saveOrToast(LockActivity.this);
                    UnlockHelper.recordFactors(LockActivity.this, file);
                    Ui.toast(LockActivity.this, "Vault restored");
                    done();
                    return;
                }
                if ((Boolean) r[2]) {
                    Session.unlock((Vault) r[0], (VaultCrypto.Key) r[1]);
                    if (Session.saveOrToast(LockActivity.this)) {
                        KeyGuard.bioDisable(LockActivity.this);
                        KeyGuard.pinDisable(LockActivity.this);
                        Ui.toast(LockActivity.this, "Vault upgraded to stronger encryption (Argon2id). Turn fingerprint/PIN on again in Settings if you used them.");
                    }
                }
                UnlockHelper.recordFactors(LockActivity.this, file);
                success((Vault) r[0], (VaultCrypto.Key) r[1]);
            }
        }, new Ui.Cb<Exception>() {
            public void on(Exception e) {
                fPassword = null;
                if (e instanceof VaultCrypto.WrongPasswordException) {
                    if (restore) Ui.message(LockActivity.this, "Restore failed", e.getMessage());
                    else wrongPassword(e.getMessage());
                } else Ui.message(LockActivity.this, restore ? "Restore failed" : "Can't open vault", String.valueOf(e.getMessage()));
            }
        });
    }

    private void success(Vault v, VaultCrypto.Key k) {
        Prefs.put(this, "failed", 0);
        Prefs.put(this, "lockout_until", 0L);
        Session.unlock(v, k);
        done();
    }

    private void wrongPassword(String what) {
        int fails = Prefs.failedAttempts(this) + 1;
        Prefs.put(this, "failed", fails);
        if (fails >= 3) {
            long secs = Math.min(300, 5L << Math.min(10, fails - 3));
            Prefs.put(this, "lockout_until", System.currentTimeMillis() + secs * 1000);
        }
        int limit = Prefs.eraseAfter(this);
        pw.setText("");
        if (limit > 0 && fails >= limit) {
            Store.eraseAll(this);
            KeyGuard.bioDisable(this);
            KeyGuard.pinDisable(this);
            Prefs.put(this, "failed", 0);
            Ui.message(this, "Vault erased", "Too many wrong passwords. The vault on this device has been erased. Restore it from a backup.");
            build();
            return;
        }
        String w = what == null ? "Wrong password." : what + ".";
        error.setText(limit > 0 ? w + " " + (limit - fails) + " attempts left before the vault is erased." : w + (fails >= 3 ? " Wait a moment before trying again." : ""));
        Ui.showKeyboard(pw);
    }

    private void unlockWithKey(final byte[] rawKey) {
        Ui.bg(this, "Unlocking…", new Ui.Task<Object[]>() {
            public Object[] run() throws Exception {
                byte[] file = Store.read(LockActivity.this);
                VaultCrypto.Key k = VaultCrypto.keyFromRaw(file, rawKey);
                return new Object[]{VaultCrypto.decryptVault(file, k), k};
            }
        }, new Ui.Cb<Object[]>() {
            public void on(Object[] r) {
                success((Vault) r[0], (VaultCrypto.Key) r[1]);
            }
        }, new Ui.Cb<Exception>() {
            public void on(Exception e) {
                // key no longer matches (e.g. password changed elsewhere)
                KeyGuard.bioDisable(LockActivity.this);
                KeyGuard.pinDisable(LockActivity.this);
                pinMode = false;
                build();
                Ui.message(LockActivity.this, "Use your master password", "Quick unlock no longer matches this vault and has been turned off.");
            }
        });
    }

    private void biometric() {
        if (Build.VERSION.SDK_INT < 28) return;
        final Cipher cipher;
        try {
            cipher = KeyGuard.bioDecryptCipher(this);
        } catch (KeyPermanentlyInvalidatedException e) {
            KeyGuard.bioDisable(this);
            build();
            Ui.message(this, "Biometrics changed", "Your fingerprints or face data changed, so biometric unlock was turned off. Unlock with your password and turn it on again in Settings.");
            return;
        } catch (Exception e) {
            Ui.toast(this, "Biometrics unavailable: " + e.getMessage());
            return;
        }
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                .setTitle("Unlock Grove Vault")
                .setNegativeButton("Use password", getMainExecutor(), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Ui.showKeyboard(pw);
                    }
                });
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
        b.build().authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(), getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        try {
                            unlockWithKey(KeyGuard.bioOpen(LockActivity.this, r.getCryptoObject().getCipher()));
                        } catch (Exception e) {
                            Ui.toast(LockActivity.this, "Biometric unlock failed");
                        }
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
                            Ui.toast(LockActivity.this, String.valueOf(msg));
                    }
                });
    }

    // ---------------------------------------------------------------- PIN

    private void buildPin(LinearLayout c) {
        TextView sub = Ui.text(this, "Enter your PIN", 15, Ui.SUB, false);
        sub.setGravity(Gravity.CENTER);
        c.addView(sub, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 20));
        pw = Ui.edit(this, "PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pw.setGravity(Gravity.CENTER);
        pw.setLetterSpacing(0.4f);
        final int len = Prefs.p(this).getInt("pin_len", 4);
        pw.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) {
                if (s.length() == len) unlockWithPin(s.toString());
            }
        });
        c.addView(pw);
        c.addView(error);
        c.addView(Ui.flatButton(this, "Use master password", new View.OnClickListener() {
            public void onClick(View v) {
                pinMode = false;
                build();
            }
        }), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        if (Prefs.bioEnabled(this) && KeyGuard.bioSupported()) {
            c.addView(Ui.flatButton(this, "👆  Use biometrics", new View.OnClickListener() {
                public void onClick(View v) {
                    biometric();
                }
            }));
        }
        Ui.showKeyboard(pw);
    }

    private void unlockWithPin(final String pin) {
        Ui.bg(this, "Checking PIN…", new Ui.Task<byte[]>() {
            public byte[] run() throws Exception {
                return KeyGuard.pinOpen(LockActivity.this, pin);
            }
        }, new Ui.Cb<byte[]>() {
            public void on(byte[] k) {
                unlockWithKey(k);
            }
        }, new Ui.Cb<Exception>() {
            public void on(Exception e) {
                pw.setText("");
                if (!Prefs.pinEnabled(LockActivity.this)) {
                    pinMode = false;
                    build();
                    error.setText("Too many wrong PINs. Use your master password.");
                } else {
                    error.setText("Wrong PIN. " + KeyGuard.pinTriesLeft(LockActivity.this) + " tries left.");
                }
            }
        });
    }

}
