package ged.daemon;

import ged.Fixtures;
import ged.client.DaemonClient;
import ged.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ged.json.Json.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The daemon end to end: a real server on a free port, spoken to over HTTP.
class DaemonTest {
    @TempDir Path dir;
    private Home home;
    private Server server;
    private DaemonClient client;
    private Path ged;
    private String id;
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws IOException {
        home = new Home(dir.resolve("home"));
        server = new Server(home, new Server.Config("127.0.0.1", 0, Optional.empty(), Optional.empty(), Optional.empty()),
                new PrintStream(log, true, StandardCharsets.UTF_8));
        assertTrue(server.start());
        client = DaemonClient.running(home).orElseThrow();
        ged = dir.resolve("cousins.ged");
        Files.writeString(ged, Fixtures.COUSINS);
        id = (String) client.post("files", obj("path", ged.toString())).get("id");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> raw(String method, String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String f(String rest) { return "files/" + id + "/" + rest; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    @Test
    void onlyOneDaemonAtATime() throws IOException {
        var second = new Server(home, new Server.Config("127.0.0.1", 0, Optional.empty(), Optional.empty(), Optional.empty()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertFalse(second.start(), "the lock is held");
    }

    @Test
    void everythingButPingNeedsTheToken() throws Exception {
        assertEquals(200, raw("GET", "/api/v1/ping", null).statusCode());
        assertEquals(401, raw("GET", "/api/v1/files", null).statusCode());
        assertEquals(401, raw("GET", "/api/v1/files", "wrong").statusCode());
        assertEquals(200, raw("GET", "/api/v1/files", client.token()).statusCode());
        assertEquals(404, raw("GET", "/api/v2/files", client.token()).statusCode());
    }

    @Test
    void opensAFileUnderAStableId() throws IOException {
        var again = client.post("files", obj("path", ged.toString()));
        assertEquals(id, again.get("id"));
        assertEquals(9L, again.get("people"));
        var files = client.get("files");
        assertEquals(1, list(files.get("files")).size());
        assertEquals(ged.toRealPath().toString(), list(files.get("recent")).getFirst().get("path"));
        var e = assertThrows(DaemonClient.ApiException.class, () -> client.post("files", obj("path", dir.resolve("nope.ged").toString())));
        assertEquals(422, e.status);
    }

    @Test
    void searchesAndShowsPeople() throws IOException {
        var found = client.get(f("people?q=rossi&sort=born"));
        assertEquals(5L, found.get("total"));
        assertEquals("Giovanni Rossi", list(found.get("people")).getFirst().get("name"));
        var luca = client.get(f("people/i7"));
        assertEquals("I7", luca.get("key"));
        var parents = list(luca.get("parents")).getFirst();
        assertEquals("Marco Rossi", map(parents.get("father")).get("name"));
        assertEquals("Sofia Verdi", map(list(luca.get("families")).getFirst().get("partner")).get("name"));
        assertEquals("", map(luca.get("fields")).get("occupation"));
        var pedigree = client.get(f("people/I9/pedigree?depth=3"));
        assertEquals("Marco Rossi", map(map(pedigree.get("father")).get("father")).get("name"));
        assertEquals(404, assertThrows(DaemonClient.ApiException.class, () -> client.get(f("people/I99"))).status);
    }

    @Test
    void editsAreCheckedAgainstWhatWasSeen() throws IOException {
        var seen = map(client.get(f("people/I3")).get("fields"));
        var r = client.post(f("people/I3/edit"), obj("set", obj("born", "3 mar 1851", "occupation", "Sailor"), "seen", seen));
        var changes = list(r.get("changes"));
        assertEquals(1, changes.size(), "an untouched value is no request");
        assertEquals("3 MAR 1851", changes.getFirst().get("after"));

        // another front end, still holding the old values, changes the same field
        var e = assertThrows(DaemonClient.ApiException.class,
                () -> client.post(f("people/I3/edit"), obj("set", obj("birth.date", "1851"), "seen", seen)));
        assertEquals(409, e.status);
        assertEquals(true, e.body.get("conflict"));
        assertTrue(e.getMessage().contains("birth.date is now '3 MAR 1851'"), e.getMessage());

        var bad = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("people/I3/edit"), obj("set", obj("born", "whenever"))));
        assertEquals(422, bad.status);
        assertEquals(422, assertThrows(DaemonClient.ApiException.class,
                () -> client.post(f("people/I3/edit"), obj("set", obj("wings", "2")))).status);
        assertEquals(400, assertThrows(DaemonClient.ApiException.class,
                () -> client.post(f("people/I3/edit"), obj("set", obj("given", 3)))).status);
        assertEquals(true, client.get("files/" + id).get("dirty"));
    }

    @Test
    void addsLinksAndUnlinksWithTheShellsRules() throws IOException {
        var added = client.post(f("people/I9/add"), obj("relation", "child", "name", "Nina Rossi", "sex", "F", "fields", obj("born", "1935")));
        var nina = map(added.get("person"));
        assertEquals("Nina Rossi", nina.get("name"));
        assertEquals("1935", nina.get("born"));
        assertEquals("Elena Rossi", map(list(nina.get("parents")).getFirst().get("mother")).get("name"));

        var luca = client.get(f("people/I7"));
        assertEquals(1, list(luca.get("families")).size());
        var refused = assertThrows(DaemonClient.ApiException.class,
                () -> client.post(f("people/I7/link"), obj("person", "I1", "as", "children")));
        assertEquals(422, refused.status);
        assertTrue(refused.getMessage().contains("ancestor"), refused.getMessage());

        var unlinked = client.post(f("people/I9/unlink"), obj("path", "children/" + nina.get("key")));
        assertTrue(((String) unlinked.get("message")).contains("Nina Rossi"), (String) unlinked.get("message"));
        assertEquals(400, assertThrows(DaemonClient.ApiException.class,
                () -> client.post(f("people/I9/unlink"), obj("path", "../I1"))).status);
    }

    @Test
    void savesAndRefusesAFileChangedOnDisk() throws Exception {
        client.post(f("people/I9/edit"), obj("set", obj("occupation", "Pilot")));
        var saved = client.post(f("save"), obj());
        assertEquals(true, saved.get("saved"));
        assertTrue(Files.readString(ged).contains("1 OCCU Pilot"));
        assertEquals(false, client.get("files/" + id).get("dirty"));

        client.post(f("people/I9/edit"), obj("set", obj("occupation", "Doctor")));
        Thread.sleep(1100); // a coarse file clock must see the change
        Files.writeString(ged, Files.readString(ged) + "\n");
        var e = assertThrows(DaemonClient.ApiException.class, () -> client.post(f("save"), obj()));
        assertEquals(409, e.status);
        assertEquals(true, e.body.get("changedOnDisk"));
        assertEquals(true, client.post(f("save"), obj("force", true)).get("saved"));
    }

    @Test
    void changesWakeWaitingClients() throws Exception {
        long rev = ((Number) client.get("files/" + id).get("revision")).longValue();
        var waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return client.getLong(f("changes?after=" + rev + "&wait=20")).get("revision");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        client.post(f("people/I9/edit"), obj("set", obj("given", "Elio")));
        assertTrue(((Number) waiting.get(10, TimeUnit.SECONDS)).longValue() > rev);
    }

    @Test
    void shellSessionsRunCommandsAndRoundTripForms() throws IOException {
        var s = (String) client.post(f("shell"), obj("home", "I3")).get("session");
        var step = client.post("shell/" + s + "/run", obj("line", "pwd; ls"));
        assertTrue(((String) step.get("output")).contains("/people/I3"), (String) step.get("output"));
        assertEquals(false, step.get("exit"));

        step = client.post("shell/" + s + "/run", obj("line", "edit"));
        var form = (String) map(step.get("edit")).get("text");
        assertTrue(form.contains("occupation:"), form);
        step = client.post("shell/" + s + "/edit", obj("text", form.replaceFirst("(?m)^occupation:.*$", "occupation: Admiral")));
        assertTrue(((String) step.get("output")).contains("Sailor → Admiral"), (String) step.get("output"));
        assertEquals("Admiral", map(client.get(f("people/I3")).get("fields")).get("occupation"), "the web sees the shell's edit");

        var c = client.post("shell/" + s + "/complete", obj("line", "cd chi", "cursor", 6));
        assertEquals("children", list(c.get("candidates")).getFirst().get("text"));

        assertEquals(true, client.post("shell/" + s + "/run", obj("line", "exit")).get("exit"));
        client.delete("shell/" + s);
        assertEquals(404, assertThrows(DaemonClient.ApiException.class, () -> client.post("shell/" + s + "/run", obj("line", "ls"))).status);
    }

    @Test
    void picturesGoByTheirSignedAddressOnly() throws Exception {
        var pic = dir.resolve("elena.jpg");
        Files.write(pic, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        Files.writeString(ged, Fixtures.COUSINS.replace("0 @I9@ INDI\n", "0 @I9@ INDI\n1 OBJE\n2 FILE elena.jpg\n"));
        client.post(f("close"), obj("force", true));
        id = (String) client.post("files", obj("path", ged.toString())).get("id");
        var photo = (String) client.get(f("people/I9")).get("photo");
        assertNotNull(photo);
        assertTrue(photo.contains("?sig="), photo);
        var ok = raw("GET", photo, null);
        assertEquals(200, ok.statusCode());
        assertEquals("image/jpeg", ok.headers().firstValue("Content-Type").orElse(""));
        assertEquals(401, raw("GET", photo.replace("photos/0", "photos/1"), null).statusCode(), "the signature is for one address");
        assertEquals(401, raw("GET", photo.substring(0, photo.length() - 2) + "xx", null).statusCode());
        assertNull(map(list(client.get(f("people?q=giovanni")).get("people")).getFirst()).get("photo"));
    }

    @Test
    void stoppingWithUnsavedEditsAsksFirstAndKeepsACopy() throws IOException {
        client.post(f("people/I9/edit"), obj("set", obj("occupation", "Pilot")));
        var e = assertThrows(DaemonClient.ApiException.class, () -> client.post("daemon/stop", obj()));
        assertEquals(409, e.status);
        server.stop();
        try (var copies = Files.list(home.recoveryDir())) {
            var copy = copies.findFirst().orElseThrow();
            assertTrue(Files.readString(copy).contains("1 OCCU Pilot"));
        }
        assertFalse(Files.readString(ged).contains("Pilot"), "the file itself is untouched");
        assertFalse(Files.exists(home.stateFile()));
    }

    @Test
    void fieldDefinitionsDescribeTheForms() throws IOException {
        var fields = client.get("fields");
        var person = list(fields.get("person"));
        var birth = person.stream().filter(x -> x.get("key").equals("birth.date")).findFirst().orElseThrow();
        assertEquals("date", birth.get("kind"));
        assertEquals("life", birth.get("group"));
        assertEquals("Birth date", birth.get("label"));
        assertTrue(list(fields.get("family")).stream().anyMatch(x -> x.get("key").equals("divorced") && x.get("kind").equals("yesno")));
        assertNotNull(Json.write(fields));
    }

    @Test
    void theWebAppIsServedWithAStrictPolicy() throws Exception {
        var index = raw("GET", "/", null);
        if (Server.class.getResource("/ged/web/index.html") == null) return; // built without the web app
        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
        assertEquals(404, raw("GET", "/../../etc/passwd.txt", null).statusCode());
    }

    @Test
    void familiesAreSearchedAndAPartnerFillsAnEmptyPlace() throws IOException {
        var found = client.get(f("families?q=verdi%20rossi"));
        assertEquals(List.of("F3", "F4"), list(found.get("families")).stream().map(x -> x.get("key")).toList());

        // a family with one partner, and a new husband for it
        var nina = map(client.post(f("people"), obj("name", "Nina /Neri/", "sex", "F")).get("person"));
        client.post(f("people/" + nina.get("key") + "/add"), obj("relation", "child", "name", "Ugo Neri"));
        var family = (String) list(client.get(f("people/" + nina.get("key"))).get("families")).getFirst().get("family");
        var r = client.post(f("people/" + nina.get("key") + "/add"), obj("relation", "spouse", "name", "Rino Neri", "sex", "M", "family", family));
        assertFalse(((String) r.get("message")).contains("\n"), "one line: " + r.get("message"));
        var fam = client.get(f("families/" + family));
        assertEquals("Rino Neri", map(fam.get("husband")).get("name"));
        assertEquals(1, list(fam.get("children")).size(), "the same family, not a new one");
        assertEquals(422, assertThrows(DaemonClient.ApiException.class, () -> client.post(f("people/" + nina.get("key") + "/add"),
                obj("relation", "spouse", "name", "X Y", "family", family))).status);
    }

    @Test
    void changesSayWhichDaemonCounted() throws IOException {
        var c = client.get(f("changes?after=-1&wait=0"));
        assertEquals(client.get("daemon").get("instance"), c.get("instance"));
        assertEquals(c.get("instance"), client.get("files/" + id).get("instance"));
        assertEquals(400, assertThrows(DaemonClient.ApiException.class, () -> client.get(f("changes?after=x"))).status);
    }

    @Test
    void summariesCarryThePhotoCrop() throws IOException {
        Files.write(dir.resolve("group.jpg"), new byte[] {(byte) 0xFF, (byte) 0xD8});
        Files.writeString(ged, Fixtures.COUSINS.replace("0 @I9@ INDI\n", "0 @I9@ INDI\n1 OBJE\n2 FILE group.jpg\n2 _CROP\n3 LEFT 10\n3 TOP 20\n3 WIDTH 30\n3 HEIGHT 40\n"));
        client.post(f("close"), obj("force", true));
        id = (String) client.post("files", obj("path", ged.toString())).get("id");
        var elena = list(client.get(f("people?q=elena")).get("people")).getFirst();
        assertEquals(Map.of("left", 10L, "top", 20L, "width", 30L, "height", 40L), elena.get("photoCrop"));
    }
}
