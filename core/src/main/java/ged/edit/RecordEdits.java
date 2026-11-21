package ged.edit;

import ged.gedcom.Node;
import ged.model.Event;
import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/// Edits to the parts of a record the apps show beyond the fixed form
/// fields: each event (`RESI:2`, its type, date, place and value), the notes,
/// the other names and nicknames, and any line of a note, source or media
/// record.
///
/// Every edit names what it changes by a `ref` and carries what the editor
/// was shown (`seen`). All are checked before any is applied: a part changed
/// elsewhere meanwhile is a [Conflict] (as in [Records#againstSeen]), a bad
/// value an [IllegalArgumentException], and either way nothing is written.
public final class RecordEdits {
    private RecordEdits() {}

    /// What an edit changed, for the reply: `RESI:1.date`, `1981` → `MAR 1981`.
    public record Change(String field, String before, String after) {}

    /// Checked edits, ready to apply: nothing is written until [#apply()],
    /// so a caller can check several kinds of edit before applying any.
    public record Plan(List<Change> changes, Runnable writer) {
        public static final Plan NONE = new Plan(List.of(), () -> { });
        public boolean isEmpty() { return changes.isEmpty(); }
        public void apply() { writer.run(); }
    }

    /// One edit of a listed item: change parts of `ref`, add one, or remove `ref`.
    public sealed interface Op {
        record Edit(String ref, Map<String, String> seen, Map<String, String> set) implements Op {}
        record Add(String tag, Map<String, String> set) implements Op {}
        record Remove(String ref, Map<String, String> seen) implements Op {}
    }

    // ---- refs ------------------------------------------------------------------

    /// `TAG:i`: the i-th (from 0) line tagged TAG directly under `rec`.
    public static String ref(Node rec, Node child) {
        int i = 0;
        for (var c : rec.children()) {
            if (c == child) return child.tag() + ":" + i;
            if (c.tag().equals(child.tag())) i++;
        }
        throw new IllegalStateException(child.tag() + " is not under " + rec.tag());
    }

    private static final Pattern REF = Pattern.compile("([A-Z_][A-Z0-9_]*):(\\d{1,6})");

    private static Node resolve(Node rec, String ref, String what) {
        var m = REF.matcher(ref == null ? "" : ref);
        if (!m.matches()) throw new IllegalArgumentException("'" + ref + "' is not a " + what + " reference");
        int want = Integer.parseInt(m.group(2)), i = 0;
        for (var c : rec.children()) {
            if (!c.tag().equals(m.group(1))) continue;
            if (i++ == want) return c;
        }
        throw new Conflict(what + " " + ref + " was removed meanwhile; nothing was applied");
    }

    // ---- events ----------------------------------------------------------------

    public static final List<String> EVENT_PARTS = List.of("type", "date", "place", "value");

    /// Tags of the family events; the other event tags are a person's.
    public static final Set<String> FAMILY_EVENTS = Set.of("MARR", "DIV", "ENGA", "MARB", "MARC", "MARL", "MARS",
            "ANUL", "DIVF");
    /// Tags shared by people and families.
    private static final Set<String> EITHER = Set.of("EVEN", "FACT", "CENS", "RESI");
    /// Facts whose line value is the point (`1 OCCU Sailor`), not a "Y" flag.
    public static final Set<String> ATTRIBUTES = Set.of("OCCU", "EDUC", "RELI", "TITL", "NATI", "DSCR", "PROP",
            "NCHI", "NMR", "FACT");

    public static boolean personEvent(String tag) {
        return Event.isEventTag(tag) && (!FAMILY_EVENTS.contains(tag) || EITHER.contains(tag));
    }

    public static boolean familyEvent(String tag) {
        return FAMILY_EVENTS.contains(tag) || EITHER.contains(tag);
    }

