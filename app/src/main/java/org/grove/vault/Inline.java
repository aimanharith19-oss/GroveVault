package org.grove.vault;

import android.app.PendingIntent;
import android.app.slice.Slice;
import android.app.slice.SliceSpec;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.service.autofill.InlinePresentation;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.widget.inline.InlinePresentationSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android 11+ inline autofill: suggestions shown as chips inside the keyboard's suggestion strip.
 * Only loaded on Android 11+ (all callers check the SDK level first).
 */
final class Inline {
    private Inline() {}

    private static final String VERSION_V1 = "androidx.autofill.inline.ui.version:v1";
    private static final String VERSIONS_KEY = "androidx.autofill.inline.ui.version:key";

    /** Does the keyboard support the standard inline suggestion style? */
    static boolean supported(Object request) {
        if (!(request instanceof InlineSuggestionsRequest)) return false;
        InlineSuggestionsRequest r = (InlineSuggestionsRequest) request;
        List<InlinePresentationSpec> specs = r.getInlinePresentationSpecs();
        if (specs == null || specs.isEmpty()) return false;
        Bundle style = specs.get(0).getStyle();
        ArrayList<String> versions = style == null ? null : style.getStringArrayList(VERSIONS_KEY);
        return versions != null && versions.contains(VERSION_V1);
    }

    static int max(Object request) {
        return ((InlineSuggestionsRequest) request).getMaxSuggestionCount();
    }

    /** Builds one keyboard chip: icon, title, subtitle. Long-pressing it opens Grove Vault. */
    static InlinePresentation chip(Context c, Object request, int index, String title, String subtitle, Icon icon, boolean pinned) {
        InlineSuggestionsRequest r = (InlineSuggestionsRequest) request;
        List<InlinePresentationSpec> specs = r.getInlinePresentationSpecs();
        InlinePresentationSpec spec = specs.get(Math.min(index, specs.size() - 1));
        Slice.Builder b = new Slice.Builder(Uri.parse("inline.slice"), new SliceSpec(VERSION_V1, 1));
        if (icon != null) b.addIcon(icon, null, Collections.singletonList("inline_start_icon"));
        if (title != null && title.length() > 0) b.addText(title, null, Collections.singletonList("inline_title"));
        if (subtitle != null && subtitle.length() > 0) b.addText(subtitle, null, Collections.singletonList("inline_subtitle"));
        Intent open = new Intent(c, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent attribution = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(attribution, new Slice.Builder(b).addHints(Collections.singletonList("inline_attribution")).build(), null);
        b.addText((title == null ? "" : title) + (subtitle == null ? "" : ", " + subtitle), null, Collections.singletonList("inline_content_description"));
        return new InlinePresentation(b.build(), spec, pinned);
    }
}
