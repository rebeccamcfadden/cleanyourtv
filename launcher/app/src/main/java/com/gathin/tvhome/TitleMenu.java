package com.gathin.tvhome;

import android.content.Context;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The hold-OK menu on a title card (design_preview/README.md, Title menu; F3, the project lead, 2026-11-01), wherever
 * a title card shows: Movies, TV Shows, Pick for me's results, Search, the person page and the title preview's More
 * like this. The app tile's menu ({@link TileMenu}) with Mark seen, Favorites, the lists and More like this, worded by
 * {@link TitleMenuRules} for the title's state. Seen, Favorites and My list change through {@link Household}, as the
 * title preview's buttons do, so the TMDB account hears of them ({@link Accounts}); the shown TMDB lists through
 * {@link Accounts#setOnShownList}. The lists row opens a second menu at the same card, with a tick on each list that
 * holds the title, where OK puts it on or takes it off and the menu stays; Back closes it.
 */
final class TitleMenu {
    private TitleMenu() {}

    /** Opens the menu when {@code card} is a title card ({@link Kit#titleCard}); returns whether it did. */
    static boolean open(Router router, View card) {
        Title t = Kit.titleOf(card);
        if (t == null) return false;
        Data data = router.data();
        Title known = data.catalog.get(t.id);
        Title title = known != null ? known : t;
        Household h = data.household;
        Shell shell = router.shell();
        Screen page = shell.top();
        Context ctx = card.getContext();
        List<TitleMenuRules.ListRow> lists = TitleMenuRules.lists(h, title, tmdb(data, title));
        List<MenuPanel.Row> rows = new ArrayList<>();
        for (TitleMenuRules.Row r : TitleMenuRules.rows(h.isSeen(title.id), h.isFavorite(title.id), lists)) {
            // Each row closes the menu itself, after the change, so the page drawn again on closing shows it.
            MenuPanel.Row row = new MenuPanel.Row(r.label, icon(r), () -> {
                switch (r.action) {
                    case SEEN: {
                        boolean now = h.toggleSeen(title.id);
                        done(router, card, title, ctx.getString(now ? R.string.preview_marked_seen
                                : R.string.preview_marked_unseen));
                        break;
                    }
                    case FAVORITE: {
                        boolean now = h.toggleFavorite(title.id);
                        done(router, card, title, ctx.getString(now ? R.string.preview_added_favorite
                                : R.string.preview_removed_favorite));
                        break;
                    }
                    case MY_LIST: {
                        boolean now = h.toggleMyList(title.id);
                        done(router, card, title, ctx.getString(now ? R.string.preview_added_my_list
                                : R.string.preview_removed_my_list));
                        break;
                    }
                    case LISTS: {
                        TileMenu.close(shell, card);
                        // The page may have drawn the card again on closing: hang the lists menu from it once it is
                        // laid out, unless something else opened meanwhile.
                        View at = anchor(shell, card, title);
                        afterLayout(at, () -> {
                            if (shell.top() == page && at.isAttachedToWindow()) openLists(router, page, at, title);
                        });
                        break;
                    }
                    default:
                        TileMenu.close(shell, card);
                        router.openMoreLike(title);
                        break;
                }
            }).stay();
            if (r.note != null) row.status(r.note, false);
            rows.add(row);
        }
        shell.push(TileMenu.create(shell, card, rows));
        return true;
    }

    /** Closes the menu after a change, brings the card's list line up to date in place, and says what changed. */
    private static void done(Router router, View card, Title t, String toast) {
        Shell shell = router.shell();
        TileMenu.close(shell, card);
        Kit.refreshTitleCard(anchor(shell, card, t), router.data());
        router.toast(toast);
    }

    /** The lists menu showing, if any: a late TMDB answer re-ticks it from the household, whichever menu sent it. */
    private static final class Lists {
        final Screen screen;
        final Title title;
        final View card;
        final List<ShownList> lists = new ArrayList<>();
        final List<MenuPanel.Row> rows = new ArrayList<>();

        Lists(Screen screen, Title title, View card) {
            this.screen = screen;
            this.title = title;
            this.card = card;
        }
    }

    private static Lists showing;

    /**
     * The lists menu: My list, then the shown TMDB lists the TV can change, each ticked when the title is on it. OK
     * puts the title on or takes it off, and the menu stays open; Back, or the D-pad past its edge, closes it. The
     * ticks are read from the household, which shows a change TMDB hasn't answered yet ({@link Accounts}), so a menu
     * opened again meanwhile shows it too. {@code page}: the page under the menus, drawn again when an answer comes.
     */
    private static void openLists(Router router, Screen page, View card, Title t) {
        Data data = router.data();
        Shell shell = router.shell();
        List<MenuPanel.Row> rows = new ArrayList<>();
        List<ShownList> lists = new ArrayList<>();
        for (TitleMenuRules.ListRow l : TitleMenuRules.lists(data.household, t, tmdb(data, t))) {
            lists.add(l.list);
            rows.add(new MenuPanel.Row(l.list.name, 0, () -> toggle(router, page, l.list, t)).tick(l.on).stay());
        }
        Screen menu = TileMenu.create(shell, card, rows);
        Lists open = new Lists(menu, t, card);
        open.lists.addAll(lists);
        open.rows.addAll(rows);
        showing = open;
        shell.push(menu);
    }

    private static void toggle(Router router, Screen page, ShownList list, Title t) {
        Data data = router.data();
        if (list.isMy()) {
            boolean now = data.household.toggleMyList(t.id);
            reconcile(router, page, t);
            router.toast(TitleMenuRules.listToast(list.name, now));
            return;
        }
        // On the TV at once; TMDB's answer follows, and a change it didn't take goes back as it was.
        boolean now = !data.household.isOn(t, list);
        boolean sent = data.accounts.setOnShownList(list.tmdbId, t.id, now, took -> {
            reconcile(router, page, t);
            router.toast(took ? TitleMenuRules.listToast(list.name, now) : TitleMenuRules.listFailed(list.name));
        });
        if (!sent) {
            router.toast(TitleMenuRules.listBusy(list.name));
            return;
        }
        reconcile(router, page, t);
    }

    /**
     * Brings what shows now up to date with the household after a list change, from whichever menu sent it: the lists
     * menu for t, if it is the one on top (its ticks, and its card's list line), else the page the menus were opened
     * over, if it is on top again (drawn again, as closing a menu does: a title can leave the list it shows).
     */
    private static void reconcile(Router router, Screen page, Title t) {
        Shell shell = router.shell();
        Data data = router.data();
        Screen top = shell.top();
        Lists open = showing;
        if (open != null && top == open.screen) {
            if (!open.title.id.equals(t.id)) return;
            for (int i = 0; i < open.rows.size(); i++) {
                MenuPanel.setTicked(open.rows.get(i), data.household.isOn(t, open.lists.get(i)));
            }
            Kit.refreshTitleCard(open.card, data);
            return;
        }
        if (page != null && top == page) {
            page.onShow();
            shell.refocus();
        }
    }

    /** Runs r once v has been laid out and any scroll to reveal it has started from there (a page drawn again). */
    private static void afterLayout(View v, Runnable r) {
        if (v.isLaidOut() && !v.isLayoutRequested()) {
            r.run();
            return;
        }
        v.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                v.post(r);
            }
        });
    }
    /** Whether the shown TMDB lists can take the title: signed in, and a TMDB title (not the mock catalogue's). */
    private static boolean tmdb(Data data, Title t) {
        return data.accounts.signedIn() && TmdbAccountApi.mediaType(t.id) != null;
    }

    /** The card for t now: the page may have drawn it again when the first menu closed. */
    private static View anchor(Shell shell, View card, Title t) {
        View now = shell.focused();
        Title on = Kit.titleOf(now);
        if (on != null && on.id.equals(t.id)) return now;
        return card;
    }

    private static int icon(TitleMenuRules.Row r) {
        switch (r.action) {
            case SEEN:
                return R.drawable.ic_ui_eye;
            case FAVORITE:
                return r.on ? R.drawable.ic_ui_heart_filled : R.drawable.ic_ui_heart;
            case MY_LIST:
            case LISTS:
                return r.on ? R.drawable.ic_ui_check : R.drawable.ic_ui_plus;
            default:
                return R.drawable.ic_ui_grid;
        }
    }
}
