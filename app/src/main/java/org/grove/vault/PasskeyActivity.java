package org.grove.vault;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialResponse;
import android.credentials.Credential;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialResponse;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CreateCredentialRequest;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.GetCredentialRequest;

import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Json;
import org.grove.vault.core.Passkey;
import org.grove.vault.core.Vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Handles passkey creation, sign-in and unlock requests from Android's Credential Manager (Android 14+). */
public class PasskeyActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final int MODE_CREATE = 1, MODE_GET = 2, MODE_UNLOCK = 3, MODE_GET_PASSWORD = 4;
    private static final int REQ_UNLOCK = 1, REQ_SAVE_PASSWORD = 2;
    private static final String DOM_INVALID_STATE = "androidx.credentials.TYPE_CREATE_PUBLIC_KEY_CREDENTIAL_DOM_EXCEPTION/androidx.credentials.TYPE_INVALID_STATE_ERROR";
    private static final String DOM_NOT_ALLOWED = "androidx.credentials.TYPE_CREATE_PUBLIC_KEY_CREDENTIAL_DOM_EXCEPTION/androidx.credentials.TYPE_NOT_ALLOWED_ERROR";
    private static final String DOM_SECURITY = "androidx.credentials.TYPE_CREATE_PUBLIC_KEY_CREDENTIAL_DOM_EXCEPTION/androidx.credentials.TYPE_SECURITY_ERROR";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        Session.checkTimeout(this);
        if (!Session.isUnlocked()) {
            startActivityForResult(new Intent(this, LockActivity.class).putExtra(LockActivity.EXTRA_RETURN, true), REQ_UNLOCK);
        } else proceed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAVE_PASSWORD) {
            if (res == RESULT_OK) {
                Intent out = new Intent();
                out.putExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_RESPONSE, new CreateCredentialResponse(new Bundle()));
                setResult(RESULT_OK, out);
                finish();
            } else createError(CreateCredentialException.TYPE_USER_CANCELED, "Not saved");
            return;
        }
        if (req != REQ_UNLOCK) return;
        if (Session.isUnlocked()) proceed();
        else cancel();
    }

    private int mode() {
        return getIntent().getIntExtra(EXTRA_MODE, 0);
    }

    private void cancel() {
        if (mode() == MODE_CREATE) createError(CreateCredentialException.TYPE_USER_CANCELED, "Cancelled");
        else if (mode() == MODE_GET || mode() == MODE_GET_PASSWORD) getError(GetCredentialException.TYPE_USER_CANCELED, "Cancelled");
        else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    private void proceed() {
        try {
            if (mode() == MODE_UNLOCK) {
                BeginGetCredentialRequest r = getIntent().getParcelableExtra(CredentialProviderService.EXTRA_BEGIN_GET_CREDENTIAL_REQUEST);
                Intent out = new Intent();
                out.putExtra(CredentialProviderService.EXTRA_BEGIN_GET_CREDENTIAL_RESPONSE, PasskeyService.buildGetResponse(this, r));
                setResult(RESULT_OK, out);
                finish();
            } else if (mode() == MODE_CREATE) {
                create((CreateCredentialRequest) getIntent().getParcelableExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST));
            } else if (mode() == MODE_GET_PASSWORD) {
                getPassword((GetCredentialRequest) getIntent().getParcelableExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST));
            } else if (mode() == MODE_GET) {
                get((GetCredentialRequest) getIntent().getParcelableExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST));
            } else finish();
        } catch (Exception e) {
            if (mode() == MODE_CREATE) createError(CreateCredentialException.TYPE_UNKNOWN, String.valueOf(e.getMessage()));
            else getError(GetCredentialException.TYPE_UNKNOWN, String.valueOf(e.getMessage()));
        }
    }

    /** Origin and client data for this caller. Returns {origin, clientDataJSON or null}; throws if not allowed. */
    private Object[] originFor(CallingAppInfo info, String rpId, String type, String challenge, byte[] clientDataHash) throws SecurityException {
        String webOrigin = info.getOrigin();
        if (webOrigin != null) {
            if (!PasskeyService.isPrivilegedBrowser(this, info))
                throw new SecurityException("This app isn't a recognised browser, so it can't use website passkeys.");
            if (!Passkey.originMatchesRp(webOrigin, rpId))
                throw new SecurityException("The website " + webOrigin + " can't use passkeys for " + rpId + ".");
            // The browser builds clientDataJSON itself and passes its hash.
            return new Object[]{webOrigin, clientDataHash == null ? Passkey.clientData(type, challenge, webOrigin, null) : null};
        }
        String origin = "android:apk-key-hash:" + Passkey.b64u(PasskeyService.certHash(info));
        return new Object[]{origin, Passkey.clientData(type, challenge, origin, info.getPackageName())};
    }

    private String callerName(CallingAppInfo info) {
        return CardActivity.appName(this, info.getPackageName());
    }

    // ---------------------------------------------------------------- create

    /** Android 14 "save password" request from an app or browser: shown in the small save window. */
    private void savePassword(CreateCredentialRequest request) {
        Bundle d = request.getData();
        CallingAppInfo info = request.getCallingAppInfo();
        String fp = PasskeyService.certFingerprint(info);
        Intent i = new Intent(this, QuickSaveActivity.class)
                .putExtra("login", d.getString(PasskeyService.KEY_ID, ""))
                .putExtra("password", d.getString(PasskeyService.KEY_PASSWORD, ""));
        if (info.getOrigin() != null && Trust.isTrustedBrowser(this, info.getPackageName(), fp)) i.putExtra("site", Card.domainOf(info.getOrigin()));
        else i.putExtra("app", info.getPackageName()).putExtra("app_cert", fp);
        startActivityForResult(i, REQ_SAVE_PASSWORD);
    }

    private void getPassword(GetCredentialRequest request) {
        Card card = Session.card(getIntent().getStringExtra("card"));
        if (card == null || card.password().length() == 0) {
            getError(GetCredentialException.TYPE_NO_CREDENTIAL, "Password not found");
            return;
        }
        CallingAppInfo info = request.getCallingAppInfo();
        String pkg = info.getPackageName(), fp = PasskeyService.certFingerprint(info);
        if (info.getOrigin() == null && card.apps.contains(pkg) && !card.appSigs.containsKey(pkg) && fp.length() > 0) {
            card.appSigs.put(pkg, fp); // pin on first use
            Session.saveOrToast(this);
        }
        Bundle b = new Bundle();
        b.putString(PasskeyService.KEY_ID, card.login());
        b.putString(PasskeyService.KEY_PASSWORD, card.password());
        Intent out = new Intent();
        out.putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE, new GetCredentialResponse(new Credential(PasskeyService.TYPE_PASSWORD, b)));
        setResult(RESULT_OK, out);
        finish();
    }

    private void create(final CreateCredentialRequest request) throws Exception {
        if (PasskeyService.TYPE_PASSWORD.equals(request.getType())) {
            savePassword(request);
            return;
        }
        final Map<String, Object> req = Json.obj(Json.parse(request.getData().getString(PasskeyService.KEY_REQUEST_JSON, "{}")));
        final byte[] clientDataHash = request.getData().getByteArray(PasskeyService.KEY_CLIENT_DATA_HASH);
        Map<String, Object> rp = Json.obj(req.get("rp"));
        Map<String, Object> user = Json.obj(req.get("user"));
        final String rpId = Json.str(rp, "id");
        final String userName = Json.str(user, "name") == null ? "" : Json.str(user, "name");
        final String displayName = Json.str(user, "displayName") == null ? "" : Json.str(user, "displayName");
        final String userId = Json.str(user, "id");
        final String challenge = Json.str(req, "challenge");
        if (rpId == null || userId == null || challenge == null) {
            createError(CreateCredentialException.TYPE_UNKNOWN, "Incomplete passkey request");
            return;
        }
        boolean es256 = false;
        for (Object p : Json.arr(req.get("pubKeyCredParams"))) {
            Object alg = Json.obj(p).get("alg");
            if (alg instanceof Number && ((Number) alg).longValue() == -7) es256 = true;
        }
        if (!es256) {
            createError(DOM_NOT_ALLOWED, "Only ES256 passkeys are supported");
            return;
        }
        for (Object ex : Json.arr(req.get("excludeCredentials"))) {
            String id = Json.str(Json.obj(ex), "id");
            if (id == null) continue;
            String cid = Passkey.b64u(Passkey.unb64u(id));
            for (Card c : Session.vault().activeCards())
                for (Passkey p : c.passkeys())
                    if (Passkey.b64u(p.credentialId).equals(cid)) {
                        createError(DOM_INVALID_STATE, "A passkey for this account is already in Grove Vault");
                        return;
                    }
        }
        final Object[] origin;
        try {
            origin = originFor(request.getCallingAppInfo(), rpId, "webauthn.create", challenge, clientDataHash);
        } catch (SecurityException e) {
            Ui.toast(this, e.getMessage());
            createError(DOM_SECURITY, e.getMessage());
            return;
        }
        // Choose where to save: a matching card or a new one.
        final List<Card> candidates = new ArrayList<Card>();
        for (Card c : Session.vault().activeCards()) {
            for (Field f : c.fields) {
                if (Field.WEBSITE.equals(f.type) && Card.baseDomain(Card.domainOf(f.value)).equalsIgnoreCase(Card.baseDomain(rpId))) {
                    candidates.add(c);
                    break;
                }
            }
        }
        final String[] items = new String[candidates.size() + 1];
        items[0] = "➕  New card “" + rpId + "”";
        for (int i = 0; i < candidates.size(); i++) items[i + 1] = "Add to “" + candidates.get(i).title + "”" + (candidates.get(i).login().length() > 0 ? " (" + candidates.get(i).login() + ")" : "");
        String from = origin[0].toString().startsWith("android:") ? callerName(request.getCallingAppInfo()) : origin[0].toString();
        Ui.dialog(this, "Create a passkey for " + rpId + "?\n" + (userName.length() > 0 ? userName + " · " : "") + "requested by " + from)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, final int w) {
                        verifyUser("Create passkey for " + rpId, new Runnable() {
                            public void run() {
                                finishCreate(w == 0 ? null : candidates.get(w - 1), rpId, userName, displayName, userId, origin, clientDataHash);
                            }
                        });
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface d) { cancel(); }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { cancel(); }
                }).show();
    }

    private void finishCreate(Card target, String rpId, String userName, String displayName, String userId, Object[] origin, byte[] clientDataHash) {
        try {
            Passkey p = Passkey.generate(rpId, userName, displayName, Passkey.unb64u(userId));
            Vault v = Session.vault();
            if (target == null) {
                target = new Card();
                target.title = rpId;
                target.icon = "web";
                target.color = Math.abs(rpId.hashCode()) % Ui.CARD_COLORS.length;
                target.fields.add(new Field("Login", userName.contains("@") ? Field.EMAIL : Field.LOGIN, userName));
                target.fields.add(new Field("Website", Field.WEBSITE, "https://" + rpId));
                v.cards.add(target);
            }
            target.fields.add(p.toField());
            target.modified = System.currentTimeMillis();
            if (!Session.saveOrToast(this)) {
                createError(CreateCredentialException.TYPE_UNKNOWN, "Could not save the vault");
                return;
            }
            String json = p.registrationResponse((byte[]) origin[1]);
            Bundle data = new Bundle();
            data.putString(PasskeyService.KEY_REGISTRATION_JSON, json);
            Intent out = new Intent();
            out.putExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_RESPONSE, new CreateCredentialResponse(data));
            setResult(RESULT_OK, out);
            Ui.toast(this, "Passkey saved in Grove Vault");
            finish();
        } catch (Exception e) {
            createError(CreateCredentialException.TYPE_UNKNOWN, String.valueOf(e.getMessage()));
        }
    }

    private void createError(String type, String msg) {
        Intent out = new Intent();
        out.putExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_EXCEPTION, new CreateCredentialException(type, msg));
        setResult(RESULT_OK, out);
        finish();
    }

    // ---------------------------------------------------------------- get (sign in)

    private void get(GetCredentialRequest request) throws Exception {
        Card card = Session.card(getIntent().getStringExtra("card"));
        String cred = getIntent().getStringExtra("cred");
        Passkey pk = null;
        if (card != null) for (Passkey p : card.passkeys()) if (Passkey.b64u(p.credentialId).equals(cred)) pk = p;
        if (pk == null) {
            getError(GetCredentialException.TYPE_NO_CREDENTIAL, "Passkey not found");
            return;
        }
        CredentialOption option = null;
        for (CredentialOption o : request.getCredentialOptions()) if (PasskeyService.TYPE_PUBLIC_KEY.equals(o.getType())) option = o;
        if (option == null) {
            getError(GetCredentialException.TYPE_NO_CREDENTIAL, "No passkey request");
            return;
        }
        Bundle data = option.getCredentialRetrievalData();
        Map<String, Object> req = Json.obj(Json.parse(data.getString(PasskeyService.KEY_REQUEST_JSON, "{}")));
        final byte[] clientDataHash = data.getByteArray(PasskeyService.KEY_CLIENT_DATA_HASH);
        String rpId = Json.str(req, "rpId");
        if (rpId == null) rpId = pk.rpId;
        if (!rpId.equalsIgnoreCase(pk.rpId)) {
            getError(GetCredentialException.TYPE_NO_CREDENTIAL, "Passkey is for another site");
            return;
        }
        final Object[] origin;
        try {
            origin = originFor(request.getCallingAppInfo(), rpId, "webauthn.get", Json.str(req, "challenge"), clientDataHash);
        } catch (SecurityException e) {
            Ui.toast(this, e.getMessage());
            getError(GetCredentialException.TYPE_UNKNOWN, e.getMessage());
            return;
        }
        final Passkey key = pk;
        Runnable sign = new Runnable() {
            public void run() {
                try {
                    String json = key.assertionResponse((byte[]) origin[1], clientDataHash);
                    Bundle b = new Bundle();
                    b.putString(PasskeyService.KEY_AUTHENTICATION_JSON, json);
                    Intent out = new Intent();
                    out.putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE,
                            new GetCredentialResponse(new Credential(PasskeyService.TYPE_PUBLIC_KEY, b)));
                    setResult(RESULT_OK, out);
                    finish();
                } catch (Exception e) {
                    getError(GetCredentialException.TYPE_UNKNOWN, String.valueOf(e.getMessage()));
                }
            }
        };
        if ("required".equals(Json.str(req, "userVerification"))) verifyUser("Sign in to " + rpId, sign);
        else sign.run();
    }

    private void getError(String type, String msg) {
        Intent out = new Intent();
        out.putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION, new GetCredentialException(type, msg));
        setResult(RESULT_OK, out);
        finish();
    }

    // ---------------------------------------------------------------- user verification

    /** Confirms the user with fingerprint/face or the screen lock (falls through if the phone has no screen lock). */
    private void verifyUser(String title, final Runnable then) {
        int auth = BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        BiometricManager bm = getSystemService(BiometricManager.class);
        if (bm == null || bm.canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            then.run();
            return;
        }
        new BiometricPrompt.Builder(this).setTitle(title).setSubtitle("Grove Vault").setAllowedAuthenticators(auth).build()
                .authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { then.run(); }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) { cancel(); }
                });
    }
}
