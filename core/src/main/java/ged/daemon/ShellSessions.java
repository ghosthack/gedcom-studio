package ged.daemon;

import ged.edit.Editor;
import ged.model.Person;
import ged.shell.Completer;
import ged.shell.Shell;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static ged.json.Json.obj;

/// Shells that terminals attach to: `gedcom-shell FILE` runs its commands
/// here, in the daemon, on the same records as the web and desktop apps.
///
/// Each session is a [Shell] with its own place in the tree. A command runs
/// on the session's own thread; when it opens a form for editing, the form
/// goes back to the terminal (its `$EDITOR`) and the command waits for the
/// edited text, holding no lock meanwhile.
final class ShellSessions {
    /// How long a session may sit unused before it is ended.
    private static final Duration IDLE = Duration.ofHours(12);
    /// How long a form may be out in an editor.
    private static final Duration EDITING = Duration.ofHours(6);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /// What a step of a command produced: done (with whether the shell
    /// exited), or a form to edit.
    sealed interface Step permits Done, Edit {}
    record Done(String output, String prompt, boolean exit, boolean ok) implements Step {}
    record Edit(String output, String name, String text) implements Step {}

    final class Session {
        final String id;
        final Workspace.Document doc;
        private final Shell shell;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final BlockingQueue<Step> steps = new ArrayBlockingQueue<>(1);
        private final BlockingQueue<Optional<String>> replies = new ArrayBlockingQueue<>(1);
        private volatile boolean busy;
        private volatile Instant used = Instant.now();

        Session(String id, Workspace.Document doc, boolean color, Optional<String> homeKey) {
            this.id = id;
            this.doc = doc;
            var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
            var store = doc.store();
            Editor editor = file -> {
                var text = Files.readString(file, StandardCharsets.UTF_8);
                steps.add(new Edit(drain(), file.getFileName().toString(), text));
                Optional<String> reply;
                try {
                    reply = replies.poll(EDITING.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (reply == null || reply.isEmpty()) return false;
                Files.writeString(file, reply.get(), StandardCharsets.UTF_8);
                return true;
            };
            this.shell = store.read(g -> {
                Optional<Person> home = homeKey.flatMap(g::person).or(() -> g.people().stream().findFirst());
                return new Shell(new Navigator(new FileSystem(g), home), out, out, new Style(color), editor, store);
            });
            shell.attached(true);
        }

        private synchronized String drain() {
            var s = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            return s;
        }

        /// Runs a line; returns its first step.
        Step run(String line) throws InterruptedException {
            used = Instant.now();
            if (busy) throw new IllegalStateException("a command is still running in this session");
            busy = true;
            Thread.ofVirtual().name("shell-" + id).start(() -> {
                boolean ok = false;
                try {
                    ok = shell.execute(line);
                } finally {
                    String prompt;
                    try {
                        prompt = shell.prompt();
                    } catch (RuntimeException e) {
                        prompt = "› ";
                    }
                    busy = false;
                    steps.add(new Done(drain(), prompt, shell.exited(), ok));
                }
            });
            return steps.take();
        }

        /// Hands back an edited form (empty: the edit was cancelled); returns
        /// the command's next step.
        Step edited(Optional<String> text) throws InterruptedException {
            used = Instant.now();
            if (!busy) throw new IllegalStateException("no form is being edited");
            replies.put(text);
            return steps.take();
        }

        Completer.Result complete(String line, int cursor) {
            used = Instant.now();
            return shell.completer().complete(line, cursor);
        }

        String prompt() { return shell.prompt(); }
    }

    Session create(Workspace.Document doc, boolean color, Optional<String> home) {
        sweep();
        var bytes = new byte[12];
        random.nextBytes(bytes);
        var id = HexFormat.of().formatHex(bytes);
        var s = new Session(id, doc, color, home);
        sessions.put(id, s);
        return s;
    }

    Optional<Session> get(String id) { return Optional.ofNullable(sessions.get(id)); }

    void end(String id) {
        var s = sessions.remove(id);
        if (s != null && s.busy) s.replies.offer(Optional.empty()); // a form left in an editor: cancelled
    }

    /// Ends sessions on files that are no longer open.
    void endFor(String documentId) {
        sessions.values().removeIf(s -> s.doc.id().equals(documentId));
    }

    private void sweep() {
        var cutoff = Instant.now().minus(IDLE);
        sessions.values().removeIf(s -> !s.busy && s.used.isBefore(cutoff));
    }

    static Map<String, Object> json(Step step) {
        return switch (step) {
            case Done d -> obj("output", d.output(), "prompt", d.prompt(), "exit", d.exit(), "ok", d.ok());
            case Edit e -> obj("output", e.output(), "edit", obj("name", e.name(), "text", e.text()));
        };
    }
}