    /// An event's editable parts, as the API shows them in `fields`. The
    /// place is the `PLAC` line (an address-only event shows its address,
    /// but edits write a `PLAC`).
    public static Map<String, String> eventParts(Node ev) {
        var out = new LinkedHashMap<String, String>();
        out.put("type", ev.text("TYPE"));
        out.put("date", ev.text("DATE"));
        out.put("place", ev.text("PLAC"));
        out.put("value", ev.value().equals("Y") ? "" : ev.value().strip());
        return out;
    }

    /// True if removing the event would lose more than its type, date, place
    /// and value: notes, sources, an address, an age...
    public static boolean hasDetails(Node ev) {
        return ev.children().stream().anyMatch(c -> !List.of("TYPE", "DATE", "PLAC").contains(c.tag()));
    }

    public static Plan personEvents(Genealogy g, Person p, List<Op> ops) {
        return events(g, p.node(), ops, RecordEdits::personEvent, tag -> PersonForm.create(p.node(), tag, ""));
    }

    public static Plan familyEvents(Genealogy g, Family f, List<Op> ops) {
        return events(g, f.node(), ops, RecordEdits::familyEvent, tag -> FamilyForm.create(f.node(), tag));
    }

    private static Plan events(Genealogy g, Node rec, List<Op> ops, java.util.function.Predicate<String> allowed,
                                       Function<String, Node> create) {
        boolean g7 = g.isGedcom7();
        var plan = new ArrayList<Runnable>();
        var changes = new ArrayList<Change>();
        var stale = new ArrayList<String>();
        for (var op : ops) {
            switch (op) {
                case Op.Edit e -> {
                    var ev = event(rec, e.ref(), allowed);
                    var now = eventParts(ev);
                    var wanted = parts(e.set(), now, e.ref(), g7, ev.tag());
                    var requested = checked(now, e.seen(), wanted, e.ref(), stale);
                    requiresType(ev.tag(), merged(now, requested), e.ref());
                    requested.forEach((k, v) -> changes.add(new Change(e.ref() + "." + k, now.get(k), v)));
                    plan.add(() -> setParts(ev, requested));
                }
                case Op.Add a -> {
                    var tag = a.tag() == null ? "" : a.tag().strip().toUpperCase(java.util.Locale.ROOT);
                    if (!allowed.test(tag)) throw new IllegalArgumentException("'" + a.tag() + "' is not an event that can be added here");
                    Map<String, String> empty = new LinkedHashMap<>();
                    EVENT_PARTS.forEach(k -> empty.put(k, ""));
                    var wanted = parts(a.set(), empty, tag, g7, tag);
                    wanted.values().removeIf(String::isEmpty);
                    requiresType(tag, merged(empty, wanted), tag);
                    wanted.forEach((k, v) -> changes.add(new Change("+" + tag + "." + k, "", v)));
                    if (wanted.isEmpty()) changes.add(new Change("+" + tag, "", "Y"));
                    plan.add(() -> setParts(create.apply(tag), wanted));
                }
                case Op.Remove r -> {
                    var ev = event(rec, r.ref(), allowed);
                    var now = eventParts(ev);
                    r.seen().forEach((k, v) -> {
                        if (now.containsKey(k) && !Records.clean(v).equals(now.get(k))) {
                            stale.add(r.ref() + "." + k + " is now " + shown(now.get(k)) + " (was " + shown(v) + ")");
                        }
                    });
                    changes.add(new Change("-" + r.ref(), ev.tag(), ""));
                    plan.add(() -> rec.remove(ev));
                }
            }
        }
        conflictIfStale(stale);
        return new Plan(changes, () -> plan.forEach(Runnable::run));
    }

    private static Node event(Node rec, String ref, java.util.function.Predicate<String> allowed) {
        var ev = resolve(rec, ref, "event");
        if (!allowed.test(ev.tag())) throw new IllegalArgumentException(ref + " is not an event");
        return ev;
    }

