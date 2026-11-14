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
    private final Ui.Swiper swiper = new Ui.Swiper();
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

    private void build() {
        LinearLayout root = Ui.screen(this);
        final TextView searchBtn = Ui.iconButton(this, "🔍", null);
        TextView menuBtn = Ui.iconButton(this, "…", null);
        TextView addBtn = Ui.iconButton(this, "+", new View.OnClickListener() {
            public void onClick(View v) { addCard(); }
        });
        final TextView viewBtn = Ui.iconButton(this, Prefs.p(this).getBoolean("grid", false) ? "☰" : "▦", null);
        viewBtn.setContentDescription("Switch between list and grid");
        viewBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean g = !Prefs.p(MainActivity.this).getBoolean("grid", false);
                Prefs.put(MainActivity.this, "grid", g);
                viewBtn.setText(g ? "☰" : "▦");
                showCards();
            }
        });
        LinearLayout bar = Ui.topBar(this, "grove vault", false, addBtn, searchBtn, viewBtn, menuBtn);
        header = bar;
        root.addView(bar);
        search = Ui.edit(this, "Search cards", InputType.TYPE_CLASS_TEXT);
        search.setVisibility(View.GONE);
        root.addView(search, Ui.margins(this, Ui.MATCH, Ui.WRAP, 16, 0, 16, 8));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                refresh();
            }
        });
        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                searching = !searching;
                search.setVisibility(searching ? View.VISIBLE : View.GONE);
                if (searching) Ui.showKeyboard(search);
                else {
                    search.setText("");
                    Ui.hideKeyboard(search);
                }
            }
        });
        menuBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showMenu(v);
            }
        });
        chips = new Ui.PivotHeader(this);
        root.addView(chips, Ui.lp(Ui.MATCH, Ui.WRAP));

        frame = new FrameLayout(this);
        root.addView(frame, Ui.lp(Ui.MATCH, 0));
        ((LinearLayout.LayoutParams) frame.getLayoutParams()).weight = 1;
        adapter = new Adapter();
        showCards();
        empty = Ui.text(this, "", 15, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
        frame.addView(empty, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        empty.setOnTouchListener(swipeListener());
        empty.setClickable(true);

    }

    private View header;

    private FrameLayout frame;
    private android.widget.AbsListView cardsView;

    /** Shows the cards as a list or as a grid of tiles (remembered). */
    private void showCards() {
        boolean grid = Prefs.p(this).getBoolean("grid", false);
        if (cardsView != null) frame.removeView(cardsView);
        android.widget.AbsListView v;
        if (grid) {
            android.widget.GridView g = new android.widget.GridView(this);
            g.setNumColumns(android.widget.GridView.AUTO_FIT);
            g.setColumnWidth(Ui.dp(this, 104));
            g.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
            g.setVerticalSpacing(Ui.dp(this, 8));
            g.setHorizontalSpacing(Ui.dp(this, 8));
            g.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 16));
            v = g;
        } else {
            ListView l = new ListView(this);
            l.setDivider(null);
            l.setPadding(0, 0, 0, Ui.dp(this, 12));
            v = l;
        }
        v.setClipToPadding(false);
        v.setOnTouchListener(swipeListener());
        v.setAdapter(adapter);
        v.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) { open(shown.get(pos)); }
        });
        v.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                quickActions(shown.get(pos));
                return true;
            }
        });
        cardsView = v;
        frame.addView(v, 0);
        adapter.notifyDataSetChanged();
    }

    private void refresh() {
        Vault v = Session.vault();
        if (v == null) return;
        if (filter.startsWith("label:") && v.findLabel(filter.substring(6)) == null) filter = "all";
        List<Card> active = v.activeCards();
        reused = Audit.reused(active);
        long now = System.currentTimeMillis();
        int days = Prefs.expiryDays(this);
        int weak = 0, re = 0, exp = 0, fav = 0;
        for (Card c : active) {
            if (Audit.hasWeakPassword(c)) weak++;
            if (Audit.isReused(c, reused)) re++;
            if (Audit.isExpiring(c, days, now)) exp++;
            if (c.favorite) fav++;
        }
        pivotKeys.clear();
        pivotNames.clear();
        addChip("all", "all");
        if (fav > 0) addChip("favourites", "fav");
        v.sortLabels();
        for (Label l : v.labels) addChip(l.name, "label:" + l.id);
        if (weak > 0) addChip("weak", "weak");
        if (re > 0) addChip("reused", "reused");
        if (exp > 0) addChip("expiring", "expiring");
        int trash = v.trash().size();
        if (trash > 0 || filter.equals("trash")) addChip("trash", "trash");
        if (filter.equals("templates")) addChip("templates", "templates");

        int sel = Math.max(0, pivotKeys.indexOf(filter));
        if (!pivotKeys.equals(lastKeys) || sel != chipsSel) {
            lastKeys = new java.util.ArrayList<String>(pivotKeys);
            chipsSel = sel;
            chips.set(pivotNames.toArray(new String[0]), sel, new Ui.Cb<Integer>() {
                public void on(Integer k) { goTo(k); }
            });
        }
        swiper.pages = pivotKeys.size();
        swiper.page = sel;

        shown.clear();
        List<Card> source = filter.equals("trash") ? v.trash() : filter.equals("templates") ? v.templates() : active;
        for (Card c : source) {
            if (!c.matches(query)) continue;
            if (filter.equals("fav") && !c.favorite) continue;
            if (filter.startsWith("label:") && !c.labelIds.contains(filter.substring(6))) continue;
            if (filter.equals("weak") && !Audit.hasWeakPassword(c)) continue;
            if (filter.equals("reused") && !Audit.isReused(c, reused)) continue;
            if (filter.equals("expiring") && !Audit.isExpiring(c, days, now)) continue;
            shown.add(c);
        }
        final int sort = Prefs.sort(this);
        final boolean favTop = Prefs.favoritesOnTop(this) && !filter.equals("templates");
        Collections.sort(shown, new Comparator<Card>() {
            public int compare(Card a, Card b) {
                if (favTop && a.favorite != b.favorite) return a.favorite ? -1 : 1;
                if (sort == 1) return Long.compare(b.modified, a.modified);
                if (sort == 2) return Long.compare(b.created, a.created);
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) {
            if (query.length() > 0) empty.setText("No cards match “" + query + "”.");
            else if (filter.equals("all")) empty.setText("Your vault is empty.\n\nTap + to add a password, card or note, or import from KeePass or another app in ⋮ → Backup & import.");
            else if (filter.equals("trash")) empty.setText("Trash is empty.");
            else empty.setText("Nothing here.");
            empty.setVisibility(View.VISIBLE);
        } else empty.setVisibility(View.GONE);
    }

    private final java.util.List<String> pivotKeys = new java.util.ArrayList<String>();
    private final java.util.List<String> pivotNames = new java.util.ArrayList<String>();
    private java.util.List<String> lastKeys;
    private int chipsSel = -1;

    private void parallax(float pos) {
        View title = header == null ? null : header.findViewWithTag("grove_title");
        if (title != null) title.setTranslationX(-Math.min(pos * Ui.dp(this, 70), Ui.dp(this, 300)));
    }

    /** Tapping a pivot header: slide the list across and move the headers. */
    private void goTo(final int k) {
        int cur = pivotKeys.indexOf(filter);
        if (k == cur || k < 0 || k >= pivotKeys.size() || cardsView == null) return;
        final int dir = k > cur ? 1 : -1;
        cardsView.animate().translationX(-dir * frame.getWidth()).setDuration(140).setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() {
                        applyPage(k);
                        cardsView.setTranslationX(dir * frame.getWidth() * 0.5f);
                        cardsView.animate().translationX(0).setDuration(320).setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f)).start();
                    }
                }).start();
    }

    private void applyPage(int k) {
        filter = pivotKeys.get(k);
        chipsSel = k;
        chips.animateTo(k);
        refresh();
        cardsView.setSelection(0);
        View title = header == null ? null : header.findViewWithTag("grove_title");
        if (title != null) title.animate().translationX(-Math.min(k * Ui.dp(this, 70), Ui.dp(this, 300))).setDuration(450)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.5f)).start();
    }

    private android.view.View.OnTouchListener swipeListener() {
        swiper.onDrag = new Ui.Cb<Float>() {
            public void on(Float f) {
                chips.drag(f);
                parallax(Math.max(0, pivotKeys.indexOf(filter) + f));
            }
        };
        swiper.onPage = new Ui.Cb<Integer>() {
            public void on(Integer dir) { applyPage(pivotKeys.indexOf(filter) + dir); }
        };
        return new android.view.View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                swiper.content = cardsView;
                if (swiper.handle(e)) {
                    if (swiper.justStarted) {
                        android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                        c.setAction(android.view.MotionEvent.ACTION_CANCEL); // don't also scroll or open a card
                        v.onTouchEvent(c);
                        c.recycle();
                    }
                    return true;
                }
                return false;
            }
        };
    }

    private void addChip(String label, final String key) {
        pivotKeys.add(key);
        pivotNames.add(label);
    }

    private void open(Card c) {
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

    private void showMenu(View anchor) {
        final java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
        final java.util.List<String> names = new java.util.ArrayList<String>();
        ids.add(1); names.add("Password generator");
        ids.add(2); names.add("Labels");
        ids.add(3); names.add(filter.equals("templates") ? "Back to cards" : "Templates");
        if (filter.equals("templates")) { ids.add(9); names.add("Restore default templates"); }
        ids.add(4); names.add("Trash");
        ids.add(5); names.add("Sort…");
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
    public void onBackPressed() {
        if (searching) {
            searching = false;
            search.setText("");
            search.setVisibility(View.GONE);
        } else if (!filter.equals("all")) {
            filter = "all";
            refresh();
        } else super.onBackPressed();
    }

    /** Live tile: square in the card's colour, icon in the middle, name in the bottom-left corner. */
    private View tile(Card c) {
        FrameLayout f = new FrameLayout(this) {
            @Override
            protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
        };
        f.setBackgroundColor(Ui.cardColor(c.color));
        View icon = Ui.cardIcon(this, c, 54); // letter (same colour as the tile) until the site icon arrives
        f.addView(icon, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        TextView title = Ui.text(this, c.title.length() == 0 ? "(untitled)" : c.title, 13, 0xFFFFFFFF, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM | Gravity.START);
        tl.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 6));
        f.addView(title, tl);
        String badges = "";
        if (!c.template && Prefs.showWarnings(this) && (Audit.isExpired(c, System.currentTimeMillis()) || Audit.hasWeakPassword(c) || Audit.isReused(c, reused))) badges += "⚠";
        for (Field pf : c.fields) if (Field.PASSKEY.equals(pf.type)) { badges += "🔐"; break; }
        if (c.favorite) badges += "★";
        if (badges.length() > 0) {
            TextView b = Ui.text(this, badges, 12, 0xFFFFFFFF, false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.END);
            lp.setMargins(0, Ui.dp(this, 5), Ui.dp(this, 7), 0);
            f.addView(b, lp);
        }
        Ui.tilt(f);
        return f;
    }

    private class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View convert, ViewGroup parent) {
            Card c = shown.get(pos);
            if (Prefs.p(MainActivity.this).getBoolean("grid", false)) return tile(c);
            LinearLayout r = Ui.horizontal(MainActivity.this);
            r.setPadding(Ui.dp(MainActivity.this, 20), Ui.dp(MainActivity.this, 5), Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 5));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(Ui.cardIcon(MainActivity.this, c, 46));
            LinearLayout tx = Ui.vertical(MainActivity.this);
            tx.setPadding(Ui.dp(MainActivity.this, 14), 0, Ui.dp(MainActivity.this, 8), 0);
            TextView t = Ui.text(MainActivity.this, c.title.length() == 0 ? "(untitled)" : c.title, 19, Ui.TEXT, false);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(t);
            String sub = c.template ? c.fields.size() + " fields" : c.subtitle();
            if (sub.length() > 0) {
                TextView s = Ui.text(MainActivity.this, sub, 13, Ui.SUB, false);
                s.setSingleLine(true);
                s.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tx.addView(s);
            }
            r.addView(tx, Ui.lpw(1));
            if (!c.template && Prefs.showWarnings(MainActivity.this)) {
                String w = "";
                if (Audit.isExpired(c, System.currentTimeMillis())) w += "⏰";
                if (Audit.hasWeakPassword(c) || Audit.isReused(c, reused)) w += "⚠";
                if (w.length() > 0) r.addView(Ui.text(MainActivity.this, w, 14, Ui.WARN, false));
            }
            for (Field pf : c.fields) if (Field.PASSKEY.equals(pf.type)) { r.addView(Ui.text(MainActivity.this, " 🔐", 14, Ui.SUB, false)); break; }
            if (c.favorite) r.addView(Ui.text(MainActivity.this, " ★", 16, Ui.WARN, false));
            return r;
        }
    }

}
