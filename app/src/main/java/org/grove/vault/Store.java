package org.grove.vault;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Vault file on disk, plus automatic backups (internal snapshots and an optional user-chosen folder). */
public final class Store {
    private Store() {}

    public static File vaultFile(Context c) {
        return new File(c.getFilesDir(), "vault.grove");
    }

    public static boolean exists(Context c) {
        return vaultFile(c).exists();
    }

    public static byte[] read(Context c) throws IOException {
        return Files.readAllBytes(vaultFile(c).toPath());
    }

    /** Atomic write: temp file, fsync, rename. */
    public static synchronized void write(Context c, byte[] data) throws IOException {
        File f = vaultFile(c);
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(data);
            o.getFD().sync();
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("Could not replace vault file");
    }

    public static byte[] readUri(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open file");
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 64 * 1024 * 1024) throw new IOException("File too large");
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    public static void writeUri(Context c, Uri uri, byte[] data) throws IOException {
        OutputStream o = c.getContentResolver().openOutputStream(uri, "wt");
        if (o == null) throw new IOException("Cannot write file");
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    // ---------------------------------------------------------------- internal snapshots

    public static File snapshotDir(Context c) {
        File d = new File(c.getFilesDir(), "snapshots");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<File> snapshots(Context c) {
        File[] fs = snapshotDir(c).listFiles();
        List<File> l = new ArrayList<File>();
        if (fs != null) l.addAll(Arrays.asList(fs));
        Collections.sort(l);
        Collections.reverse(l);
        return l;
    }

    /** Called after every save, on a background thread. */
    public static void autoBackup(Context c, byte[] data) {
        long now = System.currentTimeMillis();
        // Internal snapshot at most every 12 hours, keep 7.
        try {
            long last = Prefs.p(c).getLong("snap_last", 0);
            if (now - last > 12 * 3600 * 1000L) {
                File f = new File(snapshotDir(c), "snapshot-" + stamp() + ".grove");
                FileOutputStream o = new FileOutputStream(f);
                o.write(data);
                o.close();
                Prefs.put(c, "snap_last", now);
                List<File> all = snapshots(c);
                for (int i = 7; i < all.size(); i++) all.get(i).delete();
            }
        } catch (IOException ignored) {
        }
        // External folder backup.
        if (!Prefs.autoBackup(c) || Prefs.autoBackupTree(c) == null) return;
        long last = Prefs.p(c).getLong("ab_last", 0);
        int interval = Prefs.autoBackupInterval(c);
        if (interval > 0 && now - last < interval * 86400000L - 600000L) return;
        try {
            Uri tree = Uri.parse(Prefs.autoBackupTree(c));
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri doc = DocumentsContract.createDocument(c.getContentResolver(), dir, "application/octet-stream", "grove-backup-" + stamp() + ".grove");
            if (doc == null) throw new IOException("Could not create backup file");
            writeUri(c, doc, data);
            Prefs.put(c, "ab_last", now);
            Prefs.put(c, "ab_error", null);
            prune(c, tree, Prefs.autoBackupKeep(c));
        } catch (Exception e) {
            Prefs.put(c, "ab_error", e.getMessage() == null ? e.toString() : e.getMessage());
            Prefs.put(c, "ab_error_time", now);
        }
    }

    private static void prune(Context c, Uri tree, int keep) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        List<String[]> found = new ArrayList<String[]>();
        Cursor cur = c.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (cur == null) return;
        try {
            while (cur.moveToNext()) {
                String name = cur.getString(1);
                if (name != null && name.startsWith("grove-backup-") && name.endsWith(".grove")) found.add(new String[]{name, cur.getString(0)});
            }
        } finally {
            cur.close();
        }
        Collections.sort(found, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return b[0].compareTo(a[0]); }
        });
        for (int i = keep; i < found.size(); i++) {
            try {
                DocumentsContract.deleteDocument(c.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, found.get(i)[1]));
            } catch (Exception ignored) {
            }
        }
    }

    /** Deletes the vault and internal snapshots (used by "erase all data"). */
    public static void eraseAll(Context c) {
        vaultFile(c).delete();
        for (File f : snapshots(c)) f.delete();
    }
}
