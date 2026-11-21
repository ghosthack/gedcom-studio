package ged.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Back and forward through the places visited, like a browser: going somewhere
/// new drops the forward list; revisiting the current place adds nothing.
final class History<T> {

    private static final int LIMIT = 200;

    private final List<T> entries = new ArrayList<>();
    private int index = -1;

    /// Records a visit to `place`.
    void visit(T place) {
        if (index >= 0 && entries.get(index).equals(place)) return;
        while (entries.size() > index + 1) entries.removeLast();
        entries.add(place);
        if (entries.size() > LIMIT) entries.removeFirst();
        index = entries.size() - 1;
    }

    /// Replaces the current place (a page that changed its own view, such as a
    /// chart's depth) without adding a step.
    void replace(T place) {
        if (index < 0) visit(place);
        else entries.set(index, place);
    }

    Optional<T> current() {
        return index < 0 ? Optional.empty() : Optional.of(entries.get(index));
    }

    boolean canGoBack() {
        return index > 0;
    }

    boolean canGoForward() {
        return index >= 0 && index < entries.size() - 1;
    }

    Optional<T> back() {
        if (!canGoBack()) return Optional.empty();
        return Optional.of(entries.get(--index));
    }

    Optional<T> forward() {
        if (!canGoForward()) return Optional.empty();
        return Optional.of(entries.get(++index));
    }

    void clear() {
        entries.clear();
        index = -1;
    }
}
