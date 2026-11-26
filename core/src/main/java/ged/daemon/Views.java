package ged.daemon;

import ged.edit.FamilyForm;
import ged.edit.PersonForm;
import ged.edit.RecordEdits;
import ged.gedcom.Node;
import ged.model.Event;
import ged.model.Family;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;
import ged.model.RecordKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static ged.json.Json.obj;

/// The JSON shapes of the API (see `docs/api.md`). Each method reads the
/// genealogy, so call them inside [ged.store.Store#read].
///
/// People, families and records are named by their `key`: the id without
/// its `@`s (`I12`, `F3`, `S1`), as in the shell's paths.
final class Views {
    private final Genealogy g;
    /// The file's address in the API: `/api/v1/files/ID`.
    private final String base;
    /// Signs an address, so an `<img>` can load a picture without the token.
    private final Function<String, String> sign;

    Views(Genealogy g, String base, Function<String, String> sign) {
        this.g = g;
        this.base = base;
        this.sign = sign;
    }

    private String photoUrl(String key, int n) {
        return sign.apply(base + "/people/" + key + "/photos/" + n);
    }

    // ---- people ------------------------------------------------------------

    Map<String, Object> summary(Person p) {
        var photos = g.photos(p);
        boolean shown = !photos.isEmpty() && g.mediaPath(photos.getFirst().file()).isPresent();
        var crop = shown ? photos.getFirst().crop().map(Views::crop).orElse(null) : null;
        return obj(
                "key", p.key(),
                "name", p.name().display(),
                "given", p.name().given(),
                "surname", p.name().surname(),
                "sex", p.sex() == Person.Sex.UNKNOWN ? "U" : p.sex().code,
                "born", p.birth().shortForm(),
                "died", p.death().shortForm(),
                "deceased", p.isDeceased(),
                "lifespan", p.lifespan().strip().replaceAll("^\\(|\\)$", ""),
                "photo", shown ? photoUrl(p.key(), 0) : null,
                "photoCrop", crop);
    }

    private static Map<String, Object> crop(ged.model.Photo.Crop c) {
        return obj("left", c.left(), "top", c.top(), "width", c.width(), "height", c.height());
    }

    Optional<Map<String, Object>> summary(Optional<String> id) {
        return id.flatMap(g::person).map(this::summary);
    }

    Map<String, Object> person(Person p) {
        var out = summary(p);
        out.put("suffix", p.name().suffix());
        out.put("otherNames", p.node().all("NAME").skip(1).map(n -> {
            var parts = RecordEdits.nameParts(n);
            return obj("name", ged.model.Name.of(n).display(), "type", parts.get("type"), "ref", RecordEdits.ref(p.node(), n),
                    "fields", parts);
        }).toList());
        out.put("nicknames", p.nicknames());
        out.put("events", chronological(p.events()).stream().map(e -> event(e, p.node())).toList());

        var parents = new ArrayList<Map<String, Object>>();
        for (var f : g.familiesAsChild(p)) {
            var siblings = f.children().stream().filter(c -> !c.equals(p.id())).flatMap(c -> g.person(c).stream()).map(this::summary).toList();
            parents.add(obj(
                    "family", f.key(),
                    "father", summary(f.husband()),
                    "mother", summary(f.wife()),
                    "siblings", siblings));
        }
        out.put("parents", parents);
        out.put("halfSiblings", g.halfSiblings(p).stream().map(this::summary).toList());

        var families = new ArrayList<Map<String, Object>>();
        for (var f : g.familiesAsSpouse(p)) {
            var partner = f.partnerOf(p.id());
            families.add(obj(
                    "family", f.key(),
                    "role", f.husband().filter(p.id()::equals).isPresent() ? "husband" : "wife",
                    "partner", summary(partner),
                    "married", f.event("MARR").map(this::event).orElse(null),
                    "divorced", f.event("DIV").isPresent(),
                    "hidden", f.hidden(),
                    "children", f.children().stream().flatMap(c -> g.person(c).stream()).map(this::summary).toList()));
        }
        out.put("families", families);
        out.put("notes", notes(p.node()));
        out.put("noteItems", noteItems(p.node()));
        var photos = new ArrayList<Map<String, Object>>();
        var list = g.photos(p);
        for (int i = 0; i < list.size(); i++) {
            var ph = list.get(i);
            boolean found = g.mediaPath(ph.file()).isPresent();
            photos.add(obj(
                    "url", found ? photoUrl(p.key(), i) : null,
                    "file", ph.file(),
                    "title", ph.title(),
                    "date", ph.date().toString(),
                    "crop", ph.crop().map(Views::crop).orElse(null)));
        }
        out.put("photos", photos);
        out.put("records", cited(p.node()));
        out.put("fields", fields(PersonForm.values(p)));
        out.put("present", present(p));
        return out;
    }

