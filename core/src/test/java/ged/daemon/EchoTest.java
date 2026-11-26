package ged.daemon;

import ged.client.DaemonClient;
import ged.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ged.json.Json.obj;
import static org.junit.jupiter.api.Assertions.*;

/// Every editable value, echoed everywhere the API and the shell show it.
///
/// Each nested class edits one kind of value through the apps' edit calls
/// (over real HTTP) and then checks the exact value in every place it
/// appears: the person's page, the people list in each sort, search, the
/// chips of relatives on other people's pages, family titles and lists,
/// pedigree and descendant charts, the users of notes and sources, and the
/// shell's prompt, `ls`, `show`, `tree` and `find` (run in a shell session
/// on the same file, as an attached shell would). Every edit must move the
/// file's `revision`, and every kind of edit must survive saving, closing
/// and reopening, a new daemon, and a forced stop's recovery copy.
class EchoTest {
    static final String GED = """
            0 HEAD
            1 GEDC
            2 VERS 5.5.1
            1 CHAR UTF-8
            0 @I1@ INDI
            1 NAME Juan /Pérez/
            1 SEX M
            1 BIRT
            2 DATE 1900
            1 DEAT
            2 DATE 1970
            1 FAMS @F1@
            0 @I2@ INDI
            1 NAME María /López/
            1 NAME María /López/ de Pérez
            2 TYPE married
            1 SEX F
            1 BIRT
            2 DATE 1905
            1 FAMS @F1@
            0 @I3@ INDI
            1 NAME Tomás /Pérez/
            1 SEX M
            1 BIRT
            2 DATE 20 JUN 1930
            2 PLAC Segovia
            1 RESI
            2 DATE 1950
            1 NOTE Likes trains.
            1 NOTE @N1@
            1 SOUR @S1@
            1 OBJE @M1@
            1 FAMC @F1@
            1 FAMS @F2@
            0 @I4@ INDI
            1 NAME Ana /Pérez/
            1 SEX F
            1 BIRT
            2 DATE 1932
            1 NOTE @N1@
            1 FAMC @F1@
            0 @I5@ INDI
            1 NAME Lucía /Gómez/
            1 SEX F
            1 BIRT
            2 DATE 1933
            1 FAMS @F2@
            0 @I6@ INDI
            1 NAME Tomás /Pérez/
            1 SEX M
            1 BIRT
            2 DATE 1960
            1 FAMC @F2@
            0 @F1@ FAM
            1 HUSB @I1@
            1 WIFE @I2@
            1 CHIL @I3@
            1 CHIL @I4@
            1 MARR
            2 DATE 1925
            0 @F2@ FAM
            1 HUSB @I3@
            1 WIFE @I5@
            1 CHIL @I6@
            1 MARR
            2 DATE 1955
            1 SOUR @S1@
            0 @N1@ NOTE Shared research note.
            1 CONT Second line.
            0 @S1@ SOUR
            1 TITL Parish register
            0 @M1@ OBJE
            1 FILE tomas.jpg
            2 FORM jpg
            1 TITL Portrait
            0 TRLR
            """;

    @TempDir Path dir;
    Home home;
    Server server;
    DaemonClient client;
    Path ged;
    String id;
    String session;
    long revision;

    @BeforeEach
    void start() throws Exception {
        home = new Home(dir.resolve("home"));
        startServer();
        ged = dir.resolve("perez.ged");
        Files.writeString(ged, GED);
        id = (String) client.post("files", obj("path", ged.toString())).get("id");
        revision = rev();
    }

