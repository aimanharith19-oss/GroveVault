package org.grove.vault;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.grove.vault.core.Attachment;
import org.grove.vault.core.Card;
import org.grove.vault.core.Field;
import org.grove.vault.core.Label;
import org.grove.vault.core.Totp;
import org.grove.vault.core.Vault;

import java.util.ArrayList;
import java.util.List;

/** Create or edit a card or template. Works on a copy; nothing changes until Save. */
public class EditActivity extends BaseActivity {
    private static final int REQ_ATTACH = 7;
    private static final int MAX_ATTACHMENT = 5 * 1024 * 1024;
    private Card work;
    private boolean isNew;
    private EditText title, notes;
    private final List<EditText> values = new ArrayList<EditText>();
    private LinearLayout content;
    private String original;

    @Override
    protected void render() {
        if (work == null) {
            Vault v = Session.vault();
            String id = getIntent().getStringExtra("id");
            String tpl = getIntent().getStringExtra("template");
            if (id != null) {
                Card c = v.findCard(id);
                if (c == null) {
                    finish();
                    return;
                }
                work = c.copy();
            } else if (getIntent().getBooleanExtra("new_template", false)) {
                work = new Card();
                work.template = true;
                work.fields.add(new Field("Login", Field.LOGIN, ""));
                work.fields.add(new Field("Password", Field.PASSWORD, ""));
                isNew = true;
            } else {
                Card t = v.findCard(tpl);
                work = t != null ? t.instantiate() : new Card();
                String label = getIntent().getStringExtra("label");
                if (label != null) work.labelIds.add(label);
                if (getIntent().hasExtra("prefill_login")) prefill();
                isNew = true;
            }
        }
        if (original == null) original = signature();
        if (content == null) build();
    }

    private String signature() {
        StringBuilder b = new StringBuilder(work.title).append('\u0001').append(work.notes).append('\u0001').append(work.icon).append(work.color);
        for (Field f : work.fields) b.append('\u0001').append(f.name).append('\u0002').append(f.type).append('\u0002').append(f.value);
        b.append('\u0001').append(work.labelIds).append(work.attachments.size());
        return b.toString();
    }

    /** Values passed in from autofill "save". */
    private void prefill() {
        String login = getIntent().getStringExtra("prefill_login");
        String pass = getIntent().getStringExtra("prefill_password");
        String site = getIntent().getStringExtra("prefill_site");
        String pkg = getIntent().getStringExtra("prefill_app");
        for (Field f : work.fields) {
            if ((Field.LOGIN.equals(f.type) || Field.EMAIL.equals(f.type)) && f.value.length() == 0 && login != null) { f.value = login; login = null; }
            else if (Field.PASSWORD.equals(f.type) && f.value.length() == 0 && pass != null) { f.value = pass; pass = null; }
            else if (Field.WEBSITE.equals(f.type) && f.value.length() == 0 && site != null) { f.value = site; site = null; }
        }
        if (login != null) work.fields.add(new Field("Login", Field.LOGIN, login));
        if (pass != null) work.fields.add(new Field("Password", Field.PASSWORD, pass));
        if (site != null) work.fields.add(new Field("Website", Field.WEBSITE, site));
        if (pkg != null) work.apps.add(pkg);
        String t = getIntent().getStringExtra("prefill_title");
        if (t != null) work.title = t;
    }

