package org.grove.vault;

import android.app.PendingIntent;
import android.app.slice.Slice;
import android.app.slice.SliceSpec;
import android.content.Context;
import android.content.Intent;
import android.content.pm.Signature;
import android.credentials.ClearCredentialStateException;
import android.credentials.CreateCredentialException;
import android.credentials.GetCredentialException;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.service.credentials.Action;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.BeginGetCredentialOption;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.ClearCredentialStateRequest;
import android.service.credentials.CreateEntry;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderService;

import org.grove.vault.core.Card;
import org.grove.vault.core.Json;
import org.grove.vault.core.Passkey;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Android 14+ Credential Manager provider: offers and creates passkeys stored in the vault. */
public class PasskeyService extends CredentialProviderService {
    public static final String TYPE_PUBLIC_KEY = "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL";
    public static final String TYPE_PASSWORD = "android.credentials.TYPE_PASSWORD_CREDENTIAL";
    public static final String KEY_ID = "androidx.credentials.BUNDLE_KEY_ID";
    public static final String KEY_PASSWORD = "androidx.credentials.BUNDLE_KEY_PASSWORD";
    public static final String KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON";
    public static final String KEY_CLIENT_DATA_HASH = "androidx.credentials.BUNDLE_KEY_CLIENT_DATA_HASH";
    public static final String KEY_REGISTRATION_JSON = "androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON";
    public static final String KEY_AUTHENTICATION_JSON = "androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON";

    private static final String H = "androidx.credentials.provider.credentialEntry.";
    private static final String HC = "androidx.credentials.provider.createEntry.";
    private static final String HA = "androidx.credentials.provider.authenticationAction.";
    private static int requestCode = 5000;

