import { account } from './account';
import { store } from './store';
import { TITLES } from '../data/catalog';
import type { Title } from '../types';

// The household's lists as Movies, TV Shows and Pick for me show them: My list, then one chip per list. Signed in,
// those are the account's TMDB lists ticked "Show" in Account → Choose lists, by their TMDB names and in the account's
// order. Before any sign-in, the mock Household and Friends stand in; after a sign-out, only My list is left.

export interface ShownList {
  /** 'my', a TMDB list id, or the mock 'household' / 'friends'. */
  id: string;
  name: string;
  has: (t: Title) => boolean;
}

/** Which mock titles each TMDB list holds (the TV reads them from TMDB). */
const INDEX = new Map(TITLES.map((t, i) => [t.id, i]));
const MEMBERS: Record<string, (t: Title, i: number) => boolean> = {
  family: (t) => t.lists.includes('household'),
  tips: (t) => t.lists.includes('friends'),
  weekend: (t, i) => t.kind === 'movie' && i % 2 === 0,
  club: (t, i) => t.kind === 'movie' && i % 3 === 1,
  together: (_t, i) => i % 5 === 0,
};
const holds = (id: string) => (t: Title) => MEMBERS[id]?.(t, INDEX.get(t.id) ?? 0) ?? false;

const MY: ShownList = { id: 'my', name: 'My list', has: (t) => store.inMyList(t.id) };
const MOCK: ShownList[] = [
  { id: 'household', name: 'Household', has: (t) => t.lists.includes('household') },
  { id: 'friends', name: 'Friends', has: (t) => t.lists.includes('friends') },
];

export const lists = {
  /** The lists after My list, in chip order. */
  imported(): ShownList[] {
    if (account.get() === 'in') return account.lists().filter((l) => l.shown).map((l) => ({ id: l.id, name: l.name, has: holds(l.id) }));
    return account.everSignedIn() ? [] : MOCK;
  },
  /** My list, then {@link imported}. */
  all(): ShownList[] {
    return [MY, ...lists.imported()];
  },
  byId: (id: string) => lists.all().find((l) => l.id === id),
  /** On My list or any shown list. */
  saved: (t: Title) => lists.all().some((l) => l.has(t)),
  /** Titles on a TMDB list, shown or not, for Choose lists. */
  count: (id: string) => TITLES.filter(holds(id)).length,
  /** "My list, Family picks & Friends’ tips"; past four, "My list, Family picks & 4 more". */
  names(): string {
    const n = lists.all().map((l) => l.name);
    if (n.length > 4) return `${n[0]}, ${n[1]} & ${n.length - 2} more`;
    return n.length < 2 ? n.join('') : `${n.slice(0, -1).join(', ')} & ${n[n.length - 1]}`;
  },
  /** The card line's source: the first list in chip order holding the title, else the catalogue. */
  provenance(t: Title): string {
    return lists.all().find((l) => l.has(t))?.name ?? 'Catalogue';
  },
};
