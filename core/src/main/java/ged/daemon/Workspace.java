package ged.daemon;

import ged.json.Json;
import ged.model.Genealogy;
import ged.store.Store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ged.json.Json.obj;

/// The GEDCOM files the daemon has open, each with its [Store], and the
/// ones opened lately.
///
/// A file's id comes from its real path, so it is the same every time the
/// file is opened: a bookmarked address keeps working after a restart, and
/// asking for a recent file by its id opens it again.
final class Workspace {
    /// One open file.
    record Document(String id, Path path, Store store, Instant opened) {
        String name() { return path.getFileName().toString(); }
    }

    private static final int RECENT = 20;

    private final Home home;
    private final Map<String, Document> open = new LinkedHashMap<>();
    /// Most recent first: id → real path.
    private final LinkedHashMap<String, Path> recent = new LinkedHashMap<>();

    Workspace(Home home) {
        this.home = home;
        readRecent();
    }

    static String idOf(Path real) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(real.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Opens `file`, or returns it if it is open already.
    ///
    /// @throws IOException if it can't be read
    synchronized Document open(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("no such file: " + file);
        var real = file.toRealPath();
        var id = idOf(real);
        var doc = open.get(id);
        if (doc == null) {
            doc = new Document(id, real, new Store(Genealogy.load(real)), Instant.now());
            open.put(id, doc);
        }
        remember(id, real);
        return doc;
    }

    /// The open file with this id, opening a recent one again if need be.
    synchronized Optional<Document> get(String id) throws IOException {
        var doc = open.get(id);
        if (doc != null) return Optional.of(doc);
        var path = recent.get(id);
        if (path == null || !Files.isRegularFile(path)) return Optional.empty();
        return Optional.of(open(path));
    }

    synchronized List<Document> documents() { return List.copyOf(open.values()); }

    synchronized List<Map<String, Object>> recent() {
        var out = new ArrayList<Map<String, Object>>();
        recent.forEach((id, path) -> out.add(obj("id", id, "name", path.getFileName().toString(), "path", path.toString(),
                "open", open.containsKey(id), "exists", Files.isRegularFile(path))));
        return out;
    }

    /// Closes a file. One with unsaved edits stays open unless `force`.
    ///
    /// @throws IllegalStateException if it has unsaved edits
    synchronized boolean close(String id, boolean force) {
        var doc = open.get(id);
        if (doc == null) return false;
        if (!force && doc.store().read(Genealogy::isDirty)) {
            throw new IllegalStateException(doc.name() + " has unsaved edits; save them, or close with force to discard them");
        }
        open.remove(id);
        return true;
    }

    synchronized void forget(String id) {
        recent.remove(id);
        writeRecent();
    }

    /// Writes a copy of every file with unsaved edits to `recovery/`, so
    /// stopping the daemon never loses them. Returns where they went.
    synchronized List<Path> recover() {
        var saved = new ArrayList<Path>();
        var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        for (var doc : open.values()) {
            try {
                var target = doc.store().write(g -> {
                    if (!g.isDirty()) return null;
                    try {
                        home.create();
                        Files.createDirectories(home.recoveryDir());
                        var name = doc.name().replaceFirst("(?i)\\.ged$", "") + "." + stamp + ".ged";
                        var path = home.recoveryDir().resolve(name);
                        g.save(path);
                        return path;
                    } catch (IOException e) {
                        System.err.println("gedcom-shell daemon: could not write a recovery copy of " + doc.path() + ": " + e.getMessage());
                        return null;
                    }
                });
                if (target != null) saved.add(target);
            } catch (RuntimeException e) {
                System.err.println("gedcom-shell daemon: could not write a recovery copy of " + doc.path() + ": " + e);
            }
        }
        return saved;
    }

    private void remember(String id, Path real) {
        recent.remove(id);
        var copy = new LinkedHashMap<String, Path>();
        copy.put(id, real);
        copy.putAll(recent);
        recent.clear();
        copy.entrySet().stream().limit(RECENT).forEach(e -> recent.put(e.getKey(), e.getValue()));
        writeRecent();
    }

    private void readRecent() {
        try {
            var text = Files.readString(home.recentFile(), StandardCharsets.UTF_8);
            if (Json.parse(text) instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof String s && !s.isBlank()) {
                        var p = Path.of(s);
                        recent.putIfAbsent(idOf(p), p);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // no list yet, or an unreadable one: start afresh
        }
    }

    private void writeRecent() {
        try {
            home.writePrivate(home.recentFile(), Json.write(recent.values().stream().map(Path::toString).toList()) + "\n");
        } catch (IOException e) {
            System.err.println("gedcom-shell daemon: can't write " + home.recentFile() + ": " + e.getMessage());
        }
    }
}
