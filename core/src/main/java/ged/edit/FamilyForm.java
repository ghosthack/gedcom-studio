package ged.edit;

import ged.gedcom.Node;
import ged.model.Family;
import ged.model.Genealogy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// The editable view of a family: marriage, divorce and whether person pages
/// hide it, as a `field: value` form like [PersonForm]. Who belongs to the family is changed with
/// `link` and `unlink`, not here; the form lists them as comments.
public final class FamilyForm {
    private FamilyForm() {}

    public enum Field {
        MARRIAGE_DATE("marriage.date"),
        MARRIAGE_PLACE("marriage.place"),
        DIVORCE_DATE("divorce.date"),
        DIVORCE_PLACE("divorce.place"),
        DIVORCED("divorced"),
        HIDDEN("hidden");

        public final String key;

        Field(String key) {
            this.key = key;
        }

        /// Accepts the form key plus a few natural aliases for `FIELD=VALUE`.
        public static Optional<Field> parse(String name) {
            var n = name.strip().toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
            var alias = switch (n) {
                case "married", "marriage", "marr", "wed", "wedding", "married.date" -> "marriage.date";
                case "marriageplace", "married.place", "wedding.place" -> "marriage.place";
                case "divorce", "div", "divorcedate" -> "divorce.date";
                case "divorceplace" -> "divorce.place";
                case "hide", ".hide" -> "hidden";
                default -> n;
            };
            return Arrays.stream(values()).filter(f -> f.key.equals(alias)).findFirst();
        }
    }

    public record Change(Field field, String before, String after) {}

    /// Current values, in form order.
    public static Map<Field, String> values(Family f) {
        var n = f.node();
        var v = new LinkedHashMap<Field, String>();
        v.put(Field.MARRIAGE_DATE, n.first("MARR").map(m -> m.text("DATE")).orElse(""));
        v.put(Field.MARRIAGE_PLACE, n.first("MARR").map(m -> m.text("PLAC")).orElse(""));
        v.put(Field.DIVORCE_DATE, n.first("DIV").map(d -> d.text("DATE")).orElse(""));
        v.put(Field.DIVORCE_PLACE, n.first("DIV").map(d -> d.text("PLAC")).orElse(""));
        v.put(Field.DIVORCED, n.first("DIV").isPresent() ? "yes" : "no");
        v.put(Field.HIDDEN, f.hidden() ? "yes" : "no");
        return v;
    }

    // ---- the text form ----------------------------------------------------

    public static String render(Genealogy g, Family f, String error) {
        var sb = new StringBuilder(Records.errorBanner(error));
        sb.append("# Editing ").append(f.key()).append('\n');
        f.husband().flatMap(g::person).ifPresent(p -> sb.append("#   husband   ").append(p.key()).append("  ").append(p.label()).append('\n'));
        f.wife().flatMap(g::person).ifPresent(p -> sb.append("#   wife      ").append(p.key()).append("  ").append(p.label()).append('\n'));
        for (var id : f.children()) {
            g.person(id).ifPresent(p -> sb.append("#   child     ").append(p.key()).append("  ").append(p.label()).append('\n'));
        }
        sb.append("""
                #
                # Change values after the colon; leave a value empty to remove it.
                # Save and quit to apply. Quit without saving (or :cq in vi) to cancel.
                # To change who is in the family, use `link` and `unlink`.
                #
                #   dates     12 JUN 1850 · JUN 1850 · 1850 · ABT 1850 · BEF 1900 · AFT 1900
                #             BET 1800 AND 1810 · FROM 1914 TO 1918 · (free text)
                #             1850-06-12 and "12 june 1850" are converted for you
                #   divorced  yes or no; a divorce date or place implies yes
                #   hidden    yes or no; yes hides the family on person pages (saved as _HIDE Y)

                """);
        Field previous = null;
        for (var e : values(f).entrySet()) {
            var field = e.getKey();
            if (previous == Field.MARRIAGE_PLACE) sb.append('\n');
            sb.append(String.format("%-16s %s", field.key + ":", e.getValue()).stripTrailing()).append('\n');
            previous = field;
        }
        return sb.toString();
    }

    public static Map<Field, String> parse(String text) {
        return Records.parseForm(text, Field::parse, f -> f.key);
    }

    /// The changes an edited form asks for; lines left as they were are
    /// not requests (see [PersonForm#diffForm]).
    public static List<Change> diffForm(Family f, String text, boolean gedcom7) {
        var requested = parse(text);
        var current = values(f);
        requested.entrySet().removeIf(e -> Records.clean(e.getValue()).equals(current.get(e.getKey())));
        return diff(f, requested, gedcom7);
    }

    /// As [PersonForm#diffForm(Person, Map, String, boolean)], for a family.
    public static List<Change> diffForm(Family now, Map<Field, String> seen, String text, boolean gedcom7) {
        return diff(now, seen, parse(text), gedcom7);
    }

    /// As [#diff(Family, Map, boolean)], for requests made against `seen`.
    ///
    /// @throws Conflict if a requested field was changed meanwhile
    public static List<Change> diff(Family now, Map<Field, String> seen, Map<Field, String> requested, boolean gedcom7) {
        return diff(now, Records.againstSeen(values(now), seen, requested, f -> f.key), gedcom7);
    }

    // ---- validation, diff, apply ------------------------------------------

