# gedcom-shell daemon API, version 1

The daemon (`gedcom-shell daemon start`) serves this API on `http://127.0.0.1:7380/api/v1/`
by default. The web app, the desktop app and attached shells all use it. See
[daemon.md](daemon.md) for running the daemon and reaching it from other devices.

## Conventions

- **JSON** in and out, UTF-8. Request bodies are JSON objects (`Content-Type: application/json`).
- **Token.** Every call needs `Authorization: Bearer TOKEN`, even from the same computer.
  The token is in `~/.gedcom-shell/token`; `gedcom-shell daemon pair` prints an address that
  hands it to a browser (`/#pair=TOKEN`). Without it: `401`.
  Only `GET /api/v1/ping`, the web app's own files and *signed* addresses go without it.
- **Signed addresses.** Picture and media addresses in replies (`photo`, `photos[].url`, a
  media record's `file`) end in `?sig=…`. They work without the token, so they can go
  straight into `<img src>`. Use them as given.
- **Keys.** People, families and records are named by their key: the GEDCOM id without its
  `@`s (`I12`, `F3`, `S1`), as in the shell's paths. Lookups ignore case.
- **Files.** Each open GEDCOM file has an `id` (12 hex digits) made from its path, so it is the
  same every time that file is opened. Every per-file address starts `/api/v1/files/ID/`.
  Asking for the id of a file opened lately but not open now opens it again.
- **Errors.** `{"error": "message for people"}` with a status:
  `400` a malformed request, `401` no or wrong token, `404` no such thing,
  `409` a conflict (see *Editing*), `422` a refused edit (bad date, a family rule),
  `500` a bug (the edits held are not lost). Some errors carry more fields, named below.
- **Revision.** Each file has a `revision` that goes up with every change and every save.
  Replies that change something return the new one.
- **Dates and places** are strings as GEDCOM writes them (`12 JUN 1850`, `ABT 1850`,
  `BET 1800 AND 1810`). `born`/`died` in summaries are short forms (`1850`, `ABT 1850`).

## The daemon

| | |
|---|---|
| `GET ping` | `{"app":"gedcom-shell","api":1,"version":"…","pid":123}`, no token needed |
| `GET daemon` | `{app, api, version, instance, pid, started, bind, port, scheme, home, files}`; `instance` is new each time the daemon starts |
| `POST daemon/stop` `{"force":false}` | stops the daemon; `409` with `"dirty":[names]` if files have unsaved edits and `force` isn't set (with `force`, copies go to the recovery folder) |

## Files

`GET files` →
```json
{"files": [FILE, ...], "recent": [{"id","name","path","open","exists"}, ...]}
```

`POST files` `{"path": "/Users/me/family.ged"}` opens a file (absolute path; `~/` works) → `FILE`.
`422` if it can't be read.

`GET files/ID` → `FILE`:
```json
{"id": "a9a07dc6d84d", "name": "family.ged", "path": "/Users/me/family.ged",
 "gedcom": "5.5.1", "encoding": "UTF-8", "people": 41, "families": 13,
 "records": {"notes": 0, "sources": 2, "media": 5},
 "dirty": true, "dirtyCount": 2, "changedOnDisk": false, "revision": 7, "instance": "9f2c…",
 "home": "I1", "warnings": ["…"]}
```
`home` is the first person, a good place to start.

| | |
|---|---|
| `POST files/ID/save` `{"force":false, "as":null}` | writes the file → `{"saved":true,"records":2,"path":"…","revision":8}`. `409` with `"changedOnDisk":true` if another program changed the file since it was read; `409` with `"exists":true` if `as` names another existing file; `force` overrides both. `saved:false` when there was nothing to save. |
| `POST files/ID/close` `{"force":false}` | closes it; `409` if it has unsaved edits and no `force` |
| `POST files/ID/forget` | drops it from the recent list |
| `GET files/ID/changes?after=REV&wait=25` | waits up to `wait` seconds (max 60) for the revision to pass `REV` → `{"revision": N, "instance": "…"}`. Loop on it to follow changes from other front ends. Revisions start over when the daemon restarts: when `instance` differs from the one you had, reload everything. |

## People

`PERSON-SUMMARY`:
```json
{"key": "I1", "name": "Maria Rossi", "given": "Maria", "surname": "Rossi",
 "sex": "F", "born": "1850", "died": "~1920", "deceased": true,
 "lifespan": "1850–~1920", "photo": "/api/v1/files/ID/people/I1/photos/0?sig=…",
 "photoCrop": {"left": 10, "top": 0, "width": 200, "height": 260}|null}
```
`sex` is `M`, `F` or `U`. `born` and `died` are years, marked `~` (about, between), `<`
(before) or `>` (after), `?` for a date without a year; `died` is the death's, else the
burial's or cremation's. `lifespan` is `born–died`, `b. 1850` for the living, `1850–†` for
the dead with no date, `†` with neither, or `""`. `photo` is null when the person has none on this computer;
`photoCrop` is the part of it that shows this person, in the picture's pixels (a width or
height of 0 means "to the edge").

