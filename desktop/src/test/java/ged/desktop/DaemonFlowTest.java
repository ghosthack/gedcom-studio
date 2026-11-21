package ged.desktop;

import ged.client.DaemonClient;
import ged.daemon.Home;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// The app's API calls against a real daemon, started from the test class path
/// in `target/test-home` on a port of its own, and stopped afterwards.
class DaemonFlowTest {

    static final String GED = """
            0 HEAD
            1 GEDC
            2 VERS 5.5.1
            1 CHAR UTF-8
            0 @I1@ INDI
            1 NAME Maria /Rossi/
            1 SEX F
            1 BIRT
            2 DATE 1850
            1 FAMS @F1@
            0 @I2@ INDI
            1 NAME Marco /Bianchi/
            1 SEX M
            1 FAMS @F1@
            0 @F1@ FAM
            1 HUSB @I2@
            1 WIFE @I1@
            0 TRLR
            """;

    static Api api;
    static DaemonClient client;

    @BeforeAll
    static void start() throws Exception {
        var home = Home.fromEnvironment();
        home.create();
        Files.writeString(home.settingsFile(), "port=7431\n");
        client = DaemonClient.ensure(home, Duration.ofSeconds(30));
        api = new Api(new Connection(home, client));
    }

    @AfterAll
    static void stop() {
        try {
            client.post("daemon/stop", Map.of("force", true));
        } catch (Exception ignored) {
            // already gone
        }
    }

    @Test
    void editsConflictsRelativesAndSaving(@TempDir Path dir) throws Exception {
        var file = dir.resolve("family.ged");
        Files.writeString(file, GED);
        var info = api.open(file.toString());
        var id = info.id();
        assertEquals("I1", info.home());

        // a form opens on Maria
        var maria = api.person(id, "I1");
        var form = new EditSession(maria.fields());

        // meanwhile someone else sets her occupation
        api.editPerson(id, "I1", Map.of("occupation", "Seamstress"), Map.of("occupation", ""));

        // the form changes the birth date and the occupation, from what it showed
        var values = new java.util.HashMap<>(maria.fields());
        values.put("birth.date", "12 jun 1850");
        values.put("occupation", "Tailor");
        var req = form.request(values);
        var e = assertThrows(DaemonClient.ApiException.class, () -> api.editPerson(id, "I1", req.set(), req.seen()));
        assertEquals(409, e.status);
        assertEquals(Boolean.TRUE, e.body.get("conflict"));

        // the form moves onto the latest values, keeping the user's changes
        var latest = Model.stringMap(Model.map(e.body, "person"), "fields");
        var rebase = form.rebase(values, latest);
        assertTrue(rebase.clashes().contains("occupation"));
        assertEquals("Tailor", rebase.values().get("occupation"));
        var again = form.request(rebase.values());
        var reply = api.editPerson(id, "I1", again.set(), again.seen());
        var after = Model.Person.from(Model.map(reply, "person"));
        assertEquals("12 JUN 1850", after.fields().get("birth.date"));
        assertEquals("Tailor", after.fields().get("occupation"));

        // a refused value names its field
        var bad = assertThrows(DaemonClient.ApiException.class,
                () -> api.editPerson(id, "I1", Map.of("death.date", "sometime"), Map.of()));
        assertEquals(422, bad.status);
        assertEquals("death.date", EditSession.fieldOf(bad.getMessage(), after.fields().keySet()));

        // add a child, then unlink her: nobody is deleted
        var added = api.addRelative(id, "I1", "child", RelativeDialogs.gedcomName("Ana", "Bianchi"), "F",
                Map.of("birth.date", "1879"), null);
        assertNotNull(added.person());
        assertFalse(added.message().isBlank());
        var ana = added.person().key();
        var withChild = api.person(id, "I1");
        assertEquals(ana, withChild.families().getFirst().children().getFirst().key());
        var gone = api.unlink(id, "I1", "children/" + ana);
        assertFalse(gone.message().isBlank());
        assertEquals("Ana Bianchi", api.person(id, ana).summary().name());

        // the revision moved, and the long poll reports it
        long rev = api.fileInfo(id).revision();
        assertTrue(rev > info.revision());
        assertEquals(rev, api.changes(id, info.revision(), 1));

        // another program changes the file: saving needs force
        Files.writeString(file, GED + "\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        var conflict = assertThrows(DaemonClient.ApiException.class, () -> api.save(id, false, null));
        assertEquals(409, conflict.status);
        assertEquals(Boolean.TRUE, conflict.body.get("changedOnDisk"));
        var saved = api.save(id, true, null);
        assertEquals(Boolean.TRUE, saved.get("saved"));
        assertTrue(Files.readString(file).contains("Tailor"));
        assertEquals(0, api.fileInfo(id).dirtyCount());
        api.close(id, false);
    }

    @Test
    void fieldDefinitionsAreGrouped() throws Exception {
        var defs = api.fields();
        assertEquals("name", defs.person().getFirst().group());
        assertTrue(defs.person().stream().anyMatch(d -> d.key().equals("given") && d.group().equals("name")));
        assertFalse(defs.dates().isBlank());
        assertFalse(defs.family().isEmpty());
    }
}
