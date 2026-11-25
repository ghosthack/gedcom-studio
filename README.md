# gedcom-studio

A GEDCOM genealogy workspace: a shell, a web app (phone or browser) and a
desktop app, all editing the same files through one local daemon. It grew out
of [gedcom-shell](https://github.com/ghosthack/gedcom-shell), which stays the
simple terminal-only tool; the shell here is called `gedcom-shell` too.

Browse a GEDCOM genealogy file the way you browse a filesystem. People and
families are directories; a person's relatives are the entries inside.

```
$ bin/gedcom-shell royals.ged
Nicholas II I1 › ls
 #  PATH          ID   REL       NAME                           SEX  BORN  DIED
 1  father        I8   father    Alexander III                  M    1845  1894
 2  mother        I9   mother    Maria Feodorovna of Denmark    F    1847  1928
 3  spouses/I2    I2   wife      Alexandra Feodorovna of Hesse  F    1872  1918
 4  children/I3   I3   daughter  Olga Nikolaevna Romanova       F    1895  1918
 …
Nicholas II I1 › cd 4
Olga Nikolaevna Romanova I3 › tree -a
```

Requires JDK 25 and Maven 3.9+. The shell and daemon have no runtime
dependencies; the desktop app adds FlatLaf.

```
mvn package                         # builds core/target/gedcom-shell.jar and
                                    # desktop/target/gedcom-shell-desktop.jar, runs tests
bin/gedcom-shell FILE.ged            # interactive prompt (↑/↓ history, Tab completion, line editing)
bin/gedcom-shell FILE.ged -c 'find maria; show /found/1'
echo 'tree -a -n 5' | bin/gedcom-shell FILE.ged
bin/gedcom-shell web FILE.ged        # the web app, in your browser
bin/gedcom-desktop FILE.ged          # the desktop app
```

Options: `-c CMDS` run and exit · `-H ID` home person (default: first in
file) · `--no-color` (also honours `NO_COLOR`) · `-S` standalone (see below).
Exit status is 1 if any command failed, 2 for usage errors, which makes it
scriptable.

## Shell, web and desktop

Three front ends work on the same tree at once. A background **daemon** holds
the open files; the shell, the [web app](#the-web-app) and the
[desktop app](#the-desktop-app) are its clients, so an edit made in one shows in
the others straight away, and nothing reaches the file until one of them saves.

```
gedcom-shell FILE.ged ─┐
web app (any browser) ─┼─► gedcom-shell daemon ─► FILE.ged
desktop app ───────────┘     (127.0.0.1:7380)
```

- **It starts itself.** `gedcom-shell FILE.ged`, `gedcom-shell web` and the
  desktop app start the daemon when it isn't running. `gedcom-shell daemon
  status` shows it; `gedcom-shell daemon stop` stops it.
- **The shell runs in the daemon.** Your terminal sends each line and prints
  the result; `edit` still opens the form in your own `$EDITOR`. Leaving the
  shell keeps unsaved edits in the daemon for any front end to save.
- **Edits don't overwrite each other.** An edit carries the values its form
  showed. If a field changed elsewhere meanwhile, the edit is refused and the
  form reopens saying what changed; save it again to apply yours. Fields
  nobody else touched merge.
- **Nothing is lost.** The daemon won't stop with unsaved edits unless forced,
  and then (or on a signal) writes them to `~/.gedcom-shell/recovery/` first.
- **On your phone.** `tailscale serve --bg 7380` puts the web app on your
  tailnet over HTTPS; `gedcom-shell daemon pair` prints the address that pairs
  a device. Every request needs a token, even from this computer.
- **Standalone.** `gedcom-shell -S FILE.ged` (or `GEDCOM_SHELL_STANDALONE=1`)
  works without the daemon, as the shell always did. Scripts (`-c`, piped
  input) use the daemon only if it's already running.

[docs/daemon.md](docs/daemon.md) covers running it at login, remote access,
TLS and the security model; [docs/api.md](docs/api.md) is its HTTP API.

### The web app

`gedcom-shell web FILE.ged` opens it in your browser, already paired. It is
plain HTML, CSS and JavaScript served by the daemon, with no build step and
nothing fetched from elsewhere.

- Search people by any of their names; person pages with photos (cropped to
  the person), a timeline of events, parents, siblings, families and
  children, other names, notes, sources and media; family and record pages.
- Ancestors and descendants charts.
- Edit forms built from the same fields as `edit`, with the same date
  handling; add a child, partner, parent or sibling; link and unlink.
- Save from the top bar or with Ctrl/⌘-S. Changes made by the shell or the
  desktop app show up live.
- Light and dark, a phone layout with a bottom tab bar, and installable to a
  phone's home screen when served over HTTPS (Tailscale, see above).

To pair a phone, open the address `gedcom-shell daemon pair` prints there
(with your tailnet name). The address carries the token; once opened, the
app keeps it and drops it from the address bar.

### The desktop app

`bin/gedcom-desktop [FILE.ged]` runs `desktop/target/gedcom-shell-desktop.jar`,
a Swing app with FlatLaf's macOS look, light or dark as your system is. It
connects to the daemon (starting it from the same jar if needed), so it shows
the shell's and the browser's edits as they happen, and its edits stay safe in
the daemon if you quit without saving.

- A sidebar of people with search and sorting, plus families, notes,
  sources, media and warnings; back and forward through what you've viewed.
- Person and family pages, ancestors and descendants charts, record pages
  with media previews.
- Edit dialogs that point at a refused value and, when someone else changed
  a field meanwhile, show what changed and keep what you typed.
- Add, link and unlink relatives; save, save as, and open recent files.

## The prompt

| Key                     | Action                                   |
|-------------------------|------------------------------------------|
| ↑ / ↓ (Ctrl-P / Ctrl-N) | previous / next command                  |
| ← / → (Ctrl-B / Ctrl-F) | move; Ctrl/Alt + ← / → move by word      |
| Home / End (Ctrl-A / E) | start / end of line                      |
| Backspace / Delete      | delete before / under the cursor         |
| Ctrl-U / Ctrl-K / Ctrl-W| delete to start / to end / previous word |
| Tab                     | complete; again to list the choices      |
| Ctrl-L                  | clear the screen                         |
| Ctrl-C                  | discard the line                         |
| Ctrl-D                  | quit (on an empty line)                  |

Tab completes command names, options and their values (`ls --sort
su` → `surname`), and paths: `cd fa` → `cd father/`, and Tab after a `/`
lists what's inside, with who each entry is. A word that names no entry is
read as a name, as `cd` would: `cd olga` becomes `cd children/I3/` when Olga
is the only match, and when several people match it lists them.

History is kept in `~/.gedcom_shell_history` (last 1000 commands). Start a
command with a space to keep it out of history. The editor is built in: it
uses `stty` to switch the terminal to raw mode only while you type, so it
needs no libraries. Without `stty` (Windows) it falls back to the console's
own line input, which on Windows already recalls commands with ↑/↓.

## The filesystem

```
/
├── people/            I1, I2, …              every person, by GEDCOM id
│   └── I1/            father, mother          → the parent
│                      parents/ spouses/ children/ siblings/ families/
├── families/          F1, F2, …
│   └── F1/            husband, wife, children/
├── found/             results of the last `find`
└── notes/ sources/ media/   N1, S1, M1, …   shared notes, sources, pictures
    └── S1/            the people, families and records that use it
```

`father` and `mother` always come from one family: the first the person
belongs to by birth (`FAMC` with no `PEDI`, or `PEDI birth`), or, if no
birth family names a parent, the first adoptive or foster one. `parents/`
lists everyone from every family. `siblings/` lists full siblings, then
half-siblings (children of a parent's other families) as `half-brother` /
`half-sister`.

A path segment can be any of:

| Segment              | Meaning                                                    |
|----------------------|------------------------------------------------------------|
| `father`, `children` | an entry name                                              |
| `3`                  | row 3 of `ls` for that directory                           |
| `@I12@` or `I12`     | a record id, from anywhere (jumps to `/people/I12`, or `/sources/S3` for a source) |
| `maria`, `"jose p"`  | a unique name match, case- and accent-insensitive, on any of a person's names or nicknames; tries relatives first, then everyone |
| `..`                 | step back along the path you took (logical, like a shell)  |
| `~`, `/`, `-`        | home person, root, previous directory                      |

So `cd father/mother/children/2` walks the tree, and `cd ..` undoes the last
step rather than guessing which "parent" you meant. `pwd` shows the route;
`pwd -P` shows where you ended up.

## Commands

`help COMMAND` gives full usage for each command.

| Command | Synopsis | Notes |
|---|---|---|
| `ls` | `ls [-l] [-s KEY] [-r] [PATH...]` | At a person, lists all relatives with a cd-able `PATH`. Sort keys: `name surname id birth death birthday rel title`. Row numbers stay with their rows when sorted, so `cd 3` is unambiguous. Undated people sort last in either direction. `-l` adds full dates and places. |
| `cd` | `cd [PATH]` | No argument goes home. Ambiguous names list the candidates. |
| `pwd` | `pwd [-P]` | |
| `show` | `show [-r] [PATH...]` | A note's text, a source's fields (repository resolved) or a picture's file, and who uses it: `show @S3@`. For a person: nicknames and other names (married, birth, aka…), all facts in file order, parents/siblings, each marriage with children, notes (shared `@N@` notes resolved), age at death, last change. `-r` prints the raw GEDCOM record. `show /` summarises the file: encoding (and whether the header lied), counts, birth range, broken links. |
| `tree` | `tree [-a\|-d] [-n N\|--all] [-S] [PATH]` | Descendants by default, grouped under each partner (`∞`, with family id and marriage year); `-S` lists children directly. `-a` draws the pedigree with labels (father → grandfather → great-grandfather → 2nd great-grandfather …). Anyone reached twice (cousin marriages, pedigree collapse) is drawn once and then marked `↻`. |
| `find` | `find [OPTIONS] [NAME...]` | All NAME words must match, in any of the person's names or nicknames; a `MATCHED` column shows the name that did when it wasn't the main one. Filters: `-b/--born RANGE`, `-d/--died RANGE`, `-p/--place TEXT`, `-x/--sex M\|F\|U`, `--ancestors-of PATH`, `--descendants-of PATH`. RANGE: `1850`, `1800..1850`, `..1700`, `1900..`. Results go to `/found`. |
| `edit` | `edit [PATH] [FIELD=VALUE...]` | Change a person's name, sex, birth, baptism, death, burial, occupation and residence; or a family's marriage and divorce. See below. |
| `add` | `add RELATION NAME [FIELD=VALUE...]` | Create a person as a child, spouse, father, mother or sibling of where you are (or unlinked: `add person`). Takes `edit`'s fields. See below. |
| `link` | `link PERSON WHERE` | Make an existing person a child, partner, parent or sibling: `link I12 children`, `link I12 father`. See below. |
| `unlink` | `unlink PATH` | Take the person PATH ends with out of the family connecting you: `unlink children/I12`. Nobody is deleted. |
| `save` | `save [-f] [FILE]` | Write edits back. With FILE, writes a copy and leaves the original (and the unsaved state) alone; a FILE that already exists is replaced only with `save -f FILE`. If another program changed the file since it was opened, `save` refuses rather than lose that program's changes; `save -f` overwrites. |

Aliases: `ll` = `ls -l`, `cat` = `show`, `search` = `find`, `quit` = `exit`, `vi` = `edit`, `w` = `save`.
Separate commands with `;`. `#` starts a comment. Quote names that contain spaces.

## Editing

`edit` opens the person in `$VISUAL` / `$EDITOR` (default `vi`) as a short form:

```
given:           Alexei Nikolaevich
surname:         Romanov
suffix:
sex:             M

birth.date:      12 AUG 1904
birth.place:     Peterhof Palace, Russia

baptism.date:
baptism.place:

death.date:      17 JUL 1918
death.place:     Yekaterinburg, Russia
burial.date:
burial.place:
deceased:        yes

occupation:      Tsesarevich
residence.date:
residence.place: Alexander Palace, Tsarskoye Selo
```

Save and quit to apply. Quit without saving, or `:cq`, to cancel. If a value
is invalid (`31 FEB 1900`, `sex: X`), the form reopens as you left it, with
the error at the top. The same fields work without an editor, for scripts:

```
edit mother sex=F born="12 jun 1850" birthplace="Genova, Italia"
bin/gedcom-shell tree.ged -c 'edit @I12@ died="abt 1920" deathplace=Roma; save'
edit census.date=1881 census.place=London religion=Methodist
```

A family opens the same way, with its marriage and divorce (the partners
and children are listed at the top for reference):

```
edit /families/F4
edit @F2@ married="12 jun 1870" marriage.place="Genova, Italia"
edit @F2@ divorced=no divorce.date= divorce.place=
```

- **Dates are checked and normalised**: `12 june 1850` and `1850-06-12`
  become `12 JUN 1850`, and `circa 1850` becomes `ABT 1850`. `BEF`, `AFT`,
  `BET … AND …`, `FROM … TO …` and `(free text)` are also accepted. A date
  you leave unchanged is never rewritten, even if its existing form is
  unusual. A range that runs backwards (`BET 1810 AND 1800`) is refused.
  Dates are written in the file's GEDCOM version: 5.5.1 gets `B.C.` and
  calendar escapes (`@#DJULIAN@`), 7.0 gets `BCE` and keywords (`JULIAN`).
  GEDCOM 7 keeps free text in a `PHRASE` line, which `edit` can't write
  yet, so `(free text)` is refused there, as are `INT` and dual years
  (`1731/32`), which GEDCOM 7 doesn't have.
- **Julian, Hebrew and French Republican dates** (`@#DJULIAN@ 4 OCT 1582`,
  `HEBREW 1 TSH 5785`, `@#DFRENCH R@ 18 BRUM 8`, GEDCOM 5.5.1 escape or
  GEDCOM 7 keyword) are checked against their own months and shown as
  written, but sorted, filtered and aged by the Gregorian day they mean. A
  year or month alone counts as the Gregorian year or month holding its
  middle (Hebrew 5785 → 2025). Old Style dual years (`11 FEB 1731/32`) are
  read as Julian.
- **Death and deceased are kept consistent**: a death, burial or cremation
  date or place implies `deceased: yes`. `deceased: yes` with no details
  writes `1 DEAT Y`. `deceased: no` removes the death record (and a bare
  burial or cremation flag), but refuses if any of them has sources or
  notes, so nothing is lost silently.
- **Rarer events and facts** appear in the form only when the person has
  them; a comment lists the others, to add as new lines. Events take
  `NAME.date` and `NAME.place`: christening, adoption, confirmation,
  communion, barmitzvah, basmitzvah, graduation, ordination, emigration,
  immigration, naturalisation, census, retirement, will, probate,
  cremation. Facts take a value: education, religion, title, nationality.
  Each also answers to its verb or tag (`emigrated=1890`, `cens=1881`,
  `cremated.place=Woking`). Like occupation, they edit the first one
  recorded, and a fact with a date, place, sources or notes can be changed
  but not removed.
- **Baptism** is written as `BAPM`, unless the person's record already uses
  `CHR` (christening) for it; `christening` is a separate field only when
  the record has both. **Occupation** and **residence** edit the
  first one recorded; any others are left alone, and an occupation with
  a date, place, sources or notes can be changed but not removed.
  Divorce works the same way: a divorce date or place implies `divorced:
  yes`, which with no details writes `1 DIV Y`.
- **Edits stay in memory until `save`**. The prompt shows `*` while there
  are unsaved changes, and `exit` (or Ctrl-D) warns once before discarding them.
  In a script (`-c` or piped input) `exit` ends the run at once, with a
  warning if edits were left unsaved.
- **Saving produces a minimal diff.** Records you didn't edit are copied byte for
  byte, and inside an edited record only the changed lines are regenerated.
  The file keeps its encoding (UTF-8, ANSEL, Latin-1, …), line endings, BOM
  and permissions. The record's existing `CHAN` date is updated, as
  genealogy programs do (in UTC, marked `Z`, in a GEDCOM 7 file; in local
  time in 5.5.1, which can't mark it). A character the file's encoding can't hold is
  refused before anything is written. The write is atomic. Lines the
  parser could not read are kept where they were, and a file that mixes
  line endings keeps each line's own.

### Linking people

`link PERSON WHERE` puts someone already in the file into a family, as
seen from where you are; `unlink PATH` takes them out again. Both sides of
each link are written together: the family's `HUSB`/`WIFE`/`CHIL` line and
the person's `FAMS`/`FAMC` line.

```
link I12 children              a child of the current person
link I12 spouses               a partner: a new family for the two of you
link I12 father                the father (or mother, parents)
link I12 siblings              a child of your parents' family
link I12 families/F3/children  when you have several families, say which
unlink children/I12            I12 is no longer your child
unlink father                  the father leaves your parents' family
```

- **Families are created when needed.** A first child, a new partner, or a
  parent for someone who has none starts a new family, numbered after the
  file's own ids (`F12`, or `@P…@`-style if that's what the file uses). It is
  written just before the trailer.
- **`unlink` cuts the last person in the path loose.** `unlink father`
  takes him out of your parents' family, so he is no longer the father of
  your siblings either. To detach only yourself, unlink from his side:
  `cd father; unlink children/I9`.
- **Families that connect nobody are deleted.** Once a family has one
  member or none and records nothing else (no marriage date, note or
  source), it is removed, along with the last member's pointer to it.
- `link` refuses what it can't do cleanly: a slot that's taken ("already
  has a father"), a man linked as a mother, a link that already exists.

### Adding people

`add` creates a person and links them in one step, with any of `edit`'s
fields:

```
add child "Maria Rossi" sex=F born="12 jun 1850"
add father "Giovanni /Rossi/" birthplace=Genova
add spouse "Juan /Pérez García/" sex=M
add families/F3/children Bea
add person "Ana Ruiz"                 not linked to anyone yet
```

- **Surnames go between slashes**, as in GEDCOM. Without slashes the last
  word is taken as the surname, so a compound one needs them:
  `"Juan /Pérez García/"`. The confirmation shows how the name was split.
- Fathers are recorded as male and mothers as female unless `sex=` says
  otherwise. Where the person goes follows `link`'s rules, so a new
  family is started when needed.
- **All or nothing.** If the name, a field or the link is refused, no
  record is created and the next id stays free.
- New records are numbered after the file's own ids and written just
  before the trailer.

### Changes from the JS navigator

- `tree -a` replaces both `tree N ancestors` and `ancestors`. `ls -s birthday`
  replaces `ls -b`.
- `parents`, `children`, `siblings` and `spouses` are directories now:
  `ls children`, `cd siblings/2`.
- `stats` is `show /`.
- `cd ..` is logical (it retraces your path) instead of "go back in history".
  History is `cd -`.

## Design

[docs/architecture.html](docs/architecture.html) is an illustrated overview: open it in a browser.

```
ged.gedcom   bytes → text → Node tree     encoding detection, ANSEL, CONT/CONC
ged.model    Node tree → Person/Family    Genealogy = indexes + relationship queries
ged.vfs      Location, FileSystem, Navigator   the directory view and path resolution
ged.shell    Shell, Tokenizer, Options, one class per command
ged.edit     PersonForm (form ⇄ fields ⇄ GEDCOM nodes), Editor ($EDITOR launcher)
ged.text     Style (ANSI, width-aware), Table
ged.store    Store: the lock and revision every front end goes through
ged.daemon   Server (HTTP, token, lock), Api, Views (JSON), Workspace, ShellSessions
ged.client   DaemonClient (find or start the daemon), RemoteShell (the attached terminal)
ged.json     Json: a small strict reader and writer
desktop/     ged.desktop: the Swing app, a client of the daemon
core/src/main/resources/ged/web/   the web app (plain HTML, CSS and JS)
```

- **Parse to a generic tree first.** The parser only knows the line grammar,
  and typed records are views over the nodes. Nothing is dropped, so
  `show -r` can print the original record. This also rules out the old
  "CHAN date overwrites BIRT date" bug, because nothing tracks a "current
  event" while reading.
- **The `CHAR` header is a hint, not the truth.** BOM → UTF-16 → strict UTF-8 →
  declared charset. For files labelled ANSEL that contain high bytes, a
  heuristic tells real ANSEL (a combining mark followed by a plausible base
  letter) from mislabelled Latin-1/Windows-1252. ANSEL marks are moved after
  their base letter and NFC-normalised, so `á` is one character.
- **GEDCOM 5.5, 5.5.1 and 7.0.** The version comes from `HEAD.GEDC.VERS`
  (`show /` reports it). For 7.0, `SNOTE` shared notes are resolved like
  `NOTE @N1@`, `EXID`s are shown as external ids, `@VOID@` pointers mean
  "no link", and edited lines are saved without `CONC`, which 7.0 removed.
- **A repeated id doesn't hide a record.** When two people, families,
  notes, sources or pictures have the same id, the second is read under the
  next free one (`show /` says which, and where both are), so it can be
  opened. The file keeps the record as it is until you edit it; then it is
  saved under the new id. Pointers to the shared id mean the first record.
  For other records (repositories, submitters) the second is ignored, with
  a warning giving both lines. A person and a family may share an id: a
  pointer's place says which it means.
- **Entries and rows.** `FileSystem.entries()` holds the named children a path
  walks through. `FileSystem.rows()` holds what `ls` prints and what row
  numbers index into; for a person, that's the flattened relatives.
- **One code path for relationships.** `Genealogy` answers parents, spouses,
  children, siblings, ancestors and descendants. Ancestor and descendant
  walks are cycle-safe, since malformed files can make someone their own
  ancestor.
- **One door to the records.** Every front end reaches a file through its
  `Store`: shell commands run holding its write lock, API reads share its read
  lock. Its revision tells a shell to re-point its paths and the apps to
  refresh. While a form is out in an editor the lock is let go, and the edit
  is then checked against what the form showed (`Records.againstSeen`).
- **The apps reuse the shell's rules.** The API's add, link and unlink run the
  shell's own commands (`Headless`), so the family rules (when a family is
  made or removed, which links are refused) live in one place.
- **Zero dependencies.** The prompt's line editor (`LineEditor`, ~250 lines)
  replaces what JLine would provide. Its key handling is separate from the
  terminal setup, so it's unit-tested with byte streams. Style and tables
  work out display width themselves, so accented and CJK names line up.

### Tests

- `mvn package` runs them all. The daemon's tests (`DaemonTest`) talk to it
  over real HTTP.
- **Desktop UI tests** (`UiTest`, tagged `ui`) build the real windows (the edit
  form, the person page) against a real daemon and check what they hold.
  They're never shown, but they need a display: on Linux without one, run
  `xvfb-run mvn package`, as CI does. `-DskipUiTests` skips them.
- **Web app browser tests** (`web-tests/`, Playwright) drive the web app in
  Chromium, at desktop and phone sizes, against a daemon started from the
  built jar. After `mvn package`:
  ```
  cd web-tests && npm ci && npx playwright install chromium && npx playwright test
  ```

## Adding commands

Another jar can add commands without changing this one. Implement
`ged.shell.Command`, give the class a public no-argument constructor, and
name it in `META-INF/services/ged.shell.Command`:

```
com.example.MyCommand
```

Put that jar on the classpath beside `gedcom-shell.jar` and run `ged.Main`;
the command then shows up in `help`, runs, and is tab-completed like the
built-in ones. A command can't take a built-in's name. `Context` gives it
the navigator, the genealogy, the output stream and the style; `Options`
parses its flags. Commands run in the daemon, which starts with the class
path of the shell that started it: if it was already running without your
jar, `gedcom-shell daemon stop` it first (or run `-S`). A command runs
holding the store's write lock; one that waits on the user should do so
inside `ctx.store().outside(...)`.

The genealogy also reads what no built-in command shows yet, for commands
that want it. `Genealogy.photos(person)` lists a person's pictures (`OBJE`
links, pointer or embedded; one per link, taking the image among a
record's several `FILE`s), preferred one first, each with its file,
title, date and the crop that shows that person; `Genealogy.mediaPath(file)`
turns a picture's `FILE` value into a path on disk, relative to the GEDCOM
file.

As a Maven dependency (after `mvn install` here):

```xml
<dependency>
  <groupId>com.github.ghosthack</groupId>
  <artifactId>gedcom-shell</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Not (yet) supported

Editing an event or fact other than the first of its kind, custom `EVEN`
and `FACT` records, and family events beyond marriage and divorce; and
export/convert from the JS version.

The web and desktop apps share those limits, and add their own: repositories
and submitters are shown only as text where a source names them, and the
daemon is for one person on their own computers (one token, no accounts),
reachable from elsewhere through Tailscale or your own TLS certificate.
Phones need the daemon to be reachable; there is no offline copy.
