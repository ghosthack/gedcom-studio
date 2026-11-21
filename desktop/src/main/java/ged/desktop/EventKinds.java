package ged.desktop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// The events and facts a person or a family can have, as the edit forms
/// offer them ("Add event…"), with the labels the daemon gives them (the
/// person page's "Graduated", "Residence"). See `events` in docs/api.md.
final class EventKinds {

    /// A menu group: its title and its tags, in menu order.
    record Group(String title, List<String> tags) {
    }

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"BIRT", "Born"}, {"CHR", "Christened"}, {"BAPM", "Baptised"}, {"DEAT", "Died"}, {"BURI", "Buried"},
                {"CREM", "Cremated"}, {"ADOP", "Adopted"}, {"BARM", "Bar mitzvah"}, {"BASM", "Bas mitzvah"},
                {"CONF", "Confirmed"}, {"FCOM", "First communion"}, {"ORDN", "Ordained"}, {"NATU", "Naturalised"},
                {"EMIG", "Emigrated"}, {"IMMI", "Immigrated"}, {"CENS", "Census"}, {"PROB", "Probate"}, {"WILL", "Will"},
                {"GRAD", "Graduated"}, {"RETI", "Retired"}, {"RESI", "Residence"}, {"OCCU", "Occupation"},
                {"EDUC", "Education"}, {"RELI", "Religion"}, {"TITL", "Title"}, {"NATI", "Nationality"},
                {"DSCR", "Description"}, {"PROP", "Property"}, {"NCHI", "Children"}, {"NMR", "Marriages"},
                {"MARR", "Married"}, {"DIV", "Divorced"}, {"ENGA", "Engaged"}, {"MARB", "Banns"},
                {"MARC", "Marriage contract"}, {"MARL", "Marriage licence"}, {"MARS", "Marriage settlement"},
                {"ANUL", "Annulled"}, {"DIVF", "Divorce filed"}, {"EVEN", "Event"}, {"FACT", "Fact"}};
        for (var p : pairs) LABELS.put(p[0], p[1]);
    }

    /// Facts whose text is the point (`1 OCCU Sailor`): the form edits their value.
    static final Set<String> ATTRIBUTES = Set.of("OCCU", "EDUC", "RELI", "TITL", "NATI", "DSCR", "PROP", "NCHI", "NMR", "FACT");

    /// Tags that need a `TYPE` ("First communion"): the daemon refuses them without one.
    static final Set<String> NEED_TYPE = Set.of("EVEN", "FACT");

    static final List<Group> PERSON = List.of(
            new Group("Life", List.of("BIRT", "BAPM", "CHR", "DEAT", "BURI", "CREM")),
            new Group("Events", List.of("ADOP", "CONF", "FCOM", "BARM", "BASM", "GRAD", "ORDN", "EMIG", "IMMI", "NATU",
                    "CENS", "RESI", "RETI", "WILL", "PROB", "EVEN")),
            new Group("Facts", List.of("OCCU", "EDUC", "RELI", "TITL", "NATI", "DSCR", "PROP", "NCHI", "NMR", "FACT")));

    static final List<Group> FAMILY = List.of(
            new Group("Marriage", List.of("ENGA", "MARB", "MARC", "MARL", "MARS", "MARR")),
            new Group("Separation", List.of("DIVF", "DIV", "ANUL")),
            new Group("Other", List.of("RESI", "CENS", "EVEN", "FACT")));

    static String label(String tag) {
        return LABELS.getOrDefault(tag, tag);
    }

    /// The menu entry for a tag: "Other event…" for the generic ones, which ask for a type.
    static String menuLabel(String tag) {
        return switch (tag) {
            case "EVEN" -> "Other event…";
            case "FACT" -> "Other fact…";
            default -> label(tag);
        };
    }

    static boolean attribute(String tag) {
        return ATTRIBUTES.contains(tag);
    }

    /// A placeholder for an attribute's value: "Occupation", "Fact".
    static String valueHint(String tag) {
        return switch (tag) {
            case "NCHI" -> "Number of children";
            case "NMR" -> "Number of marriages";
            case "FACT" -> "Value";
            default -> label(tag);
        };
    }

    /// What the daemon's change names mean to people: `RESI:1.date` →
    /// "residence date", `+EVEN.type` → "event added", `-RESI:2` → "residence
    /// removed", `NOTE:0` → "note", `NAME:1.given` → "other name", a record's
    /// `-1 AUTH` → "AUTH line removed".
    private static final java.util.regex.Pattern RECORD_LINE =
            java.util.regex.Pattern.compile("^([+-]?)(?:[0-9.]+ )?([A-Z_][A-Z0-9_]*)$");

    static String describeChange(String field) {
        if (field.equals("nicknames") || field.equals("text")) return field;
        boolean added = field.startsWith("+");
        boolean removed = field.startsWith("-");
        var f = added || removed ? field.substring(1) : field;
        var tag = f.replaceAll("[:.].*$", "");
        var part = f.contains(".") ? f.substring(f.indexOf('.') + 1) : "";
        String what = switch (tag) {
            case "NOTE" -> "note";
            case "NAME" -> "other name";
            default -> LABELS.containsKey(tag) ? label(tag).toLowerCase(Locale.ROOT) : null;
        };
        if (what == null) {
            // a record's line: `0 TITL`, `-1 AUTH`, `+2 PUBL`
            var line = RECORD_LINE.matcher(field);
            if (!line.matches()) return field;
            var t = line.group(2);
            return switch (line.group(1)) {
                case "+" -> t + " line added";
                case "-" -> t + " line removed";
                default -> t + " line";
            };
        }
        if (added) return what + " added";
        if (removed) return what + " removed";
        if (tag.equals("NAME") || tag.equals("NOTE") || part.isEmpty()) return what;
        return what + " " + part;
    }

    private EventKinds() {
    }
}
