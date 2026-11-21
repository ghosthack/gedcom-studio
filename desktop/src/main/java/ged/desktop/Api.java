package ged.desktop;

import ged.client.DaemonClient;
import ged.json.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/// The daemon API of `docs/api.md`, one method per call, returning [Model]
/// records. Refusals come back as [DaemonClient.ApiException] (status and
/// body), failures to reach the daemon as plain [IOException].
///
/// Blocking: call it off the EDT ([Bg]).
final class Api {

    private final Connection conn;
    private volatile Model.FieldDefs fieldDefs;

    Api(Connection conn) {
        this.conn = conn;
    }

    Connection connection() {
        return conn;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String file(String id) {
        return "files/" + enc(id) + "/";
    }

    // ---- files ------------------------------------------------------------------

    Model.Files files() throws IOException {
        return Model.Files.from(conn.get("files"));
    }

    Model.FileInfo open(String path) throws IOException {
        return Model.FileInfo.from(conn.post("files", Json.obj("path", path)));
    }

    Model.FileInfo fileInfo(String id) throws IOException {
        return Model.FileInfo.from(conn.get("files/" + enc(id)));
    }

    /// `save`; the reply map, for `saved`, `path` and `revision`.
    Map<String, Object> save(String id, boolean force, String as) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("force", force);
        body.put("as", as);
        return conn.post(file(id) + "save", body);
    }

    void close(String id, boolean force) throws IOException {
        conn.post(file(id) + "close", Json.obj("force", force));
    }

    void forget(String id) throws IOException {
        conn.post(file(id) + "forget", Map.of());
    }

    /// Waits up to `waitSeconds` for the file's revision to pass `after`.
    long changes(String id, long after, int waitSeconds) throws IOException {
        return Model.num(conn.getLong(file(id) + "changes?after=" + after + "&wait=" + waitSeconds), "revision");
    }

    // ---- people -----------------------------------------------------------------

    Model.People people(String id, String q, String sort, int limit) throws IOException {
        var path = file(id) + "people?sort=" + enc(sort) + "&limit=" + limit + (q == null || q.isBlank() ? "" : "&q=" + enc(q.strip()));
        return Model.People.from(conn.get(path));
    }

    Model.Person person(String id, String key) throws IOException {
        return Model.Person.from(conn.get(file(id) + "people/" + enc(key)));
    }

    Model.Pedigree pedigree(String id, String key, int depth) throws IOException {
        return Model.Pedigree.from(conn.get(file(id) + "people/" + enc(key) + "/pedigree?depth=" + depth));
    }

    Model.Descendants descendants(String id, String key, int depth) throws IOException {
        return Model.Descendants.from(conn.get(file(id) + "people/" + enc(key) + "/descendants?depth=" + depth));
    }

    /// `edit` on a person: the raw reply (`changes`, `person`, `revision`).
    Map<String, Object> editPerson(String id, String key, Map<String, String> set, Map<String, String> seen) throws IOException {
        return conn.post(file(id) + "people/" + enc(key) + "/edit", Json.obj("set", set, "seen", seen));
    }

    Map<String, Object> editFamily(String id, String key, Map<String, String> set, Map<String, String> seen) throws IOException {
        return conn.post(file(id) + "families/" + enc(key) + "/edit", Json.obj("set", set, "seen", seen));
    }

    /// `edit` on a person with a whole body: `set`/`seen` and the lists
    /// (`events`, `notes`, `names`, `nicknames`) of docs/api.md.
    Map<String, Object> editPerson(String id, String key, Map<String, Object> body) throws IOException {
        return conn.post(file(id) + "people/" + enc(key) + "/edit", body);
    }

    Map<String, Object> editFamily(String id, String key, Map<String, Object> body) throws IOException {
        return conn.post(file(id) + "families/" + enc(key) + "/edit", body);
    }

    /// `edit` on a note, source or media record (`text`, `seenText`, `lines`):
    /// the raw reply (`changes`, `record`, `revision`).
    Map<String, Object> editRecord(String id, String key, Map<String, Object> body) throws IOException {
        return conn.post(file(id) + "records/" + enc(key) + "/edit", body);
    }

    Model.Outcome addPerson(String id, String name, String sex, Map<String, String> fields) throws IOException {
        return Model.Outcome.from(conn.post(file(id) + "people", Json.obj("name", name, "sex", sex, "fields", fields)));
    }

    Model.Outcome addRelative(String id, String key, String relation, String name, String sex,
                              Map<String, String> fields, String family) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("relation", relation);
        body.put("name", name);
        body.put("sex", sex);
        body.put("fields", fields);
        if (family != null) body.put("family", family);
        return Model.Outcome.from(conn.post(file(id) + "people/" + enc(key) + "/add", body));
    }

    Model.Outcome link(String id, String key, String person, String as) throws IOException {
        return Model.Outcome.from(conn.post(file(id) + "people/" + enc(key) + "/link", Json.obj("person", person, "as", as)));
    }

    Model.Outcome unlink(String id, String key, String path) throws IOException {
        return Model.Outcome.from(conn.post(file(id) + "people/" + enc(key) + "/unlink", Json.obj("path", path)));
    }

    // ---- families and records -----------------------------------------------------

    java.util.List<Model.FamilySummary> families(String id) throws IOException {
        return Model.list(conn.get(file(id) + "families?limit=5000"), "families", Model.FamilySummary::from);
    }

    Model.Family family(String id, String key) throws IOException {
        return Model.Family.from(conn.get(file(id) + "families/" + enc(key)));
    }

    java.util.List<Model.RecordSummary> records(String id, String kind, String q) throws IOException {
        return Model.list(conn.get(file(id) + "records?kind=" + enc(kind) + (q == null || q.isBlank() ? "" : "&q=" + enc(q))),
                "records", Model.RecordSummary::from);
    }

    Model.RecordDetail record(String id, String key) throws IOException {
        return Model.RecordDetail.from(conn.get(file(id) + "records/" + enc(key)));
    }

    /// `GET fields`, asked once: the definitions don't change while the daemon runs.
    Model.FieldDefs fields() throws IOException {
        var f = fieldDefs;
        if (f == null) {
            f = Model.FieldDefs.from(conn.get("fields"));
            fieldDefs = f;
        }
        return f;
    }

    // ---- pictures ---------------------------------------------------------------

    /// The bytes behind an address the API gave (a photo or a media file).
    byte[] bytes(String url) throws IOException {
        return conn.bytes(url).orElseThrow(() -> new IOException("the picture is not available"));
    }

    /// The daemon's message for a failed call, fit to show people.
    static String message(Throwable e) {
        if (e instanceof DaemonClient.ApiException a) return a.getMessage();
        var m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    static int status(Throwable e) {
        return e instanceof DaemonClient.ApiException a ? a.status : 0;
    }
}
