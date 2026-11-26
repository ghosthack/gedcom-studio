package ged.daemon;

import ged.edit.Conflict;
import ged.edit.FamilyForm;
import ged.edit.PersonForm;
import ged.edit.RecordEdits;
import ged.gedcom.Charsets;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;
import ged.model.RecordKind;
import ged.shell.Completer;
import ged.shell.Headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static ged.json.Json.obj;

/// The daemon's HTTP API, version 1 (`/api/v1/...`; see `docs/api.md`).
/// [Server] handles the transport and the token; this class turns a request
/// into a [Reply].
final class Api {
    static final int VERSION = 1;

    /// A request, already authenticated.
    record Request(String method, List<String> path, Map<String, String> query, Map<String, Object> body) {
        String param(String name, String fallback) { return query.getOrDefault(name, fallback); }

        int intParam(String name, int fallback, int min, int max) {
            try {
                return Math.clamp(Integer.parseInt(query.getOrDefault(name, "" + fallback)), min, max);
            } catch (NumberFormatException e) {
                throw new Failure(400, name + " must be a number");
            }
        }

        String string(String key) {
            var v = body.get(key);
            if (v == null) return null;
            if (v instanceof String s) return s;
            throw new Failure(400, key + " must be a string");
        }

        String required(String key) {
            var v = string(key);
            if (v == null || v.isBlank()) throw new Failure(400, key + " is required");
            return v;
        }

        boolean flag(String key) { return Boolean.TRUE.equals(body.get(key)); }

