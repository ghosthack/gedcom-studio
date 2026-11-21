package ged.desktop;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HistoryTest {

    @Test
    void backAndForward() {
        var h = new History<String>();
        assertFalse(h.canGoBack());
        h.visit("a");
        h.visit("b");
        h.visit("c");
        assertEquals(Optional.of("b"), h.back());
        assertEquals(Optional.of("a"), h.back());
        assertEquals(Optional.empty(), h.back());
        assertEquals(Optional.of("b"), h.forward());
        assertTrue(h.canGoForward());
    }

    @Test
    void goingSomewhereNewDropsTheForwardList() {
        var h = new History<String>();
        h.visit("a");
        h.visit("b");
        h.back();
        h.visit("x");
        assertFalse(h.canGoForward());
        assertEquals(Optional.of("a"), h.back());
    }

    @Test
    void revisitingTheCurrentPlaceAddsNothing() {
        var h = new History<Place>();
        h.visit(new Place.PersonPage("I1"));
        h.visit(new Place.PersonPage("I1"));
        assertFalse(h.canGoBack());
        h.replace(new Place.Ancestors("I1", 5));
        assertEquals(Optional.of(new Place.Ancestors("I1", 5)), h.current());
        assertFalse(h.canGoBack());
    }
}
