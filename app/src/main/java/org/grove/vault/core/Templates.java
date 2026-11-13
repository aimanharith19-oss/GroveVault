package org.grove.vault.core;

/** Built-in card templates and starter labels for a new vault. */
public final class Templates {
    private Templates() {}

    private static Card t(String title, String icon, int color, String... nameTypePairs) {
        Card c = new Card();
        c.template = true;
        c.title = title;
        c.icon = icon;
        c.color = color;
        for (int i = 0; i + 1 < nameTypePairs.length; i += 2) c.fields.add(new Field(nameTypePairs[i], nameTypePairs[i + 1], ""));
        return c;
    }

    public static Card[] defaults() {
        return new Card[]{
                t("Web account", "web", 0, "Login", Field.LOGIN, "Password", Field.PASSWORD, "Website", Field.WEBSITE, "One-time password", Field.OTP),
                t("Email account", "email", 1, "Email", Field.EMAIL, "Password", Field.PASSWORD, "Website", Field.WEBSITE, "One-time password", Field.OTP),
                t("Login & password", "key", 2, "Login", Field.LOGIN, "Password", Field.PASSWORD),
                t("Credit card", "card", 3, "Number", Field.NUMBER, "Cardholder", Field.TEXT, "Expires", Field.EXPIRY, "CVV", Field.PIN, "PIN", Field.PIN, "Lost card phone", Field.PHONE),
                t("Bank account", "bank", 4, "Bank", Field.TEXT, "Account number", Field.NUMBER, "Account holder", Field.TEXT, "SWIFT / BIC", Field.TEXT, "Online banking login", Field.LOGIN, "Password", Field.PASSWORD, "Website", Field.WEBSITE),
                t("ID / Passport", "id", 5, "Number", Field.NUMBER, "Full name", Field.TEXT, "Date of birth", Field.DATE, "Issued", Field.DATE, "Expires", Field.EXPIRY),
                t("Driving licence", "car", 6, "Number", Field.NUMBER, "Full name", Field.TEXT, "Class", Field.TEXT, "Issued", Field.DATE, "Expires", Field.EXPIRY),
                t("Insurance", "shield", 7, "Policy number", Field.NUMBER, "Company", Field.TEXT, "Expires", Field.EXPIRY, "Phone", Field.PHONE),
                t("Membership", "star", 8, "Member number", Field.NUMBER, "Login", Field.LOGIN, "Password", Field.PASSWORD, "Website", Field.WEBSITE, "Expires", Field.EXPIRY),
                t("Wi-Fi network", "wifi", 9, "Network name (SSID)", Field.TEXT, "Password", Field.PASSWORD, "Security", Field.TEXT, "Router admin URL", Field.WEBSITE, "Router admin password", Field.PASSWORD),
                t("Software licence", "code", 10, "Product", Field.TEXT, "Licence key", Field.SECRET, "Registered to", Field.TEXT, "Website", Field.WEBSITE),
                t("Server / SSH", "server", 11, "Host", Field.TEXT, "Port", Field.NUMBER, "User", Field.LOGIN, "Password", Field.PASSWORD),
                t("Social security", "id", 0, "Number", Field.SECRET, "Full name", Field.TEXT),
                t("PIN / code", "lock", 1, "Code", Field.PIN),
                t("Secure note", "note", 2),
                t("Custom", "key", 3, "Field", Field.TEXT),
        };
    }

    /** A fresh vault with templates and a few starter labels. */
    public static Vault newVault() {
        Vault v = new Vault();
        for (Card c : defaults()) v.cards.add(c);
        v.labels.add(new Label(Vault.newId(), "Personal"));
        v.labels.add(new Label(Vault.newId(), "Work"));
        v.labels.add(new Label(Vault.newId(), "Finance"));
        return v;
    }
}