    private void build() {
        LinearLayout root = Ui.screen(this);
        String heading = work.template ? (isNew ? "New template" : "Edit template") : (isNew ? "New card" : "Edit card");
        TextView save = Ui.flatButton(this, "Save", new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
        root.addView(Ui.topBar(this, heading, true, save));
        content = Ui.vertical(this);
        content.setPadding(0, 0, 0, Ui.dp(this, 60));
        root.addView(Ui.scroll(this, content), Ui.lp(Ui.MATCH, Ui.MATCH));
        rebuild();
    }

    private void sync() {
        if (title != null) work.title = title.getText().toString().trim();
        if (notes != null) work.notes = notes.getText().toString();
        for (int i = 0; i < values.size() && i < work.fields.size(); i++) work.fields.get(i).value = values.get(i).getText().toString();
    }

    private void rebuild() {
        content.removeAllViews();
        values.clear();

        LinearLayout head = Ui.horizontal(this);
        head.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        View badge = Ui.cardIcon(this, work, 56);
        badge.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sync();
                pickIcon();
            }
        });
        head.addView(badge);
        title = Ui.edit(this, work.template ? "Template name" : "Title", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setText(work.title);
        LinearLayout.LayoutParams tp = Ui.lpw(1);
        tp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        head.addView(title, tp);
        content.addView(head);

        LinearLayout colors = Ui.horizontal(this);
        colors.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));
        for (int i = 0; i < Ui.CARD_COLORS.length; i++) {
            final int idx = i;
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Ui.CARD_COLORS[i]);
            if (i == work.color) g.setStroke(Ui.dp(this, 3), Ui.TEXT);
            dot.setBackground(g);
            LinearLayout.LayoutParams p = Ui.lp(Ui.dp(this, 30), Ui.dp(this, 30));
            p.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            dot.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sync();
                    work.color = idx;
                    rebuild();
                }
            });
            colors.addView(dot, p);
        }
        content.addView(Ui.hscroll(this, colors));

        content.addView(Ui.section(this, "Fields"));
        for (int i = 0; i < work.fields.size(); i++) content.addView(fieldEditor(i));
        TextView add = Ui.flatButton(this, "+  Add field", new View.OnClickListener() {
            public void onClick(View v) {
                sync();
                addField();
            }
        });
        content.addView(add, Ui.margins(this, Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));

        content.addView(Ui.section(this, "Notes"));
        notes = Ui.edit(this, "Notes", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setMinLines(3);
        notes.setGravity(Gravity.TOP);
        notes.setText(work.notes);
        content.addView(notes, Ui.margins(this, Ui.MATCH, Ui.WRAP, 16, 0, 16, 0));

        content.addView(Ui.section(this, "Labels"));
        StringBuilder ls = new StringBuilder();
        for (String id : work.labelIds) {
            Label l = Session.vault().findLabel(id);
            if (l == null) continue;
            if (ls.length() > 0) ls.append(", ");
            ls.append(l.name);
        }
        content.addView(Ui.row(this, ls.length() == 0 ? "No labels" : ls.toString(), "Tap to choose", new View.OnClickListener() {
            public void onClick(View v) {
                sync();
                pickLabels();
            }
        }));

        if (!work.template) {
            content.addView(Ui.section(this, "Attachments"));
            for (final Attachment a : new ArrayList<Attachment>(work.attachments)) {
                LinearLayout r = Ui.row(this, (a.isImage() ? "🖼  " : "📎  ") + a.name, CardActivity.size(a.data.length), null);
                r.addView(Ui.iconButton(this, "✕", new View.OnClickListener() {
                    public void onClick(View v) {
                        sync();
                        work.attachments.remove(a);
                        rebuild();
                    }
                }));
                content.addView(r);
            }
            content.addView(Ui.flatButton(this, "+  Attach file or image", new View.OnClickListener() {
                public void onClick(View v) {
                    sync();
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_ATTACH);
                }
            }), Ui.margins(this, Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));
            TextView hint = Ui.text(this, "Attachments are encrypted inside the vault. Keep them small (key files, ID scans); large files make the vault slow.", 12, Ui.SUB, false);
            hint.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
            content.addView(hint);
        }
    }

    private int inputTypeFor(String type) {
        if (Field.PASSWORD.equals(type) || Field.SECRET.equals(type) || Field.OTP.equals(type)) return Ui.PASSWORD_INPUT;
        if (Field.PIN.equals(type)) return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        if (Field.NUMBER.equals(type)) return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (Field.PHONE.equals(type)) return InputType.TYPE_CLASS_PHONE;
        if (Field.EMAIL.equals(type)) return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        if (Field.WEBSITE.equals(type)) return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
        if (Field.LOGIN.equals(type)) return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (Field.MULTILINE.equals(type)) return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    }

    private String hintFor(Field f) {
        if (Field.OTP.equals(f.type)) return "Secret key or otpauth:// link";
        if (Field.EXPIRY.equals(f.type)) return "MM/YY or YYYY-MM-DD";
        if (Field.DATE.equals(f.type)) return "YYYY-MM-DD";
        if (Field.WEBSITE.equals(f.type)) return "example.com";
        return f.name;
    }

    private View fieldEditor(final int index) {
        final Field f = work.fields.get(index);
        if (Field.PASSKEY.equals(f.type)) {
            LinearLayout row = Ui.horizontal(this);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
            org.grove.vault.core.Passkey p = org.grove.vault.core.Passkey.decode(f.value);
            row.addView(Ui.text(this, "🔐 Passkey · " + (p == null ? "?" : p.rpId + (p.userName.length() > 0 ? " · " + p.userName : "")), 14, Ui.TEXT, false), Ui.lpw(1));
            row.addView(Ui.iconButton(this, "✕", new View.OnClickListener() {
                public void onClick(View v) {
                    sync();
                    Ui.confirm(EditActivity.this, "Remove passkey?", "It is deleted when you save the card.", "Remove", new Runnable() {
                        public void run() { work.fields.remove(f); rebuild(); }
                    });
                }
            }));
            EditText hidden = new EditText(this);
            hidden.setText(f.value);
            hidden.setVisibility(View.GONE);
            row.addView(hidden);
            values.add(hidden);
            return row;
        }
        LinearLayout block = Ui.vertical(this);
        block.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
        LinearLayout top = Ui.horizontal(this);
        TextView name = Ui.text(this, f.name + "  ·  " + Field.typeName(f.type), 12, Ui.SUB, false);
        top.addView(name, Ui.lpw(1));
        top.addView(Ui.iconButton(this, "⋮", new View.OnClickListener() {
            public void onClick(View v) {
                sync();
                fieldMenu(index);
            }
        }));
        block.addView(top);
        LinearLayout row = Ui.horizontal(this);
        final EditText e = Ui.edit(this, hintFor(f), inputTypeFor(f.type));
        e.setText(f.value);
        if (Field.MULTILINE.equals(f.type)) e.setMinLines(2);
        row.addView(e, Ui.lpw(1));
        values.add(e);
        if (f.isSecret()) {
            row.addView(Ui.iconButton(this, "👁", new View.OnClickListener() {
                public void onClick(View v) {
                    boolean hidden = (e.getInputType() & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD
                            || (e.getInputType() & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
                    int cls = e.getInputType() & InputType.TYPE_MASK_CLASS;
                    if (hidden) e.setInputType(cls == InputType.TYPE_CLASS_NUMBER ? InputType.TYPE_CLASS_NUMBER : Ui.VISIBLE_PASSWORD_INPUT);
                    else e.setInputType(inputTypeFor(f.type));
                    e.setSelection(e.length());
                }
            }));
        }
        if (f.isPasswordLike()) {
            row.addView(Ui.iconButton(this, "🎲", new View.OnClickListener() {
                public void onClick(View v) {
                    GeneratorDialog.show(EditActivity.this, new Ui.Cb<String>() {
                        public void on(String s) {
                            e.setInputType(Ui.VISIBLE_PASSWORD_INPUT);
                            e.setText(s);
                        }
                    });
                }
            }));
        }
        block.addView(row);
        if (f.isPasswordLike()) {
            final TextView st = Ui.text(this, " ", 12, Ui.SUB, false);
            st.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0);
            LockActivity.showStrength(st, f.value);
            e.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) { LockActivity.showStrength(st, s.toString()); }
            });
            block.addView(st);
        }
        if (Field.OTP.equals(f.type)) {
            final TextView st = Ui.text(this, " ", 12, Ui.SUB, false);
            st.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0);
            TextWatcher w = new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    if (s.length() == 0) { st.setText("Paste the setup key or otpauth:// link from the site's 2FA setup."); st.setTextColor(Ui.SUB); return; }
                    Totp t = Totp.parse(s.toString());
                    st.setText(t == null ? "Not a valid secret" : "Current code: " + t.code(System.currentTimeMillis()));
                    st.setTextColor(t == null ? Ui.DANGER : Ui.ACCENT);
                }
            };
            e.addTextChangedListener(w);
            w.afterTextChanged(e.getText());
            block.addView(st);
        }
        return block;
    }

    private void fieldMenu(final int index) {
        final Field f = work.fields.get(index);
        String[] items = {"Rename", "Change type", "Move up", "Move down", "Delete field"};
        Ui.choose(this, f.name, items, new Ui.Cb<Integer>() {
            public void on(Integer w) {
                switch (w) {
                    case 0:
                        Ui.prompt(EditActivity.this, "Field name", "Name", f.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.Cb<String>() {
                            public void on(String s) { if (s.trim().length() > 0) { f.name = s.trim(); rebuild(); } }
                        });
                        break;
                    case 1:
                        int cur = 0;
                        for (int i = 0; i < Field.TYPES.length; i++) if (Field.TYPES[i].equals(f.type)) cur = i;
                        Ui.single(EditActivity.this, "Field type", Field.TYPE_NAMES, cur, new Ui.Cb<Integer>() {
                            public void on(Integer t) { f.type = Field.TYPES[t]; rebuild(); }
                        });
                        break;
                    case 2:
                        if (index > 0) { work.fields.remove(index); work.fields.add(index - 1, f); rebuild(); }
                        break;
                    case 3:
                        if (index < work.fields.size() - 1) { work.fields.remove(index); work.fields.add(index + 1, f); rebuild(); }
                        break;
                    case 4:
                        work.fields.remove(index);
                        rebuild();
                        break;
                }
            }
        });
    }

    private void addField() {
        Ui.choose(this, "Field type", Field.TYPE_NAMES, new Ui.Cb<Integer>() {
            public void on(final Integer t) {
                Ui.prompt(EditActivity.this, "Field name", "Name", Field.TYPE_NAMES[t], InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.Cb<String>() {
                    public void on(String s) {
                        work.fields.add(new Field(s.trim().length() == 0 ? Field.TYPE_NAMES[t] : s.trim(), Field.TYPES[t], ""));
                        rebuild();
                    }
                });
            }
        });
    }

    private void pickIcon() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(5);
        g.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 8));
        final android.app.AlertDialog d = Ui.dialog(this, "Icon").setView(g).setNegativeButton("Cancel", null).create();
        for (int i = 0; i < Ui.ICON_NAMES.length; i++) {
            final String name = Ui.ICON_NAMES[i];
            TextView t = Ui.badge(this, name, work.color, 48);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = Ui.dp(this, 48);
            p.height = Ui.dp(this, 48);
            p.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            t.setLayoutParams(p);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    work.icon = name;
                    d.dismiss();
                    rebuild();
                }
            });
            g.addView(t);
        }
        d.show();
    }

    private void pickLabels() {
        final Vault v = Session.vault();
        v.sortLabels();
        final int n = v.labels.size();
        final String[] names = new String[n + 1];
        final boolean[] checked = new boolean[n + 1];
        for (int i = 0; i < n; i++) {
            names[i] = v.labels.get(i).name;
            checked[i] = work.labelIds.contains(v.labels.get(i).id);
        }
        names[n] = "+ New label…";
        Ui.multi(this, "Labels", names, checked, new Runnable() {
            public void run() {
                work.labelIds.clear();
                for (int i = 0; i < n; i++) if (checked[i]) work.labelIds.add(v.labels.get(i).id);
                if (checked[n]) {
                    Ui.prompt(EditActivity.this, "New label", "Label name", null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, new Ui.Cb<String>() {
                        public void on(String s) {
                            if (s.trim().length() > 0) work.labelIds.add(v.labelNamed(s.trim()).id);
                            rebuild();
                        }
                    });
                } else rebuild();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (!Session.isUnlocked()) {
            Ui.toast(this, "The vault locked. Unlock and try again.");
            return;
        }
        if (req != REQ_ATTACH || res != RESULT_OK || data == null || work == null) return;
        Uri uri = data.getData();
        try {
            String name = "attachment";
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    name = c.getString(0);
                    if (!c.isNull(1) && c.getLong(1) > MAX_ATTACHMENT) {
                        c.close();
                        Ui.message(this, "File too large", "Attachments can be up to 5 MB.");
                        return;
                    }
                }
                c.close();
            }
            byte[] bytes = Store.readUri(this, uri);
            if (bytes.length > MAX_ATTACHMENT) {
                Ui.message(this, "File too large", "Attachments can be up to 5 MB.");
                return;
            }
            String mime = getContentResolver().getType(uri);
            work.attachments.add(new Attachment(name, mime, bytes));
            if (content != null) rebuild();
        } catch (Exception e) {
            Ui.message(this, "Couldn't attach", String.valueOf(e.getMessage()));
        }
    }

    private void save() {
        sync();
        if (work.title.length() == 0) {
            if (work.template) {
                title.setError("Give the template a name");
                return;
            }
            String w = Card.domainOf(work.website());
            work.title = w.length() > 0 ? w : (work.login().length() > 0 ? work.login() : "Untitled");
        }
        Vault v = Session.vault();
        work.modified = System.currentTimeMillis();
        Card existing = v.findCard(work.id);
        if (existing == null) {
            v.cards.add(work);
        } else {
            // keep history for changed secrets
            for (Field nf : work.fields) {
                for (Field of : existing.fields) {
                    if (of.name.equals(nf.name) && of.type.equals(nf.type) && !of.value.equals(nf.value) && nf.isSecret() && of.value.length() > 0) {
                        boolean already = !nf.history.isEmpty() && nf.history.get(0).value.equals(of.value);
                        if (!already) nf.history.add(0, new Field.HistoryEntry(of.value, System.currentTimeMillis()));
                        while (nf.history.size() > 20) nf.history.remove(nf.history.size() - 1);
                    }
                }
            }
            v.cards.set(v.cards.indexOf(existing), work);
        }
        if (Session.saveOrToast(this)) {
            if (isNew && !work.template && getCallingActivity() == null) {
                startActivity(new Intent(this, CardActivity.class).putExtra("id", work.id));
            }
            setResult(RESULT_OK, new Intent().putExtra("id", work.id));
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        sync();
        if (!isNew && signature().equals(original)) {
            finish();
            return;
        }
        Ui.confirm(this, "Discard changes?", "Your changes to this card won't be saved.", "Discard", new Runnable() {
            public void run() {
                finish();
            }
        });
    }
}
