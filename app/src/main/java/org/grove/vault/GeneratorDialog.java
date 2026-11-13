package org.grove.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.grove.vault.core.PasswordTools;

/** Password / passphrase generator. Settings are remembered. */
public final class GeneratorDialog {
    private GeneratorDialog() {}

    public static void show(final Activity a, final Ui.Cb<String> use) {
        final PasswordTools.Options o = new PasswordTools.Options();
        o.length = Prefs.p(a).getInt("gen_len", 16);
        o.upper = Prefs.p(a).getBoolean("gen_up", true);
        o.lower = Prefs.p(a).getBoolean("gen_low", true);
        o.digits = Prefs.p(a).getBoolean("gen_dig", true);
        o.symbols = Prefs.p(a).getBoolean("gen_sym", true);
        o.avoidAmbiguous = Prefs.p(a).getBoolean("gen_amb", true);
        o.passphrase = Prefs.p(a).getBoolean("gen_phrase", false);
        o.words = Prefs.p(a).getInt("gen_words", 5);

        LinearLayout c = Ui.vertical(a);
        int pad = Ui.dp(a, 20);
        c.setPadding(pad, pad, pad, 0);
        final TextView out = Ui.text(a, "", 20, Ui.TEXT, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setGravity(Gravity.CENTER);
        out.setPadding(Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16));
        out.setBackground(Ui.round(Ui.SURFACE2, Ui.dp(a, 12)));
        c.addView(out);
        final TextView strength = Ui.text(a, "", 13, Ui.SUB, false);
        strength.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        c.addView(strength);

        final LinearLayout modeRow = Ui.horizontal(a);
        c.addView(modeRow);
        final TextView lenLabel = Ui.text(a, "", 14, Ui.TEXT, false);
        c.addView(lenLabel, Ui.margins(a, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        final SeekBar len = new SeekBar(a);
        c.addView(len);
        final LinearLayout opts = Ui.vertical(a);
        c.addView(opts);

        final Runnable[] regen = new Runnable[1];
        final Runnable[] rebuild = new Runnable[1];
        regen[0] = new Runnable() {
            public void run() {
                String p = PasswordTools.generate(o);
                out.setText(p);
                LockActivity.showStrength(strength, p);
                save(a, o);
            }
        };
        rebuild[0] = new Runnable() {
            public void run() {
                modeRow.removeAllViews();
                modeRow.addView(Ui.chip(a, "Random", !o.passphrase, new View.OnClickListener() {
                    public void onClick(View v) { o.passphrase = false; rebuild[0].run(); }
                }));
                modeRow.addView(Ui.chip(a, "Passphrase", o.passphrase, new View.OnClickListener() {
                    public void onClick(View v) { o.passphrase = true; rebuild[0].run(); }
                }));
                opts.removeAllViews();
                if (o.passphrase) {
                    len.setMax(10 - 3);
                    len.setProgress(o.words - 3);
                    lenLabel.setText("Words: " + o.words);
                    opts.addView(check(a, "Capitalise words", o.capitalize, new Ui.Cb<Boolean>() { public void on(Boolean b) { o.capitalize = b; regen[0].run(); } }));
                    opts.addView(check(a, "Add a digit", o.addNumber, new Ui.Cb<Boolean>() { public void on(Boolean b) { o.addNumber = b; regen[0].run(); } }));
                } else {
                    len.setMax(64 - 6);
                    len.setProgress(o.length - 6);
                    lenLabel.setText("Length: " + o.length);
                    opts.addView(check(a, "Uppercase (A–Z)", o.upper, new Ui.Cb<Boolean>() { public void on(Boolean b) { o.upper = b; regen[0].run(); } }));
                    opts.addView(check(a, "Lowercase (a–z)", o.lower, new Ui.Cb<Boolean>() { public void on(Boolean b) { o.lower = b; regen[0].run(); } }));
                    opts.addView(check(a, "Digits (0–9)", o.digits, new Ui.Cb<Boolean>() { public void on(Boolean b) { o.digits = b; regen[0].run(); } }));
                    opts.addView(check(a, "Symbols (!@#…)", o.symbols, new Ui.Cb<Boolean>() { public void on(Boolean b) { o.symbols = b; regen[0].run(); } }));
                    opts.addView(check(a, "Avoid look-alikes (l 1 O 0)", o.avoidAmbiguous, new Ui.Cb<Boolean>() { public void on(Boolean b) { o.avoidAmbiguous = b; regen[0].run(); } }));
                }
                regen[0].run();
            }
        };
        len.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                if (o.passphrase) {
                    o.words = p + 3;
                    lenLabel.setText("Words: " + o.words);
                } else {
                    o.length = p + 6;
                    lenLabel.setText("Length: " + o.length);
                }
                regen[0].run();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        rebuild[0].run();

        AlertDialog.Builder b = Ui.dialog(a, "Password generator").setView(Ui.scroll(a, c))
                .setNeutralButton("New", null)
                .setNegativeButton(use == null ? "Close" : "Cancel", null)
                .setPositiveButton(use == null ? "Copy" : "Use", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String p = out.getText().toString();
                        if (use == null) App.copy(a, "Password", p);
                        else use.on(p);
                    }
                });
        AlertDialog d = b.create();
        d.show();
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { regen[0].run(); }
        });
    }

    private static CheckBox check(Activity a, String label, boolean on, final Ui.Cb<Boolean> cb) {
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setTextColor(Ui.TEXT);
        c.setChecked(on);
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { cb.on(v); }
        });
        return c;
    }

    private static void save(Activity a, PasswordTools.Options o) {
        Prefs.p(a).edit().putInt("gen_len", o.length).putBoolean("gen_up", o.upper).putBoolean("gen_low", o.lower)
                .putBoolean("gen_dig", o.digits).putBoolean("gen_sym", o.symbols).putBoolean("gen_amb", o.avoidAmbiguous)
                .putBoolean("gen_phrase", o.passphrase).putInt("gen_words", o.words).apply();
    }
}
