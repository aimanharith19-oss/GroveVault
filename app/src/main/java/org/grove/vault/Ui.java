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

    /** Card tile colours (Windows Phone accents, same order as before so existing cards keep their colour family). */
    public static final int[] CARD_COLORS = {
            0xFF60A917, 0xFF1BA1E2, 0xFFAA00FF, 0xFFE51400, 0xFFFA6800, 0xFF00ABA9,
            0xFF825A2C, 0xFF0050EF, 0xFFA4C400, 0xFF1BA1E2, 0xFFD80073, 0xFF647687};

    /** The 20 Windows Phone 8 accent colours. */
    public static final int[] ACCENTS = {
            0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF0050EF, 0xFF6A00FF,
            0xFFAA00FF, 0xFFF472D0, 0xFFD80073, 0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A,
            0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E};
    public static final String[] ACCENT_NAMES = {"lime", "green", "emerald", "teal", "cyan", "cobalt", "indigo",
            "violet", "pink", "magenta", "crimson", "red", "orange", "amber", "yellow", "brown", "olive", "steel", "mauve", "taupe"};

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
        ACCENT = Prefs.accent(a);
        ON_ACCENT = 0xFFFFFFFF;
        if (dark) {
            BG = 0xFF202020;       // Mica (dark)
            SURFACE = 0xFF2B2B2B;  // card
            SURFACE2 = 0xFF323232; // control / flyout
            TEXT = 0xFFFFFFFF;
            SUB = 0xFFC5C5C5;
            DIVIDER = 0x15FFFFFF;
            DANGER = 0xFFFF5A4E;
            WARN = 0xFFF0A30A;
            a.setTheme(translucent ? R.style.AppTranslucentDark : R.style.AppDark);
        } else {
            BG = 0xFFF3F3F3;       // Mica (light)
            SURFACE = 0xFFFBFBFB;
            SURFACE2 = 0xFFFFFFFF;
            TEXT = 0xFF1B1B1B;
            SUB = 0xFF5F5F5F;
            DIVIDER = 0x12000000;
            DANGER = 0xFFE51400;
            WARN = 0xFFC27D00;
            a.setTheme(translucent ? R.style.AppTranslucent : R.style.AppLight);
        }
        if (translucent) {
            if (!Prefs.allowScreenshots(a)) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            return;
        }
        a.getWindow().setStatusBarColor(blend(BG, ACCENT, 0.06f));
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

    public static int blend(int a, int b, float t) {
        int ar = a >> 16 & 0xff, ag = a >> 8 & 0xff, ab = a & 0xff, br = b >> 16 & 0xff, bg = b >> 8 & 0xff, bb = b & 0xff;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    public static final Typeface LIGHT = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface SEMI = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** Fluent corner radii: 4dp controls, 8dp cards and flyouts. */
    public static final float R_CONTROL = 4, R_CARD = 8;

    /** Fluent card: layer fill with a hairline stroke and 8dp corners. */
    public static GradientDrawable card(Context c) {
        return stroke(SURFACE, DIVIDER, Math.max(1, dp(c, 1)), dp(c, R_CARD));
    }

    /** Pressed-state overlay on top of a background (Fluent's "subtle fill"). */
    public static android.graphics.drawable.Drawable pressable(Context c, android.graphics.drawable.Drawable normal, float radiusPx) {
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.Drawable over = round(dark ? 0x14FFFFFF : 0x0A000000, radiusPx);
        android.graphics.drawable.Drawable pressed = normal == null ? over : new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{normal, over});
        st.addState(new int[]{android.R.attr.state_pressed}, pressed);
        st.addState(new int[]{}, normal == null ? new android.graphics.drawable.ColorDrawable(0) : normal);
        st.setExitFadeDuration(150);
        return st;
    }

    /** Fluent press: a slight shrink while pressed (no tilt). */
    public static void tilt(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent e) {
                if (!view.isClickable()) return false;
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1).scaleY(1).setDuration(200)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
                        break;
                }
                return false;
            }
        });
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
        // keep a background the view already has (e.g. a colour swatch) and only add the pressed overlay
        v.setBackground(pressable(v.getContext(), bg == 0 ? v.getBackground() : round(bg, radius), radius));
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
        t.setTypeface(bold || sp >= 19 ? SEMI : Typeface.DEFAULT); // Fluent: titles semibold, body regular
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

    /** Fluent accent button: filled with the accent, 4dp corners. */
    public static TextView button(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 15, ON_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 40));
        b.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 8));
        b.setBackground(pressable(c, round(ACCENT, dp(c, R_CONTROL)), dp(c, R_CONTROL)));
        b.setOnClickListener(l);
        tilt(b);
        return b;
    }

    /** Fluent standard button: neutral fill with a hairline stroke. */
    public static TextView flatButton(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 15, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 40));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setBackground(pressable(c, stroke(SURFACE2, DIVIDER, Math.max(1, dp(c, 1)), dp(c, R_CONTROL)), dp(c, R_CONTROL)));
        b.setClickable(true);
        tilt(b);
        b.setOnClickListener(l);
        return b;
    }

    /** Square tile with the icon glyph on the card's colour. */
    public static TextView badge(Context c, String icon, int colorIndex, int sizeDp) {
        TextView t = new TextView(c);
        t.setText(glyph(icon));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.40f);
        t.setBackground(round(cardColor(colorIndex), dp(c, R_CONTROL)));
        t.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        return t;
    }

    /** True when cardIcon() shows only the letter tile (no website/app icon available yet). */
    public static boolean letterOnly(View icon) {
        if (!(icon instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) icon;
        for (int i = 0; i < g.getChildCount(); i++)
            if (g.getChildAt(i) instanceof android.widget.ImageView && g.getChildAt(i).getVisibility() == View.VISIBLE) return false;
        return true;
    }

    /** Rounded colour tile with the title's first letter (used until the site icon arrives). */
    public static TextView letterTile(Context c, String title, int color, int sizeDp) {
        String l = title == null || title.trim().length() == 0 ? "#" : title.trim().substring(0, 1).toUpperCase();
        TextView t = text(c, l, sizeDp * 0.42f, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackground(round(color, dp(c, sizeDp >= 48 ? R_CARD : R_CONTROL)));
        t.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        return t;
    }

    /**
     * A card's icon: the coloured badge, replaced by the website's (or linked app's) icon once it is available.
     */
    public static FrameLayout cardIcon(Context c, org.grove.vault.core.Card card, int sizeDp) {
        final FrameLayout f = new FrameLayout(c);
        f.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        TextView badge = letterTile(c, card.title, cardColor(card.color), sizeDp);
        f.addView(badge, new FrameLayout.LayoutParams(MATCH, MATCH));
        final String key = IconCache.keyFor(card);
        if (key == null) return f;
        final android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setBackground(round(0xFFFFFFFF, dp(c, sizeDp >= 48 ? R_CARD : R_CONTROL)));
        iv.setClipToOutline(true);
        int pad = Math.round(dp(c, sizeDp) * 0.14f);
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

    static int iconFor(String g) {
        switch (g) {
            case "🔍": return R.drawable.ic_search;
            case "👁": return R.drawable.ic_eye;
            case "✏️": return R.drawable.ic_edit;
            case "🎲": return R.drawable.ic_refresh;
            case "📞": return R.drawable.ic_phone;
            case "🗑": return R.drawable.ic_delete;
            case "✉": return R.drawable.ic_mail;
            case "⬇": return R.drawable.ic_download;
            default: return 0;
        }
    }

    /** Icon-only button with a text glyph (keeps the APK free of image assets). */
    public static TextView iconButton(Context c, String glyph, View.OnClickListener l) {
        int res = iconFor(glyph);
        TextView t = text(c, res != 0 ? "" : glyph, 18, TEXT, false);
        t.setTypeface(Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(lp(dp(c, 44), dp(c, 44)));
        if (res != 0) {
            // emoji icons are coloured; use a line icon in the theme's text colour instead (Fluent style)
            android.graphics.drawable.Drawable ic = c.getDrawable(res).mutate();
            ic.setTint(TEXT);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{pressable(c, null, dp(c, R_CONTROL)), ic});
            ld.setLayerGravity(1, Gravity.CENTER);
            ld.setLayerSize(1, dp(c, 20), dp(c, 20));
            t.setBackground(ld);
            t.setTag(R.id.grove_icon, Boolean.TRUE);
            t.setContentDescription(glyph);
        } else t.setBackground(pressable(c, null, dp(c, R_CONTROL)));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    /**
     * Fluent page header (like Grove Launcher's settings): a back arrow and a 28sp semibold title; on the home page
     * just the app name. Actions go to the command bar at the bottom.
     */
    public static LinearLayout topBar(final Activity a, String title, boolean back, View... actions) {
        LinearLayout h = horizontal(a);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(back ? dp(a, 6) : dp(a, 20), dp(a, 12), dp(a, 16), dp(a, 8));
        if (back) {
            TextView b = iconButton(a, "\u2190", new View.OnClickListener() {
                public void onClick(View v) { a.onBackPressed(); }
            });
            b.setContentDescription("Back");
            h.addView(b);
        }
        if (title != null && title.length() > 0) {
            TextView t = text(a, title, 28, TEXT, true);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setTag("grove_title");
            t.setPadding(back ? dp(a, 6) : 0, 0, 0, 0);
            h.addView(t, lpw(1));
        }
        appBar(a, actions);
        return h;
    }

    /** Fills the bottom application bar: round outlined buttons for glyphs, lowercase words for text actions. */
    public static void appBar(Activity a, View... actions) {
        View root = a.getWindow().getDecorView().findViewWithTag("grove_appbar");
        if (!(root instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) root;
        bar.removeAllViews();
        if (actions == null || actions.length == 0) {
            bar.setVisibility(View.GONE);
            return;
        }
        View sp = new View(a);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1)); // a bare View with WRAP height would fill the screen
        for (View v : actions) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                String s = t.getText().toString();
                if (t.getTag(R.id.grove_icon) != null) {
                    LinearLayout.LayoutParams p = lp(dp(a, 48), dp(a, 44));
                    p.setMargins(dp(a, 2), 0, dp(a, 2), 0);
                    bar.addView(t, p);
                } else if (s.length() <= 2) {
                    t.setBackground(pressable(a, null, dp(a, R_CONTROL)));
                    t.setTextColor(TEXT);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                    LinearLayout.LayoutParams p = lp(dp(a, 48), dp(a, 44));
                    p.setMargins(dp(a, 2), 0, dp(a, 2), 0);
                    bar.addView(t, p);
                } else {
                    // text command (e.g. "Save"): the accent button
                    t.setTextColor(ON_ACCENT);
                    t.setTypeface(SEMI);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(dp(a, 18), 0, dp(a, 18), 0);
                    t.setBackground(pressable(a, round(ACCENT, dp(a, R_CONTROL)), dp(a, R_CONTROL)));
                    LinearLayout.LayoutParams p = lp(WRAP, dp(a, 38));
                    p.setMargins(dp(a, 6), 0, dp(a, 6), 0);
                    bar.addView(t, p);
                }
            } else bar.addView(v);
        }
        View end = new View(a);
        bar.addView(end, lp(dp(a, 8), 1));
        bar.setVisibility(View.VISIBLE);
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(DIVIDER);
        v.setLayoutParams(lp(MATCH, Math.max(1, dp(c, 0.7f))));
        return v;
    }

    /** Fluent settings group header: 14sp semibold. */
    public static TextView section(Context c, String s) {
        TextView t = text(c, s, 14, TEXT, true);
        t.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 6));
        t.setTag("grove_section"); // pivotize() turns these into tabs
        return t;
    }

    /** Fluent settings card: a rounded card per row, title + secondary text. */
    public static LinearLayout row(Context c, String title, String subtitle, View.OnClickListener l) {
        LinearLayout r = horizontal(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 64));
        r.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        LinearLayout tx = vertical(c);
        TextView tt = text(c, title, 15, TEXT, false);
        tx.addView(tt);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = text(c, subtitle, 12.5f, SUB, false);
            s.setPadding(0, dp(c, 2), 0, 0);
            tx.addView(s);
        }
        r.addView(tx, lpw(1));
        r.setBackground(card(c));
        if (l != null) {
            r.setBackground(pressable(c, card(c), dp(c, R_CARD)));
            r.setClickable(true);
            r.setOnClickListener(l);
            TextView chev = text(c, "\u203A", 22, SUB, false);
            chev.setPadding(dp(c, 12), 0, 0, dp(c, 2));
            r.addView(chev);
        }
        r.setLayoutParams(margins(c, MATCH, WRAP, 16, 2, 16, 2));
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
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, dark ? 0xFFBBBBBB : 0xFF777777}));
        s.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, dark ? 0xFF555555 : 0xFFBBBBBB}));
        r.addView(s);
        r.setBackground(pressable(c, card(c), dp(c, R_CARD)));
        r.setClickable(true);
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

    /** Fluent text box: rounded fill, hairline border, accent underline while typing. */
    public static EditText edit(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextColor(TEXT);
        e.setHintTextColor(SUB);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setMinHeight(dp(c, 40));
        int fill = dark ? 0x0FFFFFFF : 0xB3FFFFFF, r = dp(c, R_CONTROL);
        GradientDrawable box = stroke(fill, dark ? 0x14FFFFFF : 0x0F000000, Math.max(1, dp(c, 1)), r);
        GradientDrawable boxF = stroke(dark ? 0xFF1F1F1F : 0xFFFFFFFF, dark ? 0x14FFFFFF : 0x0F000000, Math.max(1, dp(c, 1)), r);
        GradientDrawable line = round(ACCENT, 0);
        GradientDrawable lineN = round(dark ? 0x8AFFFFFF : 0x72000000, 0);
        android.graphics.drawable.LayerDrawable focused = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{boxF, line});
        focused.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        focused.setLayerHeight(1, dp(c, 2));
        focused.setLayerInset(1, r / 2, 0, r / 2, 0);
        android.graphics.drawable.LayerDrawable normal = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{box, lineN});
        normal.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        normal.setLayerHeight(1, Math.max(1, dp(c, 1)));
        normal.setLayerInset(1, r / 2, 0, r / 2, 0);
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, focused);
        st.addState(new int[]{}, normal);
        e.setBackground(st);
        // Ask the keyboard not to learn or store what's typed here.
        e.setImeOptions(e.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setFilterTouchesWhenObscured(true);
        return e;
    }

    public static final int PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    public static final int VISIBLE_PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

    /**
     * Fluent selector bar (tabs): 15sp labels, the selected one semibold with a short accent pill under it that
     * slides with the finger while swiping between pages.
     */
    public static class PivotHeader extends FrameLayout {
        private final LinearLayout row;
        private final View pill;
        private int current;
        private android.animation.ValueAnimator anim;

        public PivotHeader(Context c) {
            super(c);
            setClipChildren(true);
            row = horizontal(c);
            row.setPadding(dp(c, 12), 0, dp(c, 12), 0);
            addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            pill = new View(c);
            pill.setBackground(round(ACCENT, dp(c, 2)));
            addView(pill, new FrameLayout.LayoutParams(dp(c, 16), dp(c, 3)));
        }

        @Override
        protected void onMeasure(int w, int h) {
            row.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            pill.measure(MeasureSpec.makeMeasureSpec(dp(getContext(), 16), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(getContext(), 3), MeasureSpec.EXACTLY));
            setMeasuredDimension(MeasureSpec.getSize(w), row.getMeasuredHeight() + dp(getContext(), 4));
        }

        @Override
        protected void onLayout(boolean ch, int l, int t, int r, int b) {
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
            int y = row.getMeasuredHeight() - dp(getContext(), 5);
            pill.layout(0, y, pill.getMeasuredWidth(), y + pill.getMeasuredHeight());
            drag(0);
        }

        public void set(String[] names, int selected, final Cb<Integer> onTap) {
            row.removeAllViews();
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                TextView t = text(getContext(), names[i], 15, TEXT, false);
                t.setSingleLine(true);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(getContext(), 12), dp(getContext(), 10), dp(getContext(), 12), dp(getContext(), 14));
                t.setBackground(pressable(getContext(), null, dp(getContext(), R_CONTROL)));
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { onTap.on(idx); }
                });
                row.addView(t);
            }
            current = selected;
            requestLayout();
        }

        private float centerAt(float pos) {
            int n = row.getChildCount();
            if (n == 0) return 0;
            pos = Math.max(0, Math.min(n - 1, pos));
            int i = (int) Math.floor(pos);
            float f = pos - i;
            View a = row.getChildAt(i), b = row.getChildAt(Math.min(n - 1, i + 1));
            float ca = a.getLeft() + a.getWidth() / 2f, cb = b.getLeft() + b.getWidth() / 2f;
            return ca + (cb - ca) * f;
        }

        private void place(float pos) {
            int n = row.getChildCount();
            if (n == 0 || getWidth() == 0) return;
            // keep the selected tab in view when the tabs are wider than the screen
            float overflow = Math.max(0, row.getWidth() - getWidth());
            float scroll = Math.max(0, Math.min(overflow, centerAt(pos) - getWidth() / 2f));
            row.setTranslationX(-scroll);
            pill.setTranslationX(centerAt(pos) - scroll - pill.getWidth() / 2f);
            int sel = Math.round(pos);
            for (int k = 0; k < n; k++) {
                TextView t = (TextView) row.getChildAt(k);
                t.setTextColor(k == sel ? TEXT : SUB);
                t.setTypeface(k == sel ? SEMI : Typeface.DEFAULT);
            }
        }

        /** frac: how far (in pages) the finger has dragged from the current page; negative = towards the previous. */
        public void drag(float frac) {
            if (anim != null) { anim.cancel(); anim = null; }
            place(current + frac);
        }

        public void animateTo(int page) {
            if (anim != null) anim.cancel();
            float from = row.getChildCount() == 0 || getWidth() == 0 ? page : posOfPill();
            current = page;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, page);
            anim = va;
            va.setDuration(260);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator a) { place((Float) a.getAnimatedValue()); }
            });
            va.start();
        }

        /** Current pill position in pages (for smooth hand-over from a drag to the settle animation). */
        private float posOfPill() {
            float x = pill.getTranslationX() - row.getTranslationX() + pill.getWidth() / 2f;
            int n = row.getChildCount();
            for (int i = 0; i < n - 1; i++) {
                View a = row.getChildAt(i), b = row.getChildAt(i + 1);
                float ca = a.getLeft() + a.getWidth() / 2f, cb = b.getLeft() + b.getWidth() / 2f;
                if (x <= cb) return i + Math.max(0, Math.min(1, (x - ca) / Math.max(1, cb - ca)));
            }
            return n - 1;
        }
    }

    /**
     * Finger-following horizontal paging (like a ViewPager): the content moves with the finger, and on release it
     * either settles back or slides off while the next page slides in.
     */
    public static class Swiper {
        public View content;
        public Cb<Integer> onPage;           // called with +1 / -1 once the old page has slid out
        public Cb<Float> onDrag;             // fractional pages dragged (for headers and parallax)
        public int pages = 1, page = 0;
        public boolean justStarted;
        private float x0, y0, base;
        private boolean decided, dragging;
        private Runnable pending;
        private android.view.VelocityTracker vt;

        public boolean handle(android.view.MotionEvent e) {
            justStarted = false;
            if (content == null || pages < 2) return false;
            int slop = android.view.ViewConfiguration.get(content.getContext()).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    // a new touch during a page change: finish the change at once, then follow the new finger
                    content.animate().cancel();
                    if (pending != null) {
                        Runnable r = pending;
                        pending = null;
                        r.run();
                        content.setTranslationX(0);
                    }
                    base = content.getTranslationX();
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    decided = dragging = false;
                    if (vt != null) vt.recycle();
                    vt = android.view.VelocityTracker.obtain();
                    vt.addMovement(e);
                    return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (!decided && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        decided = true;
                        dragging = Math.abs(dx) > Math.abs(dy) * 1.2f;
                        justStarted = dragging;
                    }
                    if (!dragging) {
                        if (!decided) return false;
                        // went vertical: let the list scroll; snap any half-finished slide back
                        if (base != 0) { content.setTranslationX(0); base = 0; if (onDrag != null) onDrag.on(0f); }
                        return false;
                    }
                    float w = Math.max(1, content.getWidth());
                    dx += base;
                    // resist at the first and last page
                    if ((page == 0 && dx > 0) || (page == pages - 1 && dx < 0)) dx *= 0.3f;
                    dx = Math.max(-w, Math.min(w, dx));
                    content.setTranslationX(dx);
                    if (onDrag != null) onDrag.on(-dx / w);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!dragging) {
                        if (base != 0) {
                            content.animate().translationX(0).setDuration(200).start();
                            base = 0;
                        }
                        return false;
                    }
                    dragging = false;
                    base = 0;
                    float dx = content.getTranslationX(), w = Math.max(1, content.getWidth());
                    float vx = 0;
                    if (vt != null) {
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                    }
                    int dir = 0;
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        if (dx < -w / 4 || vx < -900) dir = 1;
                        else if (dx > w / 4 || vx > 900) dir = -1;
                    }
                    if (page + dir < 0 || page + dir >= pages) dir = 0;
                    if (dir == 0) {
                        content.animate().translationX(0).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
                        if (onDrag != null) onDrag.on(0f);
                    } else {
                        final int d = dir;
                        page += d; // count it now, so a quick next swipe goes on from the new page
                        final Runnable change = new Runnable() {
                            public void run() { onPage.on(d); }
                        };
                        pending = change;
                        content.animate().translationX(-d * w).setDuration(120).setInterpolator(new android.view.animation.AccelerateInterpolator())
                                .withEndAction(new Runnable() {
                                    public void run() {
                                        if (pending != change) return;
                                        pending = null;
                                        change.run();
                                        content.setTranslationX(d * content.getWidth() * 0.5f);
                                        content.animate().translationX(0).setDuration(320)
                                                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f)).start();
                                    }
                                }).start();
                    }
                    return true;
                }
            }
            return false;
        }
    }

    /** ScrollView that also reports horizontal swipes (for pivots), even when the swipe starts on a button or row. */
    public static class SwipeScroll extends ScrollView {
        public final Swiper swiper = new Swiper();

        public SwipeScroll(Context c) { super(c); }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            if (swiper.handle(e)) {
                if (swiper.justStarted) {
                    android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                    c.setAction(android.view.MotionEvent.ACTION_CANCEL); // stop the row under the finger from being tapped
                    super.dispatchTouchEvent(c);
                    c.recycle();
                }
                return true;
            }
            return super.dispatchTouchEvent(e);
        }
    }

    private static final java.util.HashMap<String, Integer> PIVOT_PAGE = new java.util.HashMap<String, Integer>();

    /**
     * Windows Phone pivot: splits a scrolling page at its section headers into pages. The section names become the
     * big pivot headers at the top (tap one, or swipe left/right to move between them). Content before the first
     * section becomes a page called firstName.
     */
    public static void pivotize(final Activity a, final LinearLayout c, String firstName) {
        if (!(c.getParent() instanceof ScrollView) || !(c.getParent().getParent() instanceof ViewGroup)) return;
        final java.util.List<String> names = new java.util.ArrayList<String>();
        final java.util.List<java.util.List<View>> pages = new java.util.ArrayList<java.util.List<View>>();
        final java.util.HashMap<View, Integer> vis = new java.util.HashMap<View, Integer>();
        java.util.List<View> headers = new java.util.ArrayList<View>();
        java.util.List<View> cur = new java.util.ArrayList<View>();
        String curName = firstName;
        for (int i = 0; i < c.getChildCount(); i++) {
            View v = c.getChildAt(i);
            if ("grove_section".equals(v.getTag())) {
                if (!cur.isEmpty()) {
                    names.add(curName);
                    pages.add(cur);
                }
                cur = new java.util.ArrayList<View>();
                curName = ((TextView) v).getText().toString();
                headers.add(v);
            } else {
                cur.add(v);
                vis.put(v, v.getVisibility());
            }
        }
        if (!cur.isEmpty()) {
            names.add(curName);
            pages.add(cur);
        }
        if (pages.size() < 2) return;
        for (View h : headers) c.removeView(h);
        final ScrollView sv = (ScrollView) c.getParent();
        ViewGroup root = (ViewGroup) sv.getParent();
        final PivotHeader ph = new PivotHeader(a);
        ph.setTag("grove_pivot");
        View old = root.findViewWithTag("grove_pivot");
        if (old != null) root.removeView(old); // page rebuilt in place
        root.addView(ph, root.indexOfChild(sv), new LinearLayout.LayoutParams(MATCH, WRAP));
        final String key = a.getClass().getName();
        Integer saved = PIVOT_PAGE.get(key);
        final int[] page = {saved == null ? 0 : Math.min(saved, pages.size() - 1)};
        final Runnable show = new Runnable() {
            public void run() {
                PIVOT_PAGE.put(key, page[0]);
                for (int k = 0; k < pages.size(); k++)
                    for (View v : pages.get(k)) v.setVisibility(k == page[0] ? vis.get(v) : View.GONE);
                if (sv instanceof SwipeScroll) ((SwipeScroll) sv).swiper.page = page[0];
            }
        };
        ph.set(names.toArray(new String[0]), page[0], new Cb<Integer>() {
            public void on(Integer k) {
                if (k == page[0]) return;
                page[0] = k;
                show.run();
                sv.scrollTo(0, 0);
                ph.animateTo(k);
            }
        });
        show.run();
        if (sv instanceof SwipeScroll) {
            Swiper sw = ((SwipeScroll) sv).swiper;
            sw.content = c;
            sw.pages = pages.size();
            sw.page = page[0];
            sw.onDrag = new Cb<Float>() {
                public void on(Float f) { ph.drag(f); }
            };
            sw.onPage = new Cb<Integer>() {
                public void on(Integer dir) {
                    page[0] += dir;
                    show.run();
                    sv.scrollTo(0, 0);
                    ph.animateTo(page[0]);
                }
            };
        }
    }

    /** Windows Phone application-bar menu: the bar expands upward into a list of lowercase commands. */
    public static void menu(Activity a, String[] items, final Cb<Integer> cb) {
        final android.app.Dialog d = new android.app.Dialog(a, dark ? R.style.MetroDialogDark : R.style.MetroDialogLight);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout l = vertical(a);
        l.setBackground(stroke(SURFACE2, dark ? 0x33000000 : 0x1A000000, Math.max(1, dp(a, 1)), dp(a, R_CARD)));
        l.setElevation(dp(a, 16));
        l.setPadding(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = text(a, items[i], 15, TEXT, false);
            t.setMinHeight(dp(a, 40));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(a, 12), dp(a, 8), dp(a, 32), dp(a, 8));
            t.setBackground(pressable(a, null, dp(a, R_CONTROL)));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    cb.on(idx);
                }
            });
            l.addView(t, lp(MATCH, WRAP));
        }
        FrameLayout pad = new FrameLayout(a);
        pad.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12));
        pad.setClipToPadding(false);
        pad.addView(l, new FrameLayout.LayoutParams(WRAP, WRAP));
        d.setContentView(pad);
        frame(d);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            d.getWindow().setDimAmount(0f);
            d.getWindow().setGravity(Gravity.BOTTOM | Gravity.END);
            d.getWindow().setWindowAnimations(R.style.MetroBottomAnim);
            d.getWindow().setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            android.view.WindowManager.LayoutParams wl = d.getWindow().getAttributes();
            wl.y = dp(a, 56); // just above the command bar
            d.getWindow().setAttributes(wl);
        }
        d.show();
    }

    /** Lowercase Windows Phone list for pickers; the selected item (if any) is shown in the accent colour. */
    private static void pickList(Context c, String title, String[] items, int selected, boolean cancel, final Cb<Integer> cb) {
        LinearLayout l = vertical(c);
        l.setPadding(0, dp(c, 4), 0, dp(c, 8));
        AlertDialog.Builder b = dialog(c, title);
        ScrollView sv = new ScrollView(c);
        sv.addView(l);
        b.setView(sv);
        if (cancel) b.setNegativeButton("Cancel", null);
        final AlertDialog d = b.create();
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = text(c, (i == selected ? "\u2713  " : "     ") + items[i], 15, i == selected ? ACCENT : TEXT, i == selected);
            t.setMinHeight(dp(c, 40));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
            t.setBackground(pressable(c, null, dp(c, R_CONTROL)));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    cb.on(idx);
                }
            });
            l.addView(t, lp(MATCH, WRAP));
        }
        d.show();
    }

    public static ScrollView scroll(Context c, View content) {
        ScrollView s = new SwipeScroll(c);
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

    /** Fluent pill toggle. */
    public static TextView chip(Context c, String label, boolean selected, View.OnClickListener l) {
        TextView t = text(c, label, 14, selected ? ON_ACCENT : TEXT, selected);
        t.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        t.setSingleLine(true);
        t.setBackground(pressable(c, selected ? round(ACCENT, dp(c, 16)) : stroke(SURFACE2, DIVIDER, Math.max(1, dp(c, 1)), dp(c, 16)), dp(c, 16)));
        t.setOnClickListener(l);
        t.setClickable(true);
        LinearLayout.LayoutParams p = lp(WRAP, WRAP);
        p.setMargins(0, 0, dp(c, 6), 0);
        t.setLayoutParams(p);
        return t;
    }

    /** Root: Mica-like background (a faint wash of the accent), page content, and the command bar at the bottom. */
    public static LinearLayout screen(Activity a) {
        LinearLayout outer = vertical(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                blend(BG, ACCENT, 0.06f), BG, BG});
        outer.setBackground(g);
        outer.setFitsSystemWindows(true);
        outer.setFilterTouchesWhenObscured(true); // ignore taps while another app draws over us (tapjacking)
        LinearLayout content = vertical(a);
        outer.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
        LinearLayout bar = horizontal(a);
        bar.setTag("grove_appbar");
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(a, 56));
        bar.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(dark ? 0xFF1C1C1C : 0xFFFAFAFA);
        android.graphics.drawable.LayerDrawable top = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bb, round(DIVIDER, 0)});
        top.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        top.setLayerHeight(1, Math.max(1, dp(a, 1)));
        bar.setBackground(top);
        bar.setVisibility(View.GONE);
        outer.addView(bar, lp(MATCH, WRAP));
        a.setContentView(outer);
        return content;
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

    /** Windows Phone message box: a square panel across the top of the screen, lowercase actions in the accent. */
    public static AlertDialog.Builder dialog(Context c, String title) {
        AlertDialog.Builder b = new AlertDialog.Builder(c, dark ? R.style.MetroDialogDark : R.style.MetroDialogLight) {
            @Override
            public AlertDialog create() {
                final AlertDialog d = super.create();
                frame(d); // place it before it is shown, so it doesn't jump from the centre to the top
                d.setOnShowListener(new DialogInterface.OnShowListener() {
                    public void onShow(DialogInterface di) { metro(d); }
                });
                return d;
            }
        };
        if (title != null) {
            TextView t = text(c, title, 20, TEXT, true);
            t.setPadding(dp(c, 24), dp(c, 24), dp(c, 24), dp(c, 8));
            b.setCustomTitle(t);
        }
        return b;
    }

    /** Centred (Fluent ContentDialog), and hidden from screenshots/recordings like the rest of the app. */
    static void frame(android.app.Dialog d) {
        if (d.getWindow() == null) return;
        d.getWindow().setGravity(Gravity.CENTER);
        int w = d.getContext().getResources().getDisplayMetrics().widthPixels;
        d.getWindow().setLayout(Math.min(w - dp(d.getContext(), 32), dp(d.getContext(), 480)), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!Prefs.allowScreenshots(d.getContext())) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    static void metro(AlertDialog d) {
        Context c = d.getContext();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(stroke(SURFACE2, dark ? 0x33000000 : 0x1A000000, Math.max(1, dp(c, 1)), dp(c, R_CARD)));
        int[] which = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int w : which) {
            android.widget.Button btn = d.getButton(w);
            if (btn == null || btn.getVisibility() != View.VISIBLE) continue;
            boolean primary = w == DialogInterface.BUTTON_POSITIVE;
            btn.setAllCaps(false);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            btn.setTypeface(primary ? SEMI : Typeface.DEFAULT);
            btn.setTextColor(primary ? ON_ACCENT : TEXT);
            int in = dp(c, 4);
            android.graphics.drawable.Drawable bgd = primary ? round(ACCENT, dp(c, R_CONTROL))
                    : stroke(dark ? 0xFF3A3A3A : 0xFFFBFBFB, DIVIDER, Math.max(1, dp(c, 1)), dp(c, R_CONTROL));
            btn.setBackground(new android.graphics.drawable.InsetDrawable(pressable(c, bgd, dp(c, R_CONTROL)), in));
            btn.setMinWidth(dp(c, 96));
            btn.setMinHeight(dp(c, 48));
            btn.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        }
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
        pickList(c, title, items, -1, false, cb);
    }

    public static void single(Context c, String title, String[] items, int checked, final Cb<Integer> cb) {
        pickList(c, title, items, checked, true, cb);
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
        final ProgressDialog pd = new ProgressDialog(a, dark ? R.style.MetroDialogDark : R.style.MetroDialogLight);
        pd.setMessage(message);
        pd.setCancelable(false);
        pd.create();
        frame(pd);
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
