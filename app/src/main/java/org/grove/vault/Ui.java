package org.grove.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Small programmatic UI toolkit: theme colours, views, dialogs and background tasks. */
public final class Ui {
    private Ui() {}

    public interface Cb<T> {
        void on(T value);
    }

    public interface Task<T> {
        T run() throws Exception;
    }

    // ---------------------------------------------------------------- palette

    public static int BG, SURFACE, SURFACE2, TEXT, SUB, ACCENT, ON_ACCENT, DIVIDER, DANGER, WARN;
    public static boolean dark;

    public static final int[] CARD_COLORS = {
            0xFF2E7D5B, 0xFF1E88E5, 0xFF8E24AA, 0xFFE53935, 0xFFF4511E, 0xFF00897B,
            0xFF6D4C41, 0xFF3949AB, 0xFFC0CA33, 0xFF00ACC1, 0xFFD81B60, 0xFF546E7A};

    public static final String[] ICON_NAMES = {"web", "email", "key", "card", "bank", "id", "car", "shield", "star", "wifi", "code", "server", "lock", "note", "phone", "game", "cart", "home", "heart", "plane"};
    public static final String[] ICON_GLYPHS = {"🌐", "✉️", "🔑", "💳", "🏦", "🆔", "🚗", "🛡️", "⭐", "📶", "💾", "🖥️", "🔒", "📝", "📱", "🎮", "🛒", "🏠", "❤️", "✈️"};

    public static String glyph(String icon) {
        for (int i = 0; i < ICON_NAMES.length; i++) if (ICON_NAMES[i].equals(icon)) return ICON_GLYPHS[i];
        return "🔑";
    }

    public static int cardColor(int i) {
        return CARD_COLORS[Math.abs(i) % CARD_COLORS.length];
    }

    /** Must be called in onCreate before setContentView. */
    public static void applyTheme(Activity a) {
        applyTheme(a, false);
    }

    /** translucent = small pop-up windows shown over other apps (e.g. "Save to Grove Vault?"). */
    public static void applyTheme(Activity a, boolean translucent) {
        int t = Prefs.theme(a);
        boolean night = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        dark = t == 2 || t == 3 || (t == 0 && night);
        boolean black = t == 3;
        if (dark) {
            BG = black ? 0xFF000000 : 0xFF0F1412;
            SURFACE = black ? 0xFF0E0F0E : 0xFF19201C;
            SURFACE2 = black ? 0xFF1A1C1A : 0xFF232B26;
            TEXT = 0xFFE6ECE8;
            SUB = 0xFF98A69E;
            ACCENT = 0xFF6FCF97;
            ON_ACCENT = 0xFF06210F;
            DIVIDER = black ? 0xFF1F221F : 0xFF2A332E;
            DANGER = 0xFFFF8A80;
            WARN = 0xFFFFCC66;
            a.setTheme(translucent ? R.style.AppTranslucentDark : black ? R.style.AppBlack : R.style.AppDark);
        } else {
            BG = 0xFFF4F6F3;
            SURFACE = 0xFFFFFFFF;
            SURFACE2 = 0xFFE9EEE9;
            TEXT = 0xFF17201B;
            SUB = 0xFF5B6A61;
            ACCENT = 0xFF2E7D5B;
            ON_ACCENT = 0xFFFFFFFF;
            DIVIDER = 0xFFE0E6E1;
            DANGER = 0xFFC62828;
            WARN = 0xFFB26A00;
            a.setTheme(translucent ? R.style.AppTranslucent : R.style.AppLight);
        }
        if (translucent) {
            if (!Prefs.allowScreenshots(a)) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            return;
        }
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        int flags = a.getWindow().getDecorView().getSystemUiVisibility();
        if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x10; // light nav bar (API 26)
        else flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x10);
        a.getWindow().getDecorView().setSystemUiVisibility(flags);
        if (!Prefs.allowScreenshots(a)) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (android.os.Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(Prefs.allowScreenshots(a));
        if (android.os.Build.VERSION.SDK_INT >= 31) a.getWindow().setHideOverlayWindows(true); // hide other apps' overlays over the vault
    }

