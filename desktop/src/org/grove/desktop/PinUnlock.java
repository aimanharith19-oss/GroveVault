package org.grove.desktop;

import org.grove.vault.core.VaultCrypto;

import java.io.File;
import java.security.MessageDigest;

/**
 * Quick unlock with a PIN on Windows: the data key is sealed with a PIN-derived key and then with Windows DPAPI,
 * so the blob is useless on another PC or Windows account. Three wrong PINs delete it.
 */
public final class PinUnlock {
    private PinUnlock() {}

    private static String id(File f) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(f.getAbsolutePath().toLowerCase().getBytes("UTF-8"));
            return WinHelper.hex(java.util.Arrays.copyOf(h, 8));
        } catch (Exception e) {
            return "x";
        }
    }

    public static boolean allowed() {
        return WinHelper.available() && (!"1".equals(Ux.get("has_factors", "0")) || "1".equals(Ux.get("quick_with_factors", "0")));
    }

    public static boolean enabled(File f) {
        return allowed() && Ux.get("pin_" + id(f), null) != null;
    }

    public static void enable(File f, String pin, byte[] dataKey) throws Exception {
        byte[] salt = VaultCrypto.random(16);
        byte[] pk = VaultCrypto.pbkdf2(pin.toCharArray(), salt, 300000);
        byte[] inner = VaultCrypto.seal(dataKey, pk);
        byte[] payload = new byte[16 + inner.length];
        System.arraycopy(salt, 0, payload, 0, 16);
        System.arraycopy(inner, 0, payload, 16, inner.length);
        Ux.put("pin_" + id(f), WinHelper.hex(WinHelper.protect(payload)));
        Ux.put("pinlen_" + id(f), pin.length());
        Ux.put("pinfail_" + id(f), 0);
    }

    public static int length(File f) {
        return Ux.getInt("pinlen_" + id(f), 6);
    }

    /** Returns the data key, or throws WrongPasswordException (after 3 failures the PIN is removed). */
    public static byte[] open(File f, String pin) throws Exception {
        byte[] payload = WinHelper.unprotect(WinHelper.unhex(Ux.get("pin_" + id(f), "")));
        byte[] salt = java.util.Arrays.copyOf(payload, 16);
        byte[] inner = java.util.Arrays.copyOfRange(payload, 16, payload.length);
        try {
            byte[] k = VaultCrypto.open(inner, VaultCrypto.pbkdf2(pin.toCharArray(), salt, 300000));
            Ux.put("pinfail_" + id(f), 0);
            return k;
        } catch (VaultCrypto.WrongPasswordException e) {
            int fails = Ux.getInt("pinfail_" + id(f), 0) + 1;
            if (fails >= 3) disable(f);
            else Ux.put("pinfail_" + id(f), fails);
            throw e;
        }
    }

    public static int triesLeft(File f) {
        return 3 - Ux.getInt("pinfail_" + id(f), 0);
    }

    public static void disable(File f) {
        Ux.put("pin_" + id(f), null);
        Ux.put("pinfail_" + id(f), null);
    }
}
