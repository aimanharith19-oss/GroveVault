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
            BG = 0xFF000000;
            SURFACE = 0xFF1F1F1F;
            SURFACE2 = 0xFF2E2E2E;
            TEXT = 0xFFFFFFFF;
            SUB = 0xFF8A8A8A;
            DIVIDER = 0x00000000;
            DANGER = 0xFFFF5A4E;
            WARN = 0xFFF0A30A;
            a.setTheme(translucent ? R.style.AppTranslucentDark : R.style.AppDark);
        } else {
            BG = 0xFFFFFFFF;
            SURFACE = 0xFFF0F0F0;
            SURFACE2 = 0xFFE2E2E2;
            TEXT = 0xFF000000;
            SUB = 0xFF6B6B6B;
            DIVIDER = 0x00000000;
            DANGER = 0xFFE51400;
            WARN = 0xFFC27D00;
            a.setTheme(translucent ? R.style.AppTranslucent : R.style.AppLight);
        }
        if (translucent) {
            if (!Prefs.allowScreenshots(a)) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            return;
        }
        a.getWindow().setStatusBarColor(blend(BG, ACCENT, dark ? 0.36f : 0.22f));
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

    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    public static final Typeface SEMI = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** Windows Phone "tilt": the item tips toward the finger and shrinks slightly while pressed. */
    public static void tilt(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent e) {
                if (!view.isClickable()) return false; // would never get the UP event and stay tilted
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: {
                        float w = Math.max(1, view.getWidth()), h = Math.max(1, view.getHeight());
                        float nx = (e.getX() / w - 0.5f) * 2f, ny = (e.getY() / h - 0.5f) * 2f;
                        float max = w > dp(view.getContext(), 240) ? 3f : 9f;
                        view.setCameraDistance(14000 * view.getResources().getDisplayMetrics().density);
                        view.animate().rotationY(-nx * max).rotationX(ny * max).scaleX(0.965f).scaleY(0.965f).setDuration(90).start();
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().rotationY(0).rotationX(0).scaleX(1).scaleY(1).setDuration(240)
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

    /** Metro shapes are square; the radius argument is kept for compatibility and ignored. */
    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        return g;
    }

    public static GradientDrawable stroke(int fill, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable g = round(fill, radiusPx);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    public static void ripple(View v, int bg, float radius) {
        if (bg != 0) v.setBackground(round(bg, 0));
        v.setClickable(true);
        tilt(v);
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
        if (bold) t.setTypeface(SEMI);
        else if (sp >= 19) t.setTypeface(LIGHT); // Windows Phone's big thin type
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

    /** Windows Phone button: square outline, lowercase label, fills with the accent while pressed. */
    public static TextView button(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label.toLowerCase(), 18, TEXT, false);
        b.setTypeface(Typeface.DEFAULT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, stroke(ACCENT, ACCENT, dp(c, 2), 0));
        st.addState(new int[]{}, stroke(0x00000000, TEXT, dp(c, 2), 0));
        b.setBackground(st);
        b.setOnClickListener(l);
        tilt(b);
        return b;
    }

    /** Text action in the accent colour (like "shuffle all" in Grove Music). */
    public static TextView flatButton(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label.toLowerCase(), 19, ACCENT, false);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 4), dp(c, 10), dp(c, 12), dp(c, 10));
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
        t.setBackground(round(cardColor(colorIndex), 0));
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

    /** Windows Phone letter tile: a coloured square with a thin lowercase letter in the bottom-left corner. */
    public static TextView letterTile(Context c, String title, int color, int sizeDp) {
        String l = title == null || title.trim().length() == 0 ? "#" : title.trim().substring(0, 1).toLowerCase();
        TextView t = text(c, l, sizeDp * 0.52f, 0xFFFFFFFF, false);
        t.setTypeface(LIGHT);
        t.setGravity(Gravity.BOTTOM | Gravity.START);
        t.setPadding(dp(c, sizeDp * 0.12f), 0, 0, dp(c, sizeDp * 0.02f));
        t.setIncludeFontPadding(false);
        t.setBackground(round(color, 0));
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
        iv.setBackground(round(0xFFFFFFFF, 0));
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

    /** Icon-only button with a text glyph (keeps the APK free of image assets). */
    public static TextView iconButton(Context c, String glyph, View.OnClickListener l) {
        TextView t = text(c, glyph, 19, TEXT, false);
        t.setTypeface(Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(lp(dp(c, 44), dp(c, 44)));
        t.setClickable(true);
        tilt(t);
        t.setOnClickListener(l);
        return t;
    }

    /**
     * Windows Phone page header: small caps app name and a huge, thin, lowercase page title. Actions go to the
     * round-button application bar at the bottom of the screen (back is the phone's back gesture/button).
     * back=false marks the hub page, which gets the bigger "panorama" title.
     */
    public static LinearLayout topBar(final Activity a, String title, boolean back, View... actions) {
        LinearLayout h = vertical(a);
        h.setPadding(dp(a, 20), dp(a, 10), dp(a, 20), dp(a, 4));
        if (back) {
            TextView app = text(a, "GROVE VAULT", 14, TEXT, true);
            app.setLetterSpacing(0.06f);
            h.addView(app);
        }
        if (title != null && title.length() > 0) {
            TextView t = text(a, title.toLowerCase(), back ? 42 : 48, TEXT, false);
            t.setTypeface(LIGHT);
            t.setSingleLine(true);
            if (back) t.setEllipsize(android.text.TextUtils.TruncateAt.END); // hub title runs off the edge, like a panorama
            t.setLetterSpacing(-0.03f);
            t.setIncludeFontPadding(false);
            t.setPadding(0, dp(a, back ? 0 : 6), 0, dp(a, 6));
            h.addView(t);
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
                if (s.length() <= 2) {
                    GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setStroke(dp(a, 2), TEXT);
                    t.setBackground(ring);
                    t.setTextColor(TEXT);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                    LinearLayout.LayoutParams p = lp(dp(a, 46), dp(a, 46));
                    p.setMargins(dp(a, 10), 0, dp(a, 10), 0);
                    bar.addView(t, p);
                } else {
                    t.setText(s.toLowerCase());
                    t.setTextColor(TEXT);
                    t.setBackground(null);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                    LinearLayout.LayoutParams p = lp(WRAP, WRAP);
                    p.setMargins(dp(a, 10), 0, dp(a, 10), 0);
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

    public static TextView section(Context c, String s) {
        TextView t = text(c, s.toLowerCase(), 20, ACCENT, false);
        t.setPadding(dp(c, 20), dp(c, 22), dp(c, 20), dp(c, 4));
        t.setTag("grove_section"); // pivotize() turns these into pivot pages
        return t;
    }

    /** A tappable two-line row. */
    public static LinearLayout row(Context c, String title, String subtitle, View.OnClickListener l) {
        LinearLayout r = horizontal(c);
        r.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        LinearLayout tx = vertical(c);
        TextView tt = text(c, title.toLowerCase(), 21, TEXT, false);
        tx.addView(tt);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = text(c, subtitle, 14, SUB, false);
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
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, dark ? 0xFFBBBBBB : 0xFF777777}));
        s.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, dark ? 0xFF555555 : 0xFFBBBBBB}));
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
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        e.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        // Windows Phone text box: square, light fill, accent border while typing
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, stroke(dark ? 0xFFFFFFFF : 0xFFFFFFFF, ACCENT, dp(c, 2), 0));
        st.addState(new int[]{}, stroke(dark ? 0x33FFFFFF : 0xFFF0F0F0, dark ? 0x99FFFFFF : 0xFF999999, dp(c, 2), 0));
        e.setBackground(st);
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                // focused box is white like Windows Phone, so text turns black
                ((EditText) v).setTextColor(has ? 0xFF000000 : TEXT);
                ((EditText) v).setHintTextColor(has ? 0xFF888888 : SUB);
            }
        });
        // Ask the keyboard not to learn or store what's typed here.
        e.setImeOptions(e.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setFilterTouchesWhenObscured(true);
        return e;
    }

    public static final int PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    public static final int VISIBLE_PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

    /** ScrollView that also reports horizontal swipes (for pivots), even when the swipe starts on a button or row. */
    public static class SwipeScroll extends ScrollView {
        public Cb<Integer> onSwipe;
        private final android.view.GestureDetector g;

        public SwipeScroll(Context c) {
            super(c);
            g = new android.view.GestureDetector(c, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                    if (onSwipe == null || a == null || b == null) return false;
                    float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                    if (Math.abs(dx) < dp(getContext(), 80) || Math.abs(dx) < Math.abs(dy) * 1.5f || Math.abs(vx) < 400) return false;
                    onSwipe.on(dx < 0 ? 1 : -1);
                    return true;
                }
            });
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            if (onSwipe != null && g.onTouchEvent(e) && e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                c.setAction(android.view.MotionEvent.ACTION_CANCEL); // the swipe must not also tap whatever is under the finger
                super.dispatchTouchEvent(c);
                c.recycle();
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
        final LinearLayout strip = horizontal(a);
        strip.setPadding(dp(a, 18), 0, dp(a, 12), dp(a, 8));
        final HorizontalScrollView hs = hscroll(a, strip);
        hs.setTag("grove_pivot");
        View old = root.findViewWithTag("grove_pivot");
        if (old != null) root.removeView(old); // page rebuilt in place
        root.addView(hs, root.indexOfChild(sv));
        final String key = a.getClass().getName();
        Integer saved = PIVOT_PAGE.get(key);
        final int[] page = {saved == null ? 0 : Math.min(saved, pages.size() - 1)};
        final Runnable[] show = new Runnable[1];
        show[0] = new Runnable() {
            public void run() {
                PIVOT_PAGE.put(key, page[0]);
                strip.removeAllViews();
                for (int k = 0; k < names.size(); k++) {
                    final int kk = k;
                    strip.addView(chip(a, names.get(k), k == page[0], new View.OnClickListener() {
                        public void onClick(View v) {
                            page[0] = kk;
                            show[0].run();
                            sv.scrollTo(0, 0);
                        }
                    }));
                }
                for (int k = 0; k < pages.size(); k++)
                    for (View v : pages.get(k)) v.setVisibility(k == page[0] ? vis.get(v) : View.GONE);
                strip.post(new Runnable() {
                    public void run() {
                        View sel = strip.getChildAt(page[0]);
                        if (sel != null) hs.smoothScrollTo(Math.max(0, sel.getLeft() - dp(a, 18)), 0);
                    }
                });
            }
        };
        show[0].run();
        if (sv instanceof SwipeScroll) ((SwipeScroll) sv).onSwipe = new Cb<Integer>() {
            public void on(Integer dir) {
                page[0] = (page[0] + dir + pages.size()) % pages.size();
                show[0].run();
                sv.scrollTo(0, 0);
                c.setTranslationX(dir * dp(a, 60));
                c.setAlpha(0f);
                c.animate().translationX(0).alpha(1f).setDuration(220).start();
            }
        };
    }

    /** Windows Phone application-bar menu: the bar expands upward into a list of lowercase commands. */
    public static void menu(Activity a, String[] items, final Cb<Integer> cb) {
        final android.app.Dialog d = new android.app.Dialog(a, dark ? R.style.MetroDialogDark : R.style.MetroDialogLight);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout l = vertical(a);
        l.setBackgroundColor(dark ? blend(0xFF000000, ACCENT, 0.12f) : blend(0xFFFFFFFF, ACCENT, 0.08f));
        l.setPadding(0, dp(a, 14), 0, dp(a, 18));
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = text(a, items[i].toLowerCase(), 21, TEXT, false);
            t.setPadding(dp(a, 24), dp(a, 11), dp(a, 24), dp(a, 11));
            t.setClickable(true);
            tilt(t);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    cb.on(idx);
                }
            });
            l.addView(t, lp(MATCH, WRAP));
        }
        d.setContentView(l);
        if (d.getWindow() != null) {
            d.getWindow().setGravity(Gravity.BOTTOM);
            d.getWindow().setWindowAnimations(R.style.MetroBottomAnim);
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
            TextView t = text(c, items[i].toLowerCase(), 21, i == selected ? ACCENT : TEXT, false);
            t.setPadding(dp(c, 24), dp(c, 10), dp(c, 24), dp(c, 10));
            t.setClickable(true);
            tilt(t);
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

    /** Pivot header: big thin lowercase word; the current one is bright, the others faded. */
    public static TextView chip(Context c, String label, boolean selected, View.OnClickListener l) {
        TextView t = text(c, label.toLowerCase(), 24, TEXT, false);
        t.setTypeface(LIGHT);
        t.setAlpha(selected ? 1f : 0.4f);
        t.setPadding(dp(c, 2), 0, dp(c, 18), dp(c, 2));
        t.setSingleLine(true);
        t.setOnClickListener(l);
        t.setClickable(true);
        LinearLayout.LayoutParams p = lp(WRAP, WRAP);
        t.setLayoutParams(p);
        return t;
    }

    /** Root: black (or white) with an accent glow at the top, page content, and the bottom application bar. */
    public static LinearLayout screen(Activity a) {
        LinearLayout outer = vertical(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                blend(BG, ACCENT, dark ? 0.36f : 0.22f), blend(BG, ACCENT, dark ? 0.10f : 0.06f), BG, BG});
        outer.setBackground(g);
        outer.setFitsSystemWindows(true);
        outer.setFilterTouchesWhenObscured(true); // ignore taps while another app draws over us (tapjacking)
        LinearLayout content = vertical(a);
        outer.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
        LinearLayout bar = horizontal(a);
        bar.setTag("grove_appbar");
        bar.setMinimumHeight(dp(a, 62));
        bar.setBackgroundColor(dark ? blend(0xFF000000, ACCENT, 0.12f) : blend(0xFFFFFFFF, ACCENT, 0.08f));
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
                d.setOnShowListener(new DialogInterface.OnShowListener() {
                    public void onShow(DialogInterface di) { metro(d); }
                });
                return d;
            }
        };
        if (title != null) {
            TextView t = text(c, title.toLowerCase(), 26, TEXT, false);
            t.setTypeface(LIGHT);
            t.setPadding(dp(c, 24), dp(c, 22), dp(c, 24), dp(c, 6));
            b.setCustomTitle(t);
        }
        return b;
    }

    static void metro(AlertDialog d) {
        if (d.getWindow() != null) {
            d.getWindow().setGravity(Gravity.TOP);
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        for (int w : new int[]{DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL}) {
            android.widget.Button btn = d.getButton(w);
            if (btn == null) continue;
            btn.setAllCaps(false);
            btn.setText(btn.getText().toString().toLowerCase());
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            btn.setTextColor(TEXT);
            android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
            st.addState(new int[]{android.R.attr.state_pressed}, stroke(ACCENT, ACCENT, d.getContext().getResources().getDisplayMetrics().density > 0 ? dp(d.getContext(), 2) : 2, 0));
            st.addState(new int[]{}, stroke(0, TEXT, dp(d.getContext(), 2), 0));
            btn.setBackground(st);
            ViewGroup.LayoutParams p = btn.getLayoutParams();
            if (p instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) p).setMargins(dp(d.getContext(), 6), dp(d.getContext(), 4), dp(d.getContext(), 6), dp(d.getContext(), 10));
            btn.setMinWidth(dp(d.getContext(), 96));
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
