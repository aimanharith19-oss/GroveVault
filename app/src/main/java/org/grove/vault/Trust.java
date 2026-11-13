package org.grove.vault;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.grove.vault.core.Json;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Who is asking? Verifies apps by their signing certificate (package names alone can be faked by sideloaded apps). */
public final class Trust {
    private Trust() {}

    /** SHA-256 of the app's current signing certificate as "AA:BB:…", or "" if unknown. */
    @SuppressWarnings("deprecation")
    public static String certFingerprint(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo == null) return "";
                sigs = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
                // with key rotation, the newest certificate is last in the history
                if (!pi.signingInfo.hasMultipleSigners() && sigs.length > 1) sigs = new Signature[]{sigs[sigs.length - 1]};
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) return "";
            return fingerprint(sigs[0].toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    public static String fingerprint(byte[] cert) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert);
        StringBuilder b = new StringBuilder();
        for (byte x : d) {
            if (b.length() > 0) b.append(':');
            b.append(String.format(Locale.ROOT, "%02X", x & 0xff));
        }
        return b.toString();
    }

    private static List<Object> browsers;

    /** True if the package is a known browser AND is signed by that browser's publisher. */
    public static boolean isTrustedBrowser(Context c, String pkg) {
        return isTrustedBrowser(c, pkg, certFingerprint(c, pkg));
    }

    public static boolean isTrustedBrowser(Context c, String pkg, String fp) {
        if (pkg == null || fp == null || fp.length() == 0) return false;
        try {
            if (browsers == null) {
                InputStream in = c.getResources().openRawResource(R.raw.privileged_browsers);
                byte[] b = org.grove.vault.core.VaultCrypto.readLimited(in, 1024 * 1024);
                in.close();
                browsers = Json.arr(Json.obj(Json.parse(new String(b, "UTF-8"))).get("apps"));
            }
            for (Object o : browsers) {
                Map<String, Object> app = Json.obj(Json.obj(o).get("info"));
                if (!pkg.equals(Json.str(app, "package_name"))) continue;
                for (Object s : Json.arr(app.get("signatures")))
                    if (fp.equalsIgnoreCase(Json.str(Json.obj(s), "cert_fingerprint_sha256"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
