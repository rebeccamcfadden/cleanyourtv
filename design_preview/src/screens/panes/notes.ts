import { app } from '../../core/app';
import { h } from '../../core/dom';
import { account } from '../../core/account';
import { INBOX_URL, notes } from '../../core/notes';
import { icon } from '../../ui/icons';
import { button, segmented } from '../../ui/widgets';
import { confirmDialog } from '../confirm';
import type { Pane, PaneHost } from './pane';

/**
 * Phone notes (no Figma design yet; drafted here from the Account pane's parts): turn the inbox on, read the
 * address and pairing code to type into the "Send to TV" Shortcut, and see each list's last send with the lines that
 * matched nothing. Off by default.
 */
export function notesPane(host: PaneHost): Pane {
  notes.listen(() => host.rerender());

  const onOff = segmented<'on' | 'off'>({
    options: [
      ['on', 'On'],
      ['off', 'Off'],
    ],
    get: () => (notes.on() ? 'on' : 'off'),
    set: (v) => notes.setOn(v === 'on'),
    group: 'notes-on',
    entry: true,
  });

  const sendRow = (s: ReturnType<typeof notes.sends>[number]) =>
    h(
      'div.notes-send',
      h(
        'div.notes-send-head',
        h('b', s.list),
        h('span', `${s.at} · ${s.lines} lines · ${s.added} added`),
        s.unmatched.length ? h('span.notes-warn', `${s.unmatched.length} not matched`) : h('span.notes-good', { html: icon('check', 20) }),
      ),
      s.unmatched.length
        ? h('div.notes-miss', s.unmatched.map((l) => h('span.notes-line', l)))
        : null,
    );

  return {
    render() {
      const on = notes.on();
      const signedIn = account.get() === 'in';
      return [
        h('div.eyebrow', 'Phone notes'),
        h('div.pane-title', 'Send lists from your phones'),
        h(
          'div.pane-sub',
          'Share a watch-list note to “Send to TV” on an iPhone. New lines are looked up and added to that person’s TMDB list; deleting a line on the phone takes nothing off.',
        ),
        h('div.pane-seg', onOff),
        on && !signedIn
          ? h('div.pane-note.notes-warn', 'Sign in to TMDB in Account first: the lists are on the account. Notes sent before then wait.')
          : null,
        on
          ? [
              h(
                'div.kvs',
                h('div.kv', h('span', 'Address'), h('b.notes-mono', INBOX_URL)),
                h('div.kv', h('span', 'Pairing code'), h('b.notes-code', notes.code())),
              ),
              h('div.pane-note.pane-note--tiny', 'Plain HTTP on your home network: the code only lets a phone add titles to your lists.'),
              h(
                'div.pane-actions',
                button('New code', {
                  icon: 'retry',
                  key: 'notes-code',
                  onOk: () =>
                    app.push(
                      confirmDialog({
                        title: 'Make a new pairing code?',
                        body: 'Phones with the old code can’t send until their Shortcut has the new one.',
                        actions: [{ label: 'New code', primary: true, onOk: notes.newCode }],
                      }),
                    ),
                }),
                button('How to set up a phone', {
                  icon: 'phone',
                  key: 'notes-help',
                  onOk: () => app.toast('docs/phone-notes.md: the “Send to TV” Shortcut, step by step'),
                }),
              ),
              h('div.pane-title.pane-title--small', 'Last sends'),
              notes.sends().length ? h('div.notes-sends', notes.sends().map(sendRow)) : h('div.pane-sub', 'Nothing sent yet.'),
            ]
          : h('div.pane-note', 'Off: the TV doesn’t listen for phones.'),
      ];
    },
  };
}