    /// The requested parts, cleaned (a date in GEDCOM form), unknown parts refused.
    private static Map<String, String> parts(Map<String, String> set, Map<String, String> now, String ref, boolean g7, String tag) {
        var out = new LinkedHashMap<String, String>();
        for (var e : set.entrySet()) {
            if (!EVENT_PARTS.contains(e.getKey())) throw new IllegalArgumentException("unknown event part '" + e.getKey() + "'");
            var v = Records.clean(e.getValue());
            if (e.getKey().equals("date")) v = Records.date(ref + ".date", v, now.get("date"), g7);
            if (e.getKey().equals("value") && !ATTRIBUTES.contains(tag) && v.equalsIgnoreCase("Y")) v = "";
            out.put(e.getKey(), v);
        }
        return out;
    }

    private static Map<String, String> merged(Map<String, String> now, Map<String, String> requested) {
        var m = new LinkedHashMap<>(now);
        m.putAll(requested);
        return m;
    }

    /// GEDCOM wants a `TYPE` on a generic event or fact: "First communion".
    private static void requiresType(String tag, Map<String, String> parts, String ref) {
        if ((tag.equals("EVEN") || tag.equals("FACT")) && parts.get("type").isEmpty()) {
            throw new IllegalArgumentException(ref + ": an " + (tag.equals("EVEN") ? "event" : "fact") + " needs a type, such as 'First communion'");
        }
    }

    /// `wanted` less what was typed back as shown; a part changed elsewhere since is noted as stale.
    private static Map<String, String> checked(Map<String, String> now, Map<String, String> seen, Map<String, String> wanted,
                                               String ref, List<String> stale) {
        var out = new LinkedHashMap<String, String>();
        for (var e : wanted.entrySet()) {
            var k = e.getKey();
            var was = seen.containsKey(k) ? Records.clean(seen.get(k)) : null;
            if (was != null && e.getValue().equals(was)) continue;
            var current = now.get(k);
            if (was != null && !current.equals(was) && !current.equals(e.getValue())) {
                stale.add(ref + "." + k + " is now " + shown(current) + " (was " + shown(was) + ")");
            }
            if (!current.equals(e.getValue())) out.put(k, e.getValue());
        }
        return out;
    }

    /// Writes parts in GEDCOM's order (`TYPE`, `DATE`, `PLAC`), keeping other sub-lines.
    private static void setParts(Node ev, Map<String, String> parts) {
        if (parts.containsKey("type")) line(ev, "TYPE", parts.get("type"), 0);
        if (parts.containsKey("date")) line(ev, "DATE", parts.get("date"), ev.first("TYPE").isPresent() ? 1 : 0);
        if (parts.containsKey("place")) {
            int at = (ev.first("TYPE").isPresent() ? 1 : 0) + (ev.first("DATE").isPresent() ? 1 : 0);
            line(ev, "PLAC", parts.get("place"), at);
        }
        if (parts.containsKey("value")) ev.setValue(parts.get("value"));
        // an event with nothing to say is still a fact: `1 DEAT Y`
        boolean said = ev.first("DATE").isPresent() || ev.first("PLAC").isPresent();
        if (!ATTRIBUTES.contains(ev.tag())) {
            if (!said && ev.value().isEmpty()) ev.setValue("Y");
            else if (said && ev.value().equals("Y")) ev.setValue("");
        }
    }

    private static void line(Node parent, String tag, String value, int index) {
        var existing = parent.first(tag);
        if (value.isEmpty()) existing.ifPresent(parent::remove);
        else if (existing.isPresent()) existing.get().setValue(value);
        else parent.insert(index, tag, value);
    }

    // ---- notes -----------------------------------------------------------------

