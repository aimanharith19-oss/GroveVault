package org.grove.vault.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** In-memory vault: cards (including templates and trashed cards) and labels. Pure Java, no Android types. */
public class Vault {
    public final List<Card> cards = new ArrayList<Card>();
    public final List<Label> labels = new ArrayList<Label>();
    public long modified = System.currentTimeMillis();

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public Card findCard(String id) {
        if (id == null) return null;
        for (Card c : cards) if (id.equals(c.id)) return c;
        return null;
    }

    public Label findLabel(String id) {
        if (id == null) return null;
        for (Label l : labels) if (id.equals(l.id)) return l;
        return null;
    }

    public Label findLabelByName(String name) {
        for (Label l : labels) if (l.name.equalsIgnoreCase(name)) return l;
        return null;
    }

    /** Returns an existing label with this name or creates one. */
    public Label labelNamed(String name) {
        Label l = findLabelByName(name);
        if (l == null) {
            l = new Label(newId(), name);
            labels.add(l);
        }
        return l;
    }

    public List<Card> templates() {
        List<Card> out = new ArrayList<Card>();
        for (Card c : cards) if (c.template && !c.deleted) out.add(c);
        return out;
    }

    /** Active (not deleted, not template) cards. */
    public List<Card> activeCards() {
        List<Card> out = new ArrayList<Card>();
        for (Card c : cards) if (!c.template && !c.deleted) out.add(c);
        return out;
    }

    public List<Card> trash() {
        List<Card> out = new ArrayList<Card>();
        for (Card c : cards) if (c.deleted) out.add(c);
        return out;
    }

    public void deleteLabel(Label l) {
        labels.remove(l);
        for (Card c : cards) c.labelIds.remove(l.id);
    }

    public void sortLabels() {
        Collections.sort(labels, new Comparator<Label>() {
            public int compare(Label a, Label b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    /** Merges another vault into this one: new cards are added, cards in both keep the newer version. Returns {added, updated}. */
    public int[] mergeFrom(Vault from) {
        java.util.Map<String, String> labelMap = new java.util.HashMap<String, String>();
        for (Label l : from.labels) labelMap.put(l.id, labelNamed(l.name).id);
        int added = 0, updated = 0;
        for (Card c : from.cards) {
            Card copy = c.copy();
            copy.labelIds.clear();
            for (String id : c.labelIds) if (labelMap.containsKey(id)) copy.labelIds.add(labelMap.get(id));
            Card mine = findCard(c.id);
            if (mine == null) {
                if (c.template) {
                    boolean dup = false;
                    for (Card t : templates()) if (t.title.equalsIgnoreCase(c.title)) dup = true;
                    if (dup) continue;
                }
                cards.add(copy);
                added++;
            } else if (c.modified > mine.modified) {
                cards.set(cards.indexOf(mine), copy);
                updated++;
            }
        }
        return new int[]{added, updated};
    }

    public void touch() {
        modified = System.currentTimeMillis();
    }
}
