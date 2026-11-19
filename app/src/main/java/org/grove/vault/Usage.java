package org.grove.vault;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Remembers which cards you use (open, copy, fill) for the "Recent" row. Stores only card ids and times, on this phone. */
public final class Usage {
    private Usage() {}

    private static JSONObject load(Context c) {
        try { return new JSONObject(Prefs.p(c).getString("usage", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public static void record(Context c, String cardId) {
        if (cardId == null) return;
        try {
            JSONObject o = load(c);
            JSONObject u = o.optJSONObject(cardId);
            if (u == null) u = new JSONObject();
            u.put("n", u.optInt("n") + 1);
            u.put("t", System.currentTimeMillis());
            o.put(cardId, u);
            if (o.length() > 200) { // forget the oldest
                String oldest = null;
                long ot = Long.MAX_VALUE;
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    long t = o.getJSONObject(k).optLong("t");
                    if (t < ot) { ot = t; oldest = k; }
                }
                o.remove(oldest);
            }
            Prefs.put(c, "usage", o.toString());
        } catch (Exception ignored) {}
    }

    /** Card ids, best first: recently used, weighted by how often (a use counts less as it gets older). */
    public static List<String> top(Context c, int max) {
        final JSONObject o = load(c);
        final long now = System.currentTimeMillis();
        List<String> ids = new ArrayList<String>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) ids.add(it.next());
        Collections.sort(ids, new Comparator<String>() {
            double score(String k) {
                JSONObject u = o.optJSONObject(k);
                if (u == null) return 0;
                double days = (now - u.optLong("t")) / 86400000.0;
                return Math.log(1 + u.optInt("n")) / (1 + days / 3);
            }
            public int compare(String a, String b) { return Double.compare(score(b), score(a)); }
        });
        return ids.size() > max ? ids.subList(0, max) : ids;
    }

    public static void forget(Context c) { Prefs.put(c, "usage", null); }
}
