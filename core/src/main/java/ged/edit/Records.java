package ged.edit;

import ged.gedcom.Node;
import ged.model.GedDate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/// Helpers shared by the person and family forms: reading the `field:
/// value` text, normalising values, and writing events into records.
public final class Records {
    private Records() {}

    /// Parses an edited form. Fields left out of the text are unchanged.
    ///
    /// @throws IllegalArgumentException on unknown or repeated fields
    static <F> Map<F, String> parseForm(String text, Function<String, Optional<F>> field, Function<F, String> key) {
        var out = new LinkedHashMap<F, String>();
        int lineNo = 0;
        for (var line : text.split("\r?\n")) {
            lineNo++;
            var t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("line " + lineNo + ": expected 'field: value', got '" + t + "'");
            var name = t.substring(0, colon).strip();
            int at = lineNo;
            var f = field.apply(name).orElseThrow(() ->
                    new IllegalArgumentException("line " + at + ": unknown field '" + name + "'"));
            if (out.put(f, t.substring(colon + 1).strip()) != null) {
                throw new IllegalArgumentException("'" + key.apply(f) + "' appears twice");
            }
        }
        return out;
    }

    /// The `# ERROR:` banner that tops a reopened form.
    static String errorBanner(String error) {
        return error == null ? "" : "# ERROR: " + error.replace("\n", "\n#        ") + "\n#\n";
    }

    private static final java.util.regex.Pattern BANNER =
            java.util.regex.Pattern.compile("\\A# ERROR: [^\\n]*\\n(?:#        [^\\n]*\\n)*#\\n");

    /// The form as the user left it, to open again with `error` on top
    /// (in place of an earlier error), so nothing they typed is lost.
    public static String reopened(String edited, String error) {
        return errorBanner(error) + BANNER.matcher(edited).replaceFirst("");
    }

    static String clean(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    /// A date, normalised for the file's GEDCOM version; text equal to `current` is kept as it is, even
    /// if the file's form is unusual.
    static String date(String key, String v, String current, boolean gedcom7) {
        if (v.equals(current)) return current;
        try {
            return GedDate.normalize(v, gedcom7);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage());
        }
    }

    static String yesNo(String key, String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1" -> "yes";
            case "no", "n", "false", "0", "" -> "no";
            default -> throw new IllegalArgumentException(key + " must be yes or no, not '" + v + "'");
        };
    }

    /// True if the event carries more than a date and place (sources,
    /// notes, …), so removing it would lose information.
    static boolean hasDetails(Node event) {
        return event.children().stream().anyMatch(c -> !List.of("DATE", "PLAC").contains(c.tag()));
    }

    private static final DateTimeFormatter CHAN_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);
    private static final DateTimeFormatter CHAN_TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /// Updates the record's existing `CHAN` date/time, as genealogy
    /// programs do. A record without one is left without one. GEDCOM 7 can
    /// say a time is UTC (a `Z` after it), so there the stamp is UTC and
    /// the same wherever the file is edited; 5.5.1 has no such mark, and
    /// its times are local, as the programs that write it mean them.
    static void touch(Node rec, boolean gedcom7) {
        rec.first("CHAN").ifPresent(chan -> {
            var now = gedcom7 ? LocalDateTime.now(java.time.ZoneOffset.UTC) : LocalDateTime.now();
            var date = chan.first("DATE").orElseGet(() -> chan.insert(0, "DATE", ""));
            date.setValue(CHAN_DATE.format(now).toUpperCase(Locale.ROOT));
            var time = date.first("TIME");
            if (time.isPresent()) time.get().setValue(CHAN_TIME.format(now) + (gedcom7 ? "Z" : ""));
        });
    }

    /// Sets DATE/PLAC under an event (null = leave as is), creating the
    /// event if needed and removing it once it carries nothing at all.
    /// With `keepAsFlag`, an event with no date or place is kept as `TAG Y`
    /// (GEDCOM's "this happened, details unknown").
    static void event(Node rec, String tag, Function<Node, Node> create, String date, String place, boolean keepAsFlag) {
        var ev = rec.first(tag).orElseGet(() -> create.apply(rec));
        if (date != null) setOrRemove(ev, "DATE", date, 0);
        if (place != null) setOrRemove(ev, "PLAC", place, ev.first("DATE").isPresent() ? 1 : 0);
        boolean hasFacts = ev.first("DATE").isPresent() || ev.first("PLAC").isPresent();
        if (keepAsFlag) {
            ev.setValue(hasFacts ? "" : "Y");
        } else if (ev.children().isEmpty() && ev.value().isEmpty()) {
            rec.remove(ev);
        }
    }

    private static void setOrRemove(Node ev, String tag, String value, int index) {
        var existing = ev.first(tag);
        if (value.isEmpty()) existing.ifPresent(ev::remove);
        else if (existing.isPresent()) existing.get().setValue(value);
        else ev.insert(index, tag, value);
    }

    /// The requests in `requested` that change something, checked against
    /// what the editor was shown (`seen`) and what the record says `now`.
    /// A field typed back as it was shown is no request. A field changed
    /// elsewhere since it was shown is a [Conflict], unless it now already
    /// says what was asked. A field missing from `seen` is not checked.
    ///
    /// @throws Conflict naming every field changed meanwhile
    public static <F> java.util.Map<F, String> againstSeen(java.util.Map<F, String> now, java.util.Map<F, String> seen,
                                                          java.util.Map<F, String> requested, java.util.function.Function<F, String> key) {
        var out = new java.util.LinkedHashMap<F, String>();
        var stale = new java.util.ArrayList<String>();
        for (var e : requested.entrySet()) {
            var f = e.getKey();
            var was = seen.get(f);
            var wanted = clean(e.getValue());
            if (was != null && wanted.equals(was)) continue;
            var current = now.getOrDefault(f, "");
            if (was != null && !current.equals(was) && !current.equals(wanted)) {
                stale.add(key.apply(f) + " is now " + shown(current) + " (was " + shown(was) + ")");
            }
            out.put(f, e.getValue());
        }
        if (!stale.isEmpty()) {
            throw new Conflict("changed elsewhere meanwhile: " + String.join("; ", stale) + "; nothing was applied");
        }
        return out;
    }

    private static String shown(String v) { return v.isEmpty() ? "empty" : "'" + v + "'"; }
}
