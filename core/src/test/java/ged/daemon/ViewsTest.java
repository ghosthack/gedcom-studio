package ged.daemon;

import ged.model.Event;
import ged.model.GedDate;
import ged.model.Genealogy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewsTest {

    /// `TAG:date` (or `TAG:type:date`) for each event.
    private static List<Event> events(String... specs) {
        return Arrays.stream(specs).map(s -> {
            var p = s.split(":", -1);
            var type = p.length == 3 ? p[1] : "";
            return new Event(p[0], type, GedDate.parse(p[p.length - 1]), "", "", null);
        }).toList();
    }

    private static List<String> order(List<Event> events) {
        return Views.chronological(events).stream()
                .map(e -> e.type().isEmpty() ? e.tag() + ":" + e.date() : e.tag() + ":" + e.type() + ":" + e.date())
                .toList();
    }

    @Test
    void residencesGroupedFirstAreSpreadThroughTheLife() {
        // as a file grouped by kind lists them (the timeline in the bug report)
        var in = events("BIRT:14 MAR 1979", "RESI:1979", "RESI:1981", "RESI:2005",
                "GRAD:Técnico:", "BAPM:APR 1980", "EVEN:First communion:OCT 1988", "NATU:8 APR 2025");
        assertEquals(List.of("BIRT:14 MAR 1979", "RESI:1979", "BAPM:APR 1980", "RESI:1981",
                "EVEN:First communion:OCT 1988", "RESI:2005", "GRAD:Técnico:", "NATU:8 APR 2025"), order(in));
    }

    @Test
    void lessPreciseDatesKeepTheirPlace() {
        assertEquals(List.of("RESI:1979", "BIRT:14 MAR 1979"), order(events("RESI:1979", "BIRT:14 MAR 1979")));
        assertEquals(List.of("BIRT:14 MAR 1979", "RESI:MAR 1979"), order(events("BIRT:14 MAR 1979", "RESI:MAR 1979")));
        assertEquals(List.of("RESI:2 MAR 1979", "BIRT:14 MAR 1979"), order(events("BIRT:14 MAR 1979", "RESI:2 MAR 1979")));
    }

    @Test
    void undatedEventsFollowTheirNeighbourAndAnUndatedBirthLeads() {
        assertEquals(List.of("BIRT:", "RESI:1900", "OCCU:", "DEAT:1950", "BURI:"),
                order(events("DEAT:1950", "BURI:", "RESI:1900", "OCCU:", "BIRT:")));
    }

    @Test
    void emptyRareEventsArePresentSoFormsShowThem() {
        var g = Genealogy.of("""
                0 HEAD
                0 @I1@ INDI
                1 NAME Tomás /Fábregas/
                1 BIRT
                2 DATE 14 MAR 1979
                1 GRAD
                2 TYPE Técnico Químico
                1 OCCU Engineer
                0 TRLR
                """);
        var p = new Views(g, "/api/v1/files/x", s -> s).person(g.person("@I1@").orElseThrow());
        assertEquals(Map.of("birth", "", "graduation", "Técnico Químico", "occupation", ""), p.get("present"));
    }
}