`GET files/ID/people?q=&sort=name&offset=0&limit=200` →
`{"total": 41, "offset": 0, "people": [PERSON-SUMMARY...], "revision": 7}`.
`q` matches names, other names and nicknames, accent- and case-blind, each word on its
own (`maria ros`); with `q`, each person also has `"matched"`: the other name or nickname
that matched, or `""`. `sort` is `name` (surname, then given), `born` (the undated last) or `file`.
`limit` is at most 5000.

`GET files/ID/people/KEY` → `PERSON`, a `PERSON-SUMMARY` plus:
```json
{"suffix": "", "otherNames": [{"name": "Mary Ross", "type": "aka"}], "nicknames": ["Mimi"],
 "events": [EVENT, ...],
 "parents": [{"family": "F1", "father": PERSON-SUMMARY|null, "mother": PERSON-SUMMARY|null,
              "siblings": [PERSON-SUMMARY...]}],
 "halfSiblings": [PERSON-SUMMARY...],
 "families": [{"family": "F2", "role": "wife", "partner": PERSON-SUMMARY|null,
               "married": EVENT|null, "divorced": false, "children": [PERSON-SUMMARY...]}],
 "notes": ["text…"],
 "photos": [{"url": "…?sig=…"|null, "file": "photos/maria.jpg", "title": "", "date": "",
             "crop": {"left":0,"top":0,"width":0,"height":0}|null}],
 "records": [{"key": "S1", "kind": "sources", "title": "Parish register"}],
 "fields": {"given": "Maria", "surname": "Rossi", "birth.date": "12 JUN 1850", ...},
 "present": {"birth": "", "graduation": "Técnico Químico"}}
```
`EVENT`: `{"tag":"BIRT","label":"Born","type":"","date":"12 JUN 1850","year":1850,"place":"Genova","value":""}`
(`value` is an attribute's text, as in `OCCU Sailor`, and `""` for a bare `1 DEAT Y`;
`year` is null without one).
`fields` holds every editable field's current value (see `GET fields`); edit forms start
from it and send it back as `seen`. Events come in date order, keeping the file's order
where the dates can't tell (an undated event stays after the one before it). `present` names the form rows (the part of a key before
the dot) the person has a record of, even an empty one such as a `GRAD` with only a `TYPE`,
each with that record's `TYPE` or `""`. A form shows those rows, and can label one by its
type. With several records of a tag, the form's fields are the first one's.

`GET files/ID/people/KEY/pedigree?depth=4` → the person with `father` and `mother`, each
the same shape, `depth` generations up (max 12).

`GET files/ID/people/KEY/descendants?depth=3` → the person with
`families: [{"family","partner","married":EVENT|null,"divorced","children":[same shape]}]`,
`depth` generations down.

`GET files/ID/people/KEY/photos/N?sig=…` → the picture's bytes (use the addresses given).

### Editing

`POST files/ID/people/KEY/edit`
```json
{"set": {"birth.date": "12 jun 1850", "occupation": "Sailor"},
 "seen": {"birth.date": "1850", "occupation": ""}}
```
→ `{"changes": [{"field","before","after"}], "person": PERSON, "revision": 8}`.

- `set` holds the fields to change (keys from `GET fields`, or the shell's aliases such as
  `born`). An empty value removes the fact. Values are checked and normalised as in the
  shell: dates become GEDCOM dates (`12 jun 1850` → `12 JUN 1850`).
- `seen` holds the values the form showed. A field in `set` whose value equals its `seen`
  value is left alone, so a form may send every field. A field that someone changed since
  (its current value is neither `seen` nor the new value) makes the whole edit fail with
  `409` and `"conflict": true`, plus the current `person`: nothing is applied. Leave a
  field out of `seen` to skip the check for it.
- `422` for a refused value, with a message such as
  `birth.date: 'sometime' is not a GEDCOM date; use 12 JUN 1850, …`.

`POST files/ID/families/KEY/edit` works the same way on family fields, returning `family`.

#### Everything else the pages show

Besides `set`, an edit can carry lists of changes to the person's events, notes and
further names, and their nicknames. Everything in one request is checked first and then
applied together, or nothing is: a `409` (changed elsewhere) or `422` (a refused value)
leaves the record as it was.

