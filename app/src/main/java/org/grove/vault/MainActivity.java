package org.grove.vault;

import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.grove.vault.core.Audit;
import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Label;
import org.grove.vault.core.Templates;
import org.grove.vault.core.Totp;
import org.grove.vault.core.Vault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Card list with search, filters (labels, favourites, audit, trash, templates) and the main menu. */
public class MainActivity extends BaseActivity {
    private String filter = "all";
    private String query = "";
    private boolean searching;
    private final List<Card> shown = new ArrayList<Card>();
    private Map<String, List<Card>> reused;
    private Adapter adapter;
    private Ui.PivotHeader chips;
    private TextView empty;
    private EditText search;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        if (b != null) filter = b.getString("filter", "all");
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("filter", filter);
    }

    @Override
    protected void render() {
        if (adapter == null) build();
        refresh();
        maybeWarnExpiring();
    }

    // ================================================================ home screen
    // Search bar · collection tabs (All, Favourites, Logins, Cards, Notes, Passkeys, Codes) · security card ·
    // Recent row · one A–Z list with a letter scrubber. Swipe a row right to copy the password, left to copy the
    // username (or the 2FA code); long-press for more.

    private static final String[] KINDS = {"all", "fav", "logins", "cards", "notes", "passkeys", "codes"};
    private static final String[] KIND_NAMES = {"All", "Favourites", "Logins", "Cards", "Notes", "Passkeys", "Codes"};

    private ListView list;
    private LinearLayout recentBox, recentRow, securityBox;
    private LinearLayout scrubber;
    private final List<Object> rows = new ArrayList<Object>(); // String = letter header, Card = card
    private final android.os.Handler ticker = new android.os.Handler();
    private final Runnable tick = new Runnable() {
        public void run() {
            if (filter.equals("codes") && adapter != null) {
                adapter.notifyDataSetChanged();
                ticker.postDelayed(this, 1000);
            }
        }
    };

    static String kind(Card c) {
        if (c.first(Field.OTP) != null && c.password().length() == 0 && c.login().length() == 0) return "codes";
        if ("card".equals(c.icon) || "bank".equals(c.icon) || "id".equals(c.icon) || "car".equals(c.icon) || "shield".equals(c.icon)) return "cards";
        if (c.login().length() > 0 || c.password().length() > 0 || !c.passkeys().isEmpty()) return "logins";
        return "notes";
    }

    static boolean inKind(Card c, String k) {
        if (k.equals("passkeys")) return !c.passkeys().isEmpty();
        if (k.equals("codes")) return c.first(Field.OTP) != null;
        if (k.equals("logins")) return kind(c).equals("logins");
        return kind(c).equals(k);
    }

    private void build() {
        LinearLayout root = Ui.screen(this);
        TextView menuBtn = Ui.iconButton(this, "…", new View.OnClickListener() {
            public void onClick(View v) { showMenu(v); }
        });
        TextView addBtn = Ui.iconButton(this, "+", new View.OnClickListener() {
            public void onClick(View v) { addCard(); }
        });
        header = Ui.topBar(this, "GroveVault", false, addBtn, menuBtn);
        root.addView(header);

        // always-visible search box with a search icon
        search = Ui.edit(this, "Search your vault", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        android.graphics.drawable.Drawable ic = getDrawable(R.drawable.ic_search).mutate();
        ic.setTint(Ui.SUB);
        ic.setBounds(0, 0, Ui.dp(this, 18), Ui.dp(this, 18));
        search.setCompoundDrawablesRelative(ic, null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        root.addView(search, Ui.margins(this, Ui.MATCH, Ui.WRAP, 16, 0, 16, 4));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                searching = query.length() > 0;
                refresh();
            }
        });

        chips = new Ui.PivotHeader(this);
        root.addView(chips, Ui.lp(Ui.MATCH, Ui.WRAP));

        frame = new FrameLayout(this);
        root.addView(frame, Ui.lp(Ui.MATCH, 0));
        ((LinearLayout.LayoutParams) frame.getLayoutParams()).weight = 1;

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 16));
        list.setVerticalScrollBarEnabled(false);

        // header: security card + recent row
        securityBox = Ui.vertical(this);
        list.addHeaderView(securityBox, null, false);
        recentBox = Ui.vertical(this);
        recentBox.addView(sectionLabel("Recent"));
        recentRow = Ui.horizontal(this);
        recentRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));
        android.widget.HorizontalScrollView hs = Ui.hscroll(this, recentRow);
        recentBox.addView(hs);
        list.addHeaderView(recentBox, null, false);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Object o = p.getItemAtPosition(pos);
                if (o instanceof Card) {
                    Card c = (Card) o;
                    if (filter.equals("codes")) copyCode(c);
                    else open(c);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                Object o = p.getItemAtPosition(pos);
                if (o instanceof Card) quickActions((Card) o);
                return o instanceof Card;
            }
        });
        list.setOnTouchListener(new RowSwiper());
        cardsView = list;
        frame.addView(list, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        scrubber = Ui.vertical(this);
        scrubber.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.MATCH, Gravity.END);
        sl.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
        frame.addView(scrubber, sl);
        scrubber.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int n = scrubber.getChildCount();
                if (n == 0) return false;
                int i = (int) (e.getY() / Math.max(1, scrubber.getHeight()) * n);
                i = Math.max(0, Math.min(n - 1, i));
                String letter = ((TextView) scrubber.getChildAt(i)).getText().toString();
                int at = rows.indexOf(letter);
                if (at >= 0) list.setSelectionFromTop(at + list.getHeaderViewsCount(), 0);
                for (int k = 0; k < n; k++) ((TextView) scrubber.getChildAt(k)).setTextColor(k == i && e.getActionMasked() != android.view.MotionEvent.ACTION_UP ? Ui.ACCENT : Ui.SUB);
                return true;
            }
        });

        empty = Ui.text(this, "", 15, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
        frame.addView(empty, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(this, s, 14, Ui.TEXT, true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 6));
        return t;
    }

    private View header;
    private FrameLayout frame;
    private android.widget.AbsListView cardsView;

    private void refresh() {
        Vault v = Session.vault();
        if (v == null) return;
        if (filter.startsWith("label:") && v.findLabel(filter.substring(6)) == null) filter = "all";
        List<Card> active = v.activeCards();
        reused = Audit.reused(active);
        long now = System.currentTimeMillis();
        int days = Prefs.expiryDays(this);
        int weak = 0, re = 0, exp = 0;
        boolean[] has = new boolean[KINDS.length];
        for (Card c : active) {
            if (c.template) continue;
            if (Audit.hasWeakPassword(c)) weak++;
            if (Audit.isReused(c, reused)) re++;
            if (Audit.isExpiring(c, days, now)) exp++;
            if (c.favorite) has[1] = true;
            for (int k = 2; k < KINDS.length; k++) if (inKind(c, KINDS[k])) has[k] = true;
        }
        // tabs: collections that have something, plus whatever extra filter is open (label, trash, audit…)
        pivotKeys.clear();
        pivotNames.clear();
        for (int k = 0; k < KINDS.length; k++) if (k == 0 || has[k] || filter.equals(KINDS[k])) addChip(KIND_NAMES[k], KINDS[k]);
        if (filter.startsWith("label:")) addChip(v.findLabel(filter.substring(6)).name, filter);
        if (filter.equals("weak")) addChip("Weak", "weak");
        if (filter.equals("reused")) addChip("Reused", "reused");
        if (filter.equals("expiring")) addChip("Expiring", "expiring");
        if (filter.equals("trash")) addChip("Trash", "trash");
        if (filter.equals("templates")) addChip("Templates", "templates");
        int sel = Math.max(0, pivotKeys.indexOf(filter));
        if (!pivotKeys.equals(lastKeys) || sel != chipsSel) {
            lastKeys = new ArrayList<String>(pivotKeys);
            chipsSel = sel;
            chips.set(pivotNames.toArray(new String[0]), sel, new Ui.Cb<Integer>() {
                public void on(Integer k) {
                    filter = pivotKeys.get(k);
                    chipsSel = k;
                    chips.animateTo(k);
                    refresh();
                    list.setSelection(0);
                }
            });
        }

        // security card (only on All, only when something needs attention)
        securityBox.removeAllViews();
        if (filter.equals("all") && !searching && Prefs.showWarnings(this) && weak + re + exp > 0) {
            final int fw = weak, fr = re, fe = exp;
            String txt = "";
            if (weak > 0) txt += weak + " weak";
            if (re > 0) txt += (txt.length() > 0 ? " · " : "") + re + " reused";
            if (exp > 0) txt += (txt.length() > 0 ? " · " : "") + exp + " expiring";
            LinearLayout card = Ui.row(this, "⚠  Security check", txt + ". Tap to fix", new View.OnClickListener() {
                public void onClick(View x) {
                    final List<String> keys = new ArrayList<String>();
                    List<String> names = new ArrayList<String>();
                    if (fw > 0) { keys.add("weak"); names.add("Weak passwords (" + fw + ")"); }
                    if (fr > 0) { keys.add("reused"); names.add("Reused passwords (" + fr + ")"); }
                    if (fe > 0) { keys.add("expiring"); names.add("Expiring (" + fe + ")"); }
                    Ui.choose(MainActivity.this, "Security check", names.toArray(new String[0]), new Ui.Cb<Integer>() {
                        public void on(Integer i) { filter = keys.get(i); refresh(); list.setSelection(0); }
                    });
                }
            });
            ((LinearLayout.LayoutParams) card.getLayoutParams()).setMargins(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
            securityBox.addView(card);
        }

        // recent row
        recentRow.removeAllViews();
        if (filter.equals("all") && !searching) {
            for (String id : Usage.top(this, 10)) {
                final Card c = Session.card(id);
                if (c == null || c.deleted || c.template) continue;
                LinearLayout chip = Ui.vertical(this);
                chip.setGravity(Gravity.CENTER_HORIZONTAL);
                chip.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
                chip.addView(Ui.cardIcon(this, c, 44));
                TextView t = Ui.text(this, c.title, 12, Ui.TEXT, false);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, Ui.dp(this, 4), 0, 0);
                chip.addView(t, Ui.lp(Ui.dp(this, 64), Ui.WRAP));
                chip.setBackground(Ui.pressable(this, null, Ui.dp(this, Ui.R_CARD)));
                chip.setClickable(true);
                chip.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) { open(c); }
                });
                chip.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View x) { quickActions(c); return true; }
                });
                recentRow.addView(chip);
            }
        }
        recentBox.setVisibility(recentRow.getChildCount() > 1 ? View.VISIBLE : View.GONE);
        if (recentBox.getVisibility() == View.GONE) recentBox.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, 1));
        else recentBox.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP));

        // the list
        shown.clear();
        List<Card> source = filter.equals("trash") ? v.trash() : filter.equals("templates") ? v.templates() : active;
        for (Card c : source) {
            if (!c.matches(query)) continue;
            if (!filter.equals("templates") && c.template) continue;
            if (filter.equals("fav") && !c.favorite) continue;
            if (filter.startsWith("label:") && !c.labelIds.contains(filter.substring(6))) continue;
            if (filter.equals("weak") && !Audit.hasWeakPassword(c)) continue;
            if (filter.equals("reused") && !Audit.isReused(c, reused)) continue;
            if (filter.equals("expiring") && !Audit.isExpiring(c, days, now)) continue;
            if ((filter.equals("logins") || filter.equals("cards") || filter.equals("notes") || filter.equals("passkeys") || filter.equals("codes")) && !inKind(c, filter)) continue;
            shown.add(c);
        }
        final int sort = Prefs.sort(this);
        Collections.sort(shown, new Comparator<Card>() {
            public int compare(Card a, Card b) {
                if (sort == 1) return Long.compare(b.modified, a.modified);
                if (sort == 2) return Long.compare(b.created, a.created);
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        // A–Z sections (alphabetical order only)
        rows.clear();
        scrubber.removeAllViews();
        boolean az = sort == 0 && shown.size() > 8;
        String last = null;
        for (Card c : shown) {
            if (az) {
                String l = letterOf(c.title);
                if (!l.equals(last)) {
                    rows.add(l);
                    last = l;
                    TextView s = Ui.text(this, l, 11, Ui.SUB, true);
                    s.setGravity(Gravity.CENTER);
                    scrubber.addView(s, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
                }
            }
            rows.add(c);
        }
        scrubber.setVisibility(az && shown.size() > 20 && scrubber.getChildCount() > 3 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        ticker.removeCallbacks(tick);
        if (filter.equals("codes")) ticker.postDelayed(tick, 1000);

        if (shown.isEmpty() && securityBox.getChildCount() == 0 && recentRow.getChildCount() == 0) {
            if (query.length() > 0) empty.setText("Nothing matches “" + query + "”.");
            else if (filter.equals("all")) empty.setText("Your vault is empty.\n\nTap + to add a password, card or note, or import from another app in … → Backup & import.");
            else if (filter.equals("trash")) empty.setText("Trash is empty.");
            else empty.setText("Nothing here.");
            empty.setVisibility(View.VISIBLE);
        } else empty.setVisibility(View.GONE);
    }

    static String letterOf(String title) {
        String t = title == null ? "" : title.trim();
        if (t.length() == 0) return "#";
        char ch = Character.toUpperCase(t.charAt(0));
        return Character.isLetter(ch) && ch < 128 ? String.valueOf(ch) : "#";
    }

    private final List<String> pivotKeys = new ArrayList<String>();
    private final List<String> pivotNames = new ArrayList<String>();
    private List<String> lastKeys;
    private int chipsSel = -1;

    private void addChip(String label, final String key) {
        pivotKeys.add(key);
        pivotNames.add(label);
    }

    // ---------------------------------------------------------------- quick copy

    private void copyPassword(Card c) {
        if (c.password().length() == 0) { Ui.toast(this, "No password on this card"); return; }
        App.copy(this, "Password", c.password());
        Usage.record(this, c.id);
        Ui.toast(this, "Password copied");
    }

    private void copyUser(Card c) {
        if (c.login().length() == 0) { Ui.toast(this, "No username on this card"); return; }
        App.copy(this, "Login", c.login());
        Usage.record(this, c.id);
        Ui.toast(this, "Username copied");
    }

    private void copyCode(Card c) {
        Field f = c.first(Field.OTP);
        Totp t = f == null ? null : Totp.parse(f.value);
        if (t == null) { Ui.toast(this, "No 2FA code on this card"); return; }
        App.copy(this, "One-time code", t.code(System.currentTimeMillis()));
        Usage.record(this, c.id);
        Ui.toast(this, "Code copied");
    }

    /** Swipe a row: right → copy password, left → copy username (2FA code on the Codes tab). */
    private class RowSwiper implements View.OnTouchListener {
        float x0, y0;
        boolean decided, dragging;
        View front, back;
        TextView backLabel;
        Card card;

        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    x0 = e.getX();
                    y0 = e.getY();
                    decided = dragging = false;
                    front = back = null;
                    card = null;
                    int pos = list.pointToPosition((int) x0, (int) y0);
                    if (pos == AdapterView.INVALID_POSITION) return false;
                    Object o = list.getItemAtPosition(pos);
                    View row = list.getChildAt(pos - list.getFirstVisiblePosition());
                    if (!(o instanceof Card) || row == null || filter.equals("trash") || ((Card) o).template) return false;
                    card = (Card) o;
                    front = row.findViewWithTag("front");
                    back = row.findViewWithTag("back");
                    backLabel = back == null ? null : (TextView) back.findViewWithTag("backLabel");
                    return false;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (front == null) return false;
                    float dx = e.getX() - x0, dy = e.getY() - y0;
                    int slop = android.view.ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
                    if (!decided && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        decided = true;
                        dragging = Math.abs(dx) > Math.abs(dy) * 1.3f;
                        if (dragging) {
                            android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                            c.setAction(android.view.MotionEvent.ACTION_CANCEL); // stop the list scrolling / pressing
                            list.onTouchEvent(c);
                            c.recycle();
                        }
                    }
                    if (!dragging) return false;
                    float w = front.getWidth(), lim = w * 0.45f;
                    float t = Math.max(-lim, Math.min(lim, dx));
                    front.setTranslationX(t);
                    boolean right = t > 0, armed = Math.abs(t) > w * 0.22f;
                    back.setBackgroundColor(armed ? (right ? Ui.ACCENT : 0xFF2B79D0) : (Ui.dark ? 0xFF3A3A3A : 0xFFE0E0E0));
                    backLabel.setText(right ? "Copy password" : filter.equals("codes") ? "Copy code" : "Copy username");
                    backLabel.setTextColor(armed ? 0xFFFFFFFF : Ui.SUB);
                    ((FrameLayout.LayoutParams) backLabel.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL | (right ? Gravity.START : Gravity.END);
                    backLabel.requestLayout();
                    back.setVisibility(View.VISIBLE);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!dragging || front == null) return false;
                    dragging = false;
                    float t = front.getTranslationX();
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && Math.abs(t) > front.getWidth() * 0.22f) {
                        if (t > 0) copyPassword(card);
                        else if (filter.equals("codes")) copyCode(card);
                        else copyUser(card);
                    }
                    final View b = back;
                    front.animate().translationX(0).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                            .withEndAction(new Runnable() {
                                public void run() { if (b != null) b.setVisibility(View.INVISIBLE); }
                            }).start();
                    return true;
                }
            }
            return false;
        }
    }

    /** Small countdown ring for 2FA codes. */
    private static class Ring extends View {
        float frac;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Ring(android.content.Context c) { super(c); }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float s = Math.min(getWidth(), getHeight()), sw = s * 0.14f;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            android.graphics.RectF r = new android.graphics.RectF(sw, sw, s - sw, s - sw);
            p.setColor(Ui.DIVIDER | 0x20000000);
            cv.drawArc(r, 0, 360, false, p);
            p.setColor(frac < 0.2f ? Ui.DANGER : Ui.ACCENT);
            cv.drawArc(r, -90, 360 * frac, false, p);
        }
    }

    private void open(Card c) {
        if (!c.template) Usage.record(this, c.id);
        if (c.template) {
            startActivity(new Intent(this, EditActivity.class).putExtra("id", c.id));
        } else {
            startActivity(new Intent(this, CardActivity.class).putExtra("id", c.id));
        }
    }

    private void addCard() {
        final Vault v = Session.vault();
        if (filter.equals("templates")) {
            startActivity(new Intent(this, EditActivity.class).putExtra("new_template", true));
            return;
        }
        final List<Card> t = v.templates();
        Collections.sort(t, new Comparator<Card>() {
            public int compare(Card a, Card b) { return a.title.compareToIgnoreCase(b.title); }
        });
        String[] names = new String[t.size()];
        for (int i = 0; i < t.size(); i++) names[i] = Ui.glyph(t.get(i).icon) + "   " + t.get(i).title;
        Ui.choose(this, "Choose a template", names, new Ui.Cb<Integer>() {
            public void on(Integer i) {
                Intent in = new Intent(MainActivity.this, EditActivity.class).putExtra("template", t.get(i).id);
                if (filter.startsWith("label:")) in.putExtra("label", filter.substring(6));
                startActivity(in);
            }
        });
    }

    private void quickActions(final Card c) {
        final List<String> items = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        if (filter.equals("trash")) {
            items.add("Restore");
            acts.add(new Runnable() { public void run() { c.deleted = false; Session.saveOrToast(MainActivity.this); refresh(); } });
            items.add("Delete forever");
            acts.add(new Runnable() { public void run() {
                Ui.confirm(MainActivity.this, "Delete forever?", "“" + c.title + "” will be permanently deleted.", "Delete", new Runnable() {
                    public void run() { Session.vault().cards.remove(c); Session.saveOrToast(MainActivity.this); refresh(); }
                });
            } });
        } else if (c.template) {
            items.add("Edit template");
            acts.add(new Runnable() { public void run() { open(c); } });
            items.add("Delete template");
            acts.add(new Runnable() { public void run() { Session.vault().cards.remove(c); Session.saveOrToast(MainActivity.this); refresh(); } });
        } else {
            final String login = c.login(), pass = c.password(), web = c.website();
            final Field otp = c.first(Field.OTP);
            if (login.length() > 0) { items.add("Copy login"); acts.add(new Runnable() { public void run() { App.copy(MainActivity.this, "Login", login); } }); }
            if (pass.length() > 0) { items.add("Copy password"); acts.add(new Runnable() { public void run() { App.copy(MainActivity.this, "Password", pass); } }); }
            if (otp != null) {
                items.add("Copy one-time code");
                acts.add(new Runnable() { public void run() {
                    Totp t = Totp.parse(otp.value);
                    if (t != null) App.copy(MainActivity.this, "One-time code", t.code(System.currentTimeMillis()));
                } });
            }
            if (web.length() > 0) { items.add("Open website"); acts.add(new Runnable() { public void run() { CardActivity.openUrl(MainActivity.this, web); } }); }
            items.add(c.favorite ? "Remove from favourites" : "Add to favourites");
            acts.add(new Runnable() { public void run() { c.favorite = !c.favorite; Session.saveOrToast(MainActivity.this); refresh(); } });
            items.add("Duplicate");
            acts.add(new Runnable() { public void run() {
                Card d = c.copy();
                d.id = Vault.newId();
                d.title = c.title + " (copy)";
                d.created = d.modified = System.currentTimeMillis();
                Session.vault().cards.add(d);
                Session.saveOrToast(MainActivity.this);
                refresh();
            } });
            items.add("Move to trash");
            acts.add(new Runnable() { public void run() { c.deleted = true; Session.saveOrToast(MainActivity.this); refresh(); Ui.toast(MainActivity.this, "Moved to trash"); } });
        }
        Ui.choose(this, c.title, items.toArray(new String[0]), new Ui.Cb<Integer>() {
            public void on(Integer i) { acts.get(i).run(); }
        });
    }

    /** Finds logins with the same username and password and combines each set into one card (others → Trash). */
    private void combineDuplicates() {
        final List<List<Card>> groups = org.grove.vault.core.Dedupe.groups(Session.vault());
        if (groups.isEmpty()) {
            Ui.message(this, "No duplicates", "No logins share the same username and password.");
            return;
        }
        String[] names = new String[groups.size()];
        final boolean[] on = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            StringBuilder b = new StringBuilder();
            for (Card c : groups.get(i)) b.append(b.length() == 0 ? "" : " + ").append(c.title.length() == 0 ? "(untitled)" : c.title);
            String user = groups.get(i).get(0).login();
            names[i] = b + (user.length() > 0 ? "  ·  " + user : "");
            on[i] = true;
        }
        Ui.multi(this, "Combine into one card each", names, on, new Runnable() {
            public void run() {
                int n = 0;
                for (int i = 0; i < groups.size(); i++) {
                    if (!on[i]) continue;
                    org.grove.vault.core.Dedupe.merge(groups.get(i));
                    n += groups.get(i).size() - 1;
                }
                if (n == 0) return;
                if (Session.saveOrToast(MainActivity.this)) {
                    refresh();
                    Ui.toast(MainActivity.this, "Combined " + n + " login" + (n == 1 ? "" : "s") + ". The old cards are in Trash");
                }
            }
        });
    }

    private void showMenu(View anchor) {
        final java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
        final java.util.List<String> names = new java.util.ArrayList<String>();
        ids.add(1); names.add("Password generator");
        if (!Session.vault().labels.isEmpty()) { ids.add(11); names.add("Show a label…"); }
        ids.add(2); names.add("Labels");
        ids.add(3); names.add(filter.equals("templates") ? "Back to cards" : "Templates");
        if (filter.equals("templates")) { ids.add(9); names.add("Restore default templates"); }
        ids.add(4); names.add("Trash");
        ids.add(5); names.add("Sort…");
        ids.add(10); names.add("Combine duplicate logins");
        ids.add(6); names.add("Backup & import");
        ids.add(7); names.add("Settings");
        ids.add(8); names.add("Lock now");
        Ui.menu(this, names.toArray(new String[0]), new Ui.Cb<Integer>() {
            public void on(Integer pick) {
                switch (ids.get(pick)) {
                    case 1: GeneratorDialog.show(MainActivity.this, null); break;
                    case 2: startActivity(new Intent(MainActivity.this, LabelsActivity.class)); break;
                    case 3: filter = filter.equals("templates") ? "all" : "templates"; refresh(); break;
                    case 4: filter = "trash"; refresh(); break;
                    case 5:
                        Ui.single(MainActivity.this, "Sort by", new String[]{"Title (A–Z)", "Recently modified", "Recently created"}, Prefs.sort(MainActivity.this), new Ui.Cb<Integer>() {
                            public void on(Integer i) { Prefs.put(MainActivity.this, "sort", i); refresh(); }
                        });
                        break;
                    case 6: startActivity(new Intent(MainActivity.this, BackupActivity.class)); break;
                    case 7: startActivity(new Intent(MainActivity.this, SettingsActivity.class)); break;
                    case 8: Session.lock(); startActivity(new Intent(MainActivity.this, LockActivity.class)); finish(); break;
                    case 10: combineDuplicates(); break;
                    case 11: {
                        final Vault vv = Session.vault();
                        vv.sortLabels();
                        String[] ln = new String[vv.labels.size()];
                        for (int i = 0; i < ln.length; i++) ln[i] = vv.labels.get(i).name;
                        Ui.choose(MainActivity.this, "Show label", ln, new Ui.Cb<Integer>() {
                            public void on(Integer i) { filter = "label:" + vv.labels.get(i).id; refresh(); list.setSelection(0); }
                        });
                        break;
                    }
                    case 9:
                        Ui.confirm(MainActivity.this, "Restore default templates?", "Missing built-in templates will be added back. Your own templates are kept.", "Restore", new Runnable() {
                            public void run() {
                                Vault v = Session.vault();
                                for (Card t : Templates.defaults()) {
                                    boolean have = false;
                                    for (Card e : v.templates()) if (e.title.equalsIgnoreCase(t.title)) have = true;
                                    if (!have) v.cards.add(t);
                                }
                                Session.saveOrToast(MainActivity.this);
                                refresh();
                            }
                        });
                        break;
                }
            }
        });
    }

    private void maybeWarnExpiring() {
        long last = Prefs.p(this).getLong("exp_warned", 0);
        long now = System.currentTimeMillis();
        if (now - last < 86400000L || Session.vault() == null) return;
        int n = 0;
        for (Card c : Session.vault().activeCards()) if (Audit.isExpiring(c, Prefs.expiryDays(this), now)) n++;
        Prefs.put(this, "exp_warned", now);
        if (n == 0) return;
        Ui.confirm(this, "Cards expiring", n + (n == 1 ? " card is" : " cards are") + " expired or expiring within " + Prefs.expiryDays(this) + " days. Show them?", "Show", new Runnable() {
            public void run() { filter = "expiring"; refresh(); }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            search.setText("");
            Ui.hideKeyboard(search);
        } else if (!filter.equals("all")) {
            filter = "all";
            refresh();
        } else super.onBackPressed();
    }

    private class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int i) { return rows.get(i) instanceof String ? 0 : 1; }
        public boolean isEnabled(int i) { return rows.get(i) instanceof Card; }
        public boolean areAllItemsEnabled() { return false; }

        public View getView(int pos, View convert, ViewGroup parent) {
            Object o = rows.get(pos);
            if (o instanceof String) {
                TextView t = Ui.text(MainActivity.this, (String) o, 13, Ui.ACCENT, true);
                t.setPadding(Ui.dp(MainActivity.this, 20), Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 20), Ui.dp(MainActivity.this, 2));
                return t;
            }
            Card c = (Card) o;
            boolean codes = filter.equals("codes");

            FrameLayout cell = new FrameLayout(MainActivity.this);
            // revealed behind the row while swiping
            FrameLayout back = new FrameLayout(MainActivity.this);
            back.setTag("back");
            back.setVisibility(View.INVISIBLE);
            TextView bl = Ui.text(MainActivity.this, "", 14, 0xFFFFFFFF, true);
            bl.setTag("backLabel");
            bl.setPadding(Ui.dp(MainActivity.this, 20), 0, Ui.dp(MainActivity.this, 20), 0);
            back.addView(bl, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER_VERTICAL | Gravity.START));
            cell.addView(back, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

            LinearLayout r = Ui.horizontal(MainActivity.this);
            r.setTag("front");
            r.setBackgroundColor(Ui.BG);
            r.setPadding(Ui.dp(MainActivity.this, 20), Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 8));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(Ui.dp(MainActivity.this, 60));
            r.addView(Ui.cardIcon(MainActivity.this, c, 36));
            LinearLayout tx = Ui.vertical(MainActivity.this);
            tx.setPadding(Ui.dp(MainActivity.this, 14), 0, Ui.dp(MainActivity.this, 8), 0);
            TextView t = Ui.text(MainActivity.this, c.title.length() == 0 ? "(untitled)" : c.title, 15, Ui.TEXT, false);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(t);
            String sub = c.template ? c.fields.size() + " fields" : c.subtitle();
            if (sub.length() > 0) {
                TextView s = Ui.text(MainActivity.this, sub, 12.5f, Ui.SUB, false);
                s.setSingleLine(true);
                s.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tx.addView(s);
            }
            r.addView(tx, Ui.lpw(1));
            if (codes) {
                Field f = c.first(Field.OTP);
                Totp tp = f == null ? null : Totp.parse(f.value);
                if (tp != null) {
                    long now = System.currentTimeMillis();
                    String code = tp.code(now);
                    if (code.length() == 6) code = code.substring(0, 3) + " " + code.substring(3);
                    TextView ct = Ui.text(MainActivity.this, code, 22, Ui.ACCENT, true);
                    ct.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                    r.addView(ct);
                    Ring ring = new Ring(MainActivity.this);
                    ring.frac = tp.secondsLeft(now) / (float) tp.period;
                    LinearLayout.LayoutParams rl = Ui.lp(Ui.dp(MainActivity.this, 22), Ui.dp(MainActivity.this, 22));
                    rl.setMargins(Ui.dp(MainActivity.this, 10), 0, 0, 0);
                    r.addView(ring, rl);
                }
            } else {
                if (!c.template && Prefs.showWarnings(MainActivity.this)) {
                    String w = "";
                    if (Audit.isExpired(c, System.currentTimeMillis())) w += "⏰";
                    if (Audit.hasWeakPassword(c) || Audit.isReused(c, reused)) w += "⚠";
                    if (w.length() > 0) r.addView(Ui.text(MainActivity.this, w, 14, Ui.WARN, false));
                }
                if (!c.passkeys().isEmpty()) r.addView(Ui.text(MainActivity.this, " 🔐", 14, Ui.SUB, false));
                if (c.favorite) r.addView(Ui.text(MainActivity.this, " ★", 16, Ui.WARN, false));
            }
            cell.addView(r, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            return cell;
        }
    }

}
