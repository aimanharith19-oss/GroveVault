package org.grove.vault;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.grove.vault.core.VaultCrypto;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed (Android Keystore) wrapping of the vault key for biometric and PIN unlock.
 * The vault key itself never touches disk unencrypted.
 */
public final class KeyGuard {
    private KeyGuard() {}

    private static final String BIO_ALIAS = "grove_bio";
    private static final String PIN_ALIAS = "grove_pin";
    public static final int PIN_TRIES = 3;

    private static KeyStore ks() throws Exception {
        KeyStore k = KeyStore.getInstance("AndroidKeyStore");
        k.load(null);
        return k;
    }

    private static SecretKey key(String alias, boolean userAuth) throws Exception {
        KeyStore k = ks();
        if (k.containsAlias(alias)) return (SecretKey) k.getKey(alias, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (userAuth) {
            b.setUserAuthenticationRequired(true);
            b.setInvalidatedByBiometricEnrollment(true);
        }
        if (Build.VERSION.SDK_INT >= 28) b.setUnlockedDeviceRequired(true);
        if (Build.VERSION.SDK_INT >= 28) {
            // Prefer a dedicated security chip (StrongBox) when the phone has one.
            try {
                b.setIsStrongBoxBacked(true);
                g.init(b.build());
                return g.generateKey();
            } catch (Exception strongBoxUnavailable) {
                b.setIsStrongBoxBacked(false);
            }
        }
        g.init(b.build());
        return g.generateKey();
    }

    public static void deleteKey(String alias) {
        try { ks().deleteEntry(alias); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- biometrics

    public static boolean bioSupported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    /** Cipher for encrypting the vault key (needs biometric auth before use). */
    public static Cipher bioEncryptCipher() throws Exception {
        deleteKey(BIO_ALIAS);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(BIO_ALIAS, true));
        return c;
    }

    public static Cipher bioDecryptCipher(Context ctx) throws Exception {
        String blob = Prefs.p(ctx).getString("bio_blob", null);
        byte[] b = Base64.decode(blob, Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(BIO_ALIAS, true), new GCMParameterSpec(128, b, 0, 12));
        return c;
    }

    public static void bioStore(Context ctx, Cipher authed, byte[] vaultKey) throws Exception {
        byte[] ct = authed.doFinal(vaultKey);
        byte[] iv = authed.getIV();
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        Prefs.put(ctx, "bio_blob", Base64.encodeToString(out, Base64.NO_WRAP));
    }

    public static byte[] bioOpen(Context ctx, Cipher authed) throws Exception {
        byte[] b = Base64.decode(Prefs.p(ctx).getString("bio_blob", null), Base64.NO_WRAP);
        return authed.doFinal(b, 12, b.length - 12);
    }

    public static void bioDisable(Context ctx) {
        Prefs.put(ctx, "bio_blob", null);
        deleteKey(BIO_ALIAS);
    }

    // ---------------------------------------------------------------- PIN (fast unlock)

    /** Stores keystoreSeal(seal(vaultKey, pbkdf2(pin))). */
    public static void pinStore(Context ctx, String pin, byte[] vaultKey) throws Exception {
        byte[] salt = VaultCrypto.random(16);
        byte[] pk = VaultCrypto.pbkdf2(pin.toCharArray(), salt, 60000);
        byte[] inner = VaultCrypto.seal(vaultKey, pk);
        byte[] payload = new byte[16 + inner.length];
        System.arraycopy(salt, 0, payload, 0, 16);
        System.arraycopy(inner, 0, payload, 16, inner.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(PIN_ALIAS, false));
        byte[] ct = c.doFinal(payload);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(c.getIV(), 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        Prefs.put(ctx, "pin_blob", Base64.encodeToString(out, Base64.NO_WRAP));
        Prefs.put(ctx, "pin_len", pin.length());
        Prefs.put(ctx, "pin_fails", 0);
    }

    /** Returns the vault key, or throws WrongPasswordException. After PIN_TRIES failures the PIN is removed. */
    public static byte[] pinOpen(Context ctx, String pin) throws Exception {
        byte[] b = Base64.decode(Prefs.p(ctx).getString("pin_blob", null), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(PIN_ALIAS, false), new GCMParameterSpec(128, b, 0, 12));
        byte[] payload = c.doFinal(b, 12, b.length - 12);
        byte[] salt = new byte[16];
        System.arraycopy(payload, 0, salt, 0, 16);
        byte[] inner = new byte[payload.length - 16];
        System.arraycopy(payload, 16, inner, 0, inner.length);
        try {
            byte[] k = VaultCrypto.open(inner, VaultCrypto.pbkdf2(pin.toCharArray(), salt, 60000));
            Prefs.put(ctx, "pin_fails", 0);
            return k;
        } catch (VaultCrypto.WrongPasswordException e) {
            int fails = Prefs.p(ctx).getInt("pin_fails", 0) + 1;
            if (fails >= PIN_TRIES) pinDisable(ctx);
            else Prefs.put(ctx, "pin_fails", fails);
            throw e;
        }
    }

    public static int pinTriesLeft(Context ctx) {
        return PIN_TRIES - Prefs.p(ctx).getInt("pin_fails", 0);
    }

    public static void pinDisable(Context ctx) {
        Prefs.put(ctx, "pin_blob", null);
        Prefs.put(ctx, "pin_fails", 0);
        deleteKey(PIN_ALIAS);
    }
}
