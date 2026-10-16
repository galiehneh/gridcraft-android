package com.galiehneh.gridcraft;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Most recently opened documents (content URIs with a persisted permission), newest first. */
final class RecentFiles {
    static final int MAX = 10;
    private static final String PREFS = "recent_files";
    private static final String KEY = "entries";

    record Entry(String uri, String name) {}

    private final SharedPreferences prefs;

    RecentFiles(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Entry> add(String uri, String name) {
        List<Entry> list = withEntry(load(), new Entry(uri, name));
        save(list);
        return list;
    }

    /** Drops entries whose URI is not in {@code allowed}; returns the remaining list. */
    List<Entry> prune(Set<String> allowed) {
        List<Entry> current = load();
        List<Entry> kept = retain(current, allowed);
        if (kept.size() != current.size()) save(kept);
        return kept;
    }

    /** New list with {@code entry} first, any older entry for the same URI removed, capped at {@link #MAX}. */
    static List<Entry> withEntry(List<Entry> list, Entry entry) {
        List<Entry> result = new ArrayList<>();
        result.add(entry);
        for (Entry e : list) {
            if (!e.uri().equals(entry.uri()) && result.size() < MAX) result.add(e);
        }
        return result;
    }

    static List<Entry> retain(List<Entry> list, Set<String> allowed) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : list) {
            if (allowed.contains(e.uri())) result.add(e);
        }
        return result;
    }

    private List<Entry> load() {
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            List<Entry> list = new ArrayList<>();
            for (int i = 0; i < array.length() && list.size() < MAX; i++) {
                JSONObject o = array.getJSONObject(i);
                list.add(new Entry(o.getString("uri"), o.getString("name")));
            }
            return list;
        } catch (JSONException e) {
            return Collections.emptyList();
        }
    }

    private void save(List<Entry> list) {
        try {
            JSONArray array = new JSONArray();
            for (Entry e : list) {
                array.put(new JSONObject().put("uri", e.uri()).put("name", e.name()));
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (JSONException ignored) {}
    }
}
