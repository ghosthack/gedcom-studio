package ged.client;

import ged.edit.Editor;
import ged.json.Json;
import ged.shell.Completer;
import ged.shell.LineInput;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ged.json.Json.obj;

/// The terminal side of a shell that runs in the daemon: reads lines, sends
/// them, prints what comes back, and when a command opens a form, edits it
/// here in `$EDITOR` and hands the text back.
public final class RemoteShell {
    private final DaemonClient daemon;
    private final String session;
    private final PrintStream out;
    private final Editor editor;
    private String prompt;
    private boolean allOk = true;

    private RemoteShell(DaemonClient daemon, String session, String prompt, PrintStream out, Editor editor) {
        this.daemon = daemon;
        this.session = session;
        this.prompt = prompt;
        this.out = out;
        this.editor = editor;
    }

    /// Opens a session on file `fileId`, starting at person `home` (null:
    /// the first person).
    public static RemoteShell open(DaemonClient daemon, String fileId, String home, boolean color, PrintStream out, Editor editor) throws IOException {
        var body = Json.obj("color", color);
        if (home != null) body.put("home", home);
        var s = daemon.post(DaemonClient.path("files", fileId, "shell"), body);
        return new RemoteShell(daemon, (String) s.get("session"), (String) s.get("prompt"), out, editor);
    }

    /// Runs one line (several `;`-separated commands); false if any failed.
    /// Returns null once the shell has exited.
    public Boolean execute(String line) throws IOException {
        var step = daemon.post(DaemonClient.path("shell", session, "run"), obj("line", line));
        while (true) {
            print(step.get("output"));
            if (step.get("edit") instanceof Map<?, ?> edit) {
                step = edit((String) edit.get("name"), (String) edit.get("text"));
                continue;
            }
            prompt = (String) step.get("prompt");
            boolean ok = Boolean.TRUE.equals(step.get("ok"));
            allOk &= ok;
            return Boolean.TRUE.equals(step.get("exit")) ? null : ok;
        }
    }

    private Map<String, Object> edit(String name, String text) throws IOException {
        var suffix = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".txt";
        var file = Files.createTempFile(name.replaceFirst("\\.[^.]*$", "").replaceAll("[^A-Za-z0-9-]", "") + "-", suffix);
        String reply = null;
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
            boolean saved;
            try {
                saved = editor.edit(file);
            } catch (IOException | RuntimeException e) {
                out.println("edit: " + e.getMessage());
                saved = false;
            }
            if (saved) reply = Files.readString(file, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(file);
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("text", reply);
        return daemon.post(DaemonClient.path("shell", session, "edit"), body);
    }

    private void print(Object output) {
        if (output instanceof String s && !s.isEmpty()) {
            out.print(s);
            out.flush();
        }
    }

    public String prompt() { return prompt; }

    /// Tab completion, asked of the daemon.
    public Completer completer() {
        return (line, cursor) -> {
            try {
                var r = daemon.post(DaemonClient.path("shell", session, "complete"), obj("line", line, "cursor", cursor));
                var candidates = new ArrayList<Completer.Candidate>();
                if (r.get("candidates") instanceof List<?> list) {
                    for (var o : list) {
                        if (o instanceof Map<?, ?> c) {
                            candidates.add(new Completer.Candidate((String) c.get("text"), (String) c.get("suffix"),
                                    (String) c.get("display"), (String) c.get("note")));
                        }
                    }
                }
                return new Completer.Result(((Number) r.get("start")).intValue(), (String) r.get("word"), candidates);
            } catch (IOException | RuntimeException e) {
                return Completer.Result.none(cursor);
            }
        };
    }

    /// Reads lines until `exit` or the end of input; ends the session.
    public int repl(LineInput input, boolean interactive) throws IOException {
        try {
            while (true) {
                var line = input.readLine(interactive ? prompt : "");
                if (line == null) break;
                if (line.isBlank()) continue;
                if (execute(line) == null) break;
            }
            if (interactive) out.println();
            return allOk ? 0 : 1;
        } finally {
            close();
        }
    }

    public boolean allOk() { return allOk; }

    public void close() {
        try {
            daemon.delete(DaemonClient.path("shell", session));
        } catch (IOException e) {
            // the daemon ends idle sessions itself
        }
    }

    /// Where the history of attached shells is kept.
    public static Path history() {
        return Path.of(System.getProperty("user.home"), ".gedcom_shell_history");
    }
}
