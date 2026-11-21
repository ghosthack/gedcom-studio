package ged.daemon;

import ged.client.DaemonClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ged.json.Json.obj;
import static org.junit.jupiter.api.Assertions.*;

/// Everything the apps show can be edited: each event (its type, date,
/// place, value), notes, further names, nicknames, family events, and the
/// lines of notes, sources and media. Over real HTTP, then saved and read back.
class EditEverythingTest {
    static final String GED = """
            0 HEAD
            1 GEDC
            2 VERS 5.5.1
            1 CHAR UTF-8
            0 @I0@ INDI
            1 NAME Tomás Iker /Fábregas/
            2 NICK Adri
            1 NAME Iker /Fábregas/
            2 TYPE aka
            1 SEX M
            1 BIRT
            2 DATE 14 MAR 1979
            2 PLAC Segovia, Spain
            1 RESI
            2 DATE 1979
            1 RESI
            2 DATE 1981
            1 RESI
            2 DATE 2005
            1 GRAD
            2 TYPE Técnico Químico
            2 NOTE IESA, Segovia.
            1 OCCU Engineer
            1 NOTE Likes trains.
            1 NOTE @N1@
            1 FAMS @F1@
            0 @I1@ INDI
            1 NAME Ana /Pérez/
            1 SEX F
            1 FAMS @F1@
            0 @F1@ FAM
            1 HUSB @I0@
            1 WIFE @I1@
            1 MARR
            2 DATE 2010
            0 @N1@ NOTE Shared research note.
            0 @S1@ SOUR
            1 TITL Parish register
            1 AUTH Unknown
            0 TRLR
            """;

    @TempDir Path dir;
    Server server;
    DaemonClient client;
    Path ged;
    String id;

