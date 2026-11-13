package org.grove.vault;

import android.app.Activity;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Totp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Autofill helper screens: unlock-then-fill, pick a card from the whole vault, or fill one card and copy its 2FA code. */
public class AutofillActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final int MODE_UNLOCK = 1, MODE_PICK = 2, MODE_CARD = 3;
    private static final int REQ_UNLOCK = 1;
    private AssistStructure structure;
    private AutofillParser parser;
    private LinearLayout list;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        structure = getIntent().getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE);
        if (structure == null) {
            finish();
            return;
        }
        parser = AutofillParser.parse(this, structure);
        Session.checkTimeout(this);
        if (!Session.isUnlocked()) {
            Intent i = new Intent(this, LockActivity.class).putExtra(LockActivity.EXTRA_RETURN, true);
            startActivityForResult(i, REQ_UNLOCK);
        } else proceed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_UNLOCK) {
            if (Session.isUnlocked()) proceed();
            else {
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    }

    private void proceed() {
        int mode = getIntent().getIntExtra(EXTRA_MODE, MODE_UNLOCK);
        if (mode == MODE_UNLOCK) {
            Intent r = new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, GroveAutofillService.response(this, parser, structure, getIntent().getParcelableExtra("inline")));
            setResult(RESULT_OK, r);
            finish();
        } else if (mode == MODE_CARD) {
            Card c = Session.card(getIntent().getStringExtra("id"));
            if (c == null) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
            deliver(c, false);
        } else showPicker();
    }

    private void deliver(final Card c, boolean link) {
        if (parser.webDomain == null && parser.packageName.length() > 0) {
            String pin = c.appSigs.get(parser.packageName);
            if (pin != null && !pin.equalsIgnoreCase(parser.appCert)) {
                Ui.dialog(this, "⚠ Different app").setMessage("“" + c.title + "” is linked to " + parser.packageName
                        + ", but this copy of the app is signed by someone else. It may be a fake. Grove Vault won't fill it.\n\nIf you reinstalled the app from an official store and are sure, remove the link in the card and link it again.")
                        .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) { setResult(RESULT_CANCELED); finish(); }
                        }).setCancelable(false).show();
                return;
            }
            if (link && !c.apps.contains(parser.packageName)) {
                confirmLink(c);
                return;
            }
            if (c.apps.contains(parser.packageName) && pin == null && parser.appCert.length() > 0) {
                c.appSigs.put(parser.packageName, parser.appCert); // pin on first use of an imported link
                Session.saveOrToast(this);
            }
        }
        finishDeliver(c);
    }

    private void confirmLink(final Card c) {
        String fp = parser.appCert.length() > 23 ? parser.appCert.substring(0, 23) + "…" : parser.appCert;
        String msg = "Fill “" + c.title + "” into this app?\n\n" + CardActivity.appName(this, parser.packageName) + "\n" + parser.packageName
                + "\nSigned by " + (fp.length() == 0 ? "unknown" : fp)
                + (parser.untrustedWebDomain != null ? "\n\n⚠ This app claims to show " + parser.untrustedWebDomain + " but it isn't a verified browser." : "")
                + "\n\nOnly continue if this is the real app. Grove Vault will remember it and refuse look-alikes.";
        Ui.dialog(this, "Link app").setMessage(msg)
                .setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { setResult(RESULT_CANCELED); finish(); }
                })
                .setPositiveButton("Fill and remember", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        c.apps.add(parser.packageName);
                        if (parser.appCert.length() > 0) c.appSigs.put(parser.packageName, parser.appCert);
                        Session.saveOrToast(AutofillActivity.this);
                        finishDeliver(c);
                    }
                }).setCancelable(false).show();
    }

    private void finishDeliver(Card c) {
        Field otp = c.first(Field.OTP);
        if (otp != null && parser.otp == null && Prefs.otpAfterFill(this)) {
            Totp t = Totp.parse(otp.value);
            if (t != null) App.copy(this, "One-time code", t.code(System.currentTimeMillis()));
        }
        android.service.autofill.Dataset d = GroveAutofillService.dataset(this, parser, c, false);
        if (d == null) {
            Ui.toast(this, "This card has nothing to fill here");
            setResult(RESULT_CANCELED);
        } else setResult(RESULT_OK, new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, d));
        finish();
    }

    private void showPicker() {
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "Fill " + GroveAutofillService.label(this, parser), true));
        EditText search = Ui.edit(this, "Search cards", InputType.TYPE_CLASS_TEXT);
        root.addView(search, Ui.margins(this, Ui.MATCH, Ui.WRAP, 16, 0, 16, 8));
        list = Ui.vertical(this);
        ScrollView sv = Ui.scroll(this, list);
        root.addView(sv, Ui.lp(Ui.MATCH, Ui.MATCH));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                fill();
            }
        });
        fill();
        if (parser.webDomain == null) {
            TextView hint = Ui.text(this, "The card you pick will be remembered for this app.", 13, Ui.SUB, false);
            hint.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4));
            root.addView(hint, 1);
        }
        Ui.showKeyboard(search);
    }

    private void fill() {
        list.removeAllViews();
        List<Card> cards = new ArrayList<Card>();
        for (Card c : Session.vault().activeCards()) {
            if (!c.matches(query)) continue;
            if (parser.hasCard() && !parser.hasLogin()) cards.add(c);
            else if (c.login().length() > 0 || c.password().length() > 0) cards.add(c);
        }
        Collections.sort(cards, new Comparator<Card>() {
            public int compare(Card a, Card b) { return a.title.compareToIgnoreCase(b.title); }
        });
        for (final Card c : cards) {
            LinearLayout r = Ui.horizontal(this);
            r.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            r.addView(Ui.cardIcon(this, c, 40));
            LinearLayout tx = Ui.vertical(this);
            tx.setPadding(Ui.dp(this, 14), 0, 0, 0);
            tx.addView(Ui.text(this, c.title, 16, Ui.TEXT, false));
            tx.addView(Ui.text(this, c.subtitle(), 13, Ui.SUB, false));
            r.addView(tx, Ui.lpw(1));
            Ui.ripple(r, 0, 0);
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { deliver(c, true); }
            });
            list.addView(r);
        }
        if (cards.isEmpty()) list.addView(Ui.row(this, "No cards found", null, null));
    }
}
