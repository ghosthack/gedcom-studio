package ged.shell;

import ged.edit.Editor;
import ged.store.Store;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The shell's relationship commands for other front ends: `add`, `link`
/// and `unlink` hold the rules about families (when one is created, when an
/// empty one goes, which refusals keep the file consistent), and the web and
/// desktop apps use them as they are rather than copies of them.
public final class Headless {
    private Headless() {}

    private static final Map<String, Command> COMMANDS = Map.of(
            "add", new AddCommand(), "link", new LinkCommand(), "unlink", new UnlinkCommand());

    /// What a command said, and the person it added, if it added one.
    public record Outcome(String message, Optional<String> added) {}

    /// Runs `command` with `args` (already split; nothing is re-read as
    /// separators) as if typed at `cwd`, a shell path such as `/people/I3`.
    /// Returns what it printed, without colours.
    ///
    /// @throws IllegalArgumentException with the command's message if it refused
    public static Outcome run(Store store, String cwd, String command, List<String> args) {
        var cmd = COMMANDS.get(command);
        if (cmd == null) throw new IllegalArgumentException("not a headless command: " + command);
        return store.write(g -> {
            var buffer = new ByteArrayOutputStream();
            var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
            var nav = new Navigator(new FileSystem(g), Optional.empty());
            Editor noEditor = _ -> { throw new IllegalStateException("no editor here"); };
            var ctx = new Context(nav, out, Style.PLAIN, noEditor, store);
            var before = new java.util.HashSet<String>();
            g.people().forEach(p -> before.add(p.key()));
            try {
                nav.cd(nav.resolve(cwd));
                cmd.run(args, ctx);
            } catch (CommandException | Navigator.NotFound e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            var said = buffer.toString(StandardCharsets.UTF_8).strip()
                    .replaceAll("\\n?unsaved: .*$", ""); // the shell's reminder; apps show their own
            var added = g.people().stream().map(p -> p.key()).filter(k -> !before.contains(k)).findFirst();
            return new Outcome(said, added);
        });
    }
}
