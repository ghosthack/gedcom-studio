package ged.desktop;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/// The bookkeeping behind an edit form: the values it started from (`seen`,
/// as the API calls them), which fields the user changed, and how to carry on
/// after a conflict without losing anyone's work.
///
/// A save sends only the fields the user changed, each with the value the form
/// showed, so the daemon can refuse it (`409`) if someone else changed one of
/// them meanwhile. After a conflict, [#rebase] moves the form onto the latest
/// values: fields the user left alone take the new values, fields the user
/// changed keep the user's input, and both kinds are reported so the form can
/// point them out. The next save is then checked against the latest values.
final class EditSession {

    /// What to send: `set` and `seen` for the `edit` call.
    record Request(Map<String, String> set, Map<String, String> seen) {
        boolean isEmpty() {
            return set.isEmpty();
        }
    }

    /// The form after a conflict: its new `values`, the fields someone else
    /// changed, and among those the ones the user changed differently too.
    record Rebase(Map<String, String> values, Set<String> changedElsewhere, Set<String> clashes) {
    }

    private Map<String, String> seen;

    EditSession(Map<String, String> seen) {
        this.seen = new LinkedHashMap<>(seen);
    }

    Map<String, String> seen() {
        return Map.copyOf(seen);
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip();
    }

    /// The fields whose value in `values` differs from what the form started with.
    Map<String, String> changes(Map<String, String> values) {
        var out = new LinkedHashMap<String, String>();
        values.forEach((k, v) -> {
            if (!norm(v).equals(norm(seen.get(k)))) out.put(k, norm(v));
        });
        return out;
    }

    Request request(Map<String, String> values) {
        var set = changes(values);
        var seenSubset = new LinkedHashMap<String, String>();
        for (var k : set.keySet()) seenSubset.put(k, norm(seen.get(k)));
        return new Request(set, seenSubset);
    }

    /// Moves the form onto `latest` (the values the daemon has now), keeping
    /// the user's own changes. From here on, saves are checked against `latest`.
    Rebase rebase(Map<String, String> values, Map<String, String> latest) {
        var keys = new LinkedHashSet<String>();
        keys.addAll(values.keySet());
        keys.addAll(latest.keySet());
        var next = new LinkedHashMap<String, String>();
        var elsewhere = new LinkedHashSet<String>();
        var clashes = new LinkedHashSet<String>();
        for (var k : keys) {
            var old = norm(seen.get(k));
            var mine = values.containsKey(k) ? norm(values.get(k)) : old;
            var theirs = latest.containsKey(k) ? norm(latest.get(k)) : old;
            boolean userChanged = !mine.equals(old);
            boolean otherChanged = !theirs.equals(old);
            if (otherChanged) elsewhere.add(k);
            if (userChanged && otherChanged && !mine.equals(theirs)) clashes.add(k);
            next.put(k, userChanged ? mine : theirs);
        }
        seen = new LinkedHashMap<>(latest);
        return new Rebase(next, elsewhere, clashes);
    }

    /// The field a refusal is about, if its message starts with a field key
    /// (`birth.date: 'sometime' is not a GEDCOM date…`).
    static String fieldOf(String message, Set<String> keys) {
        if (message == null) return null;
        int colon = message.indexOf(':');
        if (colon <= 0) return null;
        var k = message.substring(0, colon).strip();
        return keys.contains(k) ? k : null;
    }
}