    /// Edits of the notes written on a record (`1 NOTE text`). A shared note
    /// (`1 NOTE @N1@`) is only linked here: its text is edited on its own
    /// page, and removing it here unlinks it.
    public static Plan notes(Genealogy g, Node rec, List<Op> ops) {
        var plan = new ArrayList<Runnable>();
        var changes = new ArrayList<Change>();
        var stale = new ArrayList<String>();
        for (var op : ops) {
            switch (op) {
                case Op.Edit e -> {
                    var n = note(rec, e.ref());
                    if (n.isPointer()) throw new IllegalArgumentException(e.ref() + " is the shared note " + Genealogy.key(n.value()) + ": edit it on its own page");
                    var text = noteText(e.set().get("text"));
                    var was = e.seen().get("text");
                    if (was != null && noteText(was).equals(text)) continue;
                    if (was != null && !n.value().equals(noteText(was)) && !n.value().equals(text)) {
                        stale.add(e.ref() + " was changed meanwhile");
                    }
                    changes.add(new Change(e.ref(), n.value(), text));
                    plan.add(text.isEmpty() ? () -> rec.remove(n) : () -> n.setValue(text));
                }
                case Op.Add a -> {
                    var text = noteText(a.set().get("text"));
                    if (text.isEmpty()) continue;
                    changes.add(new Change("+NOTE", "", text));
                    plan.add(() -> rec.insertBefore(NOTE_BEFORE, "NOTE", text));
                }
                case Op.Remove r -> {
                    var n = note(rec, r.ref());
                    var was = r.seen().get("text");
                    if (was != null && !n.isPointer() && !n.value().equals(noteText(was))) stale.add(r.ref() + " was changed meanwhile");
                    changes.add(new Change("-" + r.ref(), n.value(), ""));
                    plan.add(() -> rec.remove(n));
                }
            }
        }
        conflictIfStale(stale);
        return new Plan(changes, () -> plan.forEach(Runnable::run));
    }

    private static final List<String> NOTE_BEFORE = List.of("SOUR", "OBJE", "REFN", "RIN", "EXID", "CHAN", "_UID");

    private static Node note(Node rec, String ref) {
        var n = resolve(rec, ref, "note");
        if (!n.tag().equals("NOTE") && !n.tag().equals("SNOTE")) throw new IllegalArgumentException(ref + " is not a note");
        return n;
    }

    /// A note's text, its lines kept, trailing spaces and blank edges dropped.
    static String noteText(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n').lines().map(String::stripTrailing)
                .collect(java.util.stream.Collectors.joining("\n")).strip();
    }

    // ---- other names and nicknames ---------------------------------------------

    public static final List<String> NAME_PARTS = List.of("given", "surname", "type");

    /// A further name's parts (`NAME:1`): given, surname and type (`aka`, `married`, `birth`...).
    public static Map<String, String> nameParts(Node name) {
        var v = name.value();
        int a = v.indexOf('/'), b = a < 0 ? -1 : v.indexOf('/', a + 1);
        var given = a < 0 ? v.strip() : (v.substring(0, a) + (b < 0 ? "" : " " + v.substring(b + 1))).strip().replaceAll("\\s+", " ");
        var surname = a < 0 ? "" : v.substring(a + 1, b < 0 ? v.length() : b).strip();
        var out = new LinkedHashMap<String, String>();
        out.put("given", given);
        out.put("surname", surname);
        out.put("type", name.text("TYPE"));
        return out;
    }