    /// The form rows (`birth`, `graduation`, `occupation`) the person has a
    /// record of, each with that record's `TYPE` (`Técnico Químico`, or
    /// ""). Forms show these rows even with nothing in them yet, so a `GRAD`
    /// with only a `TYPE` can be given a date, and name the row by its type.
    /// The form edits the first record of each tag, so the type is the first's.
    private static Map<String, Object> present(Person p) {
        var out = new LinkedHashMap<String, Object>();
        for (var f : PersonForm.Field.values()) {
            if (f.tag() == null) continue;
            p.node().first(f.tag()).ifPresent(n -> {
                int dot = f.key.indexOf('.');
                out.putIfAbsent(dot > 0 ? f.key.substring(0, dot) : f.key,
                        n.first("TYPE").map(Node::value).orElse("").strip());
            });
        }
        return out;
    }

    /// Events in date order, as a timeline shows them. Files list them in any
    /// order (often grouped by kind: all residences, then the rest).
    ///
    /// - A less precise date ties with a more precise one it could be: `1979`
    ///   keeps its place in the file next to `14 MAR 1979`.
    /// - An undated event stays after the event before it in the file; an
    ///   undated birth stays first.
    ///
    /// Each dated event carries the undated ones after it; the runs are then
    /// insertion-sorted, which is stable and needs no total order.
    static List<Event> chronological(List<Event> events) {
        var runs = new ArrayList<List<Event>>();
        for (var e : events) {
            boolean dated = e.date().hasYear();
            if (runs.isEmpty() || dated || e.tag().equals("BIRT")) runs.add(new ArrayList<>());
            runs.getLast().add(e);
        }
        var sorted = new ArrayList<List<Event>>();
        for (var run : runs) {
            int at = sorted.size();
            var d = run.getFirst().date();
            if (!d.hasYear() && run.getFirst().tag().equals("BIRT")) at = 0;
            else if (d.hasYear()) while (at > 0 && after(sorted.get(at - 1).getFirst().date(), d)) at--;
            sorted.add(at, run);
        }
        return sorted.stream().flatMap(List::stream).toList();
    }

    /// Whether `a` is surely later than `b`; an undated `a` (a leading run) never is.
    private static boolean after(GedDate a, GedDate b) {
        if (!a.hasYear()) return false;
        if (a.astronomicalYear() != b.astronomicalYear()) return a.astronomicalYear() > b.astronomicalYear();
        if (a.month() == 0 || b.month() == 0 || a.month() == b.month()) {
            return a.month() != 0 && a.month() == b.month() && a.day() != 0 && b.day() != 0 && a.day() > b.day();
        }
        return a.month() > b.month();
    }

    /// An event of `rec`, with what an edit form needs: its `ref`, its
    /// editable `fields`, and whether it carries more (`details`: notes,
    /// sources, an address), which removing it would lose.
    Map<String, Object> event(Event e, Node rec) {
        var out = event(e);
        out.put("ref", RecordEdits.ref(rec, e.node()));
        out.put("fields", RecordEdits.eventParts(e.node()));
        out.put("details", RecordEdits.hasDetails(e.node()));
        return out;
    }

    /// The notes on a record, one by one for editing: a shared note
    /// (`1 NOTE @N1@`, GEDCOM 7's `1 SNOTE @N1@`) names its record in
    /// `shared` and is edited there.
    List<Map<String, Object>> noteItems(Node rec) {
        return rec.notes().map(n -> obj("ref", RecordEdits.ref(rec, n),
                "text", n.isPointer() ? g.noteText(n.value()) : n.value(),
                "shared", n.isPointer() ? Genealogy.key(n.value()) : null)).toList();
    }