    // ---------------------------------------------------------------- basics

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static void toast(final Context c, final String s) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable stroke(int fill, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable g = round(fill, radiusPx);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    public static void ripple(View v, int bg, float radius) {
        int rc = dark ? 0x33FFFFFF : 0x22000000;
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(rc), bg == 0 ? null : round(bg, radius), round(0xFFFFFFFF, radius)));
        v.setClickable(true);
    }

    public static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static LinearLayout.LayoutParams margins(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Filled accent button. */
    public static TextView button(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 15, ON_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(ACCENT, dp(c, 24)), null));
        b.setOnClickListener(l);
        return b;
    }

    /** Low-emphasis button. */
    public static TextView flatButton(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 15, ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        ripple(b, 0, dp(c, 24));
        b.setOnClickListener(l);
        return b;
    }

    /** Round icon with an emoji glyph on a coloured circle. */
    public static TextView badge(Context c, String icon, int colorIndex, int sizeDp) {
        TextView t = new TextView(c);
        t.setText(glyph(icon));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        int col = cardColor(colorIndex);
        g.setColor((col & 0x00FFFFFF) | (dark ? 0x55000000 : 0x2E000000));
        t.setBackground(g);
        t.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        return t;
    }

    /**
     * A card's icon: the coloured badge, replaced by the website's (or linked app's) icon once it is available.
     */
    public static FrameLayout cardIcon(Context c, org.grove.vault.core.Card card, int sizeDp) {
        final FrameLayout f = new FrameLayout(c);
        f.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        TextView badge = badge(c, card.icon, card.color, sizeDp);
        f.addView(badge, new FrameLayout.LayoutParams(MATCH, MATCH));
        final String key = IconCache.keyFor(card);
        if (key == null) return f;
        final android.widget.ImageView iv = new android.widget.ImageView(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(Math.max(1, dp(c, 0.7f)), dark ? 0x33FFFFFF : 0x22000000);
        iv.setBackground(bg);
        iv.setClipToOutline(true);
        int pad = Math.round(dp(c, sizeDp) * 0.16f);
        iv.setPadding(pad, pad, pad, pad);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setVisibility(View.GONE);
        iv.setTag(key);
        f.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        final View fb = badge;
        IconCache.load(c, key, new Cb<android.graphics.Bitmap>() {
            public void on(android.graphics.Bitmap b) {
                if (!key.equals(iv.getTag())) return;
                iv.setImageBitmap(b);
                iv.setVisibility(View.VISIBLE);
                fb.setVisibility(View.INVISIBLE);
            }
        });
        return f;
    }

    /** Icon-only button with a text glyph (keeps the APK free of image assets). */
    public static TextView iconButton(Context c, String glyph, View.OnClickListener l) {
        TextView t = text(c, glyph, 20, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(lp(dp(c, 44), dp(c, 44)));
        ripple(t, 0, dp(c, 22));
        t.setOnClickListener(l);
        return t;
    }

    /** Top bar with optional back arrow, title and right-side views. */
    public static LinearLayout topBar(final Activity a, String title, boolean back, View... actions) {
        LinearLayout bar = horizontal(a);
        bar.setPadding(dp(a, 4), dp(a, 6), dp(a, 4), dp(a, 6));
        bar.setMinimumHeight(dp(a, 56));
        if (back) {
            bar.addView(iconButton(a, "←", new View.OnClickListener() {
                public void onClick(View v) {
                    a.onBackPressed();
                }
            }));
        } else {
            View sp = new View(a);
            bar.addView(sp, lp(dp(a, 12), 1));
        }
        TextView t = text(a, title, 20, TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(a, 6), 0, 0, 0);
        bar.addView(t, lpw(1));
        for (View v : actions) bar.addView(v);
        return bar;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(DIVIDER);
        v.setLayoutParams(lp(MATCH, Math.max(1, dp(c, 0.7f))));
        return v;
    }

    public static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 12, ACCENT, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 6));
        return t;
    }

    /** A tappable two-line row. */
    public static LinearLayout row(Context c, String title, String subtitle, View.OnClickListener l) {
        LinearLayout r = horizontal(c);
        r.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14));
        LinearLayout tx = vertical(c);
        tx.addView(text(c, title, 16, TEXT, false));
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = text(c, subtitle, 13, SUB, false);
            s.setPadding(0, dp(c, 2), 0, 0);
            tx.addView(s);
        }
        r.addView(tx, lpw(1));
        if (l != null) {
            ripple(r, 0, 0);
            r.setOnClickListener(l);
        }
        return r;
    }

    public static TextView subtitleOf(LinearLayout row) {
        LinearLayout tx = (LinearLayout) row.getChildAt(0);
        return tx.getChildCount() > 1 ? (TextView) tx.getChildAt(1) : null;
    }

    public static Switch switchRow(Context c, LinearLayout parent, String title, String subtitle, boolean checked, final Cb<Boolean> cb) {
        LinearLayout r = row(c, title, subtitle, null);
        final Switch s = new Switch(c);
        s.setChecked(checked);
        r.addView(s);
        ripple(r, 0, 0);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                s.toggle();
            }
        });
        s.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                cb.on(on);
            }
        });
        parent.addView(r);
        return s;
    }

    public static EditText edit(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setBackground(stroke(SURFACE, DIVIDER, dp(c, 1), dp(c, 12)));
        // Ask the keyboard not to learn or store what's typed here.
        e.setImeOptions(e.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setFilterTouchesWhenObscured(true);
        return e;
    }

    public static final int PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    public static final int VISIBLE_PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

    public static ScrollView scroll(Context c, View content) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.addView(content);
        return s;
    }

    public static HorizontalScrollView hscroll(Context c, View content) {
        HorizontalScrollView s = new HorizontalScrollView(c);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(content);
        return s;
    }

    public static TextView chip(Context c, String label, boolean selected, View.OnClickListener l) {
        TextView t = text(c, label, 14, selected ? ON_ACCENT : TEXT, selected);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        t.setBackground(selected ? round(ACCENT, dp(c, 18)) : stroke(SURFACE, DIVIDER, dp(c, 1), dp(c, 18)));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = lp(WRAP, WRAP);
        p.setMargins(dp(c, 4), 0, dp(c, 4), 0);
        t.setLayoutParams(p);
        return t;
    }

    /** Root container with background colour; returns the vertical content layout. */
    public static LinearLayout screen(Activity a) {
        LinearLayout root = vertical(a);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.setFilterTouchesWhenObscured(true); // ignore taps while another app draws over us (tapjacking)
        a.setContentView(root);
        return root;
    }

    public static void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    public static void showKeyboard(final View v) {
        v.requestFocus();
        v.postDelayed(new Runnable() {
            public void run() {
                InputMethodManager im = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    // ---------------------------------------------------------------- dialogs

    public static AlertDialog.Builder dialog(Context c, String title) {
        AlertDialog.Builder b = new AlertDialog.Builder(c);
        if (title != null) b.setTitle(title);
        return b;
    }

    public static void message(Context c, String title, String msg) {
        dialog(c, title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    public static void confirm(Context c, String title, String msg, String ok, final Runnable r) {
        dialog(c, title).setMessage(msg).setNegativeButton("Cancel", null)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        r.run();
                    }
                }).show();
    }

    public static void prompt(Context c, String title, String hint, String initial, int inputType, final Cb<String> cb) {
        final EditText e = edit(c, hint, inputType);
        if (initial != null) {
            e.setText(initial);
            e.setSelection(initial.length());
        }
        FrameLayout f = new FrameLayout(c);
        f.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        f.addView(e);
        AlertDialog d = dialog(c, title).setView(f).setNegativeButton("Cancel", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        cb.on(e.getText().toString());
                    }
                }).create();
        d.show();
        showKeyboard(e);
    }

    public static void choose(Context c, String title, String[] items, final Cb<Integer> cb) {
        dialog(c, title).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                cb.on(w);
            }
        }).show();
    }

    public static void single(Context c, String title, String[] items, int checked, final Cb<Integer> cb) {
        dialog(c, title).setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                d.dismiss();
                cb.on(w);
            }
        }).setNegativeButton("Cancel", null).show();
    }

    public static void multi(Context c, String title, String[] items, final boolean[] checked, final Runnable done) {
        dialog(c, title).setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
            public void onClick(DialogInterface d, int w, boolean on) {
                checked[w] = on;
            }
        }).setNegativeButton("Cancel", null).setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                done.run();
            }
        }).show();
    }

    /** Runs work off the main thread with a progress dialog, then delivers the result (or shows the error). */
    @SuppressWarnings("deprecation")
    public static <T> void bg(final Activity a, String message, final Task<T> task, final Cb<T> done, final Cb<Exception> failed) {
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage(message);
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                T r = null;
                Exception err = null;
                try {
                    r = task.run();
                } catch (Exception e) {
                    err = e;
                } catch (OutOfMemoryError e) {
                    err = new Exception("Not enough memory for this operation");
                }
                final T fr = r;
                final Exception fe = err;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        if (a.isFinishing()) return;
                        if (fe == null) done.on(fr);
                        else if (failed != null) failed.on(fe);
                        else message(a, "Something went wrong", fe.getMessage() == null ? fe.toString() : fe.getMessage());
                    }
                });
            }
        }).start();
    }

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

}
