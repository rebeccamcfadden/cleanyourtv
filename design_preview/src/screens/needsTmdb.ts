import { app } from '../core/app';
import { h } from '../core/dom';
import { icon } from '../ui/icons';
import { button } from '../ui/widgets';
import { launcherSettings } from './settings';

/**
 * F14: what Movies, TV Shows and Search show while Launcher settings › Features has TMDB account off. Nothing is
 * fetched from TMDB; the button opens Features, where it can be turned on. Built like the offline state (06-states A).
 */
export function needsTmdb(page: string): HTMLElement {
  return h(
    'div.state.needs-tmdb',
    h('div.state-icon', { html: icon('user', 48) }),
    h('div.empty-title', `${page} needs a TMDB account`),
    h(
      'div.empty-note.state-note',
      'Titles, artwork, your lists and search come from TMDB, a free film and TV database. Turn on TMDB account in Launcher settings › Features, then sign in. Apps, inputs and the clock on Home work without it.',
    ),
    h(
      'div.state-actions',
      button('Open Features', {
        icon: 'settings',
        key: 'open-features',
        isDefault: true,
        onOk: () => app.push(launcherSettings('features', { enter: true })),
      }),
    ),
  );
}