    /// Edits of the person's further names (`NAME:1` on; the first is the form's).
    public static Plan names(Genealogy g, Person p, List<Op> ops) {
        var rec = p.node();
        var plan = new ArrayList<Runnable>();
        var changes = new ArrayList<Change>();
        var stale = new ArrayList<String>();
        for (var op : ops) {
            switch (op) {
                case Op.Edit e -> {
                    var n = furtherName(rec, e.ref());
                    var now = nameParts(n);
                    var wanted = nameRequest(e.set());
                    var requested = checked(now, e.seen(), wanted, e.ref(), stale);
                    var after = merged(now, requested);
                    if (after.get("given").isEmpty() && after.get("surname").isEmpty()) {
                        throw new IllegalArgumentException(e.ref() + ": a name needs a given name or a surname (remove it instead)");
                    }
                    requested.forEach((k, v) -> changes.add(new Change(e.ref() + "." + k, now.get(k), v)));
                    plan.add(() -> setName(n, after, requested));
                }
                case Op.Add a -> {
                    var wanted = nameRequest(a.set());
                    var after = merged(Map.of("given", "", "surname", "", "type", ""), wanted);
                    if (after.get("given").isEmpty() && after.get("surname").isEmpty()) continue;
                    after.forEach((k, v) -> { if (!v.isEmpty()) changes.add(new Change("+NAME." + k, "", v)); });
                    plan.add(() -> setName(rec.insertAfter(List.of("NAME"), "NAME", ""), after, after));
                }
                case Op.Remove r -> {
                    var n = furtherName(rec, r.ref());
                    var now = nameParts(n);
                    r.seen().forEach((k, v) -> {
                        if (now.containsKey(k) && !Records.clean(v).equals(now.get(k))) stale.add(r.ref() + "." + k + " is now " + shown(now.get(k)));
                    });
                    changes.add(new Change("-" + r.ref(), n.value(), ""));
                    plan.add(() -> rec.remove(n));
                }
            }
        }
        conflictIfStale(stale);
        return new Plan(changes, () -> plan.forEach(Runnable::run));
    }

    private static Node furtherName(Node rec, String ref) {
        var n = resolve(rec, ref, "name");
        if (!n.tag().equals("NAME")) throw new IllegalArgumentException(ref + " is not a name");
        if (rec.first("NAME").orElse(null) == n) throw new IllegalArgumentException(ref + " is the main name: edit it with given and surname");
        return n;
    }

    private static Map<String, String> nameRequest(Map<String, String> set) {
        var out = new LinkedHashMap<String, String>();
        for (var e : set.entrySet()) {
            if (!NAME_PARTS.contains(e.getKey())) throw new IllegalArgumentException("unknown name part '" + e.getKey() + "'");
            var v = Records.clean(e.getValue());
            if (v.contains("/")) throw new IllegalArgumentException(e.getKey() + " can't contain '/'");
            out.put(e.getKey(), v);
        }
        return out;
    }

    private static void setName(Node n, Map<String, String> after, Map<String, String> changed) {
        var given = after.get("given");
        var surname = after.get("surname");
        if (changed.containsKey("given") || changed.containsKey("surname")) {
            // words written after the surname (`María /López/ de Pérez`) are shown at the end of
            // `given`; they go back after the surname while they still end it
            var v = n.value();
            int a = v.indexOf('/'), b = a < 0 ? -1 : v.indexOf('/', a + 1);
            var tail = b < 0 ? "" : v.substring(b + 1).strip().replaceAll("\\s+", " ");
            boolean keepTail = !tail.isEmpty() && !surname.isEmpty() && (given.equals(tail) || given.endsWith(" " + tail));
            var front = keepTail ? given.substring(0, given.length() - tail.length()).strip() : given;
            n.setValue(surname.isEmpty() ? given : (front + " /" + surname + "/" + (keepTail ? " " + tail : "")).strip());
        }
        if (changed.containsKey("given")) n.first("GIVN").ifPresent(x -> { if (given.isEmpty()) n.remove(x); else x.setValue(given); });
        if (changed.containsKey("surname")) n.first("SURN").ifPresent(x -> { if (surname.isEmpty()) n.remove(x); else x.setValue(surname); });
        if (changed.containsKey("type")) line(n, "TYPE", after.get("type"), 0);
    }

