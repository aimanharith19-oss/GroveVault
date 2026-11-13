package org.grove.vault.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSV import and export. Import understands the column names used by Chrome, Firefox, Edge, Bitwarden,
 * LastPass, 1Password, KeePassXC, Proton Pass and others; unknown columns become text fields.
 */
public final class Csv {
    private Csv() {}

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        if (text.length() > 0 && text.charAt(0) == '﻿') text = text.substring(1);
        char sep = guessSeparator(text);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else quoted = false;
                } else cell.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).length() == 0)) rows.add(row);
                row = new ArrayList<String>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static char guessSeparator(String text) {
        int nl = text.indexOf('\n');
        String first = nl > 0 ? text.substring(0, nl) : text;
        int commas = 0, semis = 0, tabs = 0;
        for (char c : first.toCharArray()) {
            if (c == ',') commas++;
            else if (c == ';') semis++;
            else if (c == '\t') tabs++;
        }
        if (tabs > commas && tabs > semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static int col(List<String> header, String... names) {
        for (String n : names) {
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).trim().toLowerCase(Locale.ROOT).equals(n)) return i;
            }
        }
        return -1;
    }

    private static String get(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i).trim() : "";
    }

    /** Imports rows into the vault. Returns number of cards added. */
    public static int importInto(Vault v, String text) {
        List<List<String>> rows = parse(text);
        if (rows.size() < 2) return 0;
        List<String> h = rows.get(0);
        int cTitle = col(h, "name", "title", "account", "item name", "website name");
        int cUrl = col(h, "url", "login_uri", "website", "web site", "uri", "urls", "login url");
        int cUser = col(h, "username", "login_username", "login", "user name", "user", "email", "login name");
        int cPass = col(h, "password", "login_password", "pass");
        int cNotes = col(h, "note", "notes", "extra", "comments", "comment");
        int cOtp = col(h, "totp", "otp", "login_totp", "otpauth", "one-time password", "2fa");
        int cGroup = col(h, "folder", "group", "grouping", "category", "vault", "labels", "tags");
        int cFav = col(h, "favorite", "fav", "favourite");
        List<Integer> used = new ArrayList<Integer>();
        for (int x : new int[]{cTitle, cUrl, cUser, cPass, cNotes, cOtp, cGroup, cFav}) used.add(x);
        int count = 0;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            Card c = new Card();
            c.icon = "web";
            c.title = get(row, cTitle);
            String url = get(row, cUrl);
            if (c.title.length() == 0) c.title = Card.domainOf(url);
            if (c.title.length() == 0) c.title = "Imported";
            String user = get(row, cUser);
            String pass = get(row, cPass);
            if (user.length() > 0 || cUser >= 0) c.fields.add(new Field("Login", Field.LOGIN, user));
            if (pass.length() > 0 || cPass >= 0) c.fields.add(new Field("Password", Field.PASSWORD, pass));
            if (url.length() > 0) c.fields.add(new Field("Website", Field.WEBSITE, url));
            String otp = get(row, cOtp);
            if (otp.length() > 0) c.fields.add(new Field("One-time password", Field.OTP, otp));
            for (int i = 0; i < h.size(); i++) {
                if (used.contains(i)) continue;
                String val = get(row, i);
                if (val.length() == 0) continue;
                String name = h.get(i).trim();
                if (name.length() == 0) name = "Field";
                c.fields.add(new Field(name, Field.TEXT, val));
            }
            c.notes = get(row, cNotes);
            String group = get(row, cGroup);
            if (group.length() > 0) {
                for (String g : group.split("[,;]")) if (g.trim().length() > 0) c.labelIds.add(v.labelNamed(g.trim()).id);
            }
            String fav = get(row, cFav).toLowerCase(Locale.ROOT);
            c.favorite = fav.equals("1") || fav.equals("true") || fav.equals("yes");
            boolean empty = user.length() == 0 && pass.length() == 0 && url.length() == 0 && c.notes.length() == 0 && c.fields.size() <= 2;
            if (empty && c.title.equals("Imported")) continue;
            v.cards.add(c);
            count++;
        }
        return count;
    }

    private static String q(String s) {
        if (s == null) s = "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /** Exports active cards in a common format (name,url,username,password,totp,notes,labels,extra). Unencrypted! */
    public static String export(Vault v) {
        StringBuilder b = new StringBuilder("name,url,username,password,totp,notes,labels,favorite,extra\n");
        for (Card c : v.activeCards()) {
            Field login = c.first(Field.LOGIN);
            if (login == null) login = c.first(Field.EMAIL);
            Field pass = c.first(Field.PASSWORD);
            Field url = c.first(Field.WEBSITE);
            Field otp = c.first(Field.OTP);
            StringBuilder labels = new StringBuilder();
            for (String id : c.labelIds) {
                Label l = v.findLabel(id);
                if (l == null) continue;
                if (labels.length() > 0) labels.append(';');
                labels.append(l.name);
            }
            StringBuilder extra = new StringBuilder();
            for (Field f : c.fields) {
                if (f == login || f == pass || f == url || f == otp || f.value.length() == 0 || Field.PASSKEY.equals(f.type)) continue;
                if (extra.length() > 0) extra.append('\n');
                extra.append(f.name).append(": ").append(f.value);
            }
            b.append(q(c.title)).append(',').append(q(url == null ? "" : url.value)).append(',')
                    .append(q(login == null ? "" : login.value)).append(',').append(q(pass == null ? "" : pass.value)).append(',')
                    .append(q(otp == null ? "" : otp.value)).append(',').append(q(c.notes)).append(',')
                    .append(q(labels.toString())).append(',').append(c.favorite ? "1" : "0").append(',')
                    .append(q(extra.toString())).append('\n');
        }
        return b.toString();
    }
}
