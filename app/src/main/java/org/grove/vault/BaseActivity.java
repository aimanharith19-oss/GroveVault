package org.grove.vault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Applies the theme and sends the user to the lock screen whenever the vault is locked. */
public abstract class BaseActivity extends Activity {
    private boolean built;
    private int themeAtCreate;
    private final Runnable lockWatcher = new Runnable() {
        public void run() {
            if (!Session.isUnlocked() && needsVault() && hasWindowFocus()) requireUnlock();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        themeAtCreate = Prefs.theme(this) * 2 + (Prefs.allowScreenshots(this) ? 1 : 0);
    }

    protected boolean needsVault() {
        return true;
    }

    /** Build or refresh the screen; only called while unlocked (if needsVault). */
    protected abstract void render();

    @Override
    protected void onResume() {
        super.onResume();
        if (themeAtCreate != Prefs.theme(this) * 2 + (Prefs.allowScreenshots(this) ? 1 : 0)) {
            recreate();
            return;
        }
        Session.checkTimeout(this);
        if (needsVault() && !Session.isUnlocked()) {
            requireUnlock();
            return;
        }
        render();
        built = true;
        Session.addListener(lockWatcher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Session.removeListener(lockWatcher);
    }

    private void requireUnlock() {
        Intent i = new Intent(this, LockActivity.class);
        i.putExtra(LockActivity.EXTRA_RETURN, true);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (requestCode >= 0) Session.pickerStarted();
        super.startActivityForResult(intent, requestCode, options);
    }

    private static final int REQ_PICK_DOCUMENT = 0x6F1;
    private Ui.Cb<android.net.Uri> pickCb;

    /** Opens the system file picker and delivers the chosen document. */
    public void pickDocument(Ui.Cb<android.net.Uri> cb) {
        pickCb = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK_DOCUMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Session.pickerFinished();
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_DOCUMENT) {
            Ui.Cb<android.net.Uri> cb = pickCb;
            pickCb = null;
            if (cb != null && resultCode == RESULT_OK && data != null && data.getData() != null) cb.on(data.getData());
        }
    }

    protected boolean isBuilt() {
        return built;
    }
}
