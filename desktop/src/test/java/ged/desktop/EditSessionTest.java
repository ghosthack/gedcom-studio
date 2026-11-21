package ged.desktop;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EditSessionTest {

    @Test
    void sendsOnlyChangedFieldsWithWhatTheFormShowed() {
        var s = new EditSession(Map.of("birth.date", "1850", "occupation", "", "given", "Maria"));
        var r = s.request(Map.of("birth.date", "12 jun 1850", "occupation", "", "given", "Maria "));
        assertEquals(Map.of("birth.date", "12 jun 1850"), r.set());
        assertEquals(Map.of("birth.date", "1850"), r.seen());
    }

    @Test
    void nothingChangedIsAnEmptyRequest() {
        var s = new EditSession(Map.of("given", "Maria"));
        assertTrue(s.request(Map.of("given", "Maria")).isEmpty());
    }

    @Test
    void rebaseKeepsTheUsersChangesAndTakesTheirsElsewhere() {
        var s = new EditSession(Map.of("occupation", "", "birth.date", "1850", "surname", "Rossi"));
        // the user changed the birth date; someone else set the occupation meanwhile
        var mine = Map.of("occupation", "", "birth.date", "12 JUN 1850", "surname", "Rossi");
        var latest = Map.of("occupation", "Baker", "birth.date", "1850", "surname", "Rossi");
        var r = s.rebase(mine, latest);
        assertEquals("Baker", r.values().get("occupation"));
        assertEquals("12 JUN 1850", r.values().get("birth.date"));
        assertEquals(Set.of("occupation"), r.changedElsewhere());
        assertTrue(r.clashes().isEmpty());
        // the next save is checked against the latest values
        var again = s.request(r.values());
        assertEquals(Map.of("birth.date", "12 JUN 1850"), again.set());
        assertEquals(Map.of("birth.date", "1850"), again.seen());
    }

    @Test
    void bothChangingOneFieldDifferentlyIsAClash() {
        var s = new EditSession(Map.of("occupation", ""));
        var r = s.rebase(Map.of("occupation", "Cook"), Map.of("occupation", "Baker"));
        assertEquals("Cook", r.values().get("occupation"));
        assertEquals(Set.of("occupation"), r.clashes());
    }

    @Test
    void bothMakingTheSameChangeIsNoClash() {
        var s = new EditSession(Map.of("occupation", ""));
        var r = s.rebase(Map.of("occupation", "Baker"), Map.of("occupation", "Baker"));
        assertTrue(r.clashes().isEmpty());
        assertTrue(s.request(r.values()).isEmpty());
    }

    @Test
    void findsTheFieldARefusalIsAbout() {
        var keys = Set.of("birth.date", "death.date");
        assertEquals("birth.date", EditSession.fieldOf("birth.date: 'sometime' is not a GEDCOM date; use …", keys));
        assertNull(EditSession.fieldOf("a family can't have two husbands", keys));
        assertNull(EditSession.fieldOf("other: no", keys));
        assertNull(EditSession.fieldOf(null, keys));
    }
}