        /// A list of edits (`events`, `notes`, `names`, `lines`): each
        /// `{"ref", "seen", "set"}`, `{"ref", "seen", "remove": true}` or
        /// `{"add": TAG, "set"}`.
        List<RecordEdits.Op> ops(String key) {
            var v = body.get(key);
            if (v == null) return List.of();
            if (!(v instanceof List<?> list)) throw new Failure(400, key + " must be a list");
            var out = new ArrayList<RecordEdits.Op>();
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Map<?, ?> m)) throw new Failure(400, key + "[" + i + "] must be an object");
                @SuppressWarnings("unchecked")
                var item = new Request(method, path, query, (Map<String, Object>) m);
                var where = key + "[" + i + "]";
                var set = item.strings("set");
                var seen = item.strings("seen");
                if (m.containsKey("add")) {
                    var tag = m.get("add") instanceof String t ? t : "";
                    out.add(new RecordEdits.Op.Add(tag, set));
                } else {
                    var ref = item.string("ref");
                    if (ref == null || ref.isBlank()) throw new Failure(400, where + ": ref is required (or add)");
                    out.add(item.flag("remove") ? new RecordEdits.Op.Remove(ref, seen) : new RecordEdits.Op.Edit(ref, seen, set));
                }
            }
            return out;
        }

        /// `{"seen": [..], "set": [..]}` lists of strings; null when absent.
        List<String> list(String key) {
            var v = body.get(key);
            if (v == null) return null;
            if (!(v instanceof List<?> l) || !l.stream().allMatch(x -> x instanceof String)) throw new Failure(400, key + " must be a list of strings");
            return l.stream().map(String.class::cast).toList();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> strings(String key) {
            var v = body.get(key);
            if (v == null) return Map.of();
            if (!(v instanceof Map<?, ?> m)) throw new Failure(400, key + " must be an object");
            var out = new LinkedHashMap<String, String>();
            for (var e : ((Map<String, Object>) m).entrySet()) {
                if (!(e.getValue() instanceof String s)) throw new Failure(400, key + "." + e.getKey() + " must be a string");
                out.put(e.getKey(), s);
            }
            return out;
        }
    }

    sealed interface Reply permits JsonReply, FileReply {}
    record JsonReply(int status, Object body) implements Reply {}
    record FileReply(Path file) implements Reply {}

    /// A refusal with its HTTP status; `extra` joins the error object.
    static final class Failure extends RuntimeException {
        final int status;
        final Map<String, Object> extra;

        Failure(int status, String message) { this(status, message, Map.of()); }

        Failure(int status, String message, Map<String, Object> extra) {
            super(message);
            this.status = status;
            this.extra = extra;
        }
    }

    private final Workspace workspace;
    private final ShellSessions sessions;
    private final Function<String, String> sign;
    private final Runnable stop;
    private final Map<String, Object> about;

    Api(Workspace workspace, ShellSessions sessions, Function<String, String> sign, Runnable stop, Map<String, Object> about) {
        this.workspace = workspace;
        this.sessions = sessions;
        this.sign = sign;
        this.stop = stop;
        this.about = about;
    }

    static JsonReply ok(Object body) { return new JsonReply(200, body); }

    Reply handle(Request r) throws IOException, InterruptedException {
        var p = r.path();
        var m = r.method();
        if (p.isEmpty()) throw new Failure(404, "no such endpoint");
        return switch (p.getFirst()) {
            case "daemon" -> daemon(r);
            case "fields" -> ok(fields());
            case "files" -> {
                if (p.size() == 1) yield m.equals("POST") ? openFile(r) : ok(files());
                var doc = workspace.get(p.get(1)).orElseThrow(() -> new Failure(404, "no open file " + p.get(1)));
                yield file(r, doc, p.subList(2, p.size()));
            }
            case "shell" -> shell(r);
            default -> throw new Failure(404, "no such endpoint");
        };
    }

    // ---- the daemon --------------------------------------------------------

    private Reply daemon(Request r) {
        if (r.path().size() == 2 && r.path().get(1).equals("stop") && r.method().equals("POST")) {
            var dirty = workspace.documents().stream().filter(d -> d.store().read(Genealogy::isDirty)).map(Workspace.Document::name).toList();
            if (!dirty.isEmpty() && !r.flag("force")) {
                throw new Failure(409, "unsaved edits in " + String.join(", ", dirty)
                        + "; save them first, or stop with force (copies go to the recovery folder)", obj("dirty", dirty));
            }
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100); // let this reply go out first
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                stop.run();
            });
            return ok(obj("stopping", true));
        }
        var out = new LinkedHashMap<>(about);
        out.put("files", workspace.documents().size());
        return ok(out);
    }

    // ---- files -------------------------------------------------------------

    private Map<String, Object> files() {
        return obj("files", workspace.documents().stream().map(this::fileSummary).toList(), "recent", workspace.recent());
    }

    private Reply openFile(Request r) {
        var path = Path.of(expandHome(r.required("path")));
        if (!path.isAbsolute()) throw new Failure(400, "path must be absolute");
        if (Files.isDirectory(path)) throw new Failure(422, path + " is a folder, not a GEDCOM file");
        try {
            return ok(fileSummary(workspace.open(path)));
        } catch (IOException e) {
            throw new Failure(422, "can't open " + path + ": " + e.getMessage());
        }
    }

    static String expandHome(String path) {
        return path.equals("~") || path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path;
    }

    private Map<String, Object> fileSummary(Workspace.Document doc) {
        return doc.store().read(g -> {
            var counts = new LinkedHashMap<String, Object>();
            for (var k : RecordKind.values()) counts.put(k.dirName, g.records(k).size());
            boolean changed;
            try {
                changed = g.changedOnDisk();
            } catch (RuntimeException e) {
                changed = false;
            }
            return obj(
                    "id", doc.id(),
                    "name", doc.name(),
                    "path", doc.path().toString(),
                    "gedcom", g.gedcomVersion(),
                    "encoding", g.encoding().detected(),
                    "people", g.people().size(),
                    "families", g.families().size(),
                    "records", counts,
                    "dirty", g.isDirty(),
                    "dirtyCount", g.dirtyCount(),
                    "changedOnDisk", changed,
                    "revision", g.revision(),
                    "instance", about.get("instance"),
                    "home", g.people().stream().findFirst().map(Person::key).orElse(null),
                    "warnings", g.warnings());
        });
    }

    private Views views(Genealogy g, Workspace.Document doc) {
        return new Views(g, "/api/v1/files/" + doc.id(), sign);
    }

    private Reply file(Request r, Workspace.Document doc, List<String> p) throws InterruptedException {
        if (p.isEmpty()) return ok(fileSummary(doc));
        var store = doc.store();
        return switch (p.getFirst()) {
            case "close" -> {
                post(r);
                try {
                    workspace.close(doc.id(), r.flag("force"));
                } catch (IllegalStateException e) {
                    throw new Failure(409, e.getMessage());
                }
                sessions.endFor(doc.id());
                yield ok(obj("closed", true));
            }
            case "forget" -> {
                post(r);
                workspace.forget(doc.id());
                yield ok(obj("forgotten", true));
            }
            case "changes" -> {
                long after;
                try {
                    after = Long.parseLong(r.param("after", "-1"));
                } catch (NumberFormatException e) {
                    throw new Failure(400, "after must be a revision number");
                }
                int wait = r.intParam("wait", 25, 0, 60);
                yield ok(obj("revision", store.awaitChange(after, Duration.ofSeconds(wait)), "instance", about.get("instance")));
            }
            case "people" -> people(r, doc, p.subList(1, p.size()));
            case "families" -> families(r, doc, p.subList(1, p.size()));
            case "records" -> records(r, doc, p.subList(1, p.size()));
            case "media" -> {
                if (p.size() != 3 || !p.get(2).equals("file")) throw new Failure(404, "no such endpoint");
                yield store.read(g -> {
                    var rec = g.record(p.get(1)).filter(x -> x.tag().equals("OBJE")).orElseThrow(() -> new Failure(404, "no media " + p.get(1)));
                    var file = rec.all("FILE").filter(f -> !f.value().isBlank()).findFirst().orElseThrow(() -> new Failure(404, "no file"));
                    return new FileReply(g.mediaPath(file.value().strip()).orElseThrow(() -> new Failure(404, "the file is not on this computer")));
                });
            }
            case "save" -> {
                post(r);
                yield save(r, doc);
            }
            case "shell" -> {
                post(r);
                var s = sessions.create(doc, r.flag("color"), Optional.ofNullable(r.string("home")));
                yield ok(obj("session", s.id, "prompt", s.prompt()));
            }
            default -> throw new Failure(404, "no such endpoint");
        };
    }

    private static void post(Request r) {
        if (!r.method().equals("POST")) throw new Failure(405, "use POST");
    }

    private Reply save(Request r, Workspace.Document doc) {
        var as = r.string("as");
        boolean force = r.flag("force");
        return doc.store().write(g -> {
            var target = as == null || as.isBlank() ? doc.path() : Path.of(expandHome(as));
            if (!target.isAbsolute()) throw new Failure(400, "as must be an absolute path");
            int records = g.dirtyCount();
            try {
                if (!g.isDirty() && as == null) return ok(obj("saved", false, "records", 0, "path", target.toString()));
                if (!force && !g.isSource(target) && Files.exists(target)) {
                    throw new Failure(409, target + " already exists; save with force to replace it", obj("exists", true));
                }
                if (!force && g.isSource(target) && g.changedOnDisk()) {
                    throw new Failure(409, doc.name() + " was changed by another program since it was opened; "
                            + "save a copy to compare, or save with force to overwrite it", obj("changedOnDisk", true));
                }
                g.save(target);
            } catch (Charsets.UnmappableException e) {
                throw new Failure(422, e.getMessage() + "; nothing was written");
            } catch (IOException e) {
                throw new Failure(500, "can't write " + target + ": " + e.getMessage());
            }
            return ok(obj("saved", true, "records", records, "path", target.toString(), "revision", g.revision()));
        });
    }

    // ---- people --------------------------------------------------------------

    private Reply people(Request r, Workspace.Document doc, List<String> p) {
        var store = doc.store();
        if (p.isEmpty()) {
            if (r.method().equals("POST")) return added(doc, "/", "person", r);
            var q = r.param("q", "").strip();
            int offset = r.intParam("offset", 0, 0, Integer.MAX_VALUE);
            int limit = r.intParam("limit", 200, 1, 5000);
            var sort = r.param("sort", "name");
            return store.read(g -> {
                var views = views(g, doc);
                var list = new ArrayList<>(q.isEmpty() ? g.people() : g.people().stream().filter(x -> g.nameMatches(x, q)).toList());
                Comparator<Person> byName = Comparator.comparing((Person x) -> Genealogy.fold(x.name().surname()))
                        .thenComparing(x -> Genealogy.fold(x.name().given()));
                switch (sort) {
                    case "name" -> list.sort(byName);
                    // the undated go last, as in the shell's `ls -s birth`
                    case "born" -> list.sort(Comparator.comparing((Person x) -> !x.birth().hasYear())
                            .thenComparing(Person::birth, GedDate.CHRONOLOGICAL).thenComparing(byName));
                    case "file" -> { }
                    default -> throw new Failure(400, "sort is name, born or file");
                }
                var page = list.stream().skip(offset).limit(limit).map(x -> {
                    var s = views.summary(x);
                    if (!q.isEmpty()) s.put("matched", g.matchedAs(x, q));
                    return s;
                }).toList();
                return ok(obj("total", list.size(), "offset", offset, "people", page, "revision", g.revision()));
            });
        }
        var key = p.getFirst();
        if (p.size() == 1) {
            return store.read(g -> ok(views(g, doc).person(person(g, key))));
        }
        return switch (p.get(1)) {
            case "edit" -> {
                post(r);
                yield editPerson(r, doc, key);
            }
            case "add" -> {
                post(r);
                var relation = r.required("relation").toLowerCase(Locale.ROOT);
                if (!List.of("child", "spouse", "partner", "husband", "wife", "father", "mother", "sibling").contains(relation)) {
                    throw new Failure(400, "relation is child, spouse, husband, wife, father, mother or sibling");
                }
                // with several families, say which one a child joins; a partner can fill a family's empty place
                var family = r.string("family");
                if (family != null && !family.isBlank()) {
                    if (!family.matches("[A-Za-z0-9_-]+")) throw new Failure(400, "family is a family key, such as F2");
                    relation = switch (relation) {
                        case "child" -> "families/" + family + "/children";
                        case "husband", "wife" -> "/families/" + family + "/" + relation;
                        case "spouse", "partner" -> {
                            var sex = r.string("sex") == null ? "" : r.string("sex").strip().toUpperCase(Locale.ROOT);
                            if (sex.startsWith("M")) yield "/families/" + family + "/husband";
                            if (sex.startsWith("F")) yield "/families/" + family + "/wife";
                            throw new Failure(422, "say whether the new partner is the husband or the wife (relation husband or wife)");
                        }
                        default -> throw new Failure(400, "family goes with relation child, spouse, husband or wife");
                    };
                }
                yield added(doc, "/people/" + key(doc, key), relation, r);
            }
            case "link" -> {
                post(r);
                var other = key(doc, r.required("person"));
                var as = r.required("as");
                if (as.contains("..")) throw new Failure(400, "as names a relationship, such as children or father");
                yield relinked(doc, key, "link", List.of(other, as));
            }
            case "unlink" -> {
                post(r);
                var path = r.required("path");
                if (path.startsWith("/") || path.contains("..")) throw new Failure(400, "path is relative to the person, such as father or children/I3");
                yield relinked(doc, key, "unlink", List.of(path));
            }
            case "pedigree" -> {
                int depth = r.intParam("depth", 4, 1, 12);
                yield store.read(g -> ok(views(g, doc).pedigree(person(g, key), depth)));
            }
            case "descendants" -> {
                int depth = r.intParam("depth", 3, 1, 10);
                yield store.read(g -> ok(views(g, doc).descendants(person(g, key), depth, new java.util.HashSet<>())));
            }
            case "photos" -> {
                if (p.size() != 3) throw new Failure(404, "no such endpoint");
                yield store.read(g -> {
                    var photos = g.photos(person(g, key));
                    int n;
                    try {
                        n = Integer.parseInt(p.get(2));
                    } catch (NumberFormatException e) {
                        throw new Failure(404, "no such photo");
                    }
                    if (n < 0 || n >= photos.size()) throw new Failure(404, "no such photo");
                    return new FileReply(g.mediaPath(photos.get(n).file()).orElseThrow(() -> new Failure(404, "the picture is not on this computer")));
                });
            }
            default -> throw new Failure(404, "no such endpoint");
        };
    }

    private static Person person(Genealogy g, String key) {
        return g.person(key).orElseThrow(() -> new Failure(404, "no person " + key));
    }

    private String key(Workspace.Document doc, String key) {
        return doc.store().read(g -> person(g, key).key());
    }

    private Reply editPerson(Request r, Workspace.Document doc, String key) {
        var set = parse(r.strings("set"), PersonForm.Field::parse);
        var seen = parse(r.strings("seen"), PersonForm.Field::parse);
        var events = r.ops("events");
        var notes = r.ops("notes");
        var names = r.ops("names");
        var nick = r.body().get("nicknames") instanceof Map<?, ?> m ? nested(r, m) : null;
        if (r.body().containsKey("nicknames") && nick == null) throw new Failure(400, "nicknames must be {\"seen\": [...], \"set\": [...]}");
        return doc.store().write(g -> {
            var p = person(g, key);
            try {
                // check everything before writing anything
                var changes = PersonForm.diff(p, seen, set, g.isGedcom7());
                var plans = new ArrayList<RecordEdits.Plan>(List.of(
                        RecordEdits.personEvents(g, p, events), RecordEdits.notes(g, p.node(), notes), RecordEdits.names(g, p, names)));
                if (nick != null) {
                    var to = nick.list("set");
                    if (to == null) throw new Failure(400, "nicknames.set is required");
                    plans.add(RecordEdits.nicknames(p, nick.list("seen"), to));
                }
                if (!changes.isEmpty()) p = PersonForm.apply(g, p, changes);
                if (plans.stream().anyMatch(x -> !x.isEmpty())) p = RecordEdits.apply(g, p, plans);
                var all = new ArrayList<Map<String, Object>>();
                changes.forEach(c -> all.add(obj("field", c.field().key, "before", c.before(), "after", c.after())));
                plans.forEach(x -> x.changes().forEach(c -> all.add(obj("field", c.field(), "before", c.before(), "after", c.after()))));
                return ok(obj("changes", all, "person", views(g, doc).person(p), "revision", g.revision()));
            } catch (Conflict e) {
                throw new Failure(409, e.getMessage(), obj("conflict", true, "person", views(g, doc).person(p)));
            } catch (IllegalArgumentException e) {
                throw new Failure(422, e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Request nested(Request r, Map<?, ?> m) {
        return new Request(r.method(), r.path(), r.query(), (Map<String, Object>) m);
    }

    private static <F> Map<F, String> parse(Map<String, String> byKey, Function<String, Optional<F>> parse) {
        var out = new LinkedHashMap<F, String>();
        for (var e : byKey.entrySet()) {
            var f = parse.apply(e.getKey()).orElseThrow(() -> new Failure(422, "unknown field '" + e.getKey() + "'"));
            if (out.put(f, e.getValue()) != null) throw new Failure(422, e.getKey() + " given twice");
        }
        return out;
    }

    private Reply added(Workspace.Document doc, String cwd, String relation, Request r) {
        var name = r.required("name");
        var args = new ArrayList<>(List.of(relation, name));
        var fields = new LinkedHashMap<>(r.strings("fields"));
        var sex = r.string("sex");
        if (sex != null && !sex.isBlank()) fields.put("sex", sex);
        fields.forEach((k, v) -> {
            if (k.contains("=") || k.isBlank()) throw new Failure(422, "unknown field '" + k + "'");
            args.add(k + "=" + v);
        });
        try {
            var outcome = Headless.run(doc.store(), cwd, "add", args);
            // the shell's first line says who was added; the next ones list fields the form shows anyway
            var lines = outcome.message().lines().toList();
            var said = lines.isEmpty() ? "" : lines.getFirst().replaceFirst("\\s{2,}given: .*$", "")
                    + (lines.size() > 1 ? " · " + String.join(" · ", lines.subList(1, lines.size())) : "");
            return doc.store().read(g -> ok(obj("message", said,
                    "person", outcome.added().flatMap(g::person).map(x -> views(g, doc).person(x)).orElse(null),
                    "revision", g.revision())));
        } catch (IllegalArgumentException e) {
            throw new Failure(422, e.getMessage());
        }
    }

    private Reply relinked(Workspace.Document doc, String key, String command, List<String> args) {
        var cwd = "/people/" + key(doc, key);
        try {
            var outcome = Headless.run(doc.store(), cwd, command, args);
            return doc.store().read(g -> ok(obj("message", outcome.message(),
                    "person", g.person(key).map(x -> views(g, doc).person(x)).orElse(null),
                    "revision", g.revision())));
        } catch (IllegalArgumentException e) {
            throw new Failure(422, e.getMessage());
        }
    }

    // ---- families ------------------------------------------------------------

    private Reply families(Request r, Workspace.Document doc, List<String> p) {
        var store = doc.store();
        if (p.isEmpty()) {
            int offset = r.intParam("offset", 0, 0, Integer.MAX_VALUE);
            int limit = r.intParam("limit", 200, 1, 5000);
            var q = Genealogy.fold(r.param("q", "").strip());
            return store.read(g -> {
                var v = views(g, doc);
                var all = g.families().stream().filter(x -> q.isEmpty()
                        || java.util.Arrays.stream(q.split("\\s+")).allMatch(w -> Genealogy.fold(v.familyTitle(x) + " " + x.key()).contains(w))).toList();
                return ok(obj("total", all.size(), "offset", offset,
                        "families", all.stream().skip(offset).limit(limit).map(v::familySummary).toList()));
            });
        }
        var key = p.getFirst();
        if (p.size() == 1) {
            return store.read(g -> ok(views(g, doc).family(g.family(key).orElseThrow(() -> new Failure(404, "no family " + key)))));
        }
        if (p.size() == 2 && p.get(1).equals("edit")) {
            post(r);
            var set = parse(r.strings("set"), FamilyForm.Field::parse);
            var seen = parse(r.strings("seen"), FamilyForm.Field::parse);
            var events = r.ops("events");
            var notes = r.ops("notes");
            return store.write(g -> {
                var f = g.family(key).orElseThrow(() -> new Failure(404, "no family " + key));
                try {
                    var changes = FamilyForm.diff(f, seen, set, g.isGedcom7());
                    var plans = List.of(RecordEdits.familyEvents(g, f, events), RecordEdits.notes(g, f.node(), notes));
                    if (!changes.isEmpty()) f = FamilyForm.apply(g, f, changes);
                    if (plans.stream().anyMatch(x -> !x.isEmpty())) f = RecordEdits.apply(g, f, plans);
                    var all = new ArrayList<Map<String, Object>>();
                    changes.forEach(c -> all.add(obj("field", c.field().key, "before", c.before(), "after", c.after())));
                    plans.forEach(x -> x.changes().forEach(c -> all.add(obj("field", c.field(), "before", c.before(), "after", c.after()))));
                    return ok(obj("changes", all, "family", views(g, doc).family(f), "revision", g.revision()));
                } catch (Conflict e) {
                    throw new Failure(409, e.getMessage(), obj("conflict", true, "family", views(g, doc).family(f)));
                } catch (IllegalArgumentException e) {
                    throw new Failure(422, e.getMessage());
                }
            });
        }
        throw new Failure(404, "no such endpoint");
    }

    // ---- notes, sources, media ----------------------------------------------

    private Reply records(Request r, Workspace.Document doc, List<String> p) {
        var store = doc.store();
        if (p.isEmpty()) {
            var kindName = r.param("kind", "");
            var kind = Arrays.stream(RecordKind.values()).filter(k -> k.dirName.equals(kindName)).findFirst()
                    .orElseThrow(() -> new Failure(400, "kind is notes, sources or media"));
            var q = Genealogy.fold(r.param("q", "").strip());
            return store.read(g -> {
                var v = views(g, doc);
                var list = g.records(kind).stream()
                        .filter(x -> q.isEmpty() || Genealogy.fold(Genealogy.recordTitle(x) + " " + Genealogy.key(x.xref())).contains(q))
                        .map(x -> v.recordSummary(kind, x)).toList();
                return ok(obj("kind", kind.dirName, "records", list));
            });
        }
        if (p.size() == 2 && p.get(1).equals("edit")) {
            post(r);
            var lines = r.ops("lines");
            var text = r.string("text");
            var seenText = r.string("seenText");
            return store.write(g -> {
                var rec = g.record(p.getFirst()).orElseThrow(() -> new Failure(404, "no note, source or media " + p.getFirst()));
                var kind = RecordKind.of(rec.tag()).orElseThrow();
                try {
                    var plan = RecordEdits.lines(g, rec, seenText, text, lines);
                    if (!plan.isEmpty()) RecordEdits.apply(g, rec, plan);
                    return ok(obj("changes", plan.changes().stream().map(c -> obj("field", c.field(), "before", c.before(), "after", c.after())).toList(),
                            "record", views(g, doc).record(kind, rec), "revision", g.revision()));
                } catch (Conflict e) {
                    throw new Failure(409, e.getMessage(), obj("conflict", true, "record", views(g, doc).record(kind, rec)));
                } catch (IllegalArgumentException e) {
                    throw new Failure(422, e.getMessage());
                }
            });
        }
        if (p.size() != 1) throw new Failure(404, "no such endpoint");
        return store.read(g -> {
            var rec = g.record(p.getFirst()).orElseThrow(() -> new Failure(404, "no note, source or media " + p.getFirst()));
            return ok(views(g, doc).record(RecordKind.of(rec.tag()).orElseThrow(), rec));
        });
    }

    // ---- field definitions ---------------------------------------------------

    private static Map<String, Object> fields() {
        var person = new ArrayList<Map<String, Object>>();
        for (var f : PersonForm.Field.values()) {
            var group = switch (f) {
                case GIVEN, SURNAME, SUFFIX, SEX -> "name";
                case BIRTH_DATE, BIRTH_PLACE, BAPTISM_DATE, BAPTISM_PLACE, DEATH_DATE, DEATH_PLACE,
                     BURIAL_DATE, BURIAL_PLACE, DECEASED -> "life";
                default -> f.isAttribute() || f.key.startsWith("residence") ? "facts" : "events";
            };
            person.add(obj("key", f.key, "label", label(f.key), "group", group, "kind", kind(f.key), "extra", f.isExtra()));
        }
        var family = new ArrayList<Map<String, Object>>();
        for (var f : FamilyForm.Field.values()) family.add(obj("key", f.key, "label", label(f.key), "kind", kind(f.key)));
        return obj("person", person, "family", family, "dates", ged.model.GedDate.EXAMPLES);
    }

    private static String kind(String key) {
        return key.endsWith(".date") ? "date" : key.endsWith(".place") ? "place"
                : switch (key) {
                    case "sex" -> "sex";
                    case "deceased", "divorced", "hidden" -> "yesno";
                    default -> "text";
                };
    }

    /// `birth.date` → `Birth date`; `given` → `Given names`.
    static String label(String key) {
        return switch (key) {
            case "given" -> "Given names";
            case "surname" -> "Surname";
            case "suffix" -> "Suffix";
            case "sex" -> "Sex";
            case "deceased" -> "Deceased";
            case "divorced" -> "Divorced";
            case "hidden" -> "Hidden";
            default -> {
                var words = key.replace('.', ' ');
                yield Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }

    // ---- shell sessions ------------------------------------------------------

    private Reply shell(Request r) throws InterruptedException {
        var p = r.path();
        if (p.size() < 2) throw new Failure(404, "no such endpoint");
        var s = sessions.get(p.get(1)).orElseThrow(() -> new Failure(404, "no shell session " + p.get(1) + " (it may have timed out)"));
        if (p.size() == 2) {
            if (!r.method().equals("DELETE")) throw new Failure(405, "use DELETE");
            sessions.end(s.id);
            return ok(obj("ended", true));
        }
        post(r);
        try {
            return switch (p.get(2)) {
                case "run" -> ok(ShellSessions.json(s.run(r.body().get("line") instanceof String l ? l : "")));
                case "edit" -> ok(ShellSessions.json(s.edited(Optional.ofNullable(r.string("text")))));
                case "complete" -> {
                    var line = r.body().get("line") instanceof String l ? l : "";
                    int cursor = r.body().get("cursor") instanceof Number n ? n.intValue() : line.length();
                    var c = s.complete(line, Math.clamp(cursor, 0, line.length()));
                    yield ok(obj("start", c.start(), "word", c.word(), "candidates",
                            c.candidates().stream().map(Api::candidate).toList()));
                }
                default -> throw new Failure(404, "no such endpoint");
            };
        } catch (IllegalStateException e) {
            throw new Failure(409, e.getMessage());
        }
    }

    private static Map<String, Object> candidate(Completer.Candidate c) {
        return obj("text", c.text(), "suffix", c.suffix(), "display", c.display(), "note", c.note());
    }
}
