package org.grove.vault;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.grove.vault.core.Attachment;
import org.grove.vault.core.Audit;
import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Label;
import org.grove.vault.core.PasswordTools;
import org.grove.vault.core.Passkey;
import org.grove.vault.core.Totp;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Read-only card view: tap a value to copy it, eye to reveal, live one-time codes, attachments. */
public class CardActivity extends BaseActivity {
    private static final int REQ_SAVE_ATTACHMENT = 5;
    private Card card;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> tickers = new ArrayList<Runnable>();
    private Attachment pendingSave;
    private final Runnable tick = new Runnable() {
        public void run() {
            for (Runnable r : tickers) r.run();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void render() {
        card = Session.card(getIntent().getStringExtra("id"));
        if (card == null) {
            finish();
            return;
        }
        tickers.clear();
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "", true,
                Ui.iconButton(this, card.favorite ? "★" : "☆", new View.OnClickListener() {
                    public void onClick(View v) {
                        card.favorite = !card.favorite;
                        Session.saveOrToast(CardActivity.this);
                        render();
                    }
                }),
                Ui.iconButton(this, "✏️", new View.OnClickListener() {
                    public void onClick(View v) {
                        startActivity(new Intent(CardActivity.this, EditActivity.class).putExtra("id", card.id));
                    }
                }),
                Ui.iconButton(this, "⋮", new View.OnClickListener() {
                    public void onClick(View v) {
                        more();
                    }
                })));
        LinearLayout c = Ui.vertical(this);
        c.setPadding(0, 0, 0, Ui.dp(this, 40));
        root.addView(Ui.scroll(this, c), Ui.lp(Ui.MATCH, Ui.MATCH));

        LinearLayout head = Ui.horizontal(this);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 12));
        head.addView(Ui.cardIcon(this, card, 56));
        LinearLayout ht = Ui.vertical(this);
        ht.setPadding(Ui.dp(this, 16), 0, 0, 0);
        ht.addView(Ui.text(this, card.title.length() == 0 ? "(untitled)" : card.title, 22, Ui.TEXT, true));
        StringBuilder labels = new StringBuilder();
        for (String id : card.labelIds) {
            Label l = Session.vault().findLabel(id);
            if (l == null) continue;
            if (labels.length() > 0) labels.append("  ·  ");
            labels.append(l.name);
        }
        if (labels.length() > 0) ht.addView(Ui.text(this, "🏷 " + labels, 13, Ui.SUB, false));
        head.addView(ht, Ui.lpw(1));
        c.addView(head);

        if (card.deleted) {
            TextView t = Ui.text(this, "This card is in the trash.", 14, Ui.WARN, false);
            t.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
            c.addView(t);
        }
        warnings(c);

        LinearLayout box = box(c);
        boolean any = false;
        for (Field f : card.fields) {
            if (f.value.length() == 0) continue;
            if (any) box.addView(Ui.divider(this));
            box.addView(fieldRow(f));
            any = true;
        }
        if (!any) box.setVisibility(View.GONE);

        if (card.notes.length() > 0) {
            c.addView(Ui.section(this, "Notes"));
            LinearLayout nb = box(c);
            TextView n = Ui.text(this, card.notes, 15, Ui.TEXT, false);
            n.setTextIsSelectable(true);
            n.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            nb.addView(n);
        }
        if (!card.attachments.isEmpty()) {
            c.addView(Ui.section(this, "Attachments"));
            LinearLayout ab = box(c);
            for (int i = 0; i < card.attachments.size(); i++) {
                if (i > 0) ab.addView(Ui.divider(this));
                ab.addView(attachmentRow(card.attachments.get(i)));
            }
        }
        if (!card.apps.isEmpty()) {
            c.addView(Ui.section(this, "Autofill apps"));
            LinearLayout pb = box(c);
            for (final String pkg : new ArrayList<String>(card.apps)) {
                LinearLayout r = Ui.row(this, appName(this, pkg), pkg, null);
                r.addView(Ui.iconButton(this, "✕", new View.OnClickListener() {
                    public void onClick(View v) {
                        card.apps.remove(pkg);
                        Session.saveOrToast(CardActivity.this);
                        render();
                    }
                }));
                pb.addView(r);
            }
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        TextView meta = Ui.text(this, "Created " + df.format(new Date(card.created)) + "\nModified " + df.format(new Date(card.modified)), 12, Ui.SUB, false);
        meta.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), 0);
        c.addView(meta);
        Ui.pivotize(this, c, "details");
    }

    private LinearLayout box(LinearLayout parent) {
        LinearLayout b = Ui.vertical(this);
        b.setClipToOutline(true);
        parent.addView(b, Ui.margins(this, Ui.MATCH, Ui.WRAP, 16, 4, 16, 4));
        return b;
    }

    private void warnings(LinearLayout c) {
        List<String> w = new ArrayList<String>();
        long now = System.currentTimeMillis();
        if (Audit.isExpired(card, now)) w.add("⏰ This card has expired.");
        else if (Audit.isExpiring(card, Prefs.expiryDays(this), now)) {
            long days = (Audit.expiry(card) - now) / 86400000L;
            w.add("⏰ Expires in " + days + (days == 1 ? " day." : " days."));
        }
        if (Audit.hasWeakPassword(card)) w.add("⚠ Weak password: easy to guess. Consider changing it.");
        if (Audit.isReused(card, Audit.reused(Session.vault().activeCards()))) {
            List<String> others = new ArrayList<String>();
            for (List<Card> l : Audit.reused(Session.vault().activeCards()).values()) {
                if (!l.contains(card)) continue;
                for (Card o : l) if (o != card && !others.contains(o.title)) others.add(o.title);
            }
            w.add("♻ Same password is used for: " + android.text.TextUtils.join(", ", others));
        }
        for (String s : w) {
            TextView t = Ui.text(this, s, 14, Ui.WARN, false);
            t.setBackground(Ui.round(Ui.alpha(Ui.WARN, 0x22), Ui.dp(this, 12)));
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            c.addView(t, Ui.margins(this, Ui.MATCH, Ui.WRAP, 16, 2, 16, 6));
        }
    }

    private View passkeyRow(final Field f) {
        final Passkey p = Passkey.decode(f.value);
        LinearLayout r = Ui.horizontal(this);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
        LinearLayout tx = Ui.vertical(this);
        tx.addView(Ui.text(this, "Passkey", 12, Ui.SUB, false));
        String line = p == null ? "Damaged passkey" : "🔐 " + p.rpId + (p.userName.length() > 0 ? "  ·  " + p.userName : "");
        tx.addView(Ui.text(this, line, 16, Ui.TEXT, false));
        if (p != null) tx.addView(Ui.text(this, "Created " + DateFormat.getDateInstance().format(new Date(p.created)) + " · sign in from the passkey prompt in apps and browsers", 12, Ui.SUB, false));
        r.addView(tx, Ui.lpw(1));
        r.addView(Ui.iconButton(this, "🗑", new View.OnClickListener() {
            public void onClick(View v) {
                Ui.confirm(CardActivity.this, "Delete passkey?", "You won't be able to sign in to " + (p == null ? "this site" : p.rpId) + " with this passkey any more. Remove it from the site's account settings too.", "Delete", new Runnable() {
                    public void run() {
                        card.fields.remove(f);
                        card.modified = System.currentTimeMillis();
                        Session.saveOrToast(CardActivity.this);
                        render();
                    }
                });
            }
        }));
        return r;
    }

    private View fieldRow(final Field f) {
        if (Field.PASSKEY.equals(f.type)) return passkeyRow(f);
        LinearLayout r = Ui.horizontal(this);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
        LinearLayout tx = Ui.vertical(this);
        tx.addView(Ui.text(this, f.name, 12, Ui.SUB, false));
        final TextView val = Ui.text(this, "", 17, Ui.TEXT, false);
        val.setTypeface(f.isSecret() ? Typeface.MONOSPACE : Typeface.DEFAULT);
        tx.addView(val);
        r.addView(tx, Ui.lpw(1));
        final boolean[] shown = {!f.isSecret()};

        if (Field.OTP.equals(f.type)) {
            final Totp t = Totp.parse(f.value);
            final TextView left = Ui.text(this, "", 13, Ui.SUB, false);
            left.setGravity(Gravity.CENTER);
            r.addView(left, Ui.lp(Ui.dp(this, 40), Ui.WRAP));
            Runnable upd = new Runnable() {
                public void run() {
                    if (t == null) {
                        val.setText("Invalid secret");
                        return;
                    }
                    long now = System.currentTimeMillis();
                    String code = t.code(now);
                    if (code.length() == 6) code = code.substring(0, 3) + " " + code.substring(3);
                    val.setText(code);
                    int s = t.secondsLeft(now);
                    left.setText(s + "s");
                    left.setTextColor(s <= 5 ? Ui.DANGER : Ui.SUB);
                }
            };
            upd.run();
            tickers.add(upd);
            ripple(r);
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (t != null) App.copy(CardActivity.this, "One-time code", t.code(System.currentTimeMillis()));
                }
            });
            r.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    Ui.message(CardActivity.this, "One-time password setup", f.value);
                    return true;
                }
            });
            return r;
        }

        final Runnable show = new Runnable() {
            public void run() {
                if (shown[0]) val.setText(display(f));
                else val.setText("••••••••");
            }
        };
        show.run();
        if (f.isSecret()) {
            r.addView(Ui.iconButton(this, "👁", new View.OnClickListener() {
                public void onClick(View v) {
                    shown[0] = !shown[0];
                    show.run();
                }
            }));
        }
        if (Field.WEBSITE.equals(f.type)) {
            r.addView(Ui.iconButton(this, "↗", new View.OnClickListener() {
                public void onClick(View v) {
                    openUrl(CardActivity.this, f.value);
                }
            }));
        } else if (Field.PHONE.equals(f.type)) {
            r.addView(Ui.iconButton(this, "📞", new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(f.value))));
                    } catch (Exception ignored) {}
                }
            }));
        } else if (Field.EMAIL.equals(f.type) && f.value.contains("@")) {
            r.addView(Ui.iconButton(this, "✉", new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + f.value)));
                    } catch (Exception ignored) {}
                }
            }));
        }
        ripple(r);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                App.copy(CardActivity.this, f.name, f.value);
            }
        });
        r.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                fieldMenu(f, shown, show);
                return true;
            }
        });
        return r;
    }

    private void ripple(View v) {
        Ui.ripple(v, 0, 0);
    }

    static String display(Field f) {
        if (Field.NUMBER.equals(f.type) && f.value.replace(" ", "").matches("\\d{13,19}")) {
            String d = f.value.replace(" ", "");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < d.length(); i++) {
                if (i > 0 && i % 4 == 0) b.append(' ');
                b.append(d.charAt(i));
            }
            return b.toString();
        }
        return f.value;
    }

    private void fieldMenu(final Field f, final boolean[] shown, final Runnable show) {
        List<String> items = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        items.add("Copy");
        acts.add(new Runnable() { public void run() { App.copy(CardActivity.this, f.name, f.value); } });
        if (f.isSecret()) {
            items.add(shown[0] ? "Hide" : "Show");
            acts.add(new Runnable() { public void run() { shown[0] = !shown[0]; show.run(); } });
            items.add("Show large");
            acts.add(new Runnable() { public void run() { large(f.value); } });
        }
        if (f.isPasswordLike()) {
            items.add("Password strength");
            acts.add(new Runnable() { public void run() {
                int s = PasswordTools.score(f.value);
                Ui.message(CardActivity.this, "Password strength", PasswordTools.SCORE_NAMES[s] + "\n\nAbout " + (int) PasswordTools.entropy(f.value) + " bits of entropy, " + f.value.length() + " characters.");
            } });
        }
        if (!f.history.isEmpty()) {
            items.add("History (" + f.history.size() + ")");
            acts.add(new Runnable() { public void run() { history(f); } });
        }
        Ui.choose(this, f.name, items.toArray(new String[0]), new Ui.Cb<Integer>() {
            public void on(Integer i) { acts.get(i).run(); }
        });
    }

    private void large(String value) {
        TextView t = Ui.text(this, value, 30, Ui.TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        Ui.dialog(this, null).setView(t).setPositiveButton("Close", null).show();
    }

    private void history(final Field f) {
        final String[] items = new String[f.history.size()];
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int i = 0; i < items.length; i++) items[i] = df.format(new Date(f.history.get(i).time)) + "\n" + f.history.get(i).value;
        Ui.dialog(this, "Previous values of " + f.name).setItems(items, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                App.copy(CardActivity.this, "Old " + f.name, f.history.get(w).value);
            }
        }).setNeutralButton("Clear history", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                f.history.clear();
                Session.saveOrToast(CardActivity.this);
                render();
            }
        }).setPositiveButton("Close", null).show();
    }

    private View attachmentRow(final Attachment a) {
        LinearLayout r = Ui.horizontal(this);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
        if (a.isImage()) {
            Bitmap bm = decode(a.data, 160);
            if (bm != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bm);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setClipToOutline(true);
                iv.setBackground(Ui.round(Ui.SURFACE2, Ui.dp(this, 8)));
                r.addView(iv, Ui.lp(Ui.dp(this, 56), Ui.dp(this, 56)));
            }
        } else r.addView(Ui.text(this, "📎", 24, Ui.TEXT, false));
        LinearLayout tx = Ui.vertical(this);
        tx.setPadding(Ui.dp(this, 14), 0, 0, 0);
        tx.addView(Ui.text(this, a.name, 15, Ui.TEXT, false));
        tx.addView(Ui.text(this, size(a.data.length), 12, Ui.SUB, false));
        r.addView(tx, Ui.lpw(1));
        r.addView(Ui.iconButton(this, "⬇", new View.OnClickListener() {
            public void onClick(View v) {
                pendingSave = a;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(a.mime);
                i.putExtra(Intent.EXTRA_TITLE, a.name);
                startActivityForResult(i, REQ_SAVE_ATTACHMENT);
            }
        }));
        if (a.isImage()) {
            Ui.ripple(r, 0, 0);
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Bitmap bm = decode(a.data, 1600);
                    if (bm == null) return;
                    ImageView iv = new ImageView(CardActivity.this);
                    iv.setImageBitmap(bm);
                    iv.setAdjustViewBounds(true);
                    Ui.dialog(CardActivity.this, a.name).setView(iv).setPositiveButton("Close", null).show();
                }
            });
        }
        return r;
    }

    static Bitmap decode(byte[] data, int maxSize) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (o.outWidth / s > maxSize * 2 || o.outHeight / s > maxSize * 2) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    static String size(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (!Session.isUnlocked()) {
            Ui.toast(this, "The vault locked. Unlock and try again.");
            return;
        }
        if (req == REQ_SAVE_ATTACHMENT && res == RESULT_OK && data != null && pendingSave != null) {
            try {
                Store.writeUri(this, data.getData(), pendingSave.data);
                Ui.toast(this, "Saved " + pendingSave.name);
            } catch (Exception e) {
                Ui.message(this, "Couldn't save", String.valueOf(e.getMessage()));
            }
            pendingSave = null;
        }
    }

    private void more() {
        List<String> items = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        if (card.deleted) {
            items.add("Restore from trash");
            acts.add(new Runnable() { public void run() { card.deleted = false; Session.saveOrToast(CardActivity.this); render(); } });
            items.add("Delete forever");
            acts.add(new Runnable() { public void run() {
                Ui.confirm(CardActivity.this, "Delete forever?", "This can't be undone.", "Delete", new Runnable() {
                    public void run() { Session.vault().cards.remove(card); Session.saveOrToast(CardActivity.this); finish(); }
                });
            } });
        } else {
            items.add("Move to trash");
            acts.add(new Runnable() { public void run() { card.deleted = true; Session.saveOrToast(CardActivity.this); Ui.toast(CardActivity.this, "Moved to trash"); finish(); } });
        }
        items.add("Save as template");
        acts.add(new Runnable() { public void run() {
            Card t = card.instantiate();
            t.template = true;
            t.title = card.title + " template";
            Session.vault().cards.add(t);
            if (Session.saveOrToast(CardActivity.this)) Ui.toast(CardActivity.this, "Template saved");
        } });
        Ui.choose(this, card.title, items.toArray(new String[0]), new Ui.Cb<Integer>() {
            public void on(Integer i) { acts.get(i).run(); }
        });
    }

    public static void openUrl(Activity a, String url) {
        String u = url.trim();
        if (!u.contains("://")) u = "https://" + u;
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            Ui.toast(a, "No app can open this link");
        }
    }

    static String appName(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }
}
