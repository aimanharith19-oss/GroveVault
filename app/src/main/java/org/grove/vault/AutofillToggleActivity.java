package org.grove.vault;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

/** Invisible step that opens the system "Use Grove Vault for autofill?" prompt, then reports back to the tile. */
public class AutofillToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            startActivityForResult(new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + getPackageName())), 1);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (res == RESULT_OK) {
            Prefs.put(this, "af_paused", false);
            Ui.toast(this, "Grove Vault autofill is on");
        }
        AutofillTile.update(this);
        finish();
    }
}