    /// Replaces the nicknames (`NICK` under the first name) with `wanted`.
    /// `seen` is the list the editor showed; another change since is a conflict.
    public static Plan nicknames(Person p, List<String> seen, List<String> wanted) {
        var rec = p.node();
        var now = p.nicknames();
        var want = wanted.stream().map(Records::clean).filter(s -> !s.isEmpty()).distinct().toList();
        if (want.equals(now)) return Plan.NONE;
        if (seen != null && !seen.stream().map(Records::clean).toList().equals(now)) {
            throw new Conflict("changed elsewhere meanwhile: nicknames are now " + (now.isEmpty() ? "empty" : "'" + String.join(", ", now) + "'") + "; nothing was applied");
        }
        var name = rec.first("NAME").orElseThrow(() -> new IllegalArgumentException("give a name before nicknames"));
        return new Plan(List.of(new Change("nicknames", String.join(", ", now), String.join(", ", want))), () -> {
            rec.all("NAME").forEach(n -> n.all("NICK").toList().forEach(n::remove));
            for (var nick : want) name.insertAfter(List.of("NPFX", "GIVN", "NICK"), "NICK", nick);
        });
    }

    // ---- lines of notes, sources and media -------------------------------------

    /// A line's place in a record: the indexes of its ancestors' children,
    /// from the record down (`3.1` is the second line under the fourth).
    public static String path(Node rec, Node line) {
        var out = new ArrayList<Integer>();
        if (!pathTo(rec, line, out)) throw new IllegalStateException("not in the record");
        return String.join(".", out.stream().map(String::valueOf).toList());
    }

    private static boolean pathTo(Node at, Node target, List<Integer> out) {
        var kids = at.children();
        for (int i = 0; i < kids.size(); i++) {
            out.add(i);
            if (kids.get(i) == target || pathTo(kids.get(i), target, out)) return true;
            out.removeLast();
        }
        return false;
    }

    private static final Pattern PATH = Pattern.compile("\\d{1,5}(\\.\\d{1,5}){0,8}");
    private static final Pattern TAG = Pattern.compile("_?[A-Z][A-Z0-9_]{0,30}");
    /// Lines whose value the reader works out, not the person editing.
    private static final Set<String> STRUCTURAL = Set.of("CHAN", "CONT", "CONC");

    private static Node line(Node rec, String path) {
        if (path == null || !PATH.matcher(path).matches()) throw new IllegalArgumentException("'" + path + "' is not a line reference");
        var n = rec;
        for (var i : path.split("\\.")) {
            int k = Integer.parseInt(i);
            if (k >= n.children().size()) throw new Conflict("line " + path + " was removed meanwhile; nothing was applied");
            n = n.children().get(k);
        }
        return n;
    }

    /// Whether the apps may change a line's value: not a link to a record and
    /// not a change stamp. (Links and stamps can still be removed.)
    public static boolean editableLine(Node n) {
        return !n.isPointer() && !STRUCTURAL.contains(n.tag()) && n.children().stream().noneMatch(c -> c.tag().equals("CHAN"));
    }

