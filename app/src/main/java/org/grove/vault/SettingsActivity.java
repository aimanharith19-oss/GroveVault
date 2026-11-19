package org.grove.vault;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.grove.vault.core.VaultCrypto;

import javax.crypto.Cipher;

/** Security, autofill, appearance and data settings. */
public class SettingsActivity extends BaseActivity {
    private static final int REQ_SAVE_CRX = 7311;

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE_CRX || res != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.InputStream in = getAssets().open("GroveVault.crx");
             java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Ui.toast(this, "Saved. Now drag it onto Vivaldi's Extensions page");
        } catch (Exception e) {
            Ui.toast(this, "Couldn't save: " + e.getMessage());
        }
    }

    private void showCode(boolean paired) {
        final String code = paired ? ExtBridge.code(this) : ExtBridge.newCode(this);
        android.widget.TextView t = Ui.text(this, code, 24, Ui.TEXT, true);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 24), Ui.dp(this, 4), Ui.dp(this, 24), Ui.dp(this, 8));
        android.widget.LinearLayout l = Ui.vertical(this);
        l.addView(Ui.text(this, "In the browser, open the GroveVault extension → Settings, and enter:", 14, Ui.SUB, false), Ui.margins(this, Ui.MATCH, Ui.WRAP, 24, 0, 24, 8));
        l.addView(t);
        l.addView(Ui.text(this, "Keep Grove Vault unlocked (or unlock it) when you log in.", 13, Ui.SUB, false), Ui.margins(this, Ui.MATCH, Ui.WRAP, 24, 4, 24, 0));
        android.app.AlertDialog.Builder b = Ui.dialog(this, "Pair the extension").setView(l).setPositiveButton("Done", null)
                .setNeutralButton("Copy", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code));
                        Ui.toast(SettingsActivity.this, "Copied");
                    }
                });
        if (paired) b.setNegativeButton("New code", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { showCode(false); }
        });
        b.show();
        if (!paired) render();
    }


    @Override
    protected void render() {
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "Settings", true));
        LinearLayout c = Ui.vertical(this);
        c.setPadding(0, 0, 0, Ui.dp(this, 40));
        root.addView(Ui.scroll(this, c), Ui.lp(Ui.MATCH, Ui.MATCH));

        // ---------------- security
        c.addView(Ui.section(this, "Security"));
        final String[] lockNames = {"Immediately when leaving the app", "After 30 seconds", "After 1 minute", "After 5 minutes", "After 15 minutes", "After 1 hour", "Never"};
        final int[] lockVals = {0, 30, 60, 300, 900, 3600, -1};
        c.addView(Ui.row(this, "Auto-lock", lockNames[indexOf(lockVals, Prefs.autoLockSeconds(this), 2)], new View.OnClickListener() {
            public void onClick(View v) {
                Ui.single(SettingsActivity.this, "Auto-lock", lockNames, indexOf(lockVals, Prefs.autoLockSeconds(SettingsActivity.this), 2), new Ui.Cb<Integer>() {
                    public void on(Integer i) { Prefs.put(SettingsActivity.this, "autolock", lockVals[i]); render(); }
                });
            }
        }));
        Ui.switchRow(this, c, "Lock when the screen turns off", null, Prefs.lockOnScreenOff(this), new Ui.Cb<Boolean>() {
            public void on(Boolean b) { Prefs.put(SettingsActivity.this, "lock_screen_off", b); }
        });
        c.addView(Ui.row(this, "🔐  Security key & key file", "Log in with a YubiKey (NFC or USB) and/or a key file, change the master password", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, SecurityActivity.class)); }
        }));
        final boolean quickOk = UnlockHelper.quickUnlockAllowed(this);
        if (!quickOk) {
            c.addView(Ui.row(this, "Fingerprint / PIN unlock", "Off because your vault needs a key file or security key on every unlock (change this in Security key & key file)", null));
        }
        if (KeyGuard.bioSupported() && quickOk) {
            Ui.switchRow(this, c, "Unlock with biometrics", "Fingerprint or face, using the phone's secure hardware", Prefs.bioEnabled(this), new Ui.Cb<Boolean>() {
                public void on(Boolean b) {
                    if (b) enableBio();
                    else { KeyGuard.bioDisable(SettingsActivity.this); render(); }
                }
            });
        }
        if (quickOk) Ui.switchRow(this, c, "Unlock with PIN", "A 6–12 digit PIN instead of the master password, protected by the phone's secure hardware. 3 wrong tries turn it off", Prefs.pinEnabled(this), new Ui.Cb<Boolean>() {
            public void on(Boolean b) {
                if (b) setPin();
                else { KeyGuard.pinDisable(SettingsActivity.this); render(); }
            }
        });
        final String[] clipNames = {"After 15 seconds", "After 30 seconds", "After 1 minute", "After 2 minutes", "Never"};
        final int[] clipVals = {15, 30, 60, 120, 0};
        c.addView(Ui.row(this, "Clear copied passwords", clipNames[indexOf(clipVals, Prefs.clipboardSeconds(this), 1)], new View.OnClickListener() {
            public void onClick(View v) {
                Ui.single(SettingsActivity.this, "Clear clipboard", clipNames, indexOf(clipVals, Prefs.clipboardSeconds(SettingsActivity.this), 1), new Ui.Cb<Integer>() {
                    public void on(Integer i) { Prefs.put(SettingsActivity.this, "clip_secs", clipVals[i]); render(); }
                });
            }
        }));
        Ui.switchRow(this, c, "Allow screenshots", "Off hides the app from screenshots and the recent-apps preview", Prefs.allowScreenshots(this), new Ui.Cb<Boolean>() {
            public void on(Boolean b) { Prefs.put(SettingsActivity.this, "screenshots", b); recreate(); }
        });
        final String[] eraseNames = {"Never", "After 5 wrong passwords", "After 10 wrong passwords", "After 20 wrong passwords"};
        final int[] eraseVals = {0, 5, 10, 20};
        c.addView(Ui.row(this, "Erase vault on wrong passwords", eraseNames[indexOf(eraseVals, Prefs.eraseAfter(this), 0)], new View.OnClickListener() {
            public void onClick(View v) {
                Ui.single(SettingsActivity.this, "Erase the vault on this device", eraseNames, indexOf(eraseVals, Prefs.eraseAfter(SettingsActivity.this), 0), new Ui.Cb<Integer>() {
                    public void on(final Integer i) {
                        if (eraseVals[i] == 0) { Prefs.put(SettingsActivity.this, "erase_after", 0); render(); return; }
                        Ui.confirm(SettingsActivity.this, "Are you sure?", "After " + eraseVals[i] + " wrong master passwords in a row, the vault on this phone is deleted. Make sure you have a backup.", "Turn on", new Runnable() {
                            public void run() { Prefs.put(SettingsActivity.this, "erase_after", eraseVals[i]); render(); }
                        });
                    }
                });
            }
        }));
        c.addView(Ui.row(this, "Change master password", null, new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, SecurityActivity.class)); }
        }));

        // ---------------- autofill
        c.addView(Ui.section(this, "Autofill"));
        if (android.os.Build.VERSION.SDK_INT >= 33) c.addView(Ui.row(this, "Add Quick Settings pill", "A \"Grove autofill\" pill in your notification shade: turns autofill on, or pauses it", new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.app.StatusBarManager sb = getSystemService(android.app.StatusBarManager.class);
                    sb.requestAddTileService(new android.content.ComponentName(SettingsActivity.this, AutofillTile.class), "Grove autofill",
                            android.graphics.drawable.Icon.createWithResource(SettingsActivity.this, R.drawable.ic_key), getMainExecutor(),
                            new java.util.function.Consumer<Integer>() {
                                public void accept(Integer r) {
                                    if (r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) Ui.toast(SettingsActivity.this, "Added. Find it in Quick Settings");
                                    else if (r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) Ui.toast(SettingsActivity.this, "Already in Quick Settings");
                                }
                            });
                } catch (Exception e) {
                    Ui.toast(SettingsActivity.this, "Pull down Quick Settings, tap the pencil and drag \"Grove autofill\" in");
                }
            }
        }));
        boolean on = false;
        try {
            AutofillManager am = getSystemService(AutofillManager.class);
            on = am != null && am.hasEnabledAutofillServices();
        } catch (Exception ignored) {}
        c.addView(Ui.row(this, "Autofill service", on ? "✅ Grove Vault fills logins in apps and browsers" : "Off. Tap to choose Grove Vault as your autofill service", new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
                }
            }
        }));
        c.addView(Ui.row(this, "Passkeys", Build.VERSION.SDK_INT >= 34
                ? "Save and use passkeys in Grove Vault. Tap to choose Grove Vault under Passwords, passkeys & accounts"
                : "Needs Android 14 or newer. Passkeys imported from KeePassXC are kept and exported", new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT < 34) return;
                try {
                    Intent i = new Intent("android.settings.CREDENTIAL_PROVIDER");
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
                }
            }
        }));
        final String[] afStyles = {"Keyboard chips + pop-up", "Pop-up only"};
        final String[] afVals = {"both", "popup"};
        int afi = "popup".equals(Prefs.p(this).getString("af_style", "both")) ? 1 : 0;
        c.addView(Ui.row(this, "Autofill suggestions", afStyles[afi] + ". Shown on login forms; on other fields, long-press and choose Autofill", new View.OnClickListener() {
            public void onClick(View v) {
                Ui.single(SettingsActivity.this, "Autofill suggestions", afStyles, "popup".equals(Prefs.p(SettingsActivity.this).getString("af_style", "both")) ? 1 : 0, new Ui.Cb<Integer>() {
                    public void on(Integer i) { Prefs.put(SettingsActivity.this, "af_style", afVals[i]); render(); }
                });
            }
        }));
        Ui.switchRow(this, c, "Copy one-time code after filling", "After filling a login that has 2FA, the current code is copied", Prefs.otpAfterFill(this), new Ui.Cb<Boolean>() {
            public void on(Boolean b) { Prefs.put(SettingsActivity.this, "otp_after_fill", b); }
        });

        // ---------------- browser extension
        c.addView(Ui.section(this, "Browser extension"));
        c.addView(Ui.row(this, "Get the extension", "Saves GroveVault.crx. In Vivaldi: Extensions → Developer mode → drag the file from Files onto the page", new View.OnClickListener() {
            public void onClick(View v) {
                Session.pickerStarted();
                startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/x-chrome-extension")
                        .putExtra(android.content.Intent.EXTRA_TITLE, "GroveVault.crx"), REQ_SAVE_CRX);
            }
        }));
        final boolean paired = ExtBridge.paired(this);
        c.addView(Ui.row(this, paired ? "Pairing code" : "Pair the extension", paired ? "Paired. Tap to show the code again or make a new one"
                : "Shows a code to type into the extension's settings (one time)", new View.OnClickListener() {
            public void onClick(View v) { showCode(paired); }
        }));
        if (paired) c.addView(Ui.row(this, "Unlink extension", "The extension can no longer read your vault", new View.OnClickListener() {
            public void onClick(View v) {
                Ui.confirm(SettingsActivity.this, "Unlink extension?", "Browsers paired with this code will stop filling logins.", "Unlink", new Runnable() {
                    public void run() { ExtBridge.unlink(SettingsActivity.this); render(); }
                });
            }
        }));

        // ---------------- appearance
        c.addView(Ui.section(this, "Appearance"));
        final String[] themes = {"System default", "Light", "Dark", "Dark"};
        // Windows Phone accent picker: 20 square swatches, the current one outlined
        int cur = Prefs.accent(this);
        String curName = "custom";
        for (int k = 0; k < Ui.ACCENTS.length; k++) if (Ui.ACCENTS[k] == cur) curName = Ui.ACCENT_NAMES[k];
        c.addView(Ui.row(this, "Accent colour", curName, null));
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(5);
        grid.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        int cell = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32)) / 5;
        for (int k = 0; k < Ui.ACCENTS.length; k++) {
            final int col = Ui.ACCENTS[k];
            View sw = new View(this);
            sw.setBackground(Ui.stroke(col, col == cur ? Ui.TEXT : col, Ui.dp(this, 3), Ui.dp(this, Ui.R_CONTROL)));
            sw.setContentDescription(Ui.ACCENT_NAMES[k]);
            android.widget.GridLayout.LayoutParams gp = new android.widget.GridLayout.LayoutParams();
            gp.width = cell - Ui.dp(this, 8);
            gp.height = cell - Ui.dp(this, 8);
            gp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            Ui.ripple(sw, 0, 0);
            sw.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Prefs.put(SettingsActivity.this, "accent", col); recreate(); }
            });
            grid.addView(sw, gp);
        }
        c.addView(grid);
        c.addView(Ui.row(this, "Background", themes[Prefs.theme(this)], new View.OnClickListener() {
            public void onClick(View v) {
                Ui.single(SettingsActivity.this, "Background", new String[]{"System default", "Light", "Dark"}, Math.min(2, Prefs.theme(SettingsActivity.this)), new Ui.Cb<Integer>() {
                    public void on(Integer i) { Prefs.put(SettingsActivity.this, "theme", i); recreate(); }
                });
            }
        }));
        Ui.switchRow(this, c, "Download website icons", "Fetched straight from each card's website (only the site address is used, nothing from your vault). Cards linked to an app use the app's icon", Prefs.p(this).getBoolean("icons", true), new Ui.Cb<Boolean>() {
            public void on(Boolean b) { Prefs.put(SettingsActivity.this, "icons", b); }
        });
        Ui.switchRow(this, c, "Favourites at the top", null, Prefs.favoritesOnTop(this), new Ui.Cb<Boolean>() {
            public void on(Boolean b) { Prefs.put(SettingsActivity.this, "fav_top", b); }
        });
        Ui.switchRow(this, c, "Show security warnings in the list", "Weak, reused and expired passwords", Prefs.showWarnings(this), new Ui.Cb<Boolean>() {
            public void on(Boolean b) { Prefs.put(SettingsActivity.this, "warnings", b); }
        });
        final String[] expNames = {"7 days", "14 days", "30 days", "60 days", "90 days"};
        final int[] expVals = {7, 14, 30, 60, 90};
        c.addView(Ui.row(this, "Warn about expiring cards", expNames[indexOf(expVals, Prefs.expiryDays(this), 2)] + " ahead", new View.OnClickListener() {
            public void onClick(View v) {
                Ui.single(SettingsActivity.this, "Warn ahead", expNames, indexOf(expVals, Prefs.expiryDays(SettingsActivity.this), 2), new Ui.Cb<Integer>() {
                    public void on(Integer i) { Prefs.put(SettingsActivity.this, "expiry_days", expVals[i]); render(); }
                });
            }
        }));

        // ---------------- data
        c.addView(Ui.section(this, "Data"));
        c.addView(Ui.row(this, "Backup & import", "Backups, KeePass and CSV", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, BackupActivity.class)); }
        }));
        LinearLayout erase = Ui.row(this, "Erase all data", "Delete the vault and snapshots from this phone", new View.OnClickListener() {
            public void onClick(View v) { eraseAll(); }
        });
        ((android.widget.TextView) ((LinearLayout) erase.getChildAt(0)).getChildAt(0)).setTextColor(Ui.DANGER);
        c.addView(erase);

        c.addView(Ui.section(this, "About"));
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        c.addView(Ui.row(this, "Grove Vault " + ver, "Open source (GPL-3.0). No ads, no trackers, no accounts. The only internet use is downloading website icons (can be turned off above); your vault never leaves the phone except in files you save. Vault: AES-256-GCM with Argon2id (64 MiB) key derivation; optional key file and YubiKey.", null));
        Ui.pivotize(this, c, "general");
    }

    private static int indexOf(int[] a, int v, int def) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return def;
    }

    private void enableBio() {
        if (Build.VERSION.SDK_INT < 28) return;
        final Cipher cipher;
        try {
            cipher = KeyGuard.bioEncryptCipher();
        } catch (Exception e) {
            Ui.message(this, "Biometrics unavailable", "Set up a fingerprint or face unlock in the phone's settings first.\n\n" + e.getMessage());
            render();
            return;
        }
        new BiometricPrompt.Builder(this).setTitle("Turn on biometric unlock")
                .setNegativeButton("Cancel", getMainExecutor(), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { render(); }
                }).build()
                .authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        try {
                            KeyGuard.bioStore(SettingsActivity.this, r.getCryptoObject().getCipher(), Session.key().key);
                            Ui.toast(SettingsActivity.this, "Biometric unlock is on");
                        } catch (Exception e) {
                            Ui.message(SettingsActivity.this, "Couldn't turn on", String.valueOf(e.getMessage()));
                        }
                        render();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        render();
                    }
                });
    }

    private void setPin() {
        final int type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        Ui.prompt(this, "Choose a PIN (6–12 digits)", "PIN", null, type, new Ui.Cb<String>() {
            public void on(final String pin) {
                if (!pin.matches("\\d{6,12}") || pin.matches("(\\d)\\1+") || "0123456789012".contains(pin) || "9876543210987".contains(pin)) {
                    Ui.message(SettingsActivity.this, "Choose another PIN", "Use 6 to 12 digits, and avoid repeated or sequential digits like 111111 or 123456.");
                    render();
                    return;
                }
                Ui.prompt(SettingsActivity.this, "Repeat the PIN", "PIN", null, type, new Ui.Cb<String>() {
                    public void on(String again) {
                        if (!again.equals(pin)) {
                            Ui.message(SettingsActivity.this, "PINs don't match", "Try again.");
                            render();
                            return;
                        }
                        final byte[] key = Session.key().key.clone();
                        Ui.bg(SettingsActivity.this, "Setting up PIN…", new Ui.Task<Boolean>() {
                            public Boolean run() throws Exception {
                                KeyGuard.pinStore(SettingsActivity.this, pin, key);
                                return true;
                            }
                        }, new Ui.Cb<Boolean>() {
                            public void on(Boolean b) { Ui.toast(SettingsActivity.this, "PIN unlock is on"); render(); }
                        }, null);
                    }
                });
            }
        });
        render();
    }

    private void eraseAll() {
        Ui.prompt(this, "Erase everything?", "Type ERASE to confirm", null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, new Ui.Cb<String>() {
            public void on(String s) {
                if (!s.trim().equals("ERASE")) return;
                Session.lock();
                Store.eraseAll(SettingsActivity.this);
                KeyGuard.bioDisable(SettingsActivity.this);
                KeyGuard.pinDisable(SettingsActivity.this);
                Intent i = new Intent(SettingsActivity.this, LockActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }
        });
    }

}