```json
{"events": [
   {"ref": "GRAD:0", "seen": {"date": ""}, "set": {"date": "dec 1997"}},
   {"ref": "RESI:2", "seen": {"date": "2005"}, "remove": true},
   {"add": "EVEN", "set": {"type": "First communion", "date": "oct 1988"}}],
 "notes": [
   {"ref": "NOTE:0", "seen": {"text": "Likes trains."}, "set": {"text": "Likes trains\nand boats."}},
   {"add": "", "set": {"text": "Moved in 2018."}}],
 "names": [
   {"ref": "NAME:1", "seen": {"given": "Iker"}, "set": {"given": "Iker A."}},
   {"add": "", "set": {"given": "Tomás", "surname": "Fábregas García", "type": "birth"}}],
 "nicknames": {"seen": ["Adri"], "set": ["Adri", "Fer"]}}
```

- **A `ref`** names a line of the record: `TAG:i` is the i-th (from 0) line tagged `TAG`,
  in file order. The person's `events`, `noteItems` and `otherNames` carry their `ref`.
  Refs are positions, so a reply's `person` has fresh ones: use those for the next edit.
- **An item** is changed (`ref`, `seen`, `set`), removed (`ref`, `seen`, `"remove": true`)
  or added (`"add"`, `set`). `seen` and `set` work as for fields, part by part: a part
  changed elsewhere since it was seen is a `409` naming it (`RESI:1.date is now '1982'
  (was '1981')`); a part left out of `seen` isn't checked. A removed or moved item is a
  `409` too.
- **Events** (`EVENT` gains `ref`, `fields` and `details`): parts `type`, `date`, `place`,
  `value`. `fields` holds their current values (`place` is the `PLAC` line). `value` is
  a fact's text (`OCCU Engineer`); an event with no date or place is kept as `TAG Y`.
  `add` is the tag: a person's events are those `EVENT` lists (`BIRT`, `RESI`, `GRAD`,
  `OCCU`, `EVEN`…), a family's `MARR`, `DIV`, `ENGA`, `MARB`, `MARC`, `MARL`, `MARS`,
  `ANUL`, `DIVF`, and either can have `EVEN`, `FACT`, `RESI` and `CENS`. `EVEN` and `FACT`
  need a `type`. `details: true` says the event holds more (notes, sources, an address),
  which removing it would lose: ask first.
- **Notes**: the person's `noteItems` are `[{"ref": "NOTE:0", "text": "…", "shared": null}]`.
  Part `text`, kept line by line; an emptied note is removed. A shared note
  (`"shared": "N1"`; in GEDCOM 7 files its ref may be `SNOTE:0`) is edited on its own record (below); here it can only be removed,
  which unlinks it.
- **Names**: `otherNames` items carry `ref` and `fields` (`given`, `surname`, `type`, such
  as `aka`, `birth`, `married`). Words written after the surname (`María /López/ de Pérez`)
  come at the end of `given` (`María de Pérez`) and stay after the surname when edited. The first name is the form's `given`/`surname`.
- **Nicknames**: the whole list, replacing the person's `nicknames`; `seen` is the list
  shown.

The `changes` list names these `RESI:1.date`, `+EVEN.type` (added), `-RESI:2` (removed),
`NOTE:0`, `nicknames`. A family's edit takes `events` and `notes` the same way, and a
family has `noteItems` too.

### Adding people and links

These run the shell's own `add`, `link` and `unlink`, with the same rules and messages.
Each returns `{"message": "what happened", "person": PERSON|null, "revision": N}`;
a refusal is `422` with the shell's explanation.

| | |
|---|---|
| `POST files/ID/people` `{"name":"Maria /Rossi/","sex":"F","fields":{"birth.date":"1850"}}` | a person linked to nobody |
| `POST files/ID/people/KEY/add` `{"relation":"child","name":"…","sex":"F","fields":{},"family":"F2"}` | a new relative of KEY: `relation` is `child`, `spouse`, `husband`, `wife`, `father`, `mother` or `sibling`. When KEY has several families, a child needs `family` (the `422` lists them). With `family`, `husband`/`wife` (or `spouse` with `sex`) fills that family's empty place instead of starting a new family. `person` is the new one; `message` is one line. |
| `POST files/ID/people/KEY/link` `{"person":"I12","as":"children"}` | makes an existing person a relative of KEY. `as` is `children`, `spouses`, `father`, `mother`, `siblings`, or `families/F3/children` (`/husband`, `/wife`). |
| `POST files/ID/people/KEY/unlink` `{"path":"father"}` | takes a relative out of the family that connects them: `father`, `mother`, `children/I3`, `spouses/I8`, `siblings/I5`, `families/F3`. Nobody is deleted; a family left connecting nobody goes. |

