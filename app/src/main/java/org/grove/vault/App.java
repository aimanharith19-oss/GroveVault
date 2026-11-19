package org.grove.vault;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

/** Tracks foreground/background for auto-lock, locks on screen-off, and clears the clipboard. */
public class App extends Application {
    private int started;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable clipClear;

    @Override
    public void onCreate() {
        super.onCreate();
        ExtBridge.start(this); // browser extension bridge (only if paired)
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {
                if (started++ == 0) {
                    Session.checkTimeout(a);
                    Session.cameToForeground();
                }
            }
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {
                if (--started <= 0) {
                    started = 0;
                    if (!a.isChangingConfigurations()) {
                        Session.wentToBackground();
                        if (Prefs.autoLockSeconds(a) == 0 && !Session.pickerActive()) Session.lock();
                    }
                }
            }
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        });
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Prefs.lockOnScreenOff(c)) Session.lock();
            }
        }, f);
    }

    /** Copies text, marks it sensitive (hidden from clipboard previews), and clears it after the configured delay. */
    public static void copy(final Context c, String label, String text) {
        final ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        if (Build.VERSION.SDK_INT >= 24) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        int secs = Prefs.clipboardSeconds(c);
        if (clipClear != null) handler.removeCallbacks(clipClear);
        if (secs > 0) {
            clipClear = new Runnable() {
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
                        else cm.setPrimaryClip(ClipData.newPlainText("", ""));
                    } catch (Exception ignored) {}
                }
            };
            handler.postDelayed(clipClear, secs * 1000L);
        }
        if (Build.VERSION.SDK_INT < 33) {
            Ui.toast(c, label + " copied" + (secs > 0 ? " · clears in " + secs + "s" : ""));
        }
    }

}