    /// Edits of a note, source or media record: its own text (a note's) and its lines.
    ///
    /// - `text`: the record's own value (a note's text), with what was seen;
    /// - an [Op.Edit] sets `value` of the line at `ref` (a path), an
    ///   [Op.Remove] removes it (with what lies under it), an [Op.Add]
    ///   adds `tag` with `value` under `set.under` (a path, or "" for the
    ///   record itself), last among its siblings.
    public static Plan lines(Genealogy g, Node rec, String seenText, String text, List<Op> ops) {
        var plan = new ArrayList<Runnable>();
        var changes = new ArrayList<Change>();
        var stale = new ArrayList<String>();
        if (text != null) {
            var t = noteText(text);
            if (seenText != null && !noteText(seenText).equals(rec.value()) && !rec.value().equals(t)) stale.add("the text was changed meanwhile");
            if (!t.equals(rec.value())) {
                if (rec.tag().equals("NOTE") && t.isEmpty()) throw new IllegalArgumentException("a note needs some text");
                changes.add(new Change("text", rec.value(), t));
                plan.add(() -> rec.setValue(t));
            }
        }
        // resolve every line before changing any, so paths mean what the editor saw
        for (var op : ops) {
            switch (op) {
                case Op.Edit e -> {
                    var n = line(rec, e.ref());
                    if (!editableLine(n)) throw new IllegalArgumentException("line " + e.ref() + " (" + n.tag() + ") can't be edited");
                    var v = n.tag().equals("TEXT") || n.tag().equals("NOTE") ? noteText(e.set().get("value")) : Records.clean(e.set().getOrDefault("value", ""));
                    if (n.tag().equals("DATE")) v = Records.date("line " + e.ref(), v, n.value(), g.isGedcom7());
                    var was = e.seen().get("value");
                    if (was != null && (n.tag().equals("TEXT") || n.tag().equals("NOTE") ? noteText(was) : Records.clean(was)).equals(v)) continue;
                    if (was != null && !n.value().equals(was) && !n.value().equals(v)) stale.add("line " + e.ref() + " (" + n.tag() + ") is now " + shown(n.value()) + " (was " + shown(was) + ")");
                    if (v.isEmpty() && n.children().isEmpty()) throw new IllegalArgumentException("line " + e.ref() + ": empty (remove it instead)");
                    changes.add(new Change(e.ref() + " " + n.tag(), n.value(), v));
                    var value = v;
                    plan.add(() -> n.setValue(value));
                }
                case Op.Remove r -> {
                    var n = line(rec, r.ref());
                    var was = r.seen().get("tag");
                    if (was != null && !was.equals(n.tag())) stale.add("line " + r.ref() + " is now " + n.tag() + " (was " + was + ")");
                    var parent = parentOf(rec, r.ref());
                    changes.add(new Change("-" + r.ref() + " " + n.tag(), n.value(), ""));
                    plan.add(() -> parent.remove(n));
                }
                case Op.Add a -> {
                    var tag = a.tag() == null ? "" : a.tag().strip().toUpperCase(java.util.Locale.ROOT);
                    if (!TAG.matcher(tag).matches() || STRUCTURAL.contains(tag)) throw new IllegalArgumentException("'" + a.tag() + "' is not a tag that can be added");
                    var under = a.set().getOrDefault("under", "");
                    var parent = under.isEmpty() ? rec : line(rec, under);
                    var v = Records.clean(a.set().getOrDefault("value", ""));
                    if (tag.equals("DATE")) v = Records.date(tag, v, "", g.isGedcom7());
                    if (v.isEmpty()) throw new IllegalArgumentException(tag + " needs a value");
                    if (v.startsWith("@")) throw new IllegalArgumentException("links to records can't be added here");
                    changes.add(new Change("+" + (under.isEmpty() ? "" : under + " ") + tag, "", v));
                    var value = v;
                    plan.add(() -> parent.insertBefore(List.of("CHAN"), tag, value));
                }
            }
        }
        conflictIfStale(stale);
        return new Plan(changes, () -> plan.forEach(Runnable::run));
    }

    private static Node parentOf(Node rec, String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? rec : line(rec, path.substring(0, dot));
    }

    // ---- applying --------------------------------------------------------------

    /// Applies checked plans to a person's record, stamps it, and returns the re-read person.
    public static Person apply(Genealogy g, Person p, List<Plan> plans) {
        plans.forEach(Plan::apply);
        Records.touch(p.node(), g.isGedcom7());
        return g.updated(p);
    }

    public static Family apply(Genealogy g, Family f, List<Plan> plans) {
        plans.forEach(Plan::apply);
        Records.touch(f.node(), g.isGedcom7());
        return g.updated(f);
    }

    public static void apply(Genealogy g, Node record, Plan plan) {
        plan.apply();
        Records.touch(record, g.isGedcom7());
        g.updated(record);
    }

    // ---- shared ----------------------------------------------------------------

    private static void conflictIfStale(List<String> stale) {
        if (!stale.isEmpty()) throw new Conflict("changed elsewhere meanwhile: " + String.join("; ", stale) + "; nothing was applied");
    }

    private static String shown(String v) { return v.isEmpty() ? "empty" : "'" + v + "'"; }
}
