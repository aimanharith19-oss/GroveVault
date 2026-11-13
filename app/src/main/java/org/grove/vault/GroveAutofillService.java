package org.grove.vault;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Totp;
import org.grove.vault.core.Vault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Android autofill: offers matching cards, unlocks on demand, and offers to save new logins. */
public class GroveAutofillService extends AutofillService {
    private static int requestCode = 1000;

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancel, FillCallback callback) {
        try {
            List<FillContext> ctx = request.getFillContexts();
            AssistStructure s = ctx.get(ctx.size() - 1).getStructure();
            AutofillParser p = AutofillParser.parse(this, s);
            // Any text field only when the user asks (long-press → Autofill); otherwise only real login forms.
            if ((request.getFlags() & FillRequest.FLAG_MANUAL_REQUEST) != 0) p.fallbackAnyField();
            if (getPackageName().equals(p.packageName) || !p.any()) {
                callback.onSuccess(null);
                return;
            }
            Session.checkTimeout(this);
            // "both" (default): keyboard chips when the keyboard supports them, pop-up otherwise; "popup": always the pop-up
            Object inline = Build.VERSION.SDK_INT >= 30 && !"popup".equals(Prefs.p(this).getString("af_style", "both"))
                    ? request.getInlineSuggestionsRequest() : null;
            callback.onSuccess(response(this, p, s, inline));
        } catch (Exception e) {
            callback.onSuccess(null);
        }
    }

    /** Builds the response: unlock prompt when locked, otherwise matching cards plus "search vault". */
    static FillResponse response(Context c, AutofillParser p, AssistStructure s, Object inline) {
        FillResponse.Builder b = new FillResponse.Builder();
        AutofillId[] ids = p.ids();
        if (ids.length == 0) return null;
        // Android 11+: also show suggestions as chips in the keyboard, if the keyboard supports it
        boolean chips = Build.VERSION.SDK_INT >= 30 && Inline.supported(inline);
        int maxChips = chips ? Inline.max(inline) : 0;
        if (!Session.isUnlocked()) {
            Intent i = new Intent(c, AutofillActivity.class).putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_UNLOCK);
            if (chips) i.putExtra("inline", (android.os.Parcelable) inline);
            if (chips) b.setAuthentication(ids, sender(c, i), presentation(c, "🔒  Unlock Grove Vault", label(c, p)),
                    Inline.chip(c, inline, 0, "Unlock Grove Vault", label(c, p), android.graphics.drawable.Icon.createWithResource(c, R.mipmap.ic_launcher), false));
            else b.setAuthentication(ids, sender(c, i), presentation(c, "🔒  Unlock Grove Vault", label(c, p)));
        } else {
            List<Card> matches = match(Session.vault(), p);
            int n = 0;
            for (Card card : matches) {
                if (n >= 6) break;
                Dataset d = dataset(c, p, card, true, chips && n < maxChips - 1 ? inline : null, n);
                if (d != null) {
                    b.addDataset(d);
                    n++;
                }
            }
            Intent pick = new Intent(c, AutofillActivity.class).putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_PICK);
            if (signatureMismatch(Session.vault(), p)) {
                Dataset.Builder warn = new Dataset.Builder(presentation(c, "⚠  Not the app your card is linked to", "Its signature differs, so Grove Vault won't fill it"));
                for (AutofillId id : ids) warn.setValue(id, null);
                Intent pickW = new Intent(c, AutofillActivity.class).putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_PICK);
                warn.setAuthentication(sender(c, pickW));
                b.addDataset(warn.build());
            }
            Dataset.Builder db = new Dataset.Builder(presentation(c, "🌳  Search Grove Vault…", matches.isEmpty() ? "No match for " + label(c, p) : "Choose another card"));
            for (AutofillId id : ids) db.setValue(id, null);
            db.setAuthentication(sender(c, pick));
            if (chips) db.setInlinePresentation(Inline.chip(c, inline, n, "Grove Vault", matches.isEmpty() ? "Search" : "More…",
                    android.graphics.drawable.Icon.createWithResource(c, R.mipmap.ic_launcher), true));
            b.addDataset(db.build());
        }
        if (p.pass != null && !neverSave(c, p)) {
            AutofillId[] required = new AutofillId[]{p.pass};
            int type = SaveInfo.SAVE_DATA_TYPE_PASSWORD | (p.user != null ? SaveInfo.SAVE_DATA_TYPE_USERNAME : 0);
            SaveInfo.Builder si = new SaveInfo.Builder(type, required);
            if (p.user != null) si.setOptionalIds(new AutofillId[]{p.user});
            b.setSaveInfo(si.build());
        }
        return b.build();
    }

    /** A dataset filling this card's values. With OTP copying on, it goes through a tiny activity that copies the code. */
    static Dataset dataset(Context c, AutofillParser p, Card card, boolean viaOtpActivity) {
        return dataset(c, p, card, viaOtpActivity, null, 0);
    }

    static Dataset dataset(Context c, AutofillParser p, Card card, boolean viaOtpActivity, Object inline, int index) {
        Dataset.Builder d = new Dataset.Builder(presentation(c, card.title, card.subtitle()));
        if (inline != null && Build.VERSION.SDK_INT >= 30) {
            android.graphics.Bitmap bm = IconCache.peek(IconCache.keyFor(card));
            android.graphics.drawable.Icon icon = bm != null ? android.graphics.drawable.Icon.createWithBitmap(bm)
                    : android.graphics.drawable.Icon.createWithResource(c, R.mipmap.ic_launcher);
            d.setInlinePresentation(Inline.chip(c, inline, index, card.title, card.login(), icon, false));
        }
        boolean any = false;
        String login = card.login();
        String pass = card.password();
        if (p.user != null && login.length() > 0) { d.setValue(p.user, AutofillValue.forText(login)); any = true; }
        if (p.pass != null && pass.length() > 0) { d.setValue(p.pass, AutofillValue.forText(pass)); any = true; }
        Field otp = card.first(Field.OTP);
        if (p.otp != null && otp != null) {
            Totp t = Totp.parse(otp.value);
            if (t != null) { d.setValue(p.otp, AutofillValue.forText(t.code(System.currentTimeMillis()))); any = true; }
        }
        if (p.ccNumber != null) {
            for (Field f : card.fields) {
                String n = f.name.toLowerCase(Locale.ROOT);
                if (f.value.length() == 0) continue;
                if (Field.NUMBER.equals(f.type) && f.value.replace(" ", "").matches("\\d{12,19}")) { d.setValue(p.ccNumber, AutofillValue.forText(f.value.replace(" ", ""))); any = true; }
                else if (p.ccExpiry != null && Field.EXPIRY.equals(f.type)) d.setValue(p.ccExpiry, AutofillValue.forText(f.value));
                else if (p.ccCvv != null && (n.contains("cvv") || n.contains("cvc") || n.contains("security"))) d.setValue(p.ccCvv, AutofillValue.forText(f.value));
                else if (p.ccName != null && (n.contains("holder") || n.contains("owner") || n.contains("name"))) d.setValue(p.ccName, AutofillValue.forText(f.value));
            }
        }
        if (!any) return null;
        boolean needsPin = p.webDomain == null && card.apps.contains(p.packageName) && !card.appSigs.containsKey(p.packageName);
        if (viaOtpActivity && (needsPin || (otp != null && p.otp == null && Prefs.otpAfterFill(c)))) {
            Intent i = new Intent(c, AutofillActivity.class).putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_CARD).putExtra("id", card.id);
            d.setAuthentication(sender(c, i));
        }
        return d.build();
    }

    /**
     * Cards for this screen. Websites match only when the caller is a verified browser; apps match only through an
     * explicit link whose pinned signing certificate equals the app's (no guessing from package names).
     */
    static List<Card> match(Vault v, AutofillParser p) {
        final List<Card> out = new ArrayList<Card>();
        final List<Integer> scores = new ArrayList<Integer>();
        String host = p.webDomain == null ? null : Card.domainOf(p.webDomain);
        String web = host == null ? null : Card.baseDomain(host);
        for (Card c : v.activeCards()) {
            int score = 0;
            if (web != null) {
                for (Field f : c.fields) {
                    if (!Field.WEBSITE.equals(f.type) || f.value.length() == 0) continue;
                    String d = Card.domainOf(f.value);
                    if (d.equals(host)) score = Math.max(score, 4);
                    else if (Card.baseDomain(d).equals(web)) score = Math.max(score, 3);
                }
            } else if (c.apps.contains(p.packageName)) {
                String pin = c.appSigs.get(p.packageName);
                if (pin == null || pin.equalsIgnoreCase(p.appCert)) score = 5;
            }
            if (score > 0 && (p.hasLogin() ? (c.login().length() > 0 || c.password().length() > 0) : true)) {
                out.add(c);
                scores.add(score);
            }
        }
        final List<Card> copy = new ArrayList<Card>(out);
        Collections.sort(out, new Comparator<Card>() {
            public int compare(Card a, Card b) {
                int d = scores.get(copy.indexOf(b)) - scores.get(copy.indexOf(a));
                if (d != 0) return d;
                if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
                return Long.compare(b.modified, a.modified);
            }
        });
        return out;
    }

    /** True if a card is linked to this app but the app's signature differs from the pinned one (possible fake). */
    static boolean signatureMismatch(Vault v, AutofillParser p) {
        if (p.webDomain != null) return false;
        for (Card c : v.activeCards()) {
            String pin = c.appSigs.get(p.packageName);
            if (c.apps.contains(p.packageName) && pin != null && !pin.equalsIgnoreCase(p.appCert)) return true;
        }
        return false;
    }

    /** Sites/apps the user chose "Never save" for. */
    static String saveKey(AutofillParser p) {
        return p.webDomain != null ? "web:" + Card.baseDomain(Card.domainOf(p.webDomain)) : "app:" + p.packageName;
    }

    static boolean neverSave(Context c, AutofillParser p) {
        return Prefs.p(c).getStringSet("never_save", new java.util.HashSet<String>()).contains(saveKey(p));
    }

    static String label(Context c, AutofillParser p) {
        if (p.webDomain != null) return Card.domainOf(p.webDomain);
        return CardActivity.appName(c, p.packageName);
    }

    static RemoteViews presentation(Context c, String title, String sub) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.af_item);
        rv.setTextViewText(R.id.af_title, title);
        rv.setTextViewText(R.id.af_sub, sub == null ? "" : sub);
        return rv;
    }

    static IntentSender sender(Context c, Intent i) {
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getActivity(c, requestCode++, i, flags).getIntentSender();
    }

    // ---------------------------------------------------------------- save

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        try {
            List<FillContext> ctx = request.getFillContexts();
            AssistStructure s = ctx.get(ctx.size() - 1).getStructure();
            AutofillParser p = AutofillParser.parse(this, s);
            String pass = p.passValue;
            if (pass == null || pass.length() == 0 || neverSave(this, p)) {
                callback.onSuccess();
                return;
            }
            Intent i = new Intent(this, QuickSaveActivity.class)
                    .putExtra("login", p.userValue == null ? "" : p.userValue)
                    .putExtra("password", pass)
                    .putExtra("never_key", saveKey(p))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (p.webDomain != null) i.putExtra("site", Card.domainOf(p.webDomain));
            else i.putExtra("app", p.packageName).putExtra("app_cert", p.appCert);
            if (Build.VERSION.SDK_INT >= 28) {
                callback.onSuccess(sender(this, i));
            } else {
                startActivity(i);
                callback.onSuccess();
            }
        } catch (Exception e) {
            callback.onFailure("Grove Vault couldn't save this login");
        }
    }
}
