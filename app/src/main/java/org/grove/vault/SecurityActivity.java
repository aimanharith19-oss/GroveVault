package org.grove.vault;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.grove.vault.core.VaultCrypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Master password, key file and security keys (YubiKey). Every change re-encrypts the vault under a new data key. */
public class SecurityActivity extends BaseActivity {
    private static final int REQ_NEW_KEYFILE = 11;

    // wizard state
    private String wPassword;
    private byte[] wKeyFile;
    private boolean wWantKeyFile, wNewKeyFile, wWantSecurityKey;
    private byte[] wChallenge;
    private final List<byte[]> wResponses = new ArrayList<byte[]>();
    private final List<String> wLabels = new ArrayList<String>();

    @Override
    protected void render() {
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "Security key & key file", true));
        LinearLayout c = Ui.vertical(this);
        c.setPadding(0, 0, 0, Ui.dp(this, 40));
        root.addView(Ui.scroll(this, c), Ui.lp(Ui.MATCH, Ui.MATCH));
        VaultCrypto.Header h = null;
        try {
            h = VaultCrypto.readHeader(Store.read(this));
        } catch (Exception ignored) {}

        c.addView(Ui.section(this, "How your vault is unlocked"));
        c.addView(Ui.row(this, "✅  Master password", h != null && h.version == 2
                ? "Argon2id (" + h.memoryKiB / 1024 + " MiB, " + h.ops + " passes) → AES-256-GCM" : "Older format: upgrades automatically next time you unlock with your password", null));
        boolean kf = h != null && h.version == 2 && h.keyFile;
        String kfName = UnlockHelper.keyFileName(this);
        c.addView(Ui.row(this, kf ? "🔑  Key file required" : "Key file: not used",
                kf ? (kfName != null ? "Remembered: " + kfName : "You'll be asked to choose it when unlocking") : "A file (e.g. on a USB stick or SD card) that must be present to unlock", null));
        boolean sk = h != null && h.version == 2 && h.securityKey();
        c.addView(Ui.row(this, sk ? "🔐  Security key required" : "Security key: not used",
                sk ? android.text.TextUtils.join(", ", h.securityKeyLabels()) + " (any one of them unlocks)" : "YubiKey (or compatible) HMAC-SHA1 challenge-response, over NFC or USB", null));
        c.addView(Ui.button(this, "Change password, key file or security keys…", new View.OnClickListener() {
            public void onClick(View v) { startWizard(); }
        }), Ui.margins(this, Ui.MATCH, Ui.WRAP, 20, 8, 20, 8));

        c.addView(Ui.section(this, "Convenience vs. security"));
        Ui.switchRow(this, c, "Let fingerprint / PIN skip the key file and security key",
                "Off (recommended): with a key file or security key, every unlock needs them. On: fingerprint/PIN unlock still works without them",
                Prefs.p(this).getBoolean("quick_with_factors", false), new Ui.Cb<Boolean>() {
                    public void on(Boolean b) {
                        Prefs.put(SecurityActivity.this, "quick_with_factors", b);
                        if (!b && Prefs.p(SecurityActivity.this).getBoolean("has_factors", false)) {
                            KeyGuard.bioDisable(SecurityActivity.this);
                            KeyGuard.pinDisable(SecurityActivity.this);
                        }
                    }
                });
        if (kfName != null) {
            c.addView(Ui.row(this, "Forget the key file's location", "You'll have to choose the key file every time you unlock", new View.OnClickListener() {
                public void onClick(View v) {
                    UnlockHelper.forgetKeyFile(SecurityActivity.this);
                    render();
                }
            }));
        }

        c.addView(Ui.section(this, "Setting up a YubiKey"));
        TextView t = Ui.text(this, "1. Install YubiKey Manager on a computer.\n2. Applications → OTP → Long Touch (Slot 2) → Configure → Challenge-response → Generate a secret key → Finish. Tick \"Require touch\" for extra safety.\n3. Program a second (backup) YubiKey with the same secret, or add it here as another key.\n\nWorks with YubiKey 5 NFC (tap on the back of the phone) or any YubiKey over USB. Without a working key, the vault can't be opened, so always keep a backup key.", 13, Ui.SUB, false);
        t.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        c.addView(t);
        Ui.pivotize(this, c, "unlock");
    }

    // ---------------------------------------------------------------- wizard

    private void startWizard() {
        wPassword = null;
        wKeyFile = null;
        wResponses.clear();
        wLabels.clear();
        Ui.prompt(this, "Confirm it's you", "Current master password", null, Ui.PASSWORD_INPUT, new Ui.Cb<String>() {
            public void on(final String p) {
                try {
                    UnlockHelper.unlockFile(SecurityActivity.this, Store.read(SecurityActivity.this), "Confirm it's you", p, new Ui.Cb<VaultCrypto.Key>() {
                        public void on(VaultCrypto.Key k) {
                            boolean same = Arrays.equals(k.key, Session.key().key);
                            k.wipe();
                            if (!same) {
                                Ui.message(SecurityActivity.this, "Not changed", "That doesn't unlock this vault.");
                                return;
                            }
                            wPassword = p;
                            chooseOptions();
                        }
                    }, new Ui.Cb<Exception>() {
                        public void on(Exception e) { Ui.message(SecurityActivity.this, "Not changed", String.valueOf(e.getMessage())); }
                    });
                } catch (Exception e) {
                    Ui.message(SecurityActivity.this, "Not changed", String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private void chooseOptions() {
        LinearLayout box = Ui.vertical(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final EditText np = Ui.edit(this, "New master password (leave empty to keep)", Ui.PASSWORD_INPUT);
        final EditText np2 = Ui.edit(this, "Repeat new password", Ui.PASSWORD_INPUT);
        final TextView st = Ui.text(this, " ", 12, Ui.SUB, false);
        np.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable s) { LockActivity.showStrength(st, s.toString()); }
        });
        box.addView(np);
        box.addView(st);
        box.addView(np2, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 10));
        final CheckBox useKf = new CheckBox(this);
        useKf.setText("Require a key file");
        useKf.setTextColor(Ui.TEXT);
        final RadioGroup kfMode = new RadioGroup(this);
        RadioButton kfNew = new RadioButton(this);
        kfNew.setText("Create a new key file");
        kfNew.setTextColor(Ui.TEXT);
        kfNew.setId(201);
        RadioButton kfOld = new RadioButton(this);
        kfOld.setText("Use an existing file");
        kfOld.setTextColor(Ui.TEXT);
        kfOld.setId(202);
        kfMode.addView(kfNew);
        kfMode.addView(kfOld);
        kfMode.check(201);
        kfMode.setPadding(Ui.dp(this, 28), 0, 0, 0);
        kfMode.setVisibility(View.GONE);
        useKf.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { kfMode.setVisibility(on ? View.VISIBLE : View.GONE); }
        });
        final CheckBox useSk = new CheckBox(this);
        useSk.setText("Require a security key (YubiKey)");
        useSk.setTextColor(Ui.TEXT);
        try {
            VaultCrypto.Header h = VaultCrypto.readHeader(Store.read(this));
            useKf.setChecked(h.version == 2 && h.keyFile);
            useSk.setChecked(h.version == 2 && h.securityKey());
        } catch (Exception ignored) {}
        box.addView(useKf);
        box.addView(kfMode);
        box.addView(useSk);
        TextView note = Ui.text(this, "Security keys must be tapped again now (each one you want to keep), because the vault gets a fresh encryption key.", 12, Ui.SUB, false);
        note.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(note);
        Ui.dialog(this, "New unlock method").setView(Ui.scroll(this, box)).setNegativeButton("Cancel", null)
                .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String a = np.getText().toString();
                        if (a.length() > 0) {
                            if (a.length() < 8) { Ui.message(SecurityActivity.this, "Not changed", "Use at least 8 characters."); return; }
                            if (!a.equals(np2.getText().toString())) { Ui.message(SecurityActivity.this, "Not changed", "The new passwords don't match."); return; }
                            wPassword = a;
                        }
                        wWantKeyFile = useKf.isChecked();
                        wNewKeyFile = kfMode.getCheckedRadioButtonId() == 201;
                        wWantSecurityKey = useSk.isChecked();
                        stepKeyFile();
                    }
                }).show();
    }

    private void stepKeyFile() {
        if (!wWantKeyFile) {
            stepSecurityKeys();
            return;
        }
        if (wNewKeyFile) {
            wKeyFile = VaultCrypto.random(64);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, "grove-vault.key");
            Ui.toast(this, "Save the new key file somewhere safe (e.g. SD card or USB stick)");
            startActivityForResult(i, REQ_NEW_KEYFILE);
        } else {
            pickDocument(new Ui.Cb<Uri>() {
                public void on(Uri u) {
                    try {
                        wKeyFile = Store.readUri(SecurityActivity.this, u);
                        if (wKeyFile.length < 16) {
                            Ui.message(SecurityActivity.this, "Key file", "Choose a file of at least 16 bytes (ideally random data or a photo you never change).");
                            return;
                        }
                        UnlockHelper.rememberKeyFile(SecurityActivity.this, u);
                        stepSecurityKeys();
                    } catch (Exception e) {
                        Ui.message(SecurityActivity.this, "Key file", String.valueOf(e.getMessage()));
                    }
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_NEW_KEYFILE) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            Ui.toast(this, "Not changed");
            return;
        }
        try {
            Store.writeUri(this, data.getData(), wKeyFile);
            byte[] check = Store.readUri(this, data.getData());
            if (!Arrays.equals(check, wKeyFile)) throw new Exception("The key file couldn't be saved correctly");
            UnlockHelper.rememberKeyFile(this, data.getData());
            stepSecurityKeys();
        } catch (Exception e) {
            Ui.message(this, "Not changed", String.valueOf(e.getMessage()));
        }
    }

    private void stepSecurityKeys() {
        if (!wWantSecurityKey) {
            finishWizard();
            return;
        }
        if (wChallenge == null || wResponses.isEmpty()) wChallenge = VaultCrypto.random(32);
        enrollKey();
    }

    private void enrollKey() {
        final int n = wResponses.size() + 1;
        SecurityKeyReader.request(this, "Security key " + n, wChallenge, new SecurityKeyReader.Callback() {
            public void onResponse(final byte[] r, final String via) {
                for (byte[] e : wResponses) {
                    if (Arrays.equals(e, r)) {
                        Ui.message(SecurityActivity.this, "Same key", "That key (or one with the same secret) is already added.");
                        askAnother();
                        return;
                    }
                }
                Ui.prompt(SecurityActivity.this, "Name this key", "e.g. YubiKey keyring", "YubiKey " + n, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.Cb<String>() {
                    public void on(String label) {
                        wResponses.add(r);
                        wLabels.add(label.trim().length() == 0 ? "YubiKey " + n : label.trim());
                        askAnother();
                    }
                });
            }
            public void onError(String m) {
                if (wResponses.isEmpty()) Ui.message(SecurityActivity.this, "Not changed", m);
                else askAnother();
            }
        });
    }

    private void askAnother() {
        String msg = wResponses.size() + (wResponses.size() == 1 ? " key added." : " keys added.")
                + (wResponses.size() == 1 ? "\n\nAdd a backup key now? If you lose your only key, the vault can't be opened." : "");
        Ui.dialog(this, "Security keys").setMessage(msg)
                .setNeutralButton("Cancel", null)
                .setNegativeButton("Add another key", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { enrollKey(); }
                })
                .setPositiveButton(wResponses.isEmpty() ? "Try again" : "Done", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (wResponses.isEmpty()) enrollKey();
                        else finishWizard();
                    }
                }).show();
    }

    private void finishWizard() {
        final String p = wPassword;
        final byte[] kf = wWantKeyFile ? wKeyFile : null;
        final boolean sk = wWantSecurityKey && !wResponses.isEmpty();
        final byte[] chal = wChallenge;
        final List<byte[]> resp = new ArrayList<byte[]>(wResponses);
        final List<String> labels = new ArrayList<String>(wLabels);
        Ui.bg(this, "Re-encrypting vault…", new Ui.Task<VaultCrypto.Key>() {
            public VaultCrypto.Key run() throws Exception {
                return VaultCrypto.create(p.toCharArray(), kf, sk ? chal : null, sk ? resp : null, sk ? labels : null);
            }
        }, new Ui.Cb<VaultCrypto.Key>() {
            public void on(VaultCrypto.Key k) {
                Session.replace(Session.vault(), k);
                if (!Session.saveOrToast(SecurityActivity.this)) return;
                wPassword = null;
                // the data key changed, so fingerprint/PIN wrappers are stale
                boolean hadQuick = Prefs.bioEnabled(SecurityActivity.this) || Prefs.pinEnabled(SecurityActivity.this);
                KeyGuard.bioDisable(SecurityActivity.this);
                KeyGuard.pinDisable(SecurityActivity.this);
                try { UnlockHelper.recordFactors(SecurityActivity.this, Store.read(SecurityActivity.this)); } catch (Exception ignored) {}
                if (kf == null) UnlockHelper.forgetKeyFile(SecurityActivity.this);
                StringBuilder m = new StringBuilder("Saved. Your vault now needs: master password");
                if (kf != null) m.append(" + key file");
                if (sk) m.append(" + one of your ").append(resp.size()).append(resp.size() == 1 ? " security key" : " security keys");
                m.append(".\n\nBackups made before this change still open with the old method. Make a fresh backup now (Backup & import → Back up now).");
                if (kf != null) m.append("\n\nKeep a copy of the key file somewhere safe: without it the vault can't be opened.");
                if (hadQuick) m.append("\n\nFingerprint/PIN unlock was turned off; turn it on again in Settings if allowed.");
                Ui.message(SecurityActivity.this, "Unlock method changed", m.toString());
                render();
            }
        }, null);
    }
}
