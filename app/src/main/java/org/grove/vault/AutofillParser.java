package org.grove.vault;

import android.app.assist.AssistStructure;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.ViewStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds login, password, one-time-code and credit-card fields in another app's screen. */
public final class AutofillParser {
    public AutofillId user, pass, otp, ccNumber, ccExpiry, ccCvv, ccName;
    public final List<AutofillId> extraPasswords = new ArrayList<AutofillId>();
    public String userValue, passValue;
    public String packageName = "";
    public String webDomain;
    /** Signing-certificate fingerprint of the app being filled. */
    public String appCert = "";
    /** A web domain claimed by an app that isn't a verified browser (ignored for matching). */
    public String untrustedWebDomain;
    private final List<AssistStructure.ViewNode> textFields = new ArrayList<AssistStructure.ViewNode>();
    private AssistStructure.ViewNode passNode;
    private AssistStructure.ViewNode focused;

    /**
     * Parses and verifies the caller: web domains are only believed from verified browsers, because any app can
     * claim any domain in its view structure (a classic autofill phishing trick).
     */
    public static AutofillParser parse(android.content.Context c, AssistStructure s) {
        AutofillParser p = parse(s);
        p.appCert = Trust.certFingerprint(c, p.packageName);
        if (p.webDomain != null && !Trust.isTrustedBrowser(c, p.packageName, p.appCert)) {
            p.untrustedWebDomain = p.webDomain;
            p.webDomain = null;
        }
        return p;
    }

    public static AutofillParser parse(AssistStructure s) {
        AutofillParser p = new AutofillParser();
        if (s.getActivityComponent() != null) p.packageName = s.getActivityComponent().getPackageName();
        for (int i = 0; i < s.getWindowNodeCount(); i++) p.walk(s.getWindowNodeAt(i).getRootViewNode());
        p.fallbackUser();
        return p;
    }

    public boolean hasLogin() {
        return user != null || pass != null;
    }

    public boolean hasCard() {
        return ccNumber != null;
    }

    public boolean any() {
        return hasLogin() || hasCard() || otp != null;
    }

    public AutofillId[] ids() {
        List<AutofillId> l = new ArrayList<AutofillId>();
        for (AutofillId id : new AutofillId[]{user, pass, otp, ccNumber, ccExpiry, ccCvv, ccName}) if (id != null) l.add(id);
        return l.toArray(new AutofillId[0]);
    }

    private static String lower(CharSequence s) {
        return s == null ? "" : s.toString().toLowerCase(Locale.ROOT);
    }

    private static String value(AssistStructure.ViewNode n) {
        AutofillValue v = n.getAutofillValue();
        if (v != null && v.isText()) return v.getTextValue().toString();
        return n.getText() == null ? null : n.getText().toString();
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private void walk(AssistStructure.ViewNode n) {
        if (n == null) return;
        if (n.getWebDomain() != null && n.getWebDomain().length() > 0) webDomain = n.getWebDomain();
        if (n.getVisibility() == View.VISIBLE && n.getAutofillId() != null && n.getAutofillType() == View.AUTOFILL_TYPE_TEXT) {
            if (n.isFocused()) focused = n;
            classify(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i));
    }

    private void classify(AssistStructure.ViewNode n) {
        String[] hints = n.getAutofillHints();
        StringBuilder hb = new StringBuilder();
        if (hints != null) for (String h : hints) hb.append(lower(h)).append(' ');
        String h = hb.toString();
        if (h.contains("off") && h.trim().equals("off")) return;
        int it = n.getInputType();
        int variation = it & InputType.TYPE_MASK_VARIATION;
        int cls = it & InputType.TYPE_MASK_CLASS;
        boolean pwInput = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        boolean emailInput = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        String html = "";
        ViewStructure.HtmlInfo info = n.getHtmlInfo();
        if (info != null && info.getAttributes() != null) {
            StringBuilder b = new StringBuilder();
            for (android.util.Pair<String, String> a : info.getAttributes()) b.append(lower(a.first)).append('=').append(lower(a.second)).append(' ');
            html = b.toString();
        }
        String meta = lower(n.getIdEntry()) + " " + lower(n.getHint()) + " " + html;
        String tag = lower(info == null ? "" : info.getTag());
        if (!tag.isEmpty() && !tag.equals("input")) return;
        if (html.contains("type=hidden") || html.contains("type=submit") || html.contains("type=button") || html.contains("type=checkbox")) return;

        textFields.add(n);
        // credit card (hints only, plus common html autocomplete names)
        if (containsAny(h, "creditcardnumber") || meta.contains("autocomplete=cc-number")) { if (ccNumber == null) ccNumber = n.getAutofillId(); return; }
        if (containsAny(h, "creditcardexpirationdate") || meta.contains("autocomplete=cc-exp ")) { if (ccExpiry == null) ccExpiry = n.getAutofillId(); return; }
        if (containsAny(h, "creditcardsecuritycode") || meta.contains("autocomplete=cc-csc")) { if (ccCvv == null) ccCvv = n.getAutofillId(); return; }
        if (containsAny(h, "creditcardholder", "cc-name") || meta.contains("autocomplete=cc-name")) { if (ccName == null) ccName = n.getAutofillId(); return; }
        // one-time codes
        if (containsAny(h, "onetimecode", "smsotpcode", "one-time-code", "2faappotpcode") || meta.contains("autocomplete=one-time-code")
                || containsAny(meta, "otp", "totp", "2fa", "one-time", "verification code", "authenticator")) {
            if (otp == null) otp = n.getAutofillId();
            return;
        }
        boolean isPass = h.contains("password") || pwInput || html.contains("type=password")
                || (containsAny(meta, "password", "passwd", "pwd", "passcode", "kennwort", "contraseña", "senha", "katalaluan") && !containsAny(meta, "forgot", "reset"));
        if (isPass) {
            if (pass == null) {
                pass = n.getAutofillId();
                passNode = n;
                passValue = value(n);
            } else extraPasswords.add(n.getAutofillId());
            return;
        }
        boolean isUser = containsAny(h, "username", "emailaddress", "email", "phone") || emailInput || html.contains("type=email")
                || containsAny(meta, "user", "login", "email", "e-mail", "account", "identifier", "mail", "phone", "nama pengguna");
        if (isUser && user == null && !containsAny(meta, "search", "query")) {
            user = n.getAutofillId();
            userValue = value(n);
        }
    }

    /**
     * Apps often give their fields no hints, so nothing is recognised. Still offer Grove Vault on the field the user
     * is in (or the first text field): a password-type field gets the password, anything else the login.
     */
    void fallbackAnyField() {
        if (any()) return;
        AssistStructure.ViewNode n = focused != null ? focused : (textFields.isEmpty() ? null : textFields.get(0));
        if (n == null) return;
        int it = n.getInputType(), v = it & InputType.TYPE_MASK_VARIATION;
        boolean pw = (it & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                || (it & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        if (pw) {
            pass = n.getAutofillId();
            passValue = value(n);
        } else {
            user = n.getAutofillId();
            userValue = value(n);
        }
    }

    /** If there's a password but no detected username, use the text field just before the password. */
    private void fallbackUser() {
        if (user != null || passNode == null) return;
        int idx = textFields.indexOf(passNode);
        for (int i = idx - 1; i >= 0; i--) {
            AssistStructure.ViewNode n = textFields.get(i);
            AutofillId id = n.getAutofillId();
            if (id.equals(otp) || id.equals(ccNumber)) continue;
            user = id;
            userValue = value(n);
            return;
        }
    }
}