    Map<String, Object> event(Event e) {
        return obj(
                "tag", e.tag(),
                "label", e.label(),
                "type", e.type(),
                "date", e.date().toString(),
                "year", e.date().hasYear() ? e.date().astronomicalYear() : null,
                "place", e.place(),
                // a bare `1 DEAT Y` says the event happened; its `Y` is no value to show
                "value", e.detail().equals("Y") ? "" : e.detail().strip());
    }

    /// The notes written on a record, shared ones (`@N1@`) read through.
    private List<String> notes(Node rec) {
        return rec.notes().map(n -> g.noteText(n.value())).filter(t -> !t.isBlank()).toList();
    }

    /// The notes, sources and media a record points at anywhere inside it.
    private List<Map<String, Object>> cited(Node rec) {
        var ids = new LinkedHashSet<String>();
        collect(rec, ids);
        var out = new ArrayList<Map<String, Object>>();
        for (var id : ids) {
            g.record(id).ifPresent(r -> RecordKind.of(r.tag()).ifPresent(k ->
                    out.add(obj("key", Genealogy.key(r.xref()), "kind", k.dirName, "title", title(r)))));
        }
        return out;
    }

    private static void collect(Node n, java.util.Set<String> into) {
        for (var c : n.children()) {
            if (c.isPointer() && !c.isVoid() && !List.of("FAMC", "FAMS", "HUSB", "WIFE", "CHIL").contains(c.tag())) into.add(c.value());
            collect(c, into);
        }
    }

    private static String title(Node r) {
        var t = Genealogy.recordTitle(r);
        return t.isBlank() ? Genealogy.key(r.xref()) : t;
    }

    private static Map<String, Object> fields(Map<?, String> values) {
        var out = new LinkedHashMap<String, Object>();
        values.forEach((f, v) -> out.put(f instanceof PersonForm.Field pf ? pf.key : ((FamilyForm.Field) f).key, v));
        return out;
    }

    // ---- families ----------------------------------------------------------

    String familyTitle(Family f) {
        var h = f.husband().flatMap(g::person).map(p -> p.name().display());
        var w = f.wife().flatMap(g::person).map(p -> p.name().display());
        return Stream.of(h, w).flatMap(Optional::stream).reduce((a, b) -> a + " & " + b).orElse("(no partners)");
    }

    Map<String, Object> familySummary(Family f) {
        var marr = f.event("MARR").map(Event::date).orElse(GedDate.EMPTY);
        return obj("key", f.key(), "title", familyTitle(f), "married", marr.shortForm(), "children", f.children().size());
    }

    Map<String, Object> family(Family f) {
        var out = familySummary(f);
        out.put("husband", summary(f.husband()));
        out.put("wife", summary(f.wife()));
        out.put("children", f.children().stream().flatMap(c -> g.person(c).stream()).map(this::summary).toList());
        out.put("events", chronological(f.events()).stream().map(e -> event(e, f.node())).toList());
        out.put("notes", notes(f.node()));
        out.put("noteItems", noteItems(f.node()));
        out.put("records", cited(f.node()));
        out.put("fields", fields(FamilyForm.values(f)));
        return out;
    }

