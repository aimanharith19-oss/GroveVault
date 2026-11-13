package org.grove.vault;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.grove.vault.core.Card;
import org.grove.vault.core.Csv;
import org.grove.vault.core.SafeInCloud;
import org.grove.vault.core.Label;
import org.grove.vault.core.Vault;
import org.grove.vault.core.VaultCrypto;
import org.grove.vault.core.kdbx.Kdbx;
import org.grove.vault.core.kdbx.KeePassMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Manual backup/restore, automatic folder backups, import (KeePass, CSV, Grove) and export (KeePass, CSV). */
public class BackupActivity extends BaseActivity {
    private static final int REQ_BACKUP = 1, REQ_RESTORE = 2, REQ_TREE = 3, REQ_KDBX_IN = 4, REQ_KEYFILE = 5,
            REQ_CSV_IN = 6, REQ_KDBX_OUT = 7, REQ_CSV_OUT = 8, REQ_SIC_IN = 9, REQ_SIC_OUT = 10;

    private byte[] pendingKdbx;
    private byte[] pendingKeyFile;
    private String pendingKeyName;
    private String pendingPassword = "";
    private byte[] pendingExport;

    @Override
    protected void render() {
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "Backup & import", true));
        LinearLayout c = Ui.vertical(this);
        c.setPadding(0, 0, 0, Ui.dp(this, 40));
        root.addView(Ui.scroll(this, c), Ui.lp(Ui.MATCH, Ui.MATCH));

        c.addView(Ui.section(this, "Backup"));
        c.addView(Ui.row(this, "Back up now", "Save an encrypted copy of your vault (.grove) to your phone, SD card, or Google Drive / Dropbox / OneDrive via their apps", new View.OnClickListener() {
            public void onClick(View v) {
                create(REQ_BACKUP, "grove-backup-" + Store.stamp() + ".grove", "application/octet-stream");
            }
        }));
        c.addView(Ui.row(this, "Restore from backup", "Open a .grove backup and replace or merge with this vault", new View.OnClickListener() {
            public void onClick(View v) {
                open(REQ_RESTORE);
            }
        }));
        final List<File> snaps = Store.snapshots(this);
        c.addView(Ui.row(this, "Restore a local snapshot", snaps.isEmpty() ? "None yet. Snapshots are taken automatically twice a day when you make changes" : snaps.size() + " automatic snapshots on this device", snaps.isEmpty() ? null : new View.OnClickListener() {
            public void onClick(View v) {
                pickSnapshot(snaps);
            }
        }));

        c.addView(Ui.section(this, "Automatic backup"));
        Ui.switchRow(this, c, "Back up to a folder automatically", "Encrypted copies go to a folder you choose, for example a cloud-synced one", Prefs.autoBackup(this), new Ui.Cb<Boolean>() {
            public void on(Boolean on) {
                Prefs.put(BackupActivity.this, "ab_on", on);
                if (on && Prefs.autoBackupTree(BackupActivity.this) == null) chooseFolder();
                else render();
            }
        });
        if (Prefs.autoBackup(this)) {
            String tree = Prefs.autoBackupTree(this);
            c.addView(Ui.row(this, "Folder", tree == null ? "Not chosen" : Uri.decode(Uri.parse(tree).getLastPathSegment()), new View.OnClickListener() {
                public void onClick(View v) { chooseFolder(); }
            }));
            final String[] freq = {"Every change", "Daily", "Weekly"};
            final int[] freqVals = {0, 1, 7};
            int fi = Prefs.autoBackupInterval(this) == 0 ? 0 : Prefs.autoBackupInterval(this) == 7 ? 2 : 1;
            c.addView(Ui.row(this, "Frequency", freq[fi], new View.OnClickListener() {
                public void onClick(View v) {
                    Ui.single(BackupActivity.this, "Back up", freq, Prefs.autoBackupInterval(BackupActivity.this) == 0 ? 0 : Prefs.autoBackupInterval(BackupActivity.this) == 7 ? 2 : 1, new Ui.Cb<Integer>() {
                        public void on(Integer i) { Prefs.put(BackupActivity.this, "ab_interval", freqVals[i]); render(); }
                    });
                }
            }));
            final int[] keeps = {3, 5, 10, 20, 50};
            c.addView(Ui.row(this, "Keep", Prefs.autoBackupKeep(this) + " most recent backups", new View.OnClickListener() {
                public void onClick(View v) {
                    String[] labels = new String[keeps.length];
                    int cur = 0;
                    for (int i = 0; i < keeps.length; i++) {
                        labels[i] = keeps[i] + " backups";
                        if (keeps[i] == Prefs.autoBackupKeep(BackupActivity.this)) cur = i;
                    }
                    Ui.single(BackupActivity.this, "Keep", labels, cur, new Ui.Cb<Integer>() {
                        public void on(Integer i) { Prefs.put(BackupActivity.this, "ab_keep", keeps[i]); render(); }
                    });
                }
            }));
            long last = Prefs.p(this).getLong("ab_last", 0);
            String err = Prefs.p(this).getString("ab_error", null);
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            String status = err != null ? "⚠ Last backup failed: " + err : last > 0 ? "Last backup: " + df.format(new Date(last)) : "No backup yet";
            c.addView(Ui.row(this, "Back up to folder now", status, new View.OnClickListener() {
                public void onClick(View v) {
                    Ui.bg(BackupActivity.this, "Backing up…", new Ui.Task<Boolean>() {
                        public Boolean run() throws Exception {
                            Prefs.put(BackupActivity.this, "ab_last", 0L);
                            Store.autoBackup(BackupActivity.this, Store.read(BackupActivity.this));
                            return Prefs.p(BackupActivity.this).getString("ab_error", null) == null;
                        }
                    }, new Ui.Cb<Boolean>() {
                        public void on(Boolean ok) {
                            Ui.toast(BackupActivity.this, ok ? "Backup saved" : "Backup failed");
                            render();
                        }
                    }, null);
                }
            }));
        }

        c.addView(Ui.section(this, "Import"));
        c.addView(Ui.row(this, "From KeePass (.kdbx)", "KeePass, KeePassXC, KeePassDX, Strongbox… KDBX 3.1 and 4, password and/or key file", new View.OnClickListener() {
            public void onClick(View v) {
                pendingKeyFile = null;
                pendingKeyName = null;
                pendingPassword = "";
                open(REQ_KDBX_IN);
            }
        }));
        c.addView(Ui.row(this, "From SafeInCloud", "SafeInCloud.db (encrypted, from the app's backup or cloud folder) or an XML export from SafeInCloud for Windows/Mac", new View.OnClickListener() {
            public void onClick(View v) {
                open(REQ_SIC_IN);
            }
        }));
        c.addView(Ui.row(this, "From CSV", "Chrome, Edge, Firefox, Bitwarden, LastPass, 1Password, Proton Pass and other CSV exports", new View.OnClickListener() {
            public void onClick(View v) {
                open(REQ_CSV_IN);
            }
        }));

        c.addView(Ui.section(this, "Export"));
        c.addView(Ui.row(this, "To KeePass (.kdbx)", "Encrypted KDBX 4 file that opens in any KeePass app", new View.OnClickListener() {
            public void onClick(View v) {
                exportKdbx();
            }
        }));
        c.addView(Ui.row(this, "To SafeInCloud (.db)", "Encrypted database that SafeInCloud can open (copy it into SafeInCloud's cloud folder or restore it as a backup)", new View.OnClickListener() {
            public void onClick(View v) {
                exportSic();
            }
        }));
        c.addView(Ui.row(this, "To CSV (not encrypted)", "Plain-text file for moving to another password manager", new View.OnClickListener() {
            public void onClick(View v) {
                Ui.confirm(BackupActivity.this, "Unencrypted export", "The CSV file will contain all your passwords in plain text. Anyone who gets the file can read them. Delete it as soon as you're done.", "Export", new Runnable() {
                    public void run() {
                        pendingExport = Csv.export(Session.vault()).getBytes(StandardCharsets.UTF_8);
                        create(REQ_CSV_OUT, "grove-export-" + Store.stamp() + ".csv", "text/csv");
                    }
                });
            }
        }));
    }

    private void open(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    private void create(int req, String name, String mime) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, req);
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (!Session.isUnlocked()) {
            Ui.toast(this, "The vault locked. Unlock and try again.");
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (req == REQ_TREE && Prefs.autoBackupTree(this) == null) Prefs.put(this, "ab_on", false);
            if (req == REQ_KEYFILE && pendingKdbx != null) kdbxCredentials();
            return;
        }
        final Uri uri = data.getData();
        try {
            switch (req) {
                case REQ_BACKUP:
                    Store.writeUri(this, uri, Store.read(this));
                    Prefs.put(this, "manual_backup", System.currentTimeMillis());
                    Ui.message(this, "Backup saved", "The backup is encrypted with your current master password. You'll need that password to restore it.");
                    break;
                case REQ_RESTORE:
                    restoreBytes(Store.readUri(this, uri));
                    break;
                case REQ_TREE:
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Prefs.put(this, "ab_tree", uri.toString());
                    Prefs.put(this, "ab_on", true);
                    Prefs.put(this, "ab_last", 0L);
                    final byte[] cur = Store.read(this);
                    new Thread(new Runnable() { public void run() { Store.autoBackup(BackupActivity.this, cur); } }).start();
                    Ui.toast(this, "Backup folder set");
                    break;
                case REQ_KDBX_IN:
                    pendingKdbx = Store.readUri(this, uri);
                    if (!Kdbx.isKdbx(pendingKdbx)) {
                        pendingKdbx = null;
                        Ui.message(this, "Not a KeePass file", "Choose a .kdbx database file.");
                        return;
                    }
                    kdbxCredentials();
                    break;
                case REQ_KEYFILE:
                    pendingKeyFile = Store.readUri(this, uri);
                    pendingKeyName = displayName(uri);
                    kdbxCredentials();
                    break;
                case REQ_SIC_IN:
                    importSic(Store.readUri(this, uri));
                    break;
                case REQ_SIC_OUT:
                    Store.writeUri(this, uri, pendingExport);
                    pendingExport = null;
                    Ui.message(this, "Exported", "Open it in SafeInCloud with the password you just chose. Tip: in SafeInCloud, use Settings → Database → Restore from backup, or put the file in SafeInCloud's cloud folder.");
                    break;
                case REQ_CSV_IN:
                    importCsv(new String(Store.readUri(this, uri), StandardCharsets.UTF_8));
                    break;
                case REQ_KDBX_OUT:
                case REQ_CSV_OUT:
                    Store.writeUri(this, uri, pendingExport);
                    pendingExport = null;
                    Ui.toast(this, "Exported");
                    break;
            }
        } catch (Exception e) {
            Ui.message(this, "Something went wrong", String.valueOf(e.getMessage()));
        }
    }

    private String displayName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return "key file";
    }

    // ---------------------------------------------------------------- restore

    private void pickSnapshot(final List<File> snaps) {
        String[] names = new String[snaps.size()];
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int i = 0; i < names.length; i++) names[i] = df.format(new Date(snaps.get(i).lastModified()));
        Ui.choose(this, "Snapshots", names, new Ui.Cb<Integer>() {
            public void on(Integer i) {
                try {
                    restoreBytes(Files.readAllBytes(snaps.get(i).toPath()));
                } catch (Exception e) {
                    Ui.message(BackupActivity.this, "Can't read snapshot", String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private void restoreBytes(final byte[] file) {
        if (SafeInCloud.isDb(file) || SafeInCloud.isXml(file)) {
            importSic(file);
            return;
        }
        if (!VaultCrypto.isVaultFile(file)) {
            Ui.message(this, "Not a Grove backup", "To bring in a KeePass or CSV file, use Import instead.");
            return;
        }
        // Same vault and data key? Then no password is needed.
        if (VaultCrypto.sameKey(file, Session.key())) {
            try {
                askRestoreMode(file, VaultCrypto.decryptVault(file, Session.key()), Session.key());
                return;
            } catch (Exception ignored) {}
        }
        UnlockHelper.unlockFile(this, file, "Backup password", null, new Ui.Cb<VaultCrypto.Key>() {
            public void on(VaultCrypto.Key k) {
                try {
                    askRestoreMode(file, VaultCrypto.decryptVault(file, k), k);
                } catch (Exception e) {
                    Ui.message(BackupActivity.this, "Can't open backup", String.valueOf(e.getMessage()));
                }
            }
        }, new Ui.Cb<Exception>() {
            public void on(Exception e) {
                Ui.message(BackupActivity.this, "Can't open backup", String.valueOf(e.getMessage()));
            }
        });
    }

    private void askRestoreMode(final byte[] file, final Vault backup, final VaultCrypto.Key backupKey) {
        int n = backup.activeCards().size();
        Ui.dialog(this, "Restore " + n + " cards").setMessage("Replace: your vault becomes exactly this backup (and its master password).\n\nMerge: cards from the backup are added; for cards in both, the newer version wins.")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Replace", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Ui.confirm(BackupActivity.this, "Replace vault?", "All current cards will be replaced with the backup. This can't be undone (a snapshot of the current vault is kept).", "Replace", new Runnable() {
                            public void run() {
                                try {
                                    File snap = new File(Store.snapshotDir(BackupActivity.this), "snapshot-" + Store.stamp() + "-before-restore.grove");
                                    Files.write(snap.toPath(), Store.read(BackupActivity.this));
                                    Store.write(BackupActivity.this, file);
                                    if (!Arrays.equals(backupKey.key, Session.key().key)) {
                                        KeyGuard.bioDisable(BackupActivity.this);
                                        KeyGuard.pinDisable(BackupActivity.this);
                                    }
                                    Session.replace(backup, backupKey);
                                    UnlockHelper.recordFactors(BackupActivity.this, file);
                                    Ui.toast(BackupActivity.this, "Vault restored");
                                    finish();
                                } catch (Exception e) {
                                    Ui.message(BackupActivity.this, "Restore failed", String.valueOf(e.getMessage()));
                                }
                            }
                        });
                    }
                })
                .setPositiveButton("Merge", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int[] r = merge(Session.vault(), backup);
                        if (Session.saveOrToast(BackupActivity.this))
                            Ui.message(BackupActivity.this, "Merged", r[0] + " cards added, " + r[1] + " updated.");
                    }
                }).show();
    }

    static int[] merge(Vault into, Vault from) {
        return into.mergeFrom(from);
    }

    // ---------------------------------------------------------------- KeePass import

    private void kdbxCredentials() {
        LinearLayout box = Ui.vertical(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final EditText pw = Ui.edit(this, "Database password", Ui.PASSWORD_INPUT);
        pw.setText(pendingPassword);
        box.addView(pw);
        final CheckBox bin = new CheckBox(this);
        bin.setText("Also import the Recycle Bin (into Trash)");
        bin.setTextColor(Ui.TEXT);
        TextView kf = Ui.flatButton(this, pendingKeyName == null ? "🔑  Add key file (optional)" : "🔑  Key file: " + pendingKeyName, null);
        box.addView(kf, Ui.margins(this, Ui.WRAP, Ui.WRAP, 0, 8, 0, 0));
        box.addView(bin);
        final AlertDialog d = Ui.dialog(this, "Open KeePass database").setView(box)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) { pendingKdbx = null; }
                })
                .setPositiveButton("Import", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        importKdbx(pw.getText().toString(), bin.isChecked());
                    }
                }).create();
        kf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pendingPassword = pw.getText().toString();
                d.dismiss();
                open(REQ_KEYFILE);
            }
        });
        d.show();
        Ui.showKeyboard(pw);
    }

    private void importKdbx(final String password, final boolean includeBin) {
        final byte[] file = pendingKdbx;
        final byte[] key = pendingKeyFile;
        pendingPassword = password;
        Ui.bg(this, "Opening KeePass database…", new Ui.Task<Kdbx.Database>() {
            public Kdbx.Database run() throws Exception {
                if (password.length() == 0) {
                    // KeePass treats "no password" and "empty password" differently; try both.
                    try {
                        return Kdbx.read(file, null, key);
                    } catch (VaultCrypto.WrongPasswordException e) {
                        return Kdbx.read(file, "", key);
                    }
                }
                return Kdbx.read(file, password, key);
            }
        }, new Ui.Cb<Kdbx.Database>() {
            public void on(Kdbx.Database db) {
                KeePassMapper.Result r = KeePassMapper.importInto(Session.vault(), db, includeBin);
                pendingKdbx = null;
                pendingKeyFile = null;
                pendingPassword = "";
                if (Session.saveOrToast(BackupActivity.this)) {
                    String msg = r.imported + " entries imported" + (db.name.length() > 0 ? " from “" + db.name + "”" : "") + ". KeePass groups and tags became labels.";
                    if (r.skippedRecycleBin > 0) msg += "\n\n" + r.skippedRecycleBin + " entries in the Recycle Bin were skipped.";
                    Ui.message(BackupActivity.this, "Import complete", msg);
                }
            }
        }, new Ui.Cb<Exception>() {
            public void on(Exception e) {
                if (e instanceof VaultCrypto.WrongPasswordException) {
                    Ui.toast(BackupActivity.this, "Wrong password or key file");
                    kdbxCredentials();
                } else {
                    pendingKdbx = null;
                    Ui.message(BackupActivity.this, "Can't import", String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private void importCsv(String text) {
        int n = Csv.importInto(Session.vault(), text);
        if (n == 0) {
            Ui.message(this, "Nothing imported", "No entries were found. The first line of the CSV must be a header row (for example: name,url,username,password).");
            return;
        }
        if (Session.saveOrToast(this))
            Ui.message(this, "Import complete", n + " entries imported. Delete the CSV file now: it contains your passwords in plain text.");
    }

    // ---------------------------------------------------------------- KeePass export

    private void exportKdbx() {
        LinearLayout box = Ui.vertical(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final EditText p1 = Ui.edit(this, "New password for the KeePass file", Ui.PASSWORD_INPUT);
        final EditText p2 = Ui.edit(this, "Repeat password", Ui.PASSWORD_INPUT);
        box.addView(p1);
        box.addView(p2, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 8));
        box.addView(Ui.text(this, "Key derivation", 13, Ui.SUB, false));
        final RadioGroup kdf = new RadioGroup(this);
        String[] kdfs = {"Argon2d (KeePassXC / KeePassDX default)", "Argon2id", "AES-KDF (most compatible, older KeePass)"};
        for (int i = 0; i < kdfs.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(kdfs[i]);
            rb.setTextColor(Ui.TEXT);
            rb.setId(100 + i);
            kdf.addView(rb);
        }
        kdf.check(100);
        box.addView(kdf);
        final CheckBox chacha = new CheckBox(this);
        chacha.setText("Encrypt with ChaCha20 instead of AES-256");
        chacha.setTextColor(Ui.TEXT);
        box.addView(chacha);
        final CheckBox trash = new CheckBox(this);
        trash.setText("Include Trash (as Recycle Bin)");
        trash.setTextColor(Ui.TEXT);
        box.addView(trash);
        Ui.dialog(this, "Export to KeePass").setView(Ui.scroll(this, box)).setNegativeButton("Cancel", null)
                .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String a = p1.getText().toString();
                        if (a.length() < 4 || !a.equals(p2.getText().toString())) {
                            Ui.message(BackupActivity.this, "Check the password", a.length() < 4 ? "Use at least 4 characters." : "The passwords don't match.");
                            return;
                        }
                        final Kdbx.Params p = new Kdbx.Params();
                        p.kdf = kdf.getCheckedRadioButtonId() - 100;
                        p.chacha20 = chacha.isChecked();
                        final boolean withTrash = trash.isChecked();
                        Ui.bg(BackupActivity.this, "Encrypting KeePass file…", new Ui.Task<byte[]>() {
                            public byte[] run() throws Exception {
                                return Kdbx.write(KeePassMapper.export(Session.vault(), withTrash), a, null, p);
                            }
                        }, new Ui.Cb<byte[]>() {
                            public void on(byte[] data) {
                                pendingExport = data;
                                create(REQ_KDBX_OUT, "grove-vault-" + Store.stamp() + ".kdbx", "application/octet-stream");
                            }
                        }, null);
                    }
                }).show();
    }

    // ---------------------------------------------------------------- SafeInCloud

    private void importSic(final byte[] file) {
        if (SafeInCloud.isXml(file)) {
            runSicImport(file, null);
            return;
        }
        if (!SafeInCloud.isDb(file)) {
            Ui.message(this, "Not a SafeInCloud file", "Choose SafeInCloud.db (or a .db backup) or an XML file exported from SafeInCloud.");
            return;
        }
        Ui.prompt(this, "SafeInCloud password", "Master password of the SafeInCloud database", null, Ui.PASSWORD_INPUT, new Ui.Cb<String>() {
            public void on(String p) {
                runSicImport(file, p);
            }
        });
    }

    private void runSicImport(final byte[] file, final String password) {
        Ui.bg(this, "Opening SafeInCloud database…", new Ui.Task<byte[]>() {
            public byte[] run() throws Exception {
                return password == null ? file : SafeInCloud.decrypt(file, password);
            }
        }, new Ui.Cb<byte[]>() {
            public void on(byte[] xml) {
                try {
                    SafeInCloud.Result r = SafeInCloud.importInto(Session.vault(), xml);
                    if (Session.saveOrToast(BackupActivity.this)) {
                        String msg = r.cards + " cards imported";
                        if (r.trashed > 0) msg += ", " + r.trashed + " deleted cards put in Trash";
                        if (r.templates > 0) msg += ", " + r.templates + " templates";
                        if (r.attachments > 0) msg += ", " + r.attachments + " attachments";
                        msg += ". SafeInCloud labels are now Grove labels; archived cards have the label “Archived”.";
                        if (password == null) msg += "\n\nDelete the XML file now: it contains your passwords in plain text.";
                        Ui.message(BackupActivity.this, "Import complete", msg);
                    }
                } catch (Exception e) {
                    Ui.message(BackupActivity.this, "Can't import", String.valueOf(e.getMessage()));
                }
            }
        }, new Ui.Cb<Exception>() {
            public void on(Exception e) {
                if (e instanceof VaultCrypto.WrongPasswordException) {
                    Ui.toast(BackupActivity.this, "Wrong password");
                    importSic(file);
                } else Ui.message(BackupActivity.this, "Can't import", String.valueOf(e.getMessage()));
            }
        });
    }

    private void exportSic() {
        LinearLayout box = Ui.vertical(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final EditText p1 = Ui.edit(this, "Password for the SafeInCloud file", Ui.PASSWORD_INPUT);
        final EditText p2 = Ui.edit(this, "Repeat password", Ui.PASSWORD_INPUT);
        box.addView(p1);
        box.addView(p2, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 8));
        final CheckBox trash = new CheckBox(this);
        trash.setText("Include Trash (as deleted cards)");
        trash.setTextColor(Ui.TEXT);
        box.addView(trash);
        Ui.dialog(this, "Export to SafeInCloud").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String a = p1.getText().toString();
                        if (a.length() < 1 || !a.equals(p2.getText().toString())) {
                            Ui.message(BackupActivity.this, "Check the password", a.length() < 1 ? "Enter a password." : "The passwords don't match.");
                            return;
                        }
                        final boolean withTrash = trash.isChecked();
                        Ui.bg(BackupActivity.this, "Encrypting SafeInCloud file…", new Ui.Task<byte[]>() {
                            public byte[] run() throws Exception {
                                return SafeInCloud.encrypt(SafeInCloud.export(Session.vault(), withTrash), a);
                            }
                        }, new Ui.Cb<byte[]>() {
                            public void on(byte[] data) {
                                pendingExport = data;
                                create(REQ_SIC_OUT, "SafeInCloud.db", "application/octet-stream");
                            }
                        }, null);
                    }
                }).show();
    }
}
