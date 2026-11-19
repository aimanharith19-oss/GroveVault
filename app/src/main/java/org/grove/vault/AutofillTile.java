package org.grove.vault;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.autofill.AutofillManager;

/**
 * Quick Settings pill "Grove autofill". Tap it:
 *  - Grove Vault isn't the autofill service yet → the system "Use Grove Vault for autofill?" prompt opens (one tap);
 *  - it is → autofill is paused / resumed (handy for apps where you don't want suggestions).
 */
public class AutofillTile extends TileService {

    static boolean isService(Context c) {
        try {
            AutofillManager am = c.getSystemService(AutofillManager.class);
            return am != null && am.hasEnabledAutofillServices();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean paused(Context c) {
        return Prefs.p(c).getBoolean("af_paused", false);
    }

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (!isService(this)) {
            Intent i = new Intent(this, AutofillToggleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 7, i, android.app.PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(i);
            return;
        }
        Prefs.put(this, "af_paused", !paused(this));
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean svc = isService(this), on = svc && !paused(this);
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_key));
        t.setLabel("Grove autofill");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(!svc ? "Tap to turn on" : on ? "On" : "Paused");
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    /** Asks the system to show the tile again after the setting changed elsewhere. */
    static void update(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, AutofillTile.class));
        } catch (Exception ignored) {}
    }
}
