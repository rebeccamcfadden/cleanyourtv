import type { Key } from '../../core/app';
import { h } from '../../core/dom';
import { featureOn, FEATURES, setFeature, settings } from '../../core/settings';
import { segmented } from '../../ui/widgets';
import type { Pane, PaneHost } from './pane';

/** "6 of 6 on": the section's value. */
export const featuresValue = () => `${FEATURES.filter((f) => featureOn(f.id)).length} of ${FEATURES.length} on`;

/**
 * Launcher settings › Features (F14, decided 2026-11-01; no Figma frame): one row per feature, each On or Off, laid
 * out like Performance. A feature switched off never starts: its pages, its settings section and its network calls
 * go. A row whose feature needs another that is off (Phone notes without TMDB account) says so and can't be changed.
 */
export const featuresPane = (host: PaneHost): Pane => ({
  /** ↓ goes to the next row's control on its selected option; → stays within the control, as in Performance. */
  onKey(key: Key, focused: HTMLElement) {
    const at = FEATURES.findIndex((f) => focused.dataset.key?.startsWith(`seg-feat-${f.id}-`));
    if (at < 0) return false;
    if (key === 'right') {
      if (focused.dataset.key?.endsWith('-on')) host.focusKey(`seg-feat-${FEATURES[at].id}-off`);
      return true;
    }
    if (key === 'left' && focused.dataset.key?.endsWith('-off')) {
      host.focusKey(`seg-feat-${FEATURES[at].id}-on`);
      return true;
    }
    if (key !== 'down' && key !== 'up') return false;
    const step = key === 'down' ? 1 : -1;
    for (let i = at + step; i >= 0 && i < FEATURES.length; i += step) {
      const f = FEATURES[i];
      if (f.needs && !featureOn(f.needs)) continue;
      host.focusKey(`seg-feat-${f.id}-${settings.get().features[f.id] ? 'on' : 'off'}`);
      return true;
    }
    return key === 'down';
  },
  render: () => [
    h('div.eyebrow', 'Features'),
    h('div.pane-title', 'Features'),
    h(
      'div.pane-sub',
      'Turn off what this household doesn’t use. A feature that is off never starts: no page, no settings and nothing fetched for it.',
    ),
    h(
      'div.perf-rows',
      FEATURES.map((f, i) => {
        const blocked = !!f.needs && !featureOn(f.needs);
        const needs = blocked ? FEATURES.find((x) => x.id === f.needs)!.label : '';
        const on = settings.get().features[f.id];
        const control = blocked
          ? h('span.feat-blocked', `Off · needs ${needs}`)
          : segmented<'on' | 'off'>({
              options: [
                ['on', 'On'],
                ['off', 'Off'],
              ],
              get: () => (settings.get().features[f.id] ? 'on' : 'off'),
              set: (v) => setFeature(f.id, v === 'on'),
              group: `seg-feat-${f.id}`,
              small: true,
              entry: i === 0,
            });
        return h(
          'div.perf-row',
          h('div.perf-text', h('div.perf-title', f.label), h('div.perf-sub', on && !blocked ? f.sub : `Off: ${f.off}`)),
          h('div.perf-ctl', control),
        );
      }),
    ),
    h('div.pane-note', 'A family install sets these in household.json; a pushed config.json can set them too.'),
  ],
});
