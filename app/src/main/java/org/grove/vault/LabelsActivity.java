package org.grove.vault;

import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;

import org.grove.vault.core.Card;
import org.grove.vault.core.Label;
import org.grove.vault.core.Vault;

/** Add, rename and delete labels. */
public class LabelsActivity extends BaseActivity {
    @Override
    protected void render() {
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "Labels", true, Ui.flatButton(this, "+ New", new View.OnClickListener() {
            public void onClick(View v) {
                Ui.prompt(LabelsActivity.this, "New label", "Label name", null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, new Ui.Cb<String>() {
                    public void on(String s) {
                        if (s.trim().length() == 0) return;
                        Session.vault().labelNamed(s.trim());
                        Session.saveOrToast(LabelsActivity.this);
                        render();
                    }
                });
            }
        })));
        LinearLayout list = Ui.vertical(this);
        root.addView(Ui.scroll(this, list), Ui.lp(Ui.MATCH, Ui.MATCH));
        final Vault v = Session.vault();
        v.sortLabels();
        if (v.labels.isEmpty()) list.addView(Ui.row(this, "No labels yet", "Labels group your cards, like folders, but a card can have several.", null));
        for (final Label l : v.labels) {
            int n = 0;
            for (Card c : v.activeCards()) if (c.labelIds.contains(l.id)) n++;
            list.addView(Ui.row(this, "🏷  " + l.name, n + (n == 1 ? " card" : " cards"), new View.OnClickListener() {
                public void onClick(View view) {
                    Ui.choose(LabelsActivity.this, l.name, new String[]{"Rename", "Delete"}, new Ui.Cb<Integer>() {
                        public void on(Integer w) {
                            if (w == 0) {
                                Ui.prompt(LabelsActivity.this, "Rename label", "Label name", l.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, new Ui.Cb<String>() {
                                    public void on(String s) {
                                        if (s.trim().length() == 0) return;
                                        l.name = s.trim();
                                        Session.saveOrToast(LabelsActivity.this);
                                        render();
                                    }
                                });
                            } else {
                                Ui.confirm(LabelsActivity.this, "Delete label?", "“" + l.name + "” will be removed from all cards. The cards themselves are kept.", "Delete", new Runnable() {
                                    public void run() {
                                        v.deleteLabel(l);
                                        Session.saveOrToast(LabelsActivity.this);
                                        render();
                                    }
                                });
                            }
                        }
                    });
                }
            }));
            list.addView(Ui.divider(this));
        }
    }
}
