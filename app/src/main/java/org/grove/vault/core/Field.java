package org.grove.vault.core;

import java.util.ArrayList;
import java.util.List;

/** One named value on a card. The type decides how it is shown, masked, copied and autofilled. */
public class Field {
    public static final String TEXT = "text";
    public static final String LOGIN = "login";
    public static final String EMAIL = "email";
    public static final String PASSWORD = "password";
    public static final String WEBSITE = "website";
    public static final String NUMBER = "number";
    public static final String PIN = "pin";
    public static final String PHONE = "phone";
    public static final String DATE = "date";
    public static final String EXPIRY = "expiry";
    public static final String OTP = "otp";
    public static final String SECRET = "secret";
    public static final String MULTILINE = "multiline";
    /** WebAuthn passkey (see {@link Passkey}); not user-selectable, created by sites/apps. */
    public static final String PASSKEY = "passkey";

    public static final String[] TYPES = {TEXT, LOGIN, EMAIL, PASSWORD, WEBSITE, NUMBER, PIN, PHONE, DATE, EXPIRY, OTP, SECRET, MULTILINE};
    public static final String[] TYPE_NAMES = {"Text", "Login", "Email", "Password", "Website", "Number", "PIN", "Phone", "Date", "Expiry date", "One-time password (2FA)", "Secret text", "Multi-line text"};

    public String name;
    public String type;
    public String value;
    /** Previous values of this field, newest first. */
    public final List<HistoryEntry> history = new ArrayList<HistoryEntry>();

    public Field(String name, String type, String value) {
        this.name = name;
        this.type = type == null ? TEXT : type;
        this.value = value == null ? "" : value;
    }

    public Field copy() {
        Field f = new Field(name, type, value);
        f.history.addAll(history);
        return f;
    }

    /** Values that are hidden behind dots until revealed. */
    public boolean isSecret() {
        return PASSWORD.equals(type) || PIN.equals(type) || SECRET.equals(type) || OTP.equals(type) || PASSKEY.equals(type);
    }

    public boolean isPasswordLike() {
        return PASSWORD.equals(type);
    }

    /** Sets a new value, keeping the old one in history if it is a secret. */
    public void update(String newValue) {
        if (newValue == null) newValue = "";
        if (!newValue.equals(value) && isSecret() && !PASSKEY.equals(type) && value.length() > 0) {
            history.add(0, new HistoryEntry(value, System.currentTimeMillis()));
            while (history.size() > 20) history.remove(history.size() - 1);
        }
        value = newValue;
    }

    public static String typeName(String type) {
        if (PASSKEY.equals(type)) return "Passkey";
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) return TYPE_NAMES[i];
        return "Text";
    }

    public static class HistoryEntry {
        public final String value;
        public final long time;

        public HistoryEntry(String value, long time) {
            this.value = value;
            this.time = time;
        }
    }
}