    void startServer() throws IOException {
        server = new Server(home, new Server.Config("127.0.0.1", 0, Optional.empty(), Optional.empty(), Optional.empty()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(server.start());
        client = DaemonClient.running(home).orElseThrow();
        session = null;
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    // ---- helpers ---------------------------------------------------------------

    String f(String rest) { return "files/" + id + "/" + rest; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    long rev() throws IOException { return ((Number) client.get(f("")).get("revision")).longValue(); }

    /// Posts an edit; checks the reply's revision moved past the last one, and
    /// that `changes` and the file summary say so too (clients refresh on it).
    Map<String, Object> edit(String what, Map<String, ?> body) throws IOException {
        var reply = client.post(f(what + "/edit"), body);
        assertFalse(list(reply.get("changes")).isEmpty(), "the edit changed something: " + body);
        long now = ((Number) reply.get("revision")).longValue();
        assertTrue(now > revision, "revision moves on with " + body);
        assertEquals(now, ((Number) client.get(f("changes?after=" + revision + "&wait=0")).get("revision")).longValue());
        assertEquals(now, rev());
        assertEquals(true, client.get(f("")).get("dirty"));
        revision = now;
        return reply;
    }

    Map<String, Object> person(String key) throws IOException { return client.get(f("people/" + key)); }

    Map<String, Object> family(String key) throws IOException { return client.get(f("families/" + key)); }

    List<Map<String, Object>> people(String query) throws IOException { return list(client.get(f("people?" + query)).get("people")); }

    static Map<String, Object> withKey(List<Map<String, Object>> list, String key) {
        return list.stream().filter(x -> key.equals(x.get("key")) || key.equals(x.get("family")))
                .findFirst().orElseThrow(() -> new AssertionError("no " + key + " in " + list));
    }

    static List<Object> keys(List<Map<String, Object>> list) { return list.stream().map(x -> x.get("key")).toList(); }

    /// Every summary of `key` the API gives anywhere: lists, relatives' pages,
    /// families, charts and record users. Each must be the same.
    List<Map<String, Object>> summariesOf(String key) throws IOException {
        var out = new ArrayList<Map<String, Object>>();
        for (var sort : List.of("name", "born", "file")) out.add(withKey(people("sort=" + sort), key));
        for (var p : people("sort=file")) {
            var page = person((String) p.get("key"));
            for (var par : list(page.get("parents"))) {
                for (var who : List.of("father", "mother")) {
                    if (par.get(who) != null && key.equals(map(par.get(who)).get("key"))) out.add(map(par.get(who)));
                }
                list(par.get("siblings")).stream().filter(x -> key.equals(x.get("key"))).forEach(out::add);
            }
            list(page.get("halfSiblings")).stream().filter(x -> key.equals(x.get("key"))).forEach(out::add);
            for (var fam : list(page.get("families"))) {
                if (fam.get("partner") != null && key.equals(map(fam.get("partner")).get("key"))) out.add(map(fam.get("partner")));
                list(fam.get("children")).stream().filter(x -> key.equals(x.get("key"))).forEach(out::add);
            }
        }
        for (var fk : List.of("F1", "F2")) {
            var fam = family(fk);
            for (var who : List.of("husband", "wife")) {
                if (fam.get(who) != null && key.equals(map(fam.get(who)).get("key"))) out.add(map(fam.get(who)));
            }
            list(fam.get("children")).stream().filter(x -> key.equals(x.get("key"))).forEach(out::add);
        }
        collect(client.get(f("people/I6/pedigree?depth=3")), key, out);
        collect(client.get(f("people/I1/descendants?depth=3")), key, out);
        for (var rec : List.of("N1", "S1")) {
            list(client.get(f("records/" + rec)).get("users")).stream()
                    .filter(u -> key.equals(u.get("key")) && u.get("person") != null).forEach(u -> out.add(map(u.get("person"))));
        }
        return out;
    }

    /// The summaries of `key` in a pedigree or descendants chart.
    static void collect(Map<String, Object> chart, String key, List<Map<String, Object>> out) {
        if (key.equals(chart.get("key"))) out.add(summaryPart(chart));
        for (var who : List.of("father", "mother")) if (chart.get(who) instanceof Map<?, ?> m) collect(map(m), key, out);
        if (chart.get("families") instanceof List<?> fams) {
            for (var fam : list(fams)) {
                if (fam.get("partner") != null && key.equals(map(fam.get("partner")).get("key"))) out.add(map(fam.get("partner")));
                for (var c : list(fam.get("children"))) collect(c, key, out);
            }
        }
    }

    static final List<String> SUMMARY = List.of("key", "name", "given", "surname", "sex", "born", "died", "deceased", "lifespan");

    static Map<String, Object> summaryPart(Map<String, Object> m) {
        var out = new LinkedHashMap<String, Object>();
        for (var k : SUMMARY) out.put(k, m.get(k));
        return out;
    }

    /// Checks every summary of `key` has these values, and counts them.
    void everySummary(String key, Map<String, Object> expected, int atLeast) throws IOException {
        var all = summariesOf(key);
        assertTrue(all.size() >= atLeast, "found " + all.size() + " summaries of " + key + ", expected " + atLeast);
        for (var s : all) {
            for (var e : expected.entrySet()) {
                assertEquals(e.getValue(), s.get(e.getKey()), key + "." + e.getKey() + " in " + s);
            }
        }
        var page = person(key);
        for (var e : expected.entrySet()) assertEquals(e.getValue(), page.get(e.getKey()), key + "." + e.getKey() + " on their page");
    }

    /// Runs a line in a shell session on the file (as `gedcom-shell FILE`
    /// attached to the daemon would), starting at I3; returns its output.
    String shell(String line) throws IOException {
        if (session == null) session = (String) client.post(f("shell"), obj("color", false, "home", "I3")).get("session");
        var reply = client.post("shell/" + session + "/run", obj("line", line));
        assertEquals(true, reply.get("ok"), line + " → " + reply.get("output"));
        return (String) reply.get("output");
    }

    String prompt() throws IOException {
        if (session == null) shell("pwd");
        return (String) client.post("shell/" + session + "/run", obj("line", "pwd")).get("prompt");
    }

    /// The line of a listing or tree that mentions `token`.
    static String lineWith(String text, String token) {
        return text.lines().filter(l -> l.contains(token)).findFirst().orElseThrow(() -> new AssertionError("no line with " + token + " in\n" + text));
    }

    /// `show`'s line for a relative: `  son           I3     Name (lifespan)`.
    static String relative(String label, String key, String labelText) {
        return "  %-12s  %-6s %s".formatted(label, key, labelText);
    }

    // ---- the main name -------------------------------------------------------

    @Nested
    class MainName {
        static final String NAME = "Tomás Iker Fábregas Jr.";

        @BeforeEach
        void rename() throws IOException {
            var reply = edit("people/I3", obj(
                    "set", obj("given", "Tomás Iker", "surname", "Fábregas", "suffix", "Jr."),
                    "seen", obj("given", "Tomás", "surname", "Pérez", "suffix", "")));
            assertEquals(NAME, map(reply.get("person")).get("name"));
        }

        @Test
        void onThePersonsPage() throws IOException {
            var p = person("I3");
            assertEquals(NAME, p.get("name"));
            assertEquals("Tomás Iker", p.get("given"));
            assertEquals("Fábregas", p.get("surname"));
            assertEquals("Jr.", p.get("suffix"));
            assertEquals(Map.of("given", "Tomás Iker", "surname", "Fábregas", "suffix", "Jr."),
                    Map.of("given", map(p.get("fields")).get("given"), "surname", map(p.get("fields")).get("surname"),
                            "suffix", map(p.get("fields")).get("suffix")));
        }

        @Test
        void inEverySummaryOfThem() throws IOException {
            // three lists, their parents' and son's pages, their sister's, wife's, two families, two charts, two records
            everySummary("I3", obj("name", NAME, "given", "Tomás Iker", "surname", "Fábregas"), 14);
        }

        @Test
        void inTheListsOrderAndSearch() throws IOException {
            assertEquals(List.of("I3", "I5", "I2", "I4", "I1", "I6"), keys(people("sort=name")), "Fábregas sorts first now");
            assertEquals(List.of("I3"), keys(people("q=fabregas")), "accents folded");
            assertEquals(List.of("I3"), keys(people("q=TOMAS%20jr")));
            assertEquals("", withKey(people("q=fabregas"), "I3").get("matched"));
            assertFalse(keys(people("q=perez")).contains("I3"), "the old surname no longer finds them");
        }

        @Test
        void inFamilyTitles() throws IOException {
            var title = NAME + " & Lucía Gómez";
            assertEquals(title, family("F2").get("title"));
            assertEquals(title, withKey(list(client.get(f("families")).get("families")), "F2").get("title"));
            assertEquals(List.of("F2"), keys(list(client.get(f("families?q=fabregas%20lucia")).get("families"))));
            var users = list(client.get(f("records/S1")).get("users"));
            assertEquals(List.of(NAME + " (b. 1930)", title), users.stream().map(u -> u.get("title")).toList());
            assertEquals(NAME + " (b. 1930)", withKey(list(client.get(f("records/N1")).get("users")), "I3").get("title"));
        }

        @Test
        void inTheShell() throws IOException {
            assertEquals(NAME + " I3 * › ", prompt(), "unsaved");
            client.post(f("save"), obj());
            assertEquals(NAME + " I3 › ", prompt(), "saved");
            assertTrue(shell("show").startsWith(NAME + "  I3\n"), shell("show"));
            assertTrue(lineWith(shell("ls /people"), " I3 ").contains(NAME));
            assertTrue(lineWith(shell("find fabregas"), " I3 ").contains(NAME));
            assertTrue(shell("show @F2@").startsWith(NAME + " & Lucía Gómez  F2\n"));
            assertTrue(shell("show @I1@").contains(relative("son", "I3", NAME + " (b. 1930)")), shell("show @I1@"));
            assertTrue(shell("show @I6@").contains(relative("father", "I3", NAME + " (b. 1930)")));
            assertTrue(lineWith(shell("ls /families"), " F2 ").contains(NAME));
            assertTrue(shell("tree @I1@").contains(NAME + " (b. 1930)  I3"));
            assertTrue(shell("show @S1@").contains(relative("person", "I3", NAME + " (b. 1930)")));
            assertTrue(shell("show @S1@").contains("F2     " + NAME + " & Lucía Gómez"));
        }
    }

    /// A shell sitting in a family, or holding results of `find`, sees an
    /// app's edit at its next command.
    @Test
    void aShellsPlaceAndFindResultsFollowEdits() throws IOException {
        shell("cd /families/F2");
        assertTrue(lineWith(shell("find perez"), " I3 ").contains("Tomás Pérez"));
        edit("people/I3", obj("set", obj("given", "Tomás Iker")));
        edit("families/F2", obj("events", List.of(obj("ref", "MARR:0", "set", obj("date", "1956")))));
        assertTrue(prompt().startsWith("Tomás Iker Pérez & Lucía Gómez F2"), prompt());
        var show = shell("show");
        assertTrue(show.startsWith("Tomás Iker Pérez & Lucía Gómez  F2\n"), show);
        assertTrue(show.contains("Married         1956\n"), show);
        assertTrue(lineWith(shell("ls"), " I3 ").contains("Tomás Iker Pérez"), shell("ls"));
        assertTrue(lineWith(shell("ls /found"), " I3 ").contains("Tomás Iker Pérez"), shell("ls /found"));
    }

    // ---- sex -------------------------------------------------------------------

    @Nested
    class Sex {
        @BeforeEach
        void change() throws IOException {
            edit("people/I4", obj("set", obj("sex", "M"), "seen", obj("sex", "F")));
        }

        @Test
        void inEverySummary() throws IOException {
            everySummary("I4", obj("sex", "M"), 7);
            assertEquals("M", map(person("I4").get("fields")).get("sex"));
        }

        @Test
        void inTheShellsWording() throws IOException {
            assertTrue(shell("show @I3@").contains(relative("brother", "I4", "Ana Pérez (b. 1932)")), shell("show @I3@"));
            assertTrue(shell("show @I1@").contains(relative("son", "I4", "Ana Pérez (b. 1932)")));
            assertTrue(lineWith(shell("ls siblings"), " I4 ").contains("brother"));
            assertTrue(lineWith(shell("ls /people"), " I4 ").contains(" M "));
            assertTrue(shell("show /").contains("People          6  (4 male, 2 female, 0 unknown)"), shell("show /"));
        }

        @Test
        void aPartnersSexDoesNotMoveThemInTheFamily() throws IOException {
            edit("people/I5", obj("set", obj("sex", "U")));
            var fam = family("F2");
            assertEquals("I5", map(fam.get("wife")).get("key"));
            assertEquals("U", map(fam.get("wife")).get("sex"));
            assertEquals("wife", withKey(list(person("I5").get("families")), "F2").get("role"));
            assertTrue(lineWith(shell("ls spouses"), " I5 ").contains("spouse"));
        }
    }

    // ---- life events: born, died, deceased, lifespan ------------------------------

    @Nested
    class LifeEvents {
        @Test
        void anEmptyDeathMakesThemDeceasedAndRemovingItUndoes() throws IOException {
            edit("people/I4", obj("events", List.of(obj("add", "DEAT", "set", obj()))));
            everySummary("I4", obj("born", "1932", "died", "", "deceased", true, "lifespan", "1932–†"), 7);
            assertEquals("yes", map(person("I4").get("fields")).get("deceased"));
            assertTrue(shell("tree @I1@").contains("Ana Pérez (1932–†)  I4"));
            assertTrue(lineWith(shell("ls /people"), " I4 ").contains("†"));
            assertTrue(shell("show @I4@").contains("Died            yes\n"), shell("show @I4@"));

            var deat = list(person("I4").get("events")).stream().filter(e -> e.get("tag").equals("DEAT")).findFirst().orElseThrow();
            assertEquals("", deat.get("value"), "a bare death shows no value");
            edit("people/I4", obj("events", List.of(obj("ref", deat.get("ref"), "seen", obj("date", ""), "remove", true))));
            everySummary("I4", obj("died", "", "deceased", false, "lifespan", "b. 1932"), 7);
            assertEquals("no", map(person("I4").get("fields")).get("deceased"));
            assertTrue(shell("tree @I1@").contains("Ana Pérez (b. 1932)  I4"));
        }

        @Test
        void aDeathDateByTheForm() throws IOException {
            edit("people/I4", obj("set", obj("death.date", "3 mar 1990", "death.place", "Lanús")));
            everySummary("I4", obj("died", "1990", "deceased", true, "lifespan", "1932–1990"), 7);
            var e = list(person("I4").get("events")).getLast();
            assertEquals(List.of("DEAT", "3 MAR 1990", "Lanús"), List.of(e.get("tag"), e.get("date"), e.get("place")));
            assertTrue(shell("show @I4@").contains("Died            3 MAR 1990, Lanús\n"));
            assertTrue(shell("show @I4@").contains("Age             ~58 at death"));
            assertTrue(lineWith(shell("ls -l /people"), " I4 ").contains("Lanús"));
            assertEquals(List.of("I4"), keys(people("q=ana")));
        }

        @Test
        void aBurialAloneGivesTheDeathYear() throws IOException {
            edit("people/I4", obj("events", List.of(obj("add", "BURI", "set", obj("date", "1991", "place", "Chacarita")))));
            everySummary("I4", obj("died", "1991", "deceased", true, "lifespan", "1932–1991"), 7);
            assertTrue(lineWith(shell("ls /people"), " I4 ").contains("1991"));
        }

        @Test
        void editingTheBirthByItsEvent() throws IOException {
            edit("people/I6", obj("events", List.of(obj("ref", "BIRT:0", "seen", obj("date", "1960"), "set", obj("date", "abt 1920")))));
            everySummary("I6", obj("born", "~1920", "lifespan", "b. ~1920"), 7);
            assertEquals("ABT 1920", map(person("I6").get("fields")).get("birth.date"));
            assertEquals(List.of("I1", "I2", "I6", "I3", "I4", "I5"), keys(people("sort=born")));
            assertTrue(shell("tree @I3@").contains("Tomás Pérez (b. ~1920)  I6"), shell("tree @I3@"));
            assertTrue(lineWith(shell("ls -s birth /people"), " I6 ").contains("~1920"));
            assertTrue(shell("find -b 1915..1925").contains("Tomás Pérez"));
        }

        @Test
        void removingTheBirthEmptiesTheBornField() throws IOException {
            edit("people/I6", obj("events", List.of(obj("ref", "BIRT:0", "seen", obj("date", "1960"), "remove", true))));
            everySummary("I6", obj("born", "", "lifespan", ""), 7);
            assertEquals("I6", keys(people("sort=born")).getLast(), "the undated go last");
        }

        @Test
        void eventsStayInDateOrderAsTheyChange() throws IOException {
            edit("people/I3", obj("events", List.of(obj("add", "EVEN", "set", obj("type", "First communion", "date", "1938")))));
            assertEquals(List.of("BIRT:0", "EVEN:0", "RESI:0"), refs(person("I3")));
            edit("people/I3", obj("events", List.of(obj("ref", "RESI:0", "seen", obj("date", "1950"), "set", obj("date", "1935", "place", "Cuenca")))));
            var p = person("I3");
            assertEquals(List.of("BIRT:0", "RESI:0", "EVEN:0"), refs(p));
            var resi = list(p.get("events")).get(1);
            assertEquals(List.of("1935", 1935L, "Cuenca"), List.of(resi.get("date"), resi.get("year"), resi.get("place")));
            assertEquals(Map.of("type", "", "date", "1935", "place", "Cuenca", "value", ""), resi.get("fields"));
            assertEquals("1935", map(p.get("fields")).get("residence.date"));
            assertEquals("Cuenca", map(p.get("fields")).get("residence.place"));
            var show = shell("show");
            assertTrue(show.contains("Residence       1935, Cuenca\n"), show);
            assertTrue(show.contains("Event           First communion, 1938\n"), show);
            assertTrue(shell("find -p cuenca").contains("Tomás Pérez"));
        }

        @Test
        void aFactsValueAndType() throws IOException {
            edit("people/I3", obj("events", List.of(obj("add", "OCCU", "set", obj("value", "Sailor")))));
            var p = person("I3");
            var occu = list(p.get("events")).stream().filter(e -> e.get("tag").equals("OCCU")).findFirst().orElseThrow();
            assertEquals("Sailor", occu.get("value"));
            assertEquals("Sailor", map(p.get("fields")).get("occupation"));
            assertEquals("", map(p.get("present")).get("occupation"));
            edit("people/I3", obj("events", List.of(obj("ref", "OCCU:0", "seen", obj("value", "Sailor"), "set", obj("value", "Captain", "type", "Merchant navy")))));
            p = person("I3");
            occu = list(p.get("events")).stream().filter(e -> e.get("tag").equals("OCCU")).findFirst().orElseThrow();
            assertEquals(List.of("Captain", "Merchant navy"), List.of(occu.get("value"), occu.get("type")));
            assertEquals("Captain", map(p.get("fields")).get("occupation"));
            assertEquals("Merchant navy", map(p.get("present")).get("occupation"));
            assertTrue(shell("show").contains("Occupation      Captain, Merchant navy\n"));
        }

        static List<Object> refs(Map<String, Object> p) { return list(p.get("events")).stream().map(e -> e.get("ref")).toList(); }
    }

    // ---- families: married, divorced ----------------------------------------------

    @Nested
    class FamilyEvents {
        @Test
        void theMarriageDate() throws IOException {
            edit("families/F2", obj("events", List.of(obj("ref", "MARR:0", "seen", obj("date", "1955"), "set", obj("date", "12 mar 1956", "place", "Segovia")))));
            assertEquals("1956", family("F2").get("married"));
            assertEquals("12 MAR 1956", map(family("F2").get("fields")).get("marriage.date"));
            assertEquals("1956", withKey(list(client.get(f("families")).get("families")), "F2").get("married"));
            for (var who : List.of("I3", "I5")) {
                var married = map(withKey(list(person(who).get("families")), "F2").get("married"));
                assertEquals(List.of("12 MAR 1956", 1956L, "Segovia"), List.of(married.get("date"), married.get("year"), married.get("place")));
            }
            var chart = list(client.get(f("people/I1/descendants?depth=3")).get("families")).getFirst();
            var f2 = withKey(list(list(chart.get("children")).getFirst().get("families")), "F2");
            assertEquals("12 MAR 1956", map(f2.get("married")).get("date"));
            assertTrue(shell("tree @I3@").contains("[F2 m. 1956]"), shell("tree @I3@"));
            assertTrue(lineWith(shell("ls /families"), " F2 ").contains("1956"));
            assertTrue(shell("show @F2@").contains("Married         12 MAR 1956, Segovia\n"));
            assertTrue(shell("show @I5@").contains("Married         12 MAR 1956, Segovia\n"));
        }

        @Test
        void aDivorceAndRemovingTheMarriage() throws IOException {
            edit("families/F2", obj("events", List.of(obj("add", "DIV", "set", obj("date", "1970")),
                    obj("ref", "MARR:0", "seen", obj("date", "1955"), "remove", true))));
            var fam = family("F2");
            assertEquals("", fam.get("married"));
            assertEquals("yes", map(fam.get("fields")).get("divorced"));
            assertEquals("", withKey(list(client.get(f("families")).get("families")), "F2").get("married"));
            for (var who : List.of("I3", "I5")) {
                var f2 = withKey(list(person(who).get("families")), "F2");
                assertNull(f2.get("married"));
                assertEquals(true, f2.get("divorced"));
            }
            var chart = list(client.get(f("people/I3/descendants?depth=1")).get("families")).getFirst();
            assertEquals(true, chart.get("divorced"));
            assertNull(chart.get("married"));
            assertTrue(lineWith(shell("tree @I3@"), "∞").endsWith("[F2]"), shell("tree @I3@"));
            assertTrue(shell("show @F2@").contains("Divorced        1970\n"));
        }

        @Test
        void theDivorcedFieldOfTheForm() throws IOException {
            edit("families/F2", obj("set", obj("divorced", "yes")));
            assertEquals(true, withKey(list(person("I5").get("families")), "F2").get("divorced"));
            assertEquals(List.of("MARR:0", "DIV:0"), list(family("F2").get("events")).stream().map(e -> e.get("ref")).toList());
            edit("families/F2", obj("set", obj("divorced", "no")));
            assertEquals(false, withKey(list(person("I5").get("families")), "F2").get("divorced"));
        }

        @Test
        void theHiddenFieldOfTheForm() throws IOException {
            assertEquals(false, withKey(list(person("I5").get("families")), "F2").get("hidden"));
            assertEquals("no", map(family("F2").get("fields")).get("hidden"));
            edit("families/F2", obj("set", obj("hidden", "yes"), "seen", obj("hidden", "no")));
            for (var who : List.of("I3", "I5")) {
                var f2 = withKey(list(person(who).get("families")), "F2");
                assertEquals(true, f2.get("hidden"));
                var keys = List.copyOf(f2.keySet());
                assertEquals(keys.indexOf("divorced") + 1, keys.indexOf("hidden"), keys.toString());
            }
            assertEquals("yes", map(family("F2").get("fields")).get("hidden"));
            var chart = list(client.get(f("people/I3/descendants?depth=1")).get("families")).getFirst();
            assertEquals(true, chart.get("hidden"));
            var keys = List.copyOf(chart.keySet());
            assertEquals(keys.indexOf("divorced") + 1, keys.indexOf("hidden"), keys.toString());
            // nothing else hides it
            assertNotNull(withKey(list(client.get(f("families")).get("families")), "F2"));
            assertTrue(lineWith(shell("ls /families"), " F2 ").contains("F2"));
            assertTrue(shell("edit @F2@ hidden=yes").contains("no changes"));

            var refused = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("families/F2/edit"),
                    obj("set", obj("hidden", "perhaps"))));
            assertEquals(422, refused.status);

            client.post(f("save"), obj());
            assertTrue(Files.readString(ged).contains("1 _HIDE Y"));
            edit("families/F2", obj("set", obj("hidden", "no")));
            assertEquals(false, withKey(list(person("I5").get("families")), "F2").get("hidden"));
            client.post(f("save"), obj());
            assertFalse(Files.readString(ged).contains("_HIDE"));
        }
    }

    // ---- other names and nicknames ----------------------------------------------

    @Nested
    class OtherNames {
        @BeforeEach
        void add() throws IOException {
            edit("people/I3", obj("names", List.of(obj("add", "", "set", obj("given", "Tomás", "surname", "Pérez Gómez", "type", "birth"))),
                    "nicknames", obj("seen", List.of(), "set", List.of("Tito"))));
        }

        @Test
        void onThePage() throws IOException {
            var p = person("I3");
            assertEquals(List.of("Tito"), p.get("nicknames"));
            var other = list(p.get("otherNames")).getFirst();
            assertEquals("Tomás Pérez Gómez", other.get("name"));
            assertEquals("birth", other.get("type"));
            assertEquals("NAME:1", other.get("ref"));
            assertEquals(Map.of("given", "Tomás", "surname", "Pérez Gómez", "type", "birth"), other.get("fields"));
            assertEquals("Tomás Pérez", p.get("name"), "the main name is unchanged");
        }

        @Test
        void inSearch() throws IOException {
            assertEquals(List.of("I3"), keys(people("q=tito")));
            assertEquals("Tito", withKey(people("q=tito"), "I3").get("matched"));
            assertEquals(List.of("I3"), keys(people("q=gomez%20tomas")));
            assertEquals("Tomás Pérez Gómez", withKey(people("q=gomez%20tomas"), "I3").get("matched"));
            assertTrue(lineWith(shell("find tito"), " I3 ").contains("Tito"));
        }

        @Test
        void inTheShell() throws IOException {
            var show = shell("show");
            assertTrue(show.contains("Nickname        Tito\n"), show);
            assertTrue(show.contains("Also known as   Tomás Pérez Gómez  (birth)\n"), show);
        }

        /// Words after the surname (`María /López/ de Pérez`) stay there
        /// whatever part of the name is edited.
        @Test
        void aNameWithWordsAfterTheSurname() throws IOException {
            var other = list(person("I2").get("otherNames")).getFirst();
            assertEquals("María López de Pérez", other.get("name"));
            assertEquals(Map.of("given", "María de Pérez", "surname", "López", "type", "married"), other.get("fields"));
            edit("people/I2", obj("names", List.of(obj("ref", "NAME:1", "seen", obj("type", "married"), "set", obj("type", "aka")))));
            assertEquals("María López de Pérez", list(person("I2").get("otherNames")).getFirst().get("name"), "only the type changed");
            edit("people/I2", obj("names", List.of(obj("ref", "NAME:1", "seen", obj("surname", "López"), "set", obj("surname", "López Díaz")))));
            assertEquals("María López Díaz de Pérez", list(person("I2").get("otherNames")).getFirst().get("name"));
            assertEquals(Map.of("given", "María de Pérez", "surname", "López Díaz", "type", "aka"),
                    list(person("I2").get("otherNames")).getFirst().get("fields"));
            assertTrue(shell("show @I2@").contains("Also known as   María López Díaz de Pérez\n"), shell("show @I2@"));
            edit("people/I2", obj("names", List.of(obj("ref", "NAME:1", "seen", obj("given", "María de Pérez"), "set", obj("given", "Mariela de Pérez")))));
            assertEquals("Mariela López Díaz de Pérez", list(person("I2").get("otherNames")).getFirst().get("name"));
        }

        @Test
        void editedAndRemoved() throws IOException {
            edit("people/I3", obj("names", List.of(obj("ref", "NAME:1", "seen", obj("surname", "Pérez Gómez"), "set", obj("surname", "Pérez Ruiz", "type", "aka"))),
                    "nicknames", obj("seen", List.of("Tito"), "set", List.of("Tito", "Adri"))));
            var p = person("I3");
            assertEquals(List.of("Tito", "Adri"), p.get("nicknames"));
            assertEquals("Tomás Pérez Ruiz", list(p.get("otherNames")).getFirst().get("name"));
            assertEquals(List.of(), keys(people("q=gomez%20tomas")), "the old other name no longer finds them");
            assertEquals(List.of("I3"), keys(people("q=ruiz")));
            assertEquals(List.of("I3"), keys(people("q=adri")));
            assertTrue(shell("show").contains("Also known as   Tomás Pérez Ruiz\n"));
            assertTrue(shell("show").contains("Nicknames       Tito, Adri\n"));

            edit("people/I3", obj("names", List.of(obj("ref", "NAME:1", "seen", obj("surname", "Pérez Ruiz"), "remove", true)),
                    "nicknames", obj("seen", List.of("Tito", "Adri"), "set", List.of())));
            p = person("I3");
            assertEquals(List.of(), p.get("otherNames"));
            assertEquals(List.of(), p.get("nicknames"));
            assertEquals(List.of(), keys(people("q=tito")));
            assertEquals(List.of(), keys(people("q=ruiz")));
            assertFalse(shell("show").contains("Also known as"));
            assertFalse(shell("show").contains("Nickname"));
            assertTrue(shell("find tito").contains("no matches"));
        }
    }

    // ---- notes ---------------------------------------------------------------------

    @Nested
    class Notes {
        @Test
        void anInlineNoteEditedAddedAndRemoved() throws IOException {
            edit("people/I3", obj("notes", List.of(
                    obj("ref", "NOTE:0", "seen", obj("text", "Likes trains."), "set", obj("text", "Likes trains\nand boats.")),
                    obj("add", "", "set", obj("text", "Moved in 1950.")))));
            var p = person("I3");
            assertEquals(List.of("Likes trains\nand boats.", "Shared research note.\nSecond line.", "Moved in 1950."), p.get("notes"));
            assertEquals(List.of("Likes trains\nand boats.", "Shared research note.\nSecond line.", "Moved in 1950."),
                    list(p.get("noteItems")).stream().map(n -> n.get("text")).toList());
            assertEquals(List.of("NOTE:0", "NOTE:1", "NOTE:2"), list(p.get("noteItems")).stream().map(n -> n.get("ref")).toList());
            assertTrue(shell("show").contains("Notes\n  Likes trains\n  and boats.\n  Shared research note.\n  Second line.\n  Moved in 1950.\n"), shell("show"));

            edit("people/I3", obj("notes", List.of(obj("ref", "NOTE:2", "seen", obj("text", "Moved in 1950."), "remove", true),
                    obj("ref", "NOTE:0", "seen", obj("text", "Likes trains\nand boats."), "set", obj("text", "")))));
            assertEquals(List.of("Shared research note.\nSecond line."), person("I3").get("notes"));
            assertFalse(shell("show").contains("trains"));
        }

        @Test
        void unlinkingASharedNote() throws IOException {
            assertEquals(2, ((Number) withKey(list(client.get(f("records?kind=notes")).get("records")), "N1").get("usedBy")).intValue());
            edit("people/I3", obj("notes", List.of(obj("ref", "NOTE:1", "seen", obj("text", "Shared research note.\nSecond line."), "remove", true))));
            var p = person("I3");
            assertEquals(List.of("Likes trains."), p.get("notes"));
            assertEquals(List.of("S1", "M1"), keys(list(p.get("records"))), "no longer cites the note");
            assertEquals(1, ((Number) withKey(list(client.get(f("records?kind=notes")).get("records")), "N1").get("usedBy")).intValue());
            var n1 = client.get(f("records/N1"));
            assertEquals(1L, n1.get("usedBy"));
            assertEquals(List.of("I4"), keys(list(n1.get("users"))));
            assertTrue(lineWith(shell("ls /notes"), " N1 ").strip().endsWith(" 1"), shell("ls /notes"));
            assertFalse(shell("show @N1@").contains(" I3 "));
            assertFalse(shell("show").contains("Shared research note."));
        }

        /// GEDCOM 7 links a shared note with `SNOTE`: it is one of the
        /// person's notes, to edit (on its record) and unlink like any other.
        @Test
        void aGedcom7SharedNote() throws IOException {
            var seven = dir.resolve("seven.ged");
            Files.writeString(seven, """
                    0 HEAD
                    1 GEDC
                    2 VERS 7.0
                    0 @I1@ INDI
                    1 NAME Ana /Sol/
                    1 NOTE Inline.
                    1 SNOTE @N1@
                    0 @N1@ SNOTE Shared in seven.
                    0 TRLR
                    """);
            id = (String) client.post("files", obj("path", seven.toString())).get("id");
            revision = rev();
            var p = person("I1");
            assertEquals(List.of("Inline.", "Shared in seven."), p.get("notes"));
            assertEquals(List.of(obj("ref", "NOTE:0", "text", "Inline.", "shared", null),
                    obj("ref", "SNOTE:0", "text", "Shared in seven.", "shared", "N1")), p.get("noteItems"));

            edit("records/N1", obj("seenText", "Shared in seven.", "text", "Shared, edited."));
            assertEquals("Shared, edited.", list(person("I1").get("noteItems")).get(1).get("text"));
            var refused = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("people/I1/edit"),
                    obj("notes", List.of(obj("ref", "SNOTE:0", "set", obj("text", "x"))))));
            assertEquals(422, refused.status);
            assertTrue(refused.getMessage().contains("edit it on its own page"), refused.getMessage());

            edit("people/I1", obj("notes", List.of(obj("ref", "SNOTE:0", "seen", obj("text", "Shared, edited."), "remove", true))));
            assertEquals(List.of("Inline."), person("I1").get("notes"));
            assertEquals(1, list(person("I1").get("noteItems")).size());
            assertEquals(0L, client.get(f("records/N1")).get("usedBy"));
            assertTrue(Files.readString(seven).contains("1 SNOTE @N1@"), "unsaved yet");
            client.post(f("save"), obj());
            assertFalse(Files.readString(seven).contains("1 SNOTE"), Files.readString(seven));
        }

        @Test
        void aFamilyNote() throws IOException {
            edit("families/F2", obj("notes", List.of(obj("add", "", "set", obj("text", "Civil wedding.")))));
            assertEquals(List.of("Civil wedding."), family("F2").get("notes"));
            assertEquals("NOTE:0", list(family("F2").get("noteItems")).getFirst().get("ref"));
            assertTrue(shell("show @F2@").contains("Notes\n  Civil wedding.\n"));
        }
    }

