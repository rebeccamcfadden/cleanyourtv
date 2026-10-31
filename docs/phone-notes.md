# Phone notes: send a watch list from an iPhone to the TV

When to use this: you keep the titles you want to watch in a note on your phone (Apple Notes or Google Keep) and want
them on the TV's lists without typing them with the remote. Each person's note goes to their own TMDB list, which shows
as a chip in Movies and TV Shows.

What happens: you share the note to a **Send to TV** Shortcut. It posts the note's text to the TV over your home Wi-Fi.
The TV looks each new line up on TMDB and adds the title to your list. If the account has no list with your name yet,
the TV makes a private one. A small pill at the bottom of the TV says "Importing Rebecca · 3 of 12", then how many were
added. Lines that matched nothing are listed in **Launcher settings → Phone notes**, to fix in the note.

- **Only adds.** Deleting a line on the phone takes nothing off the TV. A title you take off on the TV isn't put back
  by the next send.
- **Ticked means watched.** A ticked checklist item (or a struck-through line) goes to Seen and comes off the list.
- **At home only.** The phone has to be on the same Wi-Fi as the TV, and the TV has to be on. Away from home the
  Shortcut says it can't reach the TV; send again later.

## On the TV, once

1. Sign in to TMDB: **Launcher settings → Account**. The lists live on the account.
2. **Launcher settings → Phone notes → On.** The pane shows the TV's **address** (`http://192.168.1.50:8765/notes`,
   with your TV's own address) and a **pairing code** (`K7QM-4PXT`). Phone notes is off until it is turned on here
   (or by `note_inbox` in the TV's `config.xml` or `config.json`). "Not on Wi-Fi" in place of the address means the
   TV has no address on the home network yet; if another app on the TV holds port 8765, the pane says so.
   Beside them is a **QR code**: scan it with the iPhone camera and a setup page opens on the phone with the address
   and the header value ready to copy into the Shortcut (below).
3. In your router, give the TV a fixed address (a "DHCP reservation"), so the address in the Shortcut keeps working.

**New code** makes a new pairing code; each phone's Shortcut then needs the new one.

## On each iPhone, once

Scan the QR in the TV's Phone notes pane first: the page it opens has the address and the `Authorization` value to
copy, so nothing needs typing. Then, in the **Shortcuts** app, tap **+** for a new shortcut and name it **Send to TV**.

1. Tap the **ⓘ** (Details) and turn on **Show in Share Sheet**. Under the share sheet types, keep **Text** and
   **Notes** ticked (or Any).
2. Add **Get Text from Input** (from *Shortcut Input*).
3. Add **Text** and type your list's name, as it should show on the TV: `Rebecca`.
4. Add **Get Contents of URL**:
   - URL: the address from the TV's Phone notes pane, e.g. `http://192.168.1.50:8765/notes`
   - Method: **POST**
   - Headers: `Authorization` = `Bearer K7QM-4PXT` (your TV's code)
   - Request Body: **JSON**, with two fields:
     - `list` (Text) = the **Text** from step 3
     - `text` (Text) = the **Text** from step 2
5. Add **Get Dictionary Value**: Get **Value** for `message` in *Contents of URL*.
6. Add **Show Notification** (or **Show Result**) with the *Dictionary Value*.

The first time it runs, iOS asks whether Shortcuts may find devices on your local network: **Allow**.

## Sending a note

- **Apple Notes:** open the note, tap **Share** (or **⋯ → Send a Copy**) and pick **Send to TV**.
- **Google Keep:** open the note, tap **⋮ → Send → Send via other apps** and pick **Send to TV**.

The phone shows what the TV said, e.g. "Sent: 12 lines, 3 new". Send again whenever you add titles; only the new
lines are looked up.

## Writing the note

One title a line. The TV ignores the note's own title line, blank lines and headings, and takes off checkboxes,
bullets and numbers.

| Write | Means |
|---|---|
| `Dune` | The movie or show called exactly that; the more popular one when both exist |
| `Dune (1984)` | From that year (or the next) |
| `Fargo (tv)`, `Fargo (series)`, `Andor S2`, `The Bear - season 3` | A show |
| `Fargo (movie)` | A movie |
| `Movies:` or `Shows:` on a line of its own | The lines under it are movies, or shows |
| A themoviedb.org link | Exactly that title |
| ☑ / `[x]` / ✓ / ~~struck through~~ | Watched: goes to Seen |

The TV doesn't guess: "Dune" never becomes "Dune: Part Two". If a line didn't match, change it in the note (add the
year, or paste the title's themoviedb.org link) and send again; a changed line is looked up again.

## Safety

- The TV listens only while Phone notes is **On**, only for phones on the home network (private addresses), and only
  for `POST /notes` with the pairing code. After five wrong codes it stops listening for five minutes.
- The QR opens `GET /setup`, a fixed page with no code in it: the code rides in the QR's link after the `#`, which a
  phone keeps to itself and never sends to the TV.
- It is plain HTTP on your home network: someone on your Wi-Fi who sees the code could add titles to your lists, and
  nothing more. The code never leaves the TV and the Shortcuts; don't share screenshots of it.
- The TV keeps each line's text and what it matched, so it doesn't look the same line up twice, and the latest note
  of each list until its titles are on the list (a note sent while the TV is signed out or offline waits there), and
  nothing else from the note.

## When it fails

| The phone says | Do this |
|---|---|
| "Could not connect to the server" | The phone isn't on the home Wi-Fi, the TV is off or asleep, Phone notes is off, or the TV's address changed (check the pane, then the router reservation). |
| "Wrong pairing code" | Copy the code from the TV's pane into the Shortcut's header again (`Bearer ` then the code). |
| "Too many wrong codes" | Wait five minutes. |
| "Say which list the note is for" | The Shortcut's `list` field is empty. |
| Nothing new appears on the TV | The lines were sent before (only new lines count), or the TV isn't signed in to TMDB yet: they wait until it is. |
| "The TV will add these once it's signed in to TMDB." | Sign in on the TV (**Launcher settings → Account**). The note waits on the TV and is added after the first sync. |
| "The TV found no titles in that note." | The note had only its title, headings or blank lines. |

On the TV, an amber pill that ends "waiting for the internet", "couldn't reach TMDB" or "waiting for TMDB sign-in"
means the note is kept and tried again after the next send or when the TV next reads its lists; nothing is lost.
