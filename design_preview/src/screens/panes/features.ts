import { app, type Key } from '../../core/app';
import { h, type Child } from '../../core/dom';
import { featureOn, FEATURES, setFeature, settings, type FeatureId } from '../../core/settings';
import { button, segmented } from '../../ui/widgets';
import { placeholder } from '../placeholder';
import { launcherSettings, type SectionId } from '../settings';
import type { Pane, PaneHost } from './pane';

/** "6 of 6 on": the section's value. */
export const featuresValue = () => `${FEATURES.filter((f) => featureOn(f.id)).length} of ${FEATURES.length} on`;

/**
 * A Launcher settings section whose feature is off (F14, the project lead 2026-11-01): it stays in the list, reading
 * Off, and its pane says so and how to turn it on, with a button that opens Features.
 */
export function featureOffPane(id: FeatureId, section: string, openFeatures: () => void): Child {
  const f = FEATURES.find((x) => x.id === id)!;
  const needs = f.needs && !featureOn(f.needs) ? FEATURES.find((x) => x.id === f.needs)! : null;
  const open = button('Open Features', { icon: 'list', primary: true, key: 'open-features', onOk: openFeatures });
  open.dataset.entry = '';
  return [
    h('div.eyebrow', section),
    h('div.pane-title', `${f.label} is off`),
    h(
      'div.pane-sub',
      needs
        ? `${f.label} needs ${needs.label}, which is off. Turn on ${needs.label} in Launcher settings › Features, then ${f.label}.`
        : `This household has ${f.label} switched off, so it doesn’t run. Turn it on in Launcher settings › Features.`,
    ),
    h('div.pane-actions', open),
    h('div.pane-note', `Off: ${f.off}.`),
  ];
}

/**
 * Launcher settings › Features (F14, decided 2026-11-01; no Figma frame): one row per feature, each On or Off, laid
 * out like Performance. A feature switched off never starts: its pages, its settings section and its network calls
 * go. A row whose feature needs another that is off (Phone notes without TMDB account) says so and can't be changed.
 */
/**
 * What a feature turned on in First run's Features step opens next (F14, the project lead 2026-11-01): its own setup,
 * in the same cut-down split, with "Back to Features". Pick for me needs none. Prices' keys come from Builder B's F16
 * page (a phone scans a QR to enter the Watchmode and Movie of the Night keys, each checked with a test call); until it
 * exists this opens a stub. A TV without a TMDB key would also get F16's key page before Sign in.
 */
const SETUP: Partial<Record<FeatureId, SectionId>> = { tmdb: 'account', weather: 'weather', notes: 'notes', themes: 'theme' };

function openSetup(id: FeatureId) {
  const section = SETUP[id];
  if (section) {
    app.push(launcherSettings(section, { enter: true, setup: { steps: [section], onVisit: () => {}, backLabel: 'Back to Features' } }));
  } else if (id === 'prices') {
    app.push(
      placeholder(
        'Price keys',
        'Scan a QR with your phone to enter the Watchmode and Movie of the Night keys; each is checked with a test call. Builder B’s F16 page goes here.',
      ),
    );
  }
}

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
      host.setup
        ? 'Everything starts off. Turn on what this household wants; each one you turn on is set up next.'
        : 'Turn on what this household uses. A feature that is off never starts: no page and nothing fetched for it.',
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
              set: (v) => {
                const was = featureOn(f.id);
                setFeature(f.id, v === 'on');
                // First run (F14): a feature turned on is set up at once, and Back returns here.
                if (host.setup && v === 'on' && !was) openSetup(f.id);
              },
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
