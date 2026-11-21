package ged.shell;

import ged.edit.Editor;
import ged.store.Store;
import ged.text.Style;
import ged.vfs.Location;
import ged.vfs.Navigator;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/// Read–eval–print loop and command dispatch.
public final class Shell {
    private final Context ctx;
    private final PrintStream err;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, String> aliases = Map.of("ll", "ls -l", "cat", "show", "search", "find", "?", "help", "quit", "exit",
            "vi", "edit", "w", "save");
    private boolean exitRequested;

    private boolean exitWarned;
    /// The revision the navigator last saw; another front end's edit moves
    /// the store past it.
    private long seen;
    /// True when the edits live in a daemon that outlasts this shell, so
    /// leaving loses nothing.
    private boolean attached;

    public void attached(boolean attached) { this.attached = attached; }

    /// True once `exit` has been accepted.
    public boolean exited() { return exitRequested; }
    /// True when commands come from `-c` or a pipe: nobody is there to
    /// answer a warning, so `exit` exits.
    private boolean scripted;

    public void scripted(boolean scripted) { this.scripted = scripted; }

    public Shell(Navigator nav, PrintStream out, PrintStream err, Style style) {
        this(nav, out, err, style, Editor.external());
    }

    public Shell(Navigator nav, PrintStream out, PrintStream err, Style style, Editor editor) {
        this(nav, out, err, style, editor, new Store(nav.fs().genealogy()));
    }

    /// A shell on a [Store] that other front ends may share. `nav` must be on
    /// the store's genealogy.
    public Shell(Navigator nav, PrintStream out, PrintStream err, Style style, Editor editor, Store store) {
        if (nav.fs().genealogy() != store.genealogy()) throw new IllegalArgumentException("navigator and store differ");
        this.ctx = new Context(nav, out, style, editor, store);
        this.seen = store.revision();
        this.err = err;
        for (var c : List.of(new LsCommand(), new CdCommand(), new PwdCommand(), new ShowCommand(),
                new TreeCommand(), new FindCommand(), new EditCommand(), new AddCommand(), new LinkCommand(), new UnlinkCommand(), new SaveCommand())) {
            commands.put(c.name(), c);
        }
        // Commands other jars add: each names its classes in
        // META-INF/services/ged.shell.Command. A built-in keeps its name.
        for (var c : ServiceLoader.load(Command.class)) {
            commands.putIfAbsent(c.name(), c);
        }
    }

    /// Tab completion for interactive input.
    public Completer completer() {
        var completer = new ShellCompleter(commands, aliases, ctx);
        return (line, cursor) -> ctx.store().write(_ -> {
            catchUp();
            return completer.complete(line, cursor);
        });
    }

    /// Runs one input line (possibly several `;`-separated commands).
    /// Returns false if any command failed.
    public boolean execute(String line) {
        List<List<String>> parsed;
        try {
            parsed = Tokenizer.split(line);
        } catch (CommandException e) {
            err.println(ctx.style().red("ged: " + e.getMessage()));
            return false;
        }
        boolean ok = true;
        for (var words : parsed) {
            if (exitRequested) break;
            ok &= run(words);
        }
        return ok;
    }

    private boolean run(List<String> words) {
        var name = words.getFirst();
        var args = words.subList(1, words.size());
        if (aliases.containsKey(name)) {
            var expanded = new java.util.ArrayList<>(List.of(aliases.get(name).split(" ")));
            name = expanded.removeFirst();
            expanded.addAll(args);
            args = expanded;
        }
        if (name.equals("exit")) {
            if (attached) {
                exitRequested = true;
                return true;
            }
            if (ctx.store().read(g -> g.isDirty()) && !exitWarned && !scripted) {
                exitWarned = true;
                err.println(ctx.style().yellow(ctx.g().dirtyCount() + " unsaved record(s); `save` first, or exit again to discard"));
                return true;
            }
            exitRequested = true;
            return true;
        }
        exitWarned = false;
        var cmd = commands.get(name);
        if (cmd == null && !name.equals("help")) {
            err.println(ctx.style().red("ged: " + name + ": command not found") + ctx.style().dim("  (try: help)"));
            return false;
        }
        try {
            var command = cmd;
            var arguments = args;
            ctx.store().write(g -> {
                catchUp();
                if (command == null) help(arguments);
                else command.run(arguments, ctx);
                seen = g.revision();
                return null;
            });
            return true;
        } catch (CommandException | Navigator.NotFound e) {
            err.println(ctx.style().red(name + ": " + e.getMessage()));
            return false;
        } catch (RuntimeException | StackOverflowError e) {
            // a bug in one command must not end the session and take unsaved edits with it
            var what = e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
            err.println(ctx.style().red(name + ": internal error (" + what + ")")
                    + ctx.style().dim("  nothing was lost; your edits are still in memory"));
            return false;
        } finally {
            ctx.out().flush();
        }
    }

