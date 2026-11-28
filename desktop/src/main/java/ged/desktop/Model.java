package ged.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// The daemon's replies (see `docs/api.md`) as records, read from the maps
/// [ged.json.Json] parses. Readers are lenient: a missing field is empty, never
/// an exception, so a newer daemon with extra fields still works.
final class Model {

    // ---- reading JSON maps ------------------------------------------------------

    static String str(Map<String, ?> m, String key) {
        var v = m == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static long num(Map<String, ?> m, String key) {
        return m != null && m.get(key) instanceof Number n ? n.longValue() : 0;
    }

    static Integer intOrNull(Map<String, ?> m, String key) {
        return m != null && m.get(key) instanceof Number n ? n.intValue() : null;
    }

    static boolean bool(Map<String, ?> m, String key) {
        return m != null && Boolean.TRUE.equals(m.get(key));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, ?> m, String key) {
        return m != null && m.get(key) instanceof Map<?, ?> x ? (Map<String, Object>) x : null;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> list(Map<String, ?> m, String key, Function<Map<String, Object>, T> f) {
        var out = new ArrayList<T>();
        if (m != null && m.get(key) instanceof List<?> l) {
            for (var o : l) {
                if (o instanceof Map<?, ?> x) out.add(f.apply((Map<String, Object>) x));
            }
        }
        return List.copyOf(out);
    }

    static List<String> strings(Map<String, ?> m, String key) {
        var out = new ArrayList<String>();
        if (m != null && m.get(key) instanceof List<?> l) {
            for (var o : l) if (o != null) out.add(String.valueOf(o));
        }
        return List.copyOf(out);
    }

    static Map<String, String> stringMap(Map<String, ?> m, String key) {
        var out = new LinkedHashMap<String, String>();
        var x = map(m, key);
        if (x != null) x.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    /// The daemon's short dates mark approximate years with `~` (`1850–~1920`);
    /// people read "c. 1920" better, as the web app writes it.
    static String approx(String s) {
        return s == null || s.indexOf('~') < 0 ? s : s.replaceAll("~\\s*(?=\\d)", "c. ");
    }

    private static PersonSummary summary(Map<String, ?> m, String key) {
        var x = map(m, key);
        return x == null ? null : PersonSummary.from(x);
    }

    // ---- files ------------------------------------------------------------------

    record FileInfo(String id, String name, String path, String gedcom, String encoding,
                    long people, long families, long notes, long sources, long media,
                    boolean dirty, long dirtyCount, boolean changedOnDisk, long revision,
                    String home, List<String> warnings) {
        static FileInfo from(Map<String, Object> m) {
            var r = map(m, "records");
            return new FileInfo(str(m, "id"), str(m, "name"), str(m, "path"), str(m, "gedcom"), str(m, "encoding"),
                    num(m, "people"), num(m, "families"), num(r, "notes"), num(r, "sources"), num(r, "media"),
                    bool(m, "dirty"), num(m, "dirtyCount"), bool(m, "changedOnDisk"), num(m, "revision"),
                    str(m, "home"), strings(m, "warnings"));
        }
    }

    record RecentFile(String id, String name, String path, boolean open, boolean exists) {
        static RecentFile from(Map<String, Object> m) {
            return new RecentFile(str(m, "id"), str(m, "name"), str(m, "path"), bool(m, "open"), bool(m, "exists"));
        }
    }

    record Files(List<FileInfo> open, List<RecentFile> recent) {
        static Files from(Map<String, Object> m) {
            return new Files(list(m, "files", FileInfo::from), list(m, "recent", RecentFile::from));
        }
    }

    // ---- people -----------------------------------------------------------------

    record PersonSummary(String key, String name, String given, String surname, String sex,
                         String born, String died, boolean deceased, String lifespan, String photo,
                         String matched, Crop photoCrop, boolean cropKnown) {
        /// `photoCrop` is newer than the first daemons: `cropKnown` says whether
        /// this one sent it (null then means "no crop", not "don't know").
        static PersonSummary from(Map<String, Object> m) {
            var photo = m.get("photo") == null ? null : str(m, "photo");
            return new PersonSummary(str(m, "key"), str(m, "name"), str(m, "given"), str(m, "surname"),
                    str(m, "sex"), approx(str(m, "born")), approx(str(m, "died")), bool(m, "deceased"), approx(str(m, "lifespan")),
                    photo, str(m, "matched"), Crop.from(map(m, "photoCrop")), m.containsKey("photoCrop"));
        }

        /// The name to show: an unnamed person still needs a label.
        String displayName() {
            return name.isBlank() ? "Unnamed (" + key + ")" : name;
        }
    }

    record People(long total, List<PersonSummary> people, long revision) {
        static People from(Map<String, Object> m) {
            return new People(num(m, "total"), list(m, "people", PersonSummary::from), num(m, "revision"));
        }
    }

    /// An event as shown, and for editing: its `ref` (`RESI:1`), its editable
    /// `fields` (type, date, place, value) and whether it holds more (`details`).
    record Event(String tag, String label, String type, String date, Integer year, String place, String value,
                 String ref, Map<String, String> fields, boolean details) {
        Event(String tag, String label, String type, String date, Integer year, String place, String value) {
            this(tag, label, type, date, year, place, value, "", Map.of(), false);
        }

        static Event from(Map<String, Object> m) {
            return m == null ? null : new Event(str(m, "tag"), str(m, "label"), str(m, "type"), str(m, "date"),
                    intOrNull(m, "year"), str(m, "place"), str(m, "value"), str(m, "ref"), stringMap(m, "fields"),
                    bool(m, "details"));
        }
    }

    /// A further name; `fields` are its parts (given, surname, type).
    record OtherName(String name, String type, String ref, Map<String, String> fields) {
        static OtherName from(Map<String, Object> m) {
            return new OtherName(str(m, "name"), str(m, "type"), str(m, "ref"), stringMap(m, "fields"));
        }
    }

    /// One note on a person or family; `shared` is the note record's key
    /// when it is a shared note (edited on its own page), else null.
    record NoteItem(String ref, String text, String shared) {
        static NoteItem from(Map<String, Object> m) {
            return new NoteItem(str(m, "ref"), str(m, "text"), m.get("shared") == null ? null : str(m, "shared"));
        }
    }

    record ParentFamily(String family, PersonSummary father, PersonSummary mother, List<PersonSummary> siblings) {
        static ParentFamily from(Map<String, Object> m) {
            return new ParentFamily(str(m, "family"), summary(m, "father"), summary(m, "mother"),
                    list(m, "siblings", PersonSummary::from));
        }
    }

    /// `hidden`: the family is marked `1 _HIDE Y`, so the person page leaves it out until asked.
    record OwnFamily(String family, String role, PersonSummary partner, Event married, boolean divorced,
                     boolean hidden, List<PersonSummary> children) {
        static OwnFamily from(Map<String, Object> m) {
            return new OwnFamily(str(m, "family"), str(m, "role"), summary(m, "partner"),
                    Event.from(map(m, "married")), bool(m, "divorced"), bool(m, "hidden"),
                    list(m, "children", PersonSummary::from));
        }
    }

    record Crop(int left, int top, int width, int height) {
        static Crop from(Map<String, Object> m) {
            return m == null ? null
                    : new Crop((int) num(m, "left"), (int) num(m, "top"), (int) num(m, "width"), (int) num(m, "height"));
        }
    }

    record Photo(String url, String file, String title, String date, Crop crop) {
        static Photo from(Map<String, Object> m) {
            return new Photo(m.get("url") == null ? null : str(m, "url"), str(m, "file"), str(m, "title"),
                    str(m, "date"), Crop.from(map(m, "crop")));
        }
    }

    record RecordRef(String key, String kind, String title) {
        static RecordRef from(Map<String, Object> m) {
            return new RecordRef(str(m, "key"), str(m, "kind"), str(m, "title"));
        }
    }

    record Person(PersonSummary summary, String suffix, List<OtherName> otherNames, List<String> nicknames,
                  List<Event> events, List<ParentFamily> parents, List<PersonSummary> halfSiblings,
                  List<OwnFamily> families, List<String> notes, List<Photo> photos, List<RecordRef> records,
                  Map<String, String> fields, Map<String, String> present, List<NoteItem> noteItems) {
        static Person from(Map<String, Object> m) {
            return new Person(PersonSummary.from(m), str(m, "suffix"), list(m, "otherNames", OtherName::from),
                    strings(m, "nicknames"), list(m, "events", Event::from), list(m, "parents", ParentFamily::from),
                    list(m, "halfSiblings", PersonSummary::from), list(m, "families", OwnFamily::from),
                    strings(m, "notes"), list(m, "photos", Photo::from), list(m, "records", RecordRef::from),
                    stringMap(m, "fields"), stringMap(m, "present"), list(m, "noteItems", NoteItem::from));
        }

        String key() {
            return summary.key();
        }

        /// Everyone this person can be unlinked from, with the `unlink` path
        /// for each, in the order the person page shows them.
        List<Relative> relatives() {
            var out = new ArrayList<Relative>();
            for (var pf : parents) {
                if (pf.father() != null) out.add(new Relative(pf.father(), "Father", "father"));
                if (pf.mother() != null) out.add(new Relative(pf.mother(), "Mother", "mother"));
                for (var s : pf.siblings()) out.add(new Relative(s, "Sibling", "siblings/" + s.key()));
            }
            for (var f : families) {
                if (f.partner() != null) out.add(new Relative(f.partner(), "Spouse", "spouses/" + f.partner().key()));
                for (var c : f.children()) out.add(new Relative(c, "Child", "children/" + c.key()));
            }
            return out;
        }
    }

    /// A relative and how to unlink them (see `unlink` in the API).
    record Relative(PersonSummary person, String role, String unlinkPath) {
    }

    // ---- charts -------------------------------------------------------------------

    /// `pedigree`: a person with their parents, recursively; a missing parent is null.
    record Pedigree(PersonSummary person, Pedigree father, Pedigree mother) {
        static Pedigree from(Map<String, Object> m) {
            if (m == null) return null;
            return new Pedigree(PersonSummary.from(m), from(map(m, "father")), from(map(m, "mother")));
        }
    }

    /// `descendants`: a person with their families and children, recursively.
    record Descendants(PersonSummary person, List<DescendantFamily> families) {
        static Descendants from(Map<String, Object> m) {
            return new Descendants(PersonSummary.from(m), list(m, "families", DescendantFamily::from));
        }
    }

    record DescendantFamily(String family, PersonSummary partner, Event married, boolean divorced, List<Descendants> children) {
        static DescendantFamily from(Map<String, Object> m) {
            return new DescendantFamily(str(m, "family"), summary(m, "partner"), Event.from(map(m, "married")),
                    bool(m, "divorced"), list(m, "children", Descendants::from));
        }
    }

    // ---- families -----------------------------------------------------------------

    record FamilySummary(String key, String title, String married, long children) {
        static FamilySummary from(Map<String, Object> m) {
            return new FamilySummary(str(m, "key"), str(m, "title"), str(m, "married"),
                    m.get("children") instanceof Number n ? n.longValue() : 0);
        }
    }

    record Family(String key, String title, String married, PersonSummary husband, PersonSummary wife,
                  List<PersonSummary> children, List<Event> events, List<String> notes, List<RecordRef> records,
                  Map<String, String> fields, List<NoteItem> noteItems) {
        static Family from(Map<String, Object> m) {
            return new Family(str(m, "key"), str(m, "title"), str(m, "married"), summary(m, "husband"),
                    summary(m, "wife"), list(m, "children", PersonSummary::from), list(m, "events", Event::from),
                    strings(m, "notes"), list(m, "records", RecordRef::from), stringMap(m, "fields"),
                    list(m, "noteItems", NoteItem::from));
        }
    }

    // ---- notes, sources, media ----------------------------------------------------

    /// `file` and `mime` are set for media whose file is on this computer.
    record RecordSummary(String key, String kind, String title, long usedBy, String file, String mime) {
        static RecordSummary from(Map<String, Object> m) {
            return new RecordSummary(str(m, "key"), str(m, "kind"), str(m, "title"), num(m, "usedBy"),
                    m.get("file") == null ? null : str(m, "file"), m.get("mime") == null ? null : str(m, "mime"));
        }

        /// The address of a picture to show for it, if it is one.
        String thumbnail() {
            if (file == null) return null;
            if (mime == null) return file;
            return mime.startsWith("image/") ? file : null;
        }
    }

    /// A line of a record: `ref` is its path (`2.0`), `editable` whether its value can change.
    record RecordField(String label, String tag, String value, int depth, String link, String linkKind,
                       String ref, boolean editable) {
        static RecordField from(Map<String, Object> m) {
            return new RecordField(str(m, "label"), str(m, "tag"), str(m, "value"), (int) num(m, "depth"),
                    m.get("link") == null ? null : str(m, "link"), m.get("linkKind") == null ? null : str(m, "linkKind"),
                    str(m, "ref"), bool(m, "editable"));
        }
    }

    record RecordUser(String kind, String key, String title, PersonSummary person) {
        static RecordUser from(Map<String, Object> m) {
            return new RecordUser(str(m, "kind"), str(m, "key"), str(m, "title"), summary(m, "person"));
        }
    }

    record RecordDetail(String key, String kind, String title, long usedBy, String text, List<RecordField> fields,
                        List<RecordUser> users, String file) {
        static RecordDetail from(Map<String, Object> m) {
            return new RecordDetail(str(m, "key"), str(m, "kind"), str(m, "title"), num(m, "usedBy"), str(m, "text"),
                    list(m, "fields", RecordField::from), list(m, "users", RecordUser::from),
                    m.get("file") == null ? null : str(m, "file"));
        }
    }

    // ---- field definitions --------------------------------------------------------

    record FieldDef(String key, String label, String group, String kind, boolean extra) {
        static FieldDef from(Map<String, Object> m) {
            return new FieldDef(str(m, "key"), str(m, "label"), str(m, "group"), str(m, "kind"), bool(m, "extra"));
        }
    }

    record FieldDefs(List<FieldDef> person, List<FieldDef> family, String dates) {
        static FieldDefs from(Map<String, Object> m) {
            return new FieldDefs(list(m, "person", FieldDef::from), list(m, "family", FieldDef::from), str(m, "dates"));
        }
    }

    /// What `add`, `link` and `unlink` return.
    record Outcome(String message, Person person, long revision) {
        static Outcome from(Map<String, Object> m) {
            var p = map(m, "person");
            return new Outcome(str(m, "message"), p == null ? null : Person.from(p), num(m, "revision"));
        }
    }

    private Model() {
    }
}