    // ---- lines of notes, sources and media --------------------------------------

    @Nested
    class RecordLines {
        @Test
        void aSourcesTitle() throws IOException {
            edit("records/S1", obj("lines", List.of(obj("ref", "0", "seen", obj("value", "Parish register"), "set", obj("value", "Parish register of Segovia")))));
            var t = "Parish register of Segovia";
            assertEquals(t, client.get(f("records/S1")).get("title"));
            assertEquals(t, list(client.get(f("records/S1")).get("fields")).getFirst().get("value"));
            assertEquals(t, withKey(list(client.get(f("records?kind=sources")).get("records")), "S1").get("title"));
            assertEquals(List.of("S1"), keys(list(client.get(f("records?kind=sources&q=segovia")).get("records"))));
            assertEquals(t, withKey(list(person("I3").get("records")), "S1").get("title"));
            assertEquals(t, withKey(list(family("F2").get("records")), "S1").get("title"));
            assertTrue(lineWith(shell("ls /sources"), " S1 ").contains(t), shell("ls /sources"));
            assertTrue(shell("show @S1@").startsWith(t + "  S1\n"), shell("show @S1@"));
        }

        @Test
        void aSharedNotesText() throws IOException {
            edit("records/N1", obj("seenText", "Shared research note.\nSecond line.", "text", "Updated research note.\nSecond line."));
            var text = "Updated research note.\nSecond line.";
            assertEquals(text, client.get(f("records/N1")).get("text"));
            assertEquals("Updated research note.", client.get(f("records/N1")).get("title"));
            assertEquals("Updated research note.", withKey(list(client.get(f("records?kind=notes")).get("records")), "N1").get("title"));
            for (var who : List.of("I3", "I4")) {
                var p = person(who);
                assertTrue(list(p.get("noteItems")).stream().anyMatch(n -> text.equals(n.get("text")) && "N1".equals(n.get("shared"))), who);
                assertTrue(((List<?>) p.get("notes")).contains(text), who);
                assertEquals("Updated research note.", withKey(list(p.get("records")), "N1").get("title"));
            }
            assertTrue(shell("show @I4@").contains("Notes\n  Updated research note.\n  Second line.\n"), shell("show @I4@"));
            assertTrue(lineWith(shell("ls /notes"), " N1 ").contains("Updated research note."));
            assertTrue(shell("show @N1@").contains(text + "\n"));
        }

