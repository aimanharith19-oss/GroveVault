package org.grove.vault;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to app settings. */
public final class Prefs {
    private Prefs() {}

    public static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("grove", Context.MODE_PRIVATE);
    }

    public static int autoLockSeconds(Context c) { return p(c).getInt("autolock", 60); }
    public static boolean lockOnScreenOff(Context c) { return p(c).getBoolean("lock_screen_off", true); }
    public static int clipboardSeconds(Context c) { return p(c).getInt("clip_secs", 30); }
    public static boolean allowScreenshots(Context c) { return p(c).getBoolean("screenshots", false); }
    /** 0 = system, 1 = light, 2 = dark, 3 = black (AMOLED). */
    public static int theme(Context c) { return p(c).getInt("theme", 0); }
    /** 0 = title, 1 = recently modified, 2 = recently created. */
    public static int sort(Context c) { return p(c).getInt("sort", 0); }
    public static boolean favoritesOnTop(Context c) { return p(c).getBoolean("fav_top", true); }
    public static int expiryDays(Context c) { return p(c).getInt("expiry_days", 30); }
    public static boolean showWarnings(Context c) { return p(c).getBoolean("warnings", true); }
    /** 0 = never; otherwise erase vault after this many wrong passwords in a row. */
    public static int eraseAfter(Context c) { return p(c).getInt("erase_after", 0); }
    public static int failedAttempts(Context c) { return p(c).getInt("failed", 0); }
    public static boolean bioEnabled(Context c) { return p(c).getString("bio_blob", null) != null; }
    public static boolean pinEnabled(Context c) { return p(c).getString("pin_blob", null) != null; }
    public static boolean autoBackup(Context c) { return p(c).getBoolean("ab_on", false); }
    public static String autoBackupTree(Context c) { return p(c).getString("ab_tree", null); }
    /** 0 = on every change, 1 = daily, 7 = weekly. */
    public static int autoBackupInterval(Context c) { return p(c).getInt("ab_interval", 1); }
    public static int autoBackupKeep(Context c) { return p(c).getInt("ab_keep", 10); }
    public static boolean otpAfterFill(Context c) { return p(c).getBoolean("otp_after_fill", true); }

    public static void put(Context c, String key, Object v) {
        SharedPreferences.Editor e = p(c).edit();
        if (v == null) e.remove(key);
        else if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
        else if (v instanceof Integer) e.putInt(key, (Integer) v);
        else if (v instanceof Long) e.putLong(key, (Long) v);
        else e.putString(key, v.toString());
        e.apply();
    }
}
