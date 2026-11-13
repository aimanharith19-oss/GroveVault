package org.grove.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Label;
import org.grove.vault.core.Vault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Small pop-up shown over a website or app after you sign in: "Save to Grove Vault?" for new logins,
 * "Update password?" when the login is already in the vault. Also used for Android 14 "save password" requests.
 *
 * Extras: login, password, site (web domain) or app (+ app_cert), never_key.
 */
public class QuickSaveActivity extends Activity {
    private static final int REQ_UNLOCK = 1;
    private AlertDialog dialog;
    private String labelId;

    @Override
    protected void onCreate(Bundle b) {
        Ui.applyTheme(this, true);
        super.onCreate(b);
        Session.checkTimeout(this);
        if (!Session.isUnlocked()) {
            startActivityForResult(new Intent(this, LockActivity.class).putExtra(LockActivity.EXTRA_RETURN, true), REQ_UNLOCK);
        } else show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_UNLOCK) return;
        if (Session.isUnlocked()) show();
        else done(false);
    }

    private String extra(String k) {
        String v = getIntent().getStringExtra(k);
        return v == null ? "" : v.trim();
    }

    private String site() { return extra("site"); }

    private String app() { return extra("app"); }

    /** Cards that already hold this login for this site/app. */
    private List<Card> existing(String login) {
        List<Card> out = new ArrayList<Card>();
        String base = site().length() > 0 ? Card.baseDomain(site()) : null;
        for (Card c : Session.vault().activeCards()) {
            boolean same;
            if (base != null) {
                same = false;
                for (Field f : c.fields)
                    if (Field.WEBSITE.equals(f.type) && f.value.length() > 0 && Card.baseDomain(Card.domainOf(f.value)).equals(base)) same = true;
            } else {
                String pin = c.appSigs.get(app());
                same = c.apps.contains(app()) && (pin == null || pin.equalsIgnoreCase(extra("app_cert")));
            }
            if (same && c.login().equalsIgnoreCase(login)) out.add(c);
        }
        return out;
    }

    private String place() {
        return site().length() > 0 ? site() : CardActivity.appName(this, app());
    }

    private void show() {
        final String login = extra("login");
        final String password = getIntent().getStringExtra("password") == null ? "" : getIntent().getStringExtra("password");
        if (password.length() == 0) {
            done(false);
            return;
        }
        List<Card> match = existing(login);
        Card same = null;
        for (Card c : match) if (c.password().equals(password)) same = c;
        if (same != null) {
            Ui.toast(this, "Already saved in Grove Vault");
            done(true);
            return;
        }
        final Card update = match.isEmpty() ? null : match.get(0);

        LinearLayout box = Ui.vertical(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 4));
        LinearLayout head = Ui.horizontal(this);
        Card preview = new Card();
        preview.icon = site().length() > 0 ? "web" : "phone";
        preview.color = Math.abs(place().hashCode()) % Ui.CARD_COLORS.length;
        if (site().length() > 0) preview.fields.add(new Field("Website", Field.WEBSITE, "https://" + site()));
        else preview.apps.add(app());
        head.addView(Ui.cardIcon(this, preview, 44));
        LinearLayout ht = Ui.vertical(this);
        ht.setPadding(Ui.dp(this, 12), 0, 0, 0);
        ht.addView(Ui.text(this, update == null ? "Save to Grove Vault?" : "Update password?", 18, Ui.TEXT, true));
        ht.addView(Ui.text(this, place(), 13, Ui.SUB, false));
        head.addView(ht, Ui.lpw(1));
        box.addView(head);

        final EditText title = Ui.edit(this, "Title", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final EditText user = Ui.edit(this, "Login", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText pass = Ui.edit(this, "Password", Ui.PASSWORD_INPUT);
        pass.setText(password);
        final TextView labelRow = Ui.text(this, "", 14, Ui.ACCENT, false);
        if (update == null) {
            String t = site().length() > 0 ? site() : place();
            if (t.startsWith("www.")) t = t.substring(4);
            title.setText(t.length() > 0 ? Character.toUpperCase(t.charAt(0)) + t.substring(1) : t);
            user.setText(login);
            box.addView(title, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 6));
            box.addView(user, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 6));
        } else {
            TextView t = Ui.text(this, "“" + update.title + "”" + (login.length() > 0 ? " · " + login : "") + "\nThe old password is kept in the card's history.", 14, Ui.TEXT, false);
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
            box.addView(t);
        }
        LinearLayout pr = Ui.horizontal(this);
        pr.addView(pass, Ui.lpw(1));
        pr.addView(Ui.iconButton(this, "👁", new View.OnClickListener() {
            public void onClick(View v) {
                boolean hidden = (pass.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
                pass.setInputType(hidden ? Ui.VISIBLE_PASSWORD_INPUT : Ui.PASSWORD_INPUT);
            }
        }));
        box.addView(pr);
        if (update == null) {
            labelRow.setText("🏷  No label (tap to choose)");
            labelRow.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, Ui.dp(this, 4));
            labelRow.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { chooseLabel(labelRow); }
            });
            box.addView(labelRow);
        }

        AlertDialog.Builder b = Ui.dialog(this, null).setView(Ui.scroll(this, box))
                .setPositiveButton(update == null ? "Save" : "Update", null)
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { done(false); }
                });
        if (extra("never_key").length() > 0) {
            b.setNeutralButton("Never here", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    Set<String> never = new HashSet<String>(Prefs.p(QuickSaveActivity.this).getStringSet("never_save", new HashSet<String>()));
                    never.add(extra("never_key"));
                    Prefs.p(QuickSaveActivity.this).edit().putStringSet("never_save", never).apply();
                    Ui.toast(QuickSaveActivity.this, "Grove Vault won't offer to save logins for " + place());
                    done(false);
                }
            });
        }
        b.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { done(false); }
        });
        dialog = b.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String pw = pass.getText().toString();
                if (pw.length() == 0) return;
                if (update != null) saveUpdate(update, pw);
                else saveNew(title.getText().toString().trim(), user.getText().toString().trim(), pw);
            }
        });
    }

    private void chooseLabel(final TextView row) {
        final Vault v = Session.vault();
        v.sortLabels();
        String[] names = new String[v.labels.size() + 1];
        names[0] = "No label";
        for (int i = 0; i < v.labels.size(); i++) names[i + 1] = v.labels.get(i).name;
        Ui.choose(this, "Label", names, new Ui.Cb<Integer>() {
            public void on(Integer i) {
                Label l = i == 0 ? null : v.labels.get(i - 1);
                labelId = l == null ? null : l.id;
                row.setText("🏷  " + (l == null ? "No label (tap to choose)" : l.name));
            }
        });
    }

    private void saveNew(String title, String login, String password) {
        Card c = null;
        for (Card t : Session.vault().templates()) if ("web".equals(t.icon)) { c = t.instantiate(); break; }
        if (c == null) {
            c = new Card();
            c.fields.add(new Field("Login", Field.LOGIN, ""));
            c.fields.add(new Field("Password", Field.PASSWORD, ""));
            c.fields.add(new Field("Website", Field.WEBSITE, ""));
        }
        c.icon = site().length() > 0 ? "web" : "phone";
        c.title = title.length() > 0 ? title : place();
        c.color = Math.abs(c.title.hashCode()) % Ui.CARD_COLORS.length;
        boolean l = false, p = false, w = false;
        for (Field f : c.fields) {
            if (!l && (Field.LOGIN.equals(f.type) || Field.EMAIL.equals(f.type))) { f.value = login; f.type = login.contains("@") ? Field.EMAIL : Field.LOGIN; l = true; }
            else if (!p && Field.PASSWORD.equals(f.type)) { f.value = password; p = true; }
            else if (!w && Field.WEBSITE.equals(f.type) && site().length() > 0) { f.value = "https://" + site(); w = true; }
        }
        if (!l) c.fields.add(0, new Field("Login", Field.LOGIN, login));
        if (!p) c.fields.add(new Field("Password", Field.PASSWORD, password));
        if (!w && site().length() > 0) c.fields.add(new Field("Website", Field.WEBSITE, "https://" + site()));
        if (app().length() > 0) {
            c.apps.add(app());
            if (extra("app_cert").length() > 0) c.appSigs.put(app(), extra("app_cert"));
        }
        if (labelId != null) c.labelIds.add(labelId);
        Session.vault().cards.add(c);
        if (Session.saveOrToast(this)) {
            Ui.toast(this, "Saved to Grove Vault");
            done(true);
        }
    }

    private void saveUpdate(Card c, String password) {
        Field f = c.first(Field.PASSWORD);
        if (f == null) c.fields.add(f = new Field("Password", Field.PASSWORD, ""));
        f.update(password);
        c.modified = System.currentTimeMillis();
        if (Session.saveOrToast(this)) {
            Ui.toast(this, "Password updated for " + c.title);
            done(true);
        }
    }

    private void done(boolean saved) {
        setResult(saved ? RESULT_OK : RESULT_CANCELED);
        try { if (dialog != null) dialog.dismiss(); } catch (Exception ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }
}