        @Test
        void aMediaTitle() throws IOException {
            edit("records/M1", obj("lines", List.of(obj("ref", "1", "seen", obj("value", "Portrait"), "set", obj("value", "Portrait of Tomás")))));
            var t = "Portrait of Tomás";
            assertEquals(t, list(person("I3").get("photos")).getFirst().get("title"));
            assertEquals(t, withKey(list(person("I3").get("records")), "M1").get("title"));
            assertEquals(t, withKey(list(client.get(f("records?kind=media")).get("records")), "M1").get("title"));
            assertTrue(lineWith(shell("ls /media"), " M1 ").contains(t));
        }

        @Test
        void addingAndRemovingLines() throws IOException {
            edit("records/S1", obj("lines", List.of(obj("add", "AUTH", "set", obj("value", "Diocese")))));
            var fields = list(client.get(f("records/S1")).get("fields"));
            assertEquals(List.of("TITL", "AUTH"), fields.stream().map(x -> x.get("tag")).toList().subList(0, 2));
            assertEquals("Diocese", fields.get(1).get("value"));
            assertTrue(shell("show @S1@").contains("Author          Diocese\n"));
            edit("records/S1", obj("lines", List.of(obj("ref", "0", "seen", obj("tag", "TITL"), "remove", true))));
            assertEquals("Diocese", client.get(f("records/S1")).get("title"), "the author stands in for a title");
            assertEquals("Diocese", withKey(list(person("I3").get("records")), "S1").get("title"));
        }
    }

