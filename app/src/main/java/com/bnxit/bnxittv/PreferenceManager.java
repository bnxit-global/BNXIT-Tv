package com.bnxit.bnxittv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Simple SharedPreferences wrapper for persisting user state.
 * Stores: last played channel URL, last selected category.
 */
public class PreferenceManager {

    private static final String PREF_NAME = "bnxit_tv_prefs";
    private static final String KEY_LAST_CHANNEL_URL = "last_channel_url";
    private static final String KEY_LAST_CATEGORY = "last_category";

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveLastChannel(String url) {
        if (url != null) {
            prefs.edit().putString(KEY_LAST_CHANNEL_URL, url).apply();
        }
    }

    public String getLastChannelUrl() {
        return prefs.getString(KEY_LAST_CHANNEL_URL, null);
    }

    public void saveLastCategory(String category) {
        if (category != null) {
            prefs.edit().putString(KEY_LAST_CATEGORY, category).apply();
        }
    }

    public String getLastCategory() {
        return prefs.getString(KEY_LAST_CATEGORY, "All");
    }
}