    @Override
    public void onBeginCreateCredential(BeginCreateCredentialRequest request, CancellationSignal cancel,
                                        OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException> callback) {
        boolean password = TYPE_PASSWORD.equals(request.getType());
        if (!TYPE_PUBLIC_KEY.equals(request.getType()) && !password) {
            callback.onError(new CreateCredentialException(CreateCredentialException.TYPE_NO_CREATE_OPTIONS, "Only passkeys and passwords are supported"));
            return;
        }
        Intent i = new Intent(this, PasskeyActivity.class).putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_CREATE);
        Slice.Builder b = new Slice.Builder(Uri.EMPTY, new SliceSpec("CreateEntry", 1));
        b.addText("Grove Vault", null, Collections.singletonList(HC + "SLICE_HINT_USER_PROVIDER_ACCOUNT_NAME"));
        b.addText(password ? "Save the password in your encrypted vault" : "Save the passkey in your encrypted vault", null, Collections.singletonList(HC + "SLICE_HINT_NOTE"));
        b.addIcon(Icon.createWithResource(this, R.mipmap.ic_launcher), null, Collections.singletonList(HC + "SLICE_HINT_PROFILE_ICON"));
        addAction(b, pending(this, i), HC + "SLICE_HINT_PENDING_INTENT");
        b.addText("false", null, Collections.singletonList(HC + "SLICE_HINT_AUTO_SELECT_ALLOWED"));
        callback.onResult(new BeginCreateCredentialResponse.Builder().addCreateEntry(new CreateEntry(b.build())).build());
    }

    @Override
    public void onBeginGetCredential(BeginGetCredentialRequest request, CancellationSignal cancel,
                                     OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException> callback) {
        try {
            Session.checkTimeout(this);
            callback.onResult(buildGetResponse(this, request));
        } catch (Exception e) {
            callback.onError(new GetCredentialException(GetCredentialException.TYPE_UNKNOWN, String.valueOf(e.getMessage())));
        }
    }

    @Override
    public void onClearCredentialState(ClearCredentialStateRequest request, CancellationSignal cancel,
                                       OutcomeReceiver<Void, ClearCredentialStateException> callback) {
        callback.onResult(null);
    }

    /** Matching passkeys when unlocked, otherwise a single "Unlock Grove Vault" action. */
    static BeginGetCredentialResponse buildGetResponse(Context c, BeginGetCredentialRequest request) {
        BeginGetCredentialResponse.Builder r = new BeginGetCredentialResponse.Builder();
        boolean wanted = false;
        for (BeginGetCredentialOption o : request.getBeginGetCredentialOptions())
            if (TYPE_PUBLIC_KEY.equals(o.getType()) || TYPE_PASSWORD.equals(o.getType())) wanted = true;
        if (!wanted) return r.build();
        if (!Session.isUnlocked()) {
            Intent i = new Intent(c, PasskeyActivity.class).putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_UNLOCK);
            Slice.Builder b = new Slice.Builder(Uri.EMPTY, new SliceSpec("AuthenticationAction", 0));
            addAction(b, pending(c, i), HA + "SLICE_HINT_PENDING_INTENT");
            b.addText("Unlock Grove Vault", null, Collections.singletonList(HA + "SLICE_HINT_TITLE"));
            r.addAuthenticationAction(new Action(b.build()));
            return r.build();
        }
        for (BeginGetCredentialOption o : request.getBeginGetCredentialOptions()) {
            if (TYPE_PASSWORD.equals(o.getType())) {
                addPasswords(c, r, o, request.getCallingAppInfo());
                continue;
            }
            if (!TYPE_PUBLIC_KEY.equals(o.getType())) continue;
            Map<String, Object> req = Json.obj(Json.parse(o.getCandidateQueryData().getString(KEY_REQUEST_JSON, "{}")));
            String rpId = Json.str(req, "rpId");
            Set<String> allowed = new HashSet<String>();
            for (Object a : Json.arr(req.get("allowCredentials"))) {
                String id = Json.str(Json.obj(a), "id");
                if (id != null) allowed.add(Passkey.b64u(Passkey.unb64u(id)));
            }
            for (Card card : Session.vault().activeCards()) {
                for (Passkey p : card.passkeys()) {
                    if (rpId != null && !p.rpId.equalsIgnoreCase(rpId)) continue;
                    String cid = Passkey.b64u(p.credentialId);
                    if (!allowed.isEmpty() && !allowed.contains(cid)) continue;
                    Intent i = new Intent(c, PasskeyActivity.class)
                            .putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_GET)
                            .putExtra("card", card.id).putExtra("cred", cid);
                    r.addCredentialEntry(new CredentialEntry(o, entrySlice(c, o, p, card, pending(c, i))));
                }
            }
        }
        return r.build();
    }

    /** Password entries: websites only from verified browsers, apps only through a pinned link (same rules as autofill). */
    private static void addPasswords(Context c, BeginGetCredentialResponse.Builder r, BeginGetCredentialOption o, CallingAppInfo info) {
        if (info == null) return;
        String pkg = info.getPackageName();
        String fp = certFingerprint(info);
        String host = null;
        if (info.getOrigin() != null && Trust.isTrustedBrowser(c, pkg, fp)) {
            String origin = info.getOrigin();
            host = Card.domainOf(origin);
        }
        for (Card card : Session.vault().activeCards()) {
            if (card.password().length() == 0) continue;
            boolean ok = false;
            if (host != null) {
                for (org.grove.vault.core.Field f : card.fields)
                    if (org.grove.vault.core.Field.WEBSITE.equals(f.type) && f.value.length() > 0
                            && Card.baseDomain(Card.domainOf(f.value)).equals(Card.baseDomain(host))) ok = true;
            } else if (card.apps.contains(pkg)) {
                String pin = card.appSigs.get(pkg);
                ok = pin == null || pin.equalsIgnoreCase(fp);
            }
            if (!ok) continue;
            Intent i = new Intent(c, PasskeyActivity.class).putExtra(PasskeyActivity.EXTRA_MODE, PasskeyActivity.MODE_GET_PASSWORD).putExtra("card", card.id);
            String user = card.login().length() > 0 ? card.login() : card.title;
            r.addCredentialEntry(new CredentialEntry(o, slice(c, TYPE_PASSWORD, o, user, card.title, "Password", card.modified, pending(c, i))));
        }
    }

    private static Slice entrySlice(Context c, BeginGetCredentialOption o, Passkey p, Card card, PendingIntent pi) {
        String user = p.userName.length() > 0 ? p.userName : card.title;
        String display = p.displayName.length() > 0 ? p.displayName : card.title;
        return slice(c, TYPE_PUBLIC_KEY, o, user, display, "Passkey", card.modified, pi);
    }

    private static Slice slice(Context c, String type, BeginGetCredentialOption o, String user, String display, String typeName, long lastUsed, PendingIntent pi) {
        Slice.Builder b = new Slice.Builder(Uri.EMPTY, new SliceSpec(type, 1));
        b.addText(o.getId(), null, Collections.singletonList(H + "SLICE_HINT_OPTION_ID"));
        b.addText(user, null, Collections.singletonList(H + "SLICE_HINT_DEDUPLICATION_ID"));
        b.addText("false", null, Collections.singletonList(H + "SLICE_HINT_IS_DEFAULT_ICON_PREFERRED"));
        b.addText(null, null, Collections.singletonList(H + "SLICE_HINT_AFFILIATED_DOMAIN"));
        b.addText(typeName, null, Collections.singletonList(H + "SLICE_HINT_TYPE_DISPLAY_NAME"));
        b.addText(user, null, Collections.singletonList(H + "SLICE_HINT_USER_NAME"));
        b.addText(display, null, Collections.singletonList(H + "SLICE_HINT_CREDENTIAL_TYPE_DISPLAY_NAME"));
        b.addText("false", null, Collections.singletonList(H + "SLICE_HINT_AUTO_ALLOWED"));
        b.addIcon(Icon.createWithResource(c, R.mipmap.ic_launcher), null, Collections.singletonList(H + "SLICE_HINT_PROFILE_ICON"));
        b.addLong(lastUsed, null, Collections.singletonList(H + "SLICE_HINT_LAST_USED_TIME_MILLIS"));
        addAction(b, pi, H + "SLICE_HINT_PENDING_INTENT");
        return b.build();
    }

    private static void addAction(Slice.Builder b, PendingIntent pi, String hint) {
        b.addAction(pi, new Slice.Builder(b).addHints(Collections.singletonList(hint)).build(), null);
    }

    static PendingIntent pending(Context c, Intent i) {
        return PendingIntent.getActivity(c, requestCode++, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    // ---------------------------------------------------------------- caller verification

    /** SHA-256 of the caller's signing certificate, as "AA:BB:...". */
    static String certFingerprint(CallingAppInfo info) {
        try {
            Signature[] sigs = info.getSigningInfo().hasMultipleSigners() ? info.getSigningInfo().getApkContentsSigners()
                    : info.getSigningInfo().getSigningCertificateHistory();
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                if (b.length() > 0) b.append(':');
                b.append(String.format(Locale.ROOT, "%02X", x & 0xff));
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static byte[] certHash(CallingAppInfo info) {
        try {
            Signature[] sigs = info.getSigningInfo().hasMultipleSigners() ? info.getSigningInfo().getApkContentsSigners()
                    : info.getSigningInfo().getSigningCertificateHistory();
            return MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static List<Object> allowlist;

    /** True if the caller is a browser on the published privileged list (package + signing certificate). */
    static boolean isPrivilegedBrowser(Context c, CallingAppInfo info) {
        try {
            if (allowlist == null) {
                InputStream in = c.getResources().openRawResource(R.raw.privileged_browsers);
                byte[] b = new byte[in.available()];
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                in.close();
                allowlist = Json.arr(Json.obj(Json.parse(new String(b, "UTF-8"))).get("apps"));
            }
            String fp = certFingerprint(info);
            for (Object o : allowlist) {
                Map<String, Object> app = Json.obj(Json.obj(o).get("info"));
                if (!info.getPackageName().equals(Json.str(app, "package_name"))) continue;
                for (Object s : Json.arr(app.get("signatures")))
                    if (fp.equalsIgnoreCase(Json.str(Json.obj(s), "cert_fingerprint_sha256"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
