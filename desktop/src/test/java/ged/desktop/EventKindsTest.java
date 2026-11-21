package ged.desktop;

import ged.edit.RecordEdits;
import ged.model.Event;
import ged.model.GedDate;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class EventKindsTest {

    private static String coreLabel(String tag) {
        return new Event(tag, "", GedDate.EMPTY, "", "", null).label();
    }

    @Test
    void theMenusOfferWhatTheDaemonAcceptsWithItsLabels() {
        var seen = new HashSet<String>();
        for (var g : EventKinds.PERSON) {
            for (var tag : g.tags()) {
                assertTrue(RecordEdits.personEvent(tag), tag + " is a person's");
                assertEquals(coreLabel(tag), EventKinds.label(tag), tag);
                assertTrue(seen.add(tag), tag + " twice");
            }
        }
        seen.clear();
        for (var g : EventKinds.FAMILY) {
            for (var tag : g.tags()) {
                assertTrue(RecordEdits.familyEvent(tag), tag + " is a family's");
                assertEquals(coreLabel(tag), EventKinds.label(tag), tag);
                assertTrue(seen.add(tag), tag + " twice");
            }
        }
        assertEquals(RecordEdits.ATTRIBUTES, EventKinds.ATTRIBUTES);
    }

    @Test
    void changesReadAsWords() {
        assertEquals("residence date", EventKinds.describeChange("RESI:1.date"));
        assertEquals("event added", EventKinds.describeChange("+EVEN.type"));
        assertEquals("died added", EventKinds.describeChange("+DEAT"));
        assertEquals("residence removed", EventKinds.describeChange("-RESI:2"));
        assertEquals("note", EventKinds.describeChange("NOTE:0"));
        assertEquals("note added", EventKinds.describeChange("+NOTE"));
        assertEquals("other name", EventKinds.describeChange("NAME:1.given"));
        assertEquals("nicknames", EventKinds.describeChange("nicknames"));
        assertEquals("TITL line", EventKinds.describeChange("0 TITL"));
        assertEquals("AUTH line removed", EventKinds.describeChange("-1 AUTH"));
    }
}
