package com.gathin.tvhome;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Launcher settings' Motion section (design_preview/src/screens/panes/misc.ts motionPane): Full, Reduced or Off, the
 * same choice as the quick-settings dropdown's Motion row (MotionChoice, saved by MotionPrefs). Reduced and Off are
 * Config#reducedMotion for now: pages, menus, Home's scroll and focus switch at once, without moving (Ui.ms); the
 * spec's Reduced keeps the fades (BACKLOG.md). A saved choice wins over config.xml's reduced_motion; only Android's
 * animations turned off keep motion reduced whatever is chosen here, and the pane says so. Takes effect when Home is
 * rebuilt, as the page closes.
 */
final class MotionSection implements SettingsSection {
    private final Config config;
    private View first;

    MotionSection(Config config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "motion";
    }

    @Override
    public int label() {
        return R.string.settings_motion;
    }

    @Override
    public int icon() {
        return R.drawable.ic_ui_motion;
    }

    @Override
    public CharSequence value(Context ctx) {
        return ctx.getString(systemOff(ctx) ? R.string.motion_reduced : label(MotionPrefs.load(ctx, config)));
    }

    @Override
    public View pane(Context ctx, Host host) {
        LinearLayout pane = SettingsKit.column(ctx);
        pane.addView(SettingsKit.eyebrow(ctx, ctx.getString(R.string.settings_motion)));
        SettingsKit.add(pane, SettingsKit.title(ctx, ctx.getString(R.string.settings_motion), false), 10);
        SettingsKit.add(pane, SettingsKit.sub(ctx, ctx.getString(R.string.motion_sub)), 8);
        MotionChoice[] all = MotionChoice.values();
        String[] codes = new String[all.length];
        CharSequence[] labels = new CharSequence[all.length];
        for (int i = 0; i < all.length; i++) {
            codes[i] = all[i].code;
            labels[i] = ctx.getString(label(all[i]));
        }
        View seg = SettingsKit.segmented(ctx, "seg-motion", codes, labels, MotionPrefs.load(ctx, config).code, false,
                value -> {
                    MotionPrefs.save(ctx, MotionChoice.parse(value));
                    host.valueChanged();
                });
        SettingsKit.add(pane, seg, 22);
        first = SettingsKit.selectedOption(seg);
        if (systemOff(ctx)) SettingsKit.add(pane, SettingsKit.note(ctx, ctx.getString(R.string.motion_forced_system)), 22);
        return pane;
    }

    @Override
    public View firstFocus() {
        return first;
    }

    private static int label(MotionChoice m) {
        switch (m) {
            case REDUCED:
                return R.string.motion_reduced;
            case OFF:
                return R.string.motion_off;
            default:
                return R.string.motion_full;
        }
    }

    private static boolean systemOff(Context ctx) {
        return Settings.Global.getFloat(ctx.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }
}
