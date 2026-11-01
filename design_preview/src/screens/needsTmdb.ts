import { account } from '../core/account';
import { app } from '../core/app';
import { h, replace } from '../core/dom';
import { featureOn } from '../core/settings';
import { icon } from '../ui/icons';
import { button } from '../ui/widgets';
import { launcherSettings } from './settings';

/**
 * F14 (the project lead, 2026-11-01): Movies, TV Shows and Search need a TMDB account. They show only this state while
 * Launcher settings › Features has TMDB account off, or while the TV isn't signed in. Nothing is fetched from TMDB; the
 * button opens Features or Account. Built like the offline state (06-states A).
 */
export const tmdbReady = () => featureOn('tmdb') && account.get() === 'in';

export function needsTmdb(page: string): { el: HTMLElement; paint(): void } {
  const el = h('div.state.needs-tmdb');
  const paint = () => {
    const off = !featureOn('tmdb');
    replace(
      el,
      h('div.state-icon', { html: icon('user', 48) }),
      h('div.empty-title', `${page} needs a TMDB account`),
      h(
        'div.empty-note.state-note',
        `Titles, artwork, your lists and search come from TMDB, a free film and TV database. ${
          off
            ? 'TMDB account is off in Launcher settings › Features: turn it on there, then sign in.'
            : 'Sign in once with your phone in Launcher settings › Account.'
        } Apps, inputs and the clock on Home work without it.`,
      ),
      h(
        'div.state-actions',
        button(off ? 'Open Features' : 'Sign in', {
          icon: off ? 'settings' : 'user',
          key: 'needs-tmdb',
          isDefault: true,
          onOk: () => app.push(launcherSettings(off ? 'features' : 'account', { enter: true })),
        }),
      ),
    );
  };
  paint();
  return { el, paint };
}