    // ---- notes, sources, media --------------------------------------------

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("TITL", "Title"), Map.entry("AUTH", "Author"), Map.entry("PUBL", "Publication"),
            Map.entry("ABBR", "Short title"), Map.entry("TEXT", "Text"), Map.entry("REPO", "Repository"),
            Map.entry("CALN", "Call number"), Map.entry("FILE", "File"), Map.entry("FORM", "Format"),
            Map.entry("MEDI", "Medium"), Map.entry("TYPE", "Type"), Map.entry("DATE", "Date"),
            Map.entry("PLAC", "Place"), Map.entry("DATA", "Data"), Map.entry("EVEN", "Events"),
            Map.entry("AGNC", "Agency"), Map.entry("REFN", "Reference"), Map.entry("LANG", "Language"),
            Map.entry("MIME", "Format"), Map.entry("SOUR", "Source"), Map.entry("OBJE", "Media"),
            Map.entry("PAGE", "Page"), Map.entry("QUAY", "Quality"), Map.entry("RIN", "Record number"),
            Map.entry("_UID", "Unique id"), Map.entry("UID", "Unique id"), Map.entry("NOTE", "Note"),
            Map.entry("CHAN", "Changed"));

    Map<String, Object> recordSummary(RecordKind kind, Node r) {
        var out = obj("key", Genealogy.key(r.xref()), "kind", kind.dirName, "title", title(r), "usedBy", g.usersOf(r).size());
        if (kind == RecordKind.MEDIA) {
            var file = r.all("FILE").filter(f -> !f.value().isBlank()).findFirst();
            boolean onDisk = file.isPresent() && g.mediaPath(file.get().value().strip()).isPresent();
            out.put("file", onDisk ? sign.apply(base + "/media/" + Genealogy.key(r.xref()) + "/file") : null);
            out.put("mime", onDisk ? Server.contentType(file.get().value().strip()).replaceFirst(";.*", "") : null);
        }
        return out;
    }

    Map<String, Object> record(RecordKind kind, Node r) {
        var out = recordSummary(kind, r);
        out.put("text", kind == RecordKind.NOTES ? r.value() : "");
        var fields = new ArrayList<Map<String, Object>>();
        for (var c : r.children()) field(fields, r, c, 0);
        out.put("fields", fields);
        var users = new ArrayList<Map<String, Object>>();
        for (var u : g.usersOf(r)) {
            switch (u.tag()) {
                case "INDI" -> g.person(u.xref()).ifPresent(p -> users.add(obj("kind", "person", "key", p.key(), "title", p.label(), "person", summary(p))));
                case "FAM" -> g.family(u.xref()).ifPresent(f -> users.add(obj("kind", "family", "key", f.key(), "title", familyTitle(f))));
                default -> RecordKind.of(u.tag()).ifPresent(k -> users.add(obj("kind", k.dirName, "key", Genealogy.key(u.xref()), "title", title(u))));
            }
        }
        out.put("users", users);
        if (kind != RecordKind.MEDIA) {
            out.put("file", null);
            out.put("mime", null);
        }
        return out;
    }

    private void field(List<Map<String, Object>> out, Node root, Node n, int depth) {
        var value = n.value();
        String link = null, linkKind = null;
        if (n.isPointer()) {
            var target = g.pointed(n.value());
            value = target.map(Views::title).orElse("(missing " + Genealogy.key(n.value()) + ")");
            if (target.isPresent()) {
                link = Genealogy.key(n.value());
                linkKind = RecordKind.of(target.get().tag()).map(k -> k.dirName).orElse(null);
            }
        }
        out.add(obj("label", LABELS.getOrDefault(n.tag(), n.tag()), "tag", n.tag(), "value", value, "depth", depth,
                "link", link, "linkKind", linkKind, "ref", RecordEdits.path(root, n), "editable", RecordEdits.editableLine(n)));
        for (var c : n.children()) field(out, root, c, depth + 1);
    }

    // ---- trees -------------------------------------------------------------

    /// `p` with their ancestors `up` generations deep.
    Map<String, Object> pedigree(Person p, int up) {
        var out = summary(p);
        if (up > 0) {
            out.put("father", g.father(p).map(f -> pedigree(f, up - 1)).orElse(null));
            out.put("mother", g.mother(p).map(m -> pedigree(m, up - 1)).orElse(null));
        }
        return out;
    }

    /// `p` with their partners' families and descendants `down` generations deep.
    Map<String, Object> descendants(Person p, int down, java.util.Set<String> seen) {
        var out = summary(p);
        if (!seen.add(p.id())) return out; // a loop in the file
        if (down > 0) {
            var families = new ArrayList<Map<String, Object>>();
            for (var f : g.familiesAsSpouse(p)) {
                families.add(obj(
                        "family", f.key(),
                        "partner", summary(f.partnerOf(p.id())),
                        "married", f.event("MARR").map(this::event).orElse(null),
                        "divorced", f.event("DIV").isPresent(),
                        "hidden", f.hidden(),
                        "children", f.children().stream().flatMap(c -> g.person(c).stream())
                                .map(c -> descendants(c, down - 1, seen)).toList()));
            }
            out.put("families", families);
        }
        return out;
    }
}