    // ---- persistence ---------------------------------------------------------------

    /// One edit of every kind, unsaved.
    void editEverything() throws IOException {
        edit("people/I3", obj(
                "set", obj("given", "Tomás Iker", "surname", "Fábregas", "suffix", "Jr.", "occupation", "Sailor"),
                "events", List.of(obj("ref", "RESI:0", "seen", obj("date", "1950"), "set", obj("date", "1935", "place", "Cuenca")),
                        obj("add", "EVEN", "set", obj("type", "First communion", "date", "1938"))),
                "notes", List.of(obj("ref", "NOTE:0", "seen", obj("text", "Likes trains."), "set", obj("text", "Likes trains\nand boats.")),
                        obj("ref", "NOTE:1", "remove", true)),
                "names", List.of(obj("add", "", "set", obj("given", "Tomás", "surname", "Pérez Gómez", "type", "birth"))),
                "nicknames", obj("seen", List.of(), "set", List.of("Tito", "Adri"))));
        edit("people/I4", obj("set", obj("sex", "M"), "events", List.of(obj("add", "DEAT", "set", obj()))));
        edit("people/I6", obj("events", List.of(obj("ref", "BIRT:0", "remove", true))));
        edit("families/F2", obj("events", List.of(obj("ref", "MARR:0", "set", obj("date", "12 mar 1956")), obj("add", "DIV", "set", obj())),
                "notes", List.of(obj("add", "", "set", obj("text", "Civil wedding.")))));
        edit("records/S1", obj("lines", List.of(obj("ref", "0", "set", obj("value", "Parish register of Segovia")),
                obj("add", "AUTH", "set", obj("value", "Diocese")))));
        edit("records/N1", obj("text", "Updated research note.\nSecond line."));
        edit("records/M1", obj("lines", List.of(obj("ref", "1", "set", obj("value", "Portrait of Tomás")))));
    }