    /// Validates and normalises `requested`; returns only real changes.
    ///
    /// Dates are written in the file's GEDCOM version (`gedcom7`, else 5.5.1).
    ///
    /// @throws IllegalArgumentException with a user-facing message
    public static List<Change> diff(Family f, Map<Field, String> requested, boolean gedcom7) {
        var current = values(f);
        var wanted = new LinkedHashMap<>(current);
        for (var e : requested.entrySet()) {
            var field = e.getKey();
            var v = Records.clean(e.getValue());
            wanted.put(field, switch (field) {
                case MARRIAGE_DATE, DIVORCE_DATE -> Records.date(field.key, v, current.get(field), gedcom7);
                case MARRIAGE_PLACE, DIVORCE_PLACE -> v;
                case DIVORCED, HIDDEN -> Records.yesNo(field.key, v);
            });
        }

        boolean divorceFacts = !wanted.get(Field.DIVORCE_DATE).isEmpty() || !wanted.get(Field.DIVORCE_PLACE).isEmpty();
        if (divorceFacts && requested.containsKey(Field.DIVORCED) && wanted.get(Field.DIVORCED).equals("no")) {
            throw new IllegalArgumentException("divorced is 'no' but a divorce date or place is given (clear them too: divorce.date= divorce.place=)");
        }
        if (divorceFacts) wanted.put(Field.DIVORCED, "yes");
        if (wanted.get(Field.DIVORCED).equals("no") && current.get(Field.DIVORCED).equals("yes")) {
            var div = f.node().first("DIV");
            if (div.isPresent() && Records.hasDetails(div.get())) {
                throw new IllegalArgumentException("can't remove the divorce: its record has sources or notes; edit the file directly");
            }
        }
        var changes = new ArrayList<Change>();
        for (var field : Field.values()) {
            if (!wanted.get(field).equals(current.get(field))) changes.add(new Change(field, current.get(field), wanted.get(field)));
        }
        return changes;
    }

    /// Family events go after the partners and before children, notes and
    /// sources, which is where genealogy programs put them.
    private static final List<String> LEADING = List.of("HUSB", "WIFE", "MARR", "MARB", "MARC", "MARL", "MARS", "ENGA");

    /// Inserts a new event line where genealogy programs put it: engagement,
    /// banns and marriage after the partners, the rest after those, all
    /// before the children, notes and sources.
    static Node create(Node rec, String tag) {
        return switch (tag) {
            case "ENGA", "MARB", "MARC", "MARL", "MARS", "MARR" -> {
                // in the order they happen: after the partners and the steps before this one
                var steps = List.of("ENGA", "MARB", "MARC", "MARL", "MARS", "MARR");
                var after = new java.util.ArrayList<>(List.of("HUSB", "WIFE"));
                after.addAll(steps.subList(0, steps.indexOf(tag) + 1));
                yield rec.insertAfter(after, tag, "");
            }
            default -> rec.insertAfter(java.util.stream.Stream.concat(LEADING.stream(),
                    java.util.stream.Stream.of("DIV", "DIVF", "ANUL", "EVEN", "RESI", "CENS", "FACT")).toList(), tag, "");
        };
    }

    /// Writes `changes` into the family's record and returns the
    /// re-indexed family.
    public static Family apply(Genealogy g, Family f, List<Change> changes) {
        var rec = f.node();
        var after = new LinkedHashMap<Field, String>();
        for (var c : changes) after.put(c.field(), c.after());

        if (after.containsKey(Field.MARRIAGE_DATE) || after.containsKey(Field.MARRIAGE_PLACE)) {
            Records.event(rec, "MARR", n -> n.insertAfter(List.of("HUSB", "WIFE"), "MARR", ""),
                    after.get(Field.MARRIAGE_DATE), after.get(Field.MARRIAGE_PLACE), false);
        }
        var divorced = after.get(Field.DIVORCED);
        if ("no".equals(divorced)) {
            rec.first("DIV").ifPresent(rec::remove);
        } else if (after.containsKey(Field.DIVORCE_DATE) || after.containsKey(Field.DIVORCE_PLACE) || "yes".equals(divorced)) {
            Records.event(rec, "DIV", n -> n.insertAfter(LEADING, "DIV", ""),
                    after.get(Field.DIVORCE_DATE), after.get(Field.DIVORCE_PLACE), true);
        }
        var hidden = after.get(Field.HIDDEN);
        if ("no".equals(hidden)) {
            rec.all("_HIDE").toList().forEach(rec::remove);
        } else if ("yes".equals(hidden)) {
            hide(g, rec);
        }
        Records.touch(rec, g.isGedcom7());
        return g.updated(f);
    }

    /// The GEDCOM 7 schema URI that declares `_HIDE` (see the README).
    public static final String HIDE_URI = "https://github.com/ghosthack/gedcom-studio#hidden-families";

    /// Marks the record `1 _HIDE Y`: an existing `_HIDE` line is set to `Y`,
    /// else a new one goes last, before a `CHAN` (which programs keep last).
    /// A GEDCOM 7 file declares the extension tag in its header.
    private static void hide(Genealogy g, Node rec) {
        var existing = rec.first("_HIDE");
        if (existing.isPresent()) existing.get().setValue("Y");
        else rec.insertBefore(List.of("CHAN"), "_HIDE", "Y");
        if (g.isGedcom7()) declareHide(g);
    }

    /// Adds `2 TAG _HIDE <uri>` under the header's `SCHMA` (made after `GEDC`
    /// if there is none), unless `_HIDE` is declared there already. GEDCOM
    /// 5.5.1 needs no declaration for an underscore tag.
    private static void declareHide(Genealogy g) {
        var head = g.header().orElse(null);
        if (head == null) return;
        var schma = head.first("SCHMA").orElse(null);
        if (schma != null && schma.all("TAG").anyMatch(t -> t.value().strip().split("\\s+")[0].equals("_HIDE"))) return;
        if (schma == null) schma = head.insertAfter(List.of("GEDC"), "SCHMA", "");
        schma.insert(Integer.MAX_VALUE, "TAG", "_HIDE " + HIDE_URI);
        g.updated(head);
    }
}
