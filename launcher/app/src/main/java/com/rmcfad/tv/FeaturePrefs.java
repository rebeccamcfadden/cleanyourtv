package com.rmcfad.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Launcher settings › Features' switches as saved on the TV, over this TV's config (Features decides what they mean). */
final class FeaturePrefs {
    private FeaturePrefs() {}

    static Features load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Map<String, Object> saved = new HashMap<>();
        for (Features.Feature f : Features.Feature.values()) {
            if (p.contains(f.key())) saved.put(f.key(), p.getBoolean(f.key(), true));
        }
        return Features.resolve(saved, config(ctx));
    }

    /**
     * This TV's config for each feature: config.xml's {@code feature_<id>}, then a pushed config.json's
     * {@code "features": {"<id>": true|false}}, which wins.
     */
    static Map<String, Boolean> config(Context ctx) {
        Resources res = ctx.getResources();
        Map<String, Boolean> out = new HashMap<>();
        out.put(Features.Feature.TMDB.id, res.getBoolean(R.bool.feature_tmdb));
        out.put(Features.Feature.NOTES.id, res.getBoolean(R.bool.feature_notes));
        out.put(Features.Feature.PICK.id, res.getBoolean(R.bool.feature_pick));
        out.put(Features.Feature.PRICES.id, res.getBoolean(R.bool.feature_prices));
        out.put(Features.Feature.WEATHER.id, res.getBoolean(R.bool.feature_weather));
        out.put(Features.Feature.THEMES.id, res.getBoolean(R.bool.feature_themes));
        JSONObject json = Config.overlay(ctx);
        JSONObject pushed = json == null ? null : json.optJSONObject("features");
        if (pushed != null) {
            for (Features.Feature f : Features.Feature.values()) {
                Object v = pushed.opt(f.id);
                if (v instanceof Boolean) out.put(f.id, (Boolean) v);
            }
        }
        return out;
    }

    /** Saves the household's switch, and moves Launcher settings' change count so the launcher is rebuilt with it. */
    static void save(Context ctx, Features.Feature f, boolean on) {
        SharedPreferences p = prefs(ctx);
        p.edit().putBoolean(f.key(), on)
                .putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