    /// Everything the API shows of the file, as JSON text, with the file's id
    /// and revisions taken out so copies can be compared.
    String snapshot() throws IOException {
        var all = new LinkedHashMap<String, Object>();
        for (var sort : List.of("name", "born", "file")) all.put("people " + sort, people("sort=" + sort));
        all.put("search", people("q=tito"));
        for (var p : people("sort=file")) {
            var key = (String) p.get("key");
            all.put(key, person(key));
            all.put(key + " pedigree", client.get(f("people/" + key + "/pedigree?depth=3")));
            all.put(key + " descendants", client.get(f("people/" + key + "/descendants?depth=3")));
        }
        all.put("families", client.get(f("families")));
        for (var fk : List.of("F1", "F2")) all.put(fk, family(fk));
        for (var kind : List.of("notes", "sources", "media")) {
            var records = client.get(f("records?kind=" + kind));
            all.put(kind, records);
            for (var r : list(records.get("records"))) all.put((String) r.get("key"), client.get(f("records/" + r.get("key"))));
        }
        return Json.write(all).replace(id, "ID").replaceAll("\"revision\":\\d+", "\"revision\":0");
    }

    @Test
    void everyKindOfEditSurvivesSavingClosingAndReopening() throws IOException {
        editEverything();
        var before = snapshot();
        assertTrue(before.contains("Tomás Iker Fábregas Jr."));

        var saved = client.post(f("save"), obj());
        assertEquals(true, saved.get("saved"));
        assertTrue(((Number) saved.get("revision")).longValue() > revision, "saving moves the revision too");
        assertEquals(false, client.get(f("")).get("dirty"));
        assertEquals(before, snapshot(), "saving changes nothing shown");

        client.post(f("close"), obj());
        assertEquals(id, client.post("files", obj("path", ged.toString())).get("id"));
        assertEquals(before, snapshot(), "read back from the file");

        server.stop();
        startServer();
        client.post("files", obj("path", ged.toString()));
        assertEquals(before, snapshot(), "read back by a new daemon");
        assertTrue(shell("show").startsWith("Tomás Iker Fábregas Jr.  I3\n"));
    }

    @Test
    void unsavedEditsSurviveAForcedStop() throws Exception {
        editEverything();
        var before = snapshot();
        client.post("daemon/stop", obj("force", true));
        Path copy = null;
        for (int i = 0; i < 100 && copy == null; i++) {
            if (Files.isDirectory(home.recoveryDir())) {
                try (var files = Files.list(home.recoveryDir())) {
                    copy = files.findFirst().orElse(null);
                }
            }
            if (copy == null) Thread.sleep(50);
        }
        assertNotNull(copy, "a recovery copy was written");
        assertFalse(Files.readString(ged).contains("Fábregas"), "the file itself is untouched");
        server.stop();

        startServer();
        var recovered = dir.resolve("perez.ged");
        Files.copy(copy, recovered, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        client.post("files", obj("path", recovered.toString()));
        assertEquals(before, snapshot(), "the recovery copy holds every edit");
    }
}