    private void help(List<String> args) {
        var s = ctx.style();
        if (!args.isEmpty()) {
            var name = aliases.getOrDefault(args.getFirst(), args.getFirst()).split(" ")[0];
            var cmd = commands.get(name);
            if (cmd == null) throw new CommandException("no help for " + args.getFirst());
            ctx.out().print(cmd.usage());
            return;
        }
        ctx.out().println(s.bold("The tree is a filesystem:") + " people and families are directories;");
        ctx.out().println("their relatives are the entries inside. Try: ls · cd father · tree -a · find maria");
        ctx.out().println();
        for (var c : commands.values()) {
            ctx.out().println("  " + s.green(Style.pad(c.name(), 6)) + "  " + c.summary());
        }
        ctx.out().println("  " + s.green(Style.pad("help", 6)) + "  help [COMMAND]");
        ctx.out().println("  " + s.green(Style.pad("exit", 6)) + "  leave (also Ctrl-D)");
        ctx.out().println();
        ctx.out().println(s.dim("Paths: /  /people  /families  /found  ~ (home)  ..  -   e.g. ~/children/2/spouses"));
        ctx.out().println(s.dim("Separate commands with ';'. Aliases: ll = ls -l, cat = show, search = find, vi = edit, w = save."));
    }

    /// Re-points the navigator at current records if another front end
    /// changed them since the last command. Call holding the store's lock.
    private void catchUp() {
        if (ctx.g().revision() != seen) ctx.nav().refresh();
        seen = ctx.g().revision();
    }

    /// For non-interactive runs (-c, piped input) that end with edits unsaved.
    public void warnIfDirty() {
        if (attached) return;
        if (ctx.store().read(g -> g.isDirty()) && (scripted || !exitRequested)) {
            err.println(ctx.style().yellow("warning: " + ctx.g().dirtyCount() + " edited record(s) not saved (add `save`)"));
        }
    }

    public String prompt() {
        return ctx.store().write(_ -> {
            catchUp();
            return promptText();
        });
    }

    private String promptText() {
        var loc = ctx.nav().cwd().location();
        var s = ctx.style();
        var where = switch (loc) {
            case Location.PersonDir(var p) -> s.bold(p.name().display()) + " " + s.dim(p.key());
            case Location.FamilyDir(var f) -> s.bold(ctx.fs().familyTitle(f)) + " " + s.dim(f.key());
            default -> s.blue(loc.canonicalPath());
        };
        return where + (ctx.g().isDirty() ? s.yellow(" *") : "") + s.cyan(" › ");
    }

    /// Reads and runs lines until `exit` or end of input. With
    /// `interactive`, shows a prompt and, at Ctrl-D with unsaved edits,
    /// warns once before quitting.
    public int repl(LineInput input, boolean interactive) throws IOException {
        boolean allOk = true;
        scripted = !interactive;
        while (!exitRequested) {
            var line = input.readLine(interactive ? prompt() : "");
            if (line == null) {
                if (interactive && !attached && ctx.store().read(g -> g.isDirty()) && !exitWarned) {
                    ctx.out().println();
                    execute("exit");
                    continue;
                }
                break;
            }
            if (line.isBlank()) continue;
            allOk &= execute(line);
        }
        if (interactive) ctx.out().println();
        warnIfDirty();
        return allOk ? 0 : 1;
    }
}