In `name`, the surname goes between slashes; without them the last word is the surname.

## Families

`FAMILY-SUMMARY`: `{"key":"F2","title":"Marco Rossi & Ana Pérez","married":"1875","children":3}`

`GET files/ID/families?q=&offset=0&limit=200` → `{"total","offset","families":[FAMILY-SUMMARY...]}`;
`q` matches the partners' names and the key, each word on its own.

`GET files/ID/families/KEY` → `FAMILY-SUMMARY` plus
`{"husband": PERSON-SUMMARY|null, "wife": …, "children": [PERSON-SUMMARY...], "events": [EVENT...],
"notes": [...], "records": [...], "fields": {"marriage.date": …, "divorced": "no"}}`.

## Notes, sources and media

`GET files/ID/records?kind=sources&q=` → `{"kind":"sources","records":[{"key","kind","title","usedBy"}]}`
(`kind` is `notes`, `sources` or `media`; `q` filters by title or key). Media records also
have `file` and `mime` (below), for thumbnails.

`GET files/ID/records/KEY` →
```json
{"key": "S1", "kind": "sources", "title": "Parish register", "usedBy": 2, "text": "",
 "fields": [{"label": "Title", "tag": "TITL", "value": "…", "depth": 0, "link": null, "linkKind": null,
             "ref": "0", "editable": true}],
 "users": [{"kind": "person", "key": "I1", "title": "Maria Rossi (1850–1920)", "person": PERSON-SUMMARY}],
 "file": "/api/v1/files/ID/media/M1/file?sig=…"|null, "mime": "image/jpeg"|null}
```
`text` is a note's text. `fields` is the record's structure, flattened: `depth` is the
nesting; a field pointing at another record has its `link` key and `linkKind`. `users` are
the people, families and records that point at it (`kind`: `person`, `family`, `notes`,
`sources`, `media`). `file` is set for a media record whose file is on this computer, with its `mime` type.
Repositories (`REPO`) and submitters aren't records here: a field pointing at one has
`link` set and `linkKind` null.

`POST files/ID/records/KEY/edit` changes a record's text and lines, checked and applied
as one, like a person's edit; it returns `{"changes", "record", "revision"}`.
```json
{"seenText": "Shared research note.", "text": "Shared research note,\nupdated.",
 "lines": [
   {"ref": "0", "seen": {"value": "Parish register"}, "set": {"value": "Parish register of Segovia"}},
   {"ref": "1", "seen": {"tag": "AUTH"}, "remove": true},
   {"add": "PUBL", "set": {"value": "Diocese of Segovia", "under": ""}}]}
```
- `text` is a note's text (`seenText` what was shown).
- A line's `ref` is its path: the indexes of the lines above it, from the record down
  (`2.0` is the first line under the third). Each field carries its `ref`.
- A line whose `editable` is true can have its `value` changed (a `DATE` line takes dates
  as forms do). Links to other records and change stamps (`CHAN`) aren't editable, though
  any line can be removed, with what lies under it; a removed link only unlinks.
- `add` puts a new line, `TAG` with `value`, last under `under` (a line's ref, or `""` for
  the record itself).

## Field definitions

`GET fields` →
```json
{"person": [{"key": "birth.date", "label": "Birth date", "group": "life", "kind": "date", "extra": false}, ...],
 "family": [{"key": "marriage.date", "label": "Marriage date", "kind": "date"}, ...],
 "dates": "12 JUN 1850, JUN 1850, 1850, ABT 1850, …"}
```
`group`: `name`, `life` (birth, baptism, death, burial, deceased), `events` (rarer events)
or `facts` (occupation, religion, residence, …). `kind`: `text`, `date`, `place`, `sex`
(`M`/`F`/`U`) or `yesno` (`yes`/`no`). `extra` fields are rarer: a form can show them only
when they have a value or are `present` on the person, with a way to add one.

## Shell sessions

What `gedcom-shell FILE` uses to run its shell in the daemon.

| | |
|---|---|
| `POST files/ID/shell` `{"color":true,"home":"I1"}` | → `{"session":"…","prompt":"…"}` |
| `POST shell/S/run` `{"line":"cd father; ls"}` | → `{"output","prompt","exit","ok"}`, or `{"output","edit":{"name","text"}}` when a command opens a form |
| `POST shell/S/edit` `{"text":"…"}` | the edited form (`null`: cancelled) → the next step, as `run` |
| `POST shell/S/complete` `{"line":"cd chi","cursor":6}` | → `{"start","word","candidates":[{"text","suffix","display","note"}]}` |
| `DELETE shell/S` | ends it (idle sessions end after 12 hours) |