    @BeforeEach
    void start() throws Exception {
        var home = new Home(dir.resolve("home"));
        server = new Server(home, new Server.Config("127.0.0.1", 0, Optional.empty(), Optional.empty(), Optional.empty()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(server.start());
        client = DaemonClient.running(home).orElseThrow();
        ged = dir.resolve("tomas.ged");
        Files.writeString(ged, GED);
        id = (String) client.post("files", obj("path", ged.toString())).get("id");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    String f(String rest) { return "files/" + id + "/" + rest; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    Map<String, Object> event(Map<String, Object> person, String ref) {
        return list(person.get("events")).stream().filter(e -> ref.equals(e.get("ref"))).findFirst().orElseThrow(() -> new AssertionError("no " + ref));
    }

    String saved() throws Exception {
        client.post(f("save"), obj());
        return Files.readString(ged);
    }

    @Test
    void everyEventShowsItsRefAndFields() throws Exception {
        var p = client.get(f("people/I0"));
        var refs = list(p.get("events")).stream().map(e -> e.get("ref")).toList();
        assertEquals(List.of("BIRT:0", "RESI:0", "RESI:1", "RESI:2", "GRAD:0", "OCCU:0"), refs);
        var grad = event(p, "GRAD:0");
        assertEquals(Map.of("type", "Técnico Químico", "date", "", "place", "", "value", ""), grad.get("fields"));
        assertEquals(true, grad.get("details"), "its note would go with it");
        assertEquals(Map.of("type", "", "date", "", "place", "", "value", "Engineer"), event(p, "OCCU:0").get("fields"));
        assertEquals(List.of(obj("ref", "NOTE:0", "text", "Likes trains.", "shared", null),
                obj("ref", "NOTE:1", "text", "Shared research note.", "shared", "N1")), p.get("noteItems"));
        var other = list(p.get("otherNames")).getFirst();
        assertEquals("NAME:1", other.get("ref"));
        assertEquals(Map.of("given", "Iker", "surname", "Fábregas", "type", "aka"), other.get("fields"));
    }

    @Test
    void editingAddingAndRemovingEvents() throws Exception {
        var reply = client.post(f("people/I0/edit"), obj("events", List.of(
                obj("ref", "GRAD:0", "seen", obj("date", ""), "set", obj("date", "dec 1997", "type", "Técnico en Química")),
                obj("ref", "RESI:1", "seen", obj("date", "1981"), "set", obj("place", "Serrano 101, Segovia")),
                obj("ref", "RESI:2", "seen", obj("date", "2005"), "remove", true),
                obj("add", "EVEN", "set", obj("type", "First communion", "date", "oct 1988")),
                obj("ref", "OCCU:0", "seen", obj("value", "Engineer"), "set", obj("value", "Chemical engineer")))));
        var p = map(reply.get("person"));
        assertEquals("DEC 1997", map(event(p, "GRAD:0").get("fields")).get("date"));
        assertEquals("Técnico en Química", map(event(p, "GRAD:0").get("fields")).get("type"));
        assertEquals(List.of("BIRT:0", "RESI:0", "RESI:1", "EVEN:0", "GRAD:0", "OCCU:0"),
                list(p.get("events")).stream().map(e -> e.get("ref")).toList(), "in date order");
        assertEquals(7, list(reply.get("changes")).size(), "two parts of the graduation, a place, a removal, two parts of the new event, the occupation");

        var text = saved();
        assertTrue(text.contains("1 GRAD\n2 TYPE Técnico en Química\n2 DATE DEC 1997\n2 NOTE IESA, Segovia.\n"), text);
        assertTrue(text.contains("1 RESI\n2 DATE 1981\n2 PLAC Serrano 101, Segovia\n"), text);
        assertFalse(text.contains("2 DATE 2005"), text);
        assertTrue(text.contains("1 EVEN\n2 TYPE First communion\n2 DATE OCT 1988\n"), text);
        assertTrue(text.contains("1 OCCU Chemical engineer\n"), text);
    }

    @Test
    void anEventChangedElsewhereIsAConflictAndNothingIsApplied() throws Exception {
        client.post(f("people/I0/edit"), obj("events", List.of(obj("ref", "RESI:1", "seen", obj("date", "1981"), "set", obj("date", "1982")))));
        var e = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("people/I0/edit"), obj(
                "set", obj("given", "Tomás"), "seen", obj("given", "Tomás Iker"),
                "events", List.of(obj("ref", "RESI:1", "seen", obj("date", "1981"), "set", obj("date", "1983"))))));
        assertEquals(409, e.status);
        assertTrue(e.getMessage().contains("RESI:1.date is now '1982' (was '1981')"), e.getMessage());
        var p = client.get(f("people/I0"));
        assertEquals("Tomás Iker", map(p.get("fields")).get("given"), "the name was not changed either");
    }

    @Test
    void badValuesAreRefusedWithNothingApplied() throws Exception {
        var e = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("people/I0/edit"), obj("events", List.of(
                obj("ref", "RESI:0", "seen", obj("date", "1979"), "set", obj("date", "1978")),
                obj("add", "EVEN", "set", obj("date", "1990"))))));
        assertEquals(422, e.status);
        assertTrue(e.getMessage().contains("needs a type"), e.getMessage());
        assertEquals("1979", map(event(client.get(f("people/I0")), "RESI:0").get("fields")).get("date"));

        var bad = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("people/I0/edit"),
                obj("events", List.of(obj("add", "MARR", "set", obj("date", "1990"))))));
        assertEquals(422, bad.status, "a marriage is the family's");
    }

    @Test
    void notesNamesAndNicknames() throws Exception {
        var reply = client.post(f("people/I0/edit"), obj(
                "notes", List.of(obj("ref", "NOTE:0", "seen", obj("text", "Likes trains."), "set", obj("text", "Likes trains\nand boats.")),
                        obj("add", "", "set", obj("text", "Moved to Lisbon in 2018."))),
                "names", List.of(obj("ref", "NAME:1", "seen", obj("given", "Iker"), "set", obj("given", "Iker A.")),
                        obj("add", "", "set", obj("given", "Tomás", "surname", "Fábregas García", "type", "birth"))),
                "nicknames", obj("seen", List.of("Adri"), "set", List.of("Adri", "Fer"))));
        var p = map(reply.get("person"));
        assertEquals(List.of("Adri", "Fer"), p.get("nicknames"));
        var text = saved();
        assertTrue(text.contains("1 NOTE Likes trains\n2 CONT and boats.\n"), text);
        assertTrue(text.contains("1 NOTE Moved to Lisbon in 2018.\n"), text);
        assertTrue(text.contains("1 NAME Iker A. /Fábregas/\n2 TYPE aka\n"), text);
        assertTrue(text.contains("1 NAME Tomás /Fábregas García/\n2 TYPE birth\n"), text);
        assertTrue(text.contains("2 NICK Adri\n2 NICK Fer\n"), text);

        var shared = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("people/I0/edit"),
                obj("notes", List.of(obj("ref", "NOTE:1", "set", obj("text", "x"))))));
        assertEquals(422, shared.status);
        assertTrue(shared.getMessage().contains("edit it on its own page"), shared.getMessage());
    }

    @Test
    void familyEventsAndNotes() throws Exception {
        var reply = client.post(f("families/F1/edit"), obj(
                "events", List.of(obj("ref", "MARR:0", "seen", obj("date", "2010"), "set", obj("date", "12 mar 2010", "place", "Segovia")),
                        obj("add", "ENGA", "set", obj("date", "2009"))),
                "notes", List.of(obj("add", "", "set", obj("text", "Civil wedding.")))));
        var fam = map(reply.get("family"));
        assertEquals(List.of("ENGA:0", "MARR:0"), list(fam.get("events")).stream().map(e -> e.get("ref")).toList());
        var text = saved();
        assertTrue(text.contains("1 MARR\n2 DATE 12 MAR 2010\n2 PLAC Segovia\n"), text);
        assertTrue(text.contains("1 WIFE @I1@\n1 ENGA\n2 DATE 2009\n1 MARR"), text);
        assertTrue(text.contains("1 NOTE Civil wedding.\n"), text);
    }

    @Test
    void linesOfSourcesAndNotes() throws Exception {
        var src = client.get(f("records/S1"));
        var lines = list(src.get("fields"));
        assertEquals(List.of("0", "1"), lines.stream().map(l -> l.get("ref")).toList());
        assertEquals(true, lines.getFirst().get("editable"));

        client.post(f("records/S1/edit"), obj("lines", List.of(
                obj("ref", "0", "seen", obj("value", "Parish register"), "set", obj("value", "Parish register of Segovia")),
                obj("ref", "1", "seen", obj("tag", "AUTH"), "remove", true),
                obj("add", "PUBL", "set", obj("value", "Diocese of Segovia")))));
        client.post(f("records/N1/edit"), obj("seenText", "Shared research note.", "text", "Shared research note,\nupdated."));
        var text = saved();
        assertTrue(text.contains("0 @S1@ SOUR\n1 TITL Parish register of Segovia\n1 PUBL Diocese of Segovia\n"), text);
        assertTrue(text.contains("0 @N1@ NOTE Shared research note,\n1 CONT updated.\n"), text);

        var stale = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("records/S1/edit"), obj("lines", List.of(
                obj("ref", "0", "seen", obj("value", "Parish register"), "set", obj("value", "Register"))))));
        assertEquals(409, stale.status);
    }
}
