package com.gathin.tvhome;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * All Apps, pushed from Home's Shortcuts (docs/rework.md, decided 2026-10-29): a plain working grid of every app with a
 * TV entry point, plus phone apps that have none, sorted by name. OK opens an app; hold-OK adds it to Home's row or
 * takes it off, or opens App info. Styled from the tokens until it is designed (BACKLOG.md, All Apps).
 */
final class AllAppsPage {
    private AllAppsPage() {}

    private static final int COLUMNS = 6;
    private static final float TILE_W = 264;
    private static final float TILE_H = TILE_W * 9 / 16;
    private static final int COLUMN_GAP = 20;
    private static final int ROW_GAP = 28;
    private static final int GRID_TOP = 262;
    /** Room above the first row for the focused tile's ring and scale-up. */
    private static final int ROOM = 16;
    private static final String GROUP = "all-apps";

    static Screen create(MainActivity activity, Shell shell, HomeApps apps) {
        FrameLayout page = StubPage.frame(activity);
        View back = StubPage.backButton(activity, shell);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        blp.topMargin = Ui.px(60 - StubPage.ROOM);
        blp.leftMargin = Ui.px(Ui.SAFE_X - StubPage.ROOM);
        page.addView(StubPage.roomy(back, Ui.px(66)), blp);

        List<HomeApps.App> all = apps.all();
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBaselineAligned(true);
        TextView title = Ui.line(activity, Ui.T_H1, Ui.TEXT_1, Ui.REGULAR);
        title.setText(R.string.all_apps);
        head.addView(title);
        TextView count = Ui.line(activity, Ui.T_SMALL, Ui.TEXT_3, Ui.REGULAR);
        count.setText(activity.getResources().getQuantityString(R.plurals.all_apps_count, all.size(), all.size()));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.px(20);
        head.addView(count, clp);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        hlp.topMargin = Ui.px(150);
        hlp.leftMargin = Ui.px(Ui.SAFE_X);
        page.addView(head, hlp);

        // The grid scrolls by its translation inside this window, which the page clips at the screen's bottom edge. The
        // grid is measured at its full height, so rows below the window are laid out and reachable with the D-pad.
        FrameLayout window = new FrameLayout(activity) {
            @Override
            protected void measureChildWithMargins(View child, int widthSpec, int widthUsed, int heightSpec,
                                                   int heightUsed) {
                super.measureChildWithMargins(child, widthSpec, widthUsed,
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightUsed);
            }
        };
        window.setClipChildren(false);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.px(Ui.STAGE_H - GRID_TOP + ROOM), Gravity.TOP);
        wlp.topMargin = Ui.px(GRID_TOP - ROOM);
        page.addView(window, wlp);
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.px(ROOM), 0, Ui.px(Ui.SAFE_Y));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        glp.leftMargin = Ui.px(Ui.SAFE_X - AppTile.RING);
        window.addView(grid, glp);

        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            HomeApps.App app = all.get(i);
            if (i % COLUMNS == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Ui.px(ROW_GAP);
                grid.addView(row, rlp);
            }
            AppTile t = new AppTile(activity, app.pkg, app.label, TILE_W, TILE_H, 12);
            Focus.make(t, GROUP, "aa-" + app.pkg, i == 0, v -> open(activity, shell, apps, app.pkg));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS > 0) tlp.leftMargin = Ui.px(COLUMN_GAP - 2 * AppTile.RING);
            row.addView(t, tlp);
        }

        return new Screen(activity.getString(R.string.all_apps), page, Screen.Layer.PAGE) {
            @Override
            View defaultFocus() {
                View first = Focus.byKey(grid, all.isEmpty() ? null : "aa-" + all.get(0).pkg);
                return first != null ? first : back;
            }

            @Override
            void onFocus(View v) {
                if (!(v instanceof AppTile) || !(v.getParent() instanceof View)) return;
                View r = (View) v.getParent();
                Runnable reveal = () -> {
                    float target = Reveal.offset(grid.getTranslationY(), grid.getTop() + r.getTop() + v.getTop(),
                            grid.getTop() + r.getTop() + v.getBottom(), Ui.px(ROOM), window.getHeight() - Ui.px(Ui.SAFE_Y));
                    if (target == grid.getTranslationY()) return;
                    grid.animate().translationY(target).setDuration(Ui.ms(240)).setInterpolator(Ui.EASE).start();
                };
                if (grid.isLaidOut() && !grid.isLayoutRequested()) reveal.run();
                else grid.post(reveal);
            }

            @Override
            boolean onLongOk(View focused) {
                if (!(focused instanceof AppTile)) return false;
                AppTile tile = (AppTile) focused;
                String pkg = tile.pkg;
                List<MenuPanel.Row> rows = new ArrayList<>();
                if (apps.onRow(pkg)) {
                    rows.add(new MenuPanel.Row(activity.getString(R.string.library_remove_shortcut), 0, () -> {
                        apps.save(AppRow.remove(apps.full(), pkg));
                        shell.toast(activity.getString(R.string.home_removed));
                    }));
                } else {
                    rows.add(new MenuPanel.Row(activity.getString(R.string.tile_add_to_home), 0, () -> {
                        apps.save(AppRow.add(apps.full(), pkg));
                        shell.toast(activity.getString(R.string.home_added));
                    }));
                }
                Intent info = SystemSettings.appInfo(activity, pkg);
                if (info != null) {
                    rows.add(new MenuPanel.Row(activity.getString(R.string.library_app_info), 0,
                            () -> activity.start(info)));
                }
                shell.push(TileMenu.create(shell, tile, rows));
                return true;
            }
        };
    }

    private static void open(MainActivity activity, Shell shell, HomeApps apps, String pkg) {
        Intent i = apps.launchIntent(pkg);
        if (i == null) {
            shell.toast(activity.getString(R.string.not_available));
            return;
        }
        LastOpened.record(activity, pkg);
        activity.start(i);
    }
}
