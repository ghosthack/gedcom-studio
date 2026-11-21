package ged;

import ged.client.DaemonClient;
import ged.client.RemoteShell;
import ged.daemon.Home;
import ged.daemon.Server;
import ged.edit.Editor;
import ged.model.Genealogy;
import ged.model.Person;
import ged.shell.LineInput;
import ged.shell.Shell;
import ged.text.Style;
import ged.vfs.FileSystem;
import ged.vfs.Navigator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Main {
    private static final String USAGE = """
            usage: gedcom-shell [OPTIONS] FILE.ged
                   gedcom-shell daemon [run|start|stop|status|pair|token] [OPTIONS]
                   gedcom-shell web [FILE.ged]

            Browse a GEDCOM file like a filesystem. Commands: ls, cd, pwd, show, tree, find,
            edit, add, link, unlink, save.

              -c, --command CMDS   run CMDS (';'-separated) and exit
              -H, --home ID        home person for `cd` and `~` (default: first person)
                  --no-color       disable ANSI colours (also honours NO_COLOR)
              -S, --standalone     work on the file in this process only, without the daemon
              -h, --help           show this help

            The shell runs in the gedcom-shell daemon, which it starts when needed, so
            the web app and the desktop app see your edits as you make them (and you
            theirs). Scripted runs (-c, or piped input) use the daemon only if it is
            already running. See `gedcom-shell daemon --help`.

            Without -c, reads commands interactively, or from stdin when piped:
              echo 'find -b 1500..1550; tree -a /found/1' | gedcom-shell royals.ged
            """;

    private static final String DAEMON_USAGE = """
            usage: gedcom-shell daemon COMMAND [OPTIONS]

            The daemon holds open GEDCOM files for the shell, the web app and the
            desktop app, so they all see one set of edits. It keeps them until they
            are saved; stopped with unsaved edits, it writes copies to its recovery
            folder first.

              start                 start it in the background (if it isn't running)
              run                   run it in this terminal (for launchd, systemd, ...)
                  --port N          listen on port N (default 7380, or `port` in
                                    ~/.gedcom-shell/daemon.properties)
                  --bind ADDRESS    listen on ADDRESS (default 127.0.0.1, this computer
                                    only; 0.0.0.0 for the local network)
                  --tls KEYSTORE    serve HTTPS with this PKCS#12 keystore (password in
                                    GEDCOM_SHELL_TLS_PASSWORD)
                  --web-dir DIR     serve the web app from DIR (for working on it)
              stop [--force]        stop it; --force even with unsaved edits
              status                where it listens and which files it has open
              pair [FILE.ged]       print the address that pairs a browser or phone
              token --rotate        replace the token (paired devices must pair again)

            From another device: `tailscale serve --bg 7380` puts the daemon on your
            tailnet over HTTPS; open the `pair` address there with the tailnet name.
            """;

    public static void main(String[] argv) throws IOException {
        System.exit(run(argv));
    }

    static int run(String[] argv) throws IOException {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        if (argv.length > 0 && argv[0].equals("daemon")) return daemon(List.of(argv).subList(1, argv.length), out, err);
        if (argv.length > 0 && argv[0].equals("web")) return web(List.of(argv).subList(1, argv.length), out, err);
        String file = null, command = null, homeId = null;
        boolean color = true;
        boolean standalone = "1".equals(System.getenv("GEDCOM_SHELL_STANDALONE"));
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "-h", "--help" -> { out.print(USAGE); return 0; }
                case "-c", "--command" -> { if (++i < argv.length) command = argv[i]; else return usage(err, "-c needs a value"); }
                case "-H", "--home" -> { if (++i < argv.length) homeId = argv[i]; else return usage(err, "--home needs a value"); }
                case "--no-color" -> color = false;
                case "-S", "--standalone" -> standalone = true;
                default -> {
                    if (argv[i].startsWith("-") || file != null) return usage(err, "unexpected argument: " + argv[i]);
                    file = argv[i];
                }
            }
        }
        if (file == null) return usage(err, "no GEDCOM file given");
        var path = Path.of(file);
        if (!Files.isReadable(path)) {
            err.println("gedcom-shell: cannot read " + file);
            return 2;
        }
        if (Files.isDirectory(path)) {
            err.println("gedcom-shell: " + file + " is a directory, not a GEDCOM file");
            return 2;
        }

        var console = System.console();
        boolean interactive = command == null && console != null && console.isTerminal();
        var style = color ? Style.auto() : Style.PLAIN;

        if (!standalone) {
            var home = Home.fromEnvironment();
            Optional<DaemonClient> daemon;
            if (interactive) {
                try {
                    daemon = Optional.of(DaemonClient.ensure(home, Duration.ofSeconds(15)));
                } catch (IOException e) {
                    err.println(style.yellow("gedcom-shell: the daemon could not start, so this shell works on its own: " + e.getMessage()));
                    daemon = Optional.empty();
                }
            } else {
                daemon = DaemonClient.running(home);
            }
            if (daemon.isPresent()) return attached(daemon.get(), path, command, homeId, interactive, style, out, err);
        }
        return standalone(path, file, command, homeId, interactive, style, out, err);
    }

    /// The shell in the daemon, on the daemon's copy of the file.
    private static int attached(DaemonClient daemon, Path path, String command, String homeId, boolean interactive,
                                Style style, PrintStream out, PrintStream err) throws IOException {
        Map<String, Object> summary;
        try {
            summary = daemon.post("files", ged.json.Json.obj("path", path.toAbsolutePath().toString()));
        } catch (DaemonClient.ApiException e) {
            err.println("gedcom-shell: " + e.getMessage());
            return 2;
        }
        var id = (String) summary.get("id");
        try {
            var version = daemon.get("ping").get("version");
            if (interactive && !Server.VERSION.equals(version)) {
                err.println(style.yellow("note: the daemon runs gedcom-shell " + version + ", this is " + Server.VERSION
                        + "; save, then `gedcom-shell daemon stop` to run the new one"));
            }
        } catch (IOException e) {
            // carry on; the next call says what's wrong
        }
        if (homeId != null) {
            try {
                var p = daemon.get(DaemonClient.path("files", id, "people", homeId));
                homeId = (String) p.get("key");
            } catch (DaemonClient.ApiException e) {
                err.println("gedcom-shell: --home: no person " + homeId);
                return 2;
            }
        }
        RemoteShell shell;
        try {
            shell = RemoteShell.open(daemon, id, homeId, style.enabled(), out, Editor.external());
        } catch (IOException e) {
            err.println("gedcom-shell: " + e.getMessage());
            return 2;
        }
        try {
            if (command != null) {
                var ok = shell.execute(command);
                return ok == null || ok ? 0 : 1;
            }
            if (interactive) {
                out.println(style.bold("gedcom-shell") + " · " + path.getFileName() + " · "
                        + summary.get("people") + " people, " + summary.get("families") + " families · " + summary.get("encoding")
                        + style.dim(" · daemon " + daemon.base()));
                var notes = new ArrayList<String>();
                if (Boolean.TRUE.equals(summary.get("dirty"))) {
                    notes.add(summary.get("dirtyCount") + " unsaved record(s) held by the daemon");
                }
                if (summary.get("warnings") instanceof List<?> w && !w.isEmpty()) notes.add(w.size() + " warnings (show /)");
                if (!notes.isEmpty()) out.println(style.yellow(String.join("; ", notes)));
                out.println(style.dim("Type 'help' for commands. Edits are shared with the web and desktop apps until you save."));
            }
            var input = interactive
                    ? LineInput.terminal(RemoteShell.history(), out, shell.completer())
                    : LineInput.of(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
            int code = shell.repl(input, interactive);
            remindUnsaved(daemon, id, style, err);
            return code;
        } catch (IOException e) {
            err.println(style.red("gedcom-shell: lost the daemon: " + e.getMessage()));
            return 2;
        } finally {
            shell.close();
        }
    }

    private static void remindUnsaved(DaemonClient daemon, String id, Style style, PrintStream err) {
        try {
            var s = daemon.get(DaemonClient.path("files", id));
            if (Boolean.TRUE.equals(s.get("dirty"))) {
                err.println(style.yellow(s.get("dirtyCount") + " unsaved record(s) stay in the daemon; `save` from any front end writes them"));
            }
        } catch (IOException e) {
            // nothing to add
        }
    }

    /// The shell on its own, as before the daemon: the file is read here.
    private static int standalone(Path path, String file, String command, String homeId, boolean interactive,
                                  Style style, PrintStream out, PrintStream err) throws IOException {
        Genealogy g;
        try {
            g = Genealogy.load(path);
        } catch (IOException e) {
            err.println("gedcom-shell: cannot read " + file + ": " + e.getMessage());
            return 2;
        }
        Optional<Person> home;
        if (homeId != null) {
            home = g.person(homeId);
            if (home.isEmpty()) {
                err.println("gedcom-shell: --home: no person " + homeId);
                return 2;
            }
        } else {
            home = g.people().stream().findFirst();
        }

        var nav = new Navigator(new FileSystem(g), home);
        var shell = new Shell(nav, out, err, style);

        if (command != null) {
            shell.scripted(true);
            boolean ok = shell.execute(command);
            shell.warnIfDirty();
            return ok ? 0 : 1;
        }

        if (interactive) {
            var enc = g.encoding();
            out.println(style.bold("gedcom-shell") + " · " + path.getFileName() + " · "
                    + g.people().size() + " people, " + g.families().size() + " families · " + enc.detected());
            var notes = new ArrayList<String>();
            if (enc.mismatch()) notes.add("header says " + enc.declared() + ", decoded as " + enc.detected());
            if (!g.warnings().isEmpty()) notes.add(g.warnings().size() + " warnings (show /)");
            if (!notes.isEmpty()) out.println(style.yellow(String.join("; ", notes)));
            out.println(style.dim("Type 'help' for commands. Home: ")
                    + home.map(Person::label).orElse("(no people)"));
        }
        var input = interactive
                ? LineInput.terminal(RemoteShell.history(), out, shell.completer())
                : LineInput.of(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        return shell.repl(input, interactive);
    }

    // ---- gedcom-shell daemon ... ---------------------------------------------

    private static int daemon(List<String> args, PrintStream out, PrintStream err) throws IOException {
        var home = Home.fromEnvironment();
        if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
            out.print(DAEMON_USAGE);
            return args.isEmpty() ? 2 : 0;
        }
        var rest = args.subList(1, args.size());
        return switch (args.getFirst()) {
            case "run" -> daemonRun(home, rest, out, err);
            case "start" -> {
                try {
                    var d = DaemonClient.ensure(home, Duration.ofSeconds(15));
                    out.println("gedcom-shell daemon running at " + d.base() + " (pid " + d.pid() + ")");
                    yield 0;
                } catch (IOException e) {
                    err.println("gedcom-shell: " + e.getMessage());
                    yield 1;
                }
            }
            case "stop" -> {
                var d = DaemonClient.running(home);
                if (d.isEmpty()) {
                    out.println("gedcom-shell daemon is not running");
                    yield 0;
                }
                try {
                    d.get().post("daemon/stop", ged.json.Json.obj("force", rest.contains("--force") || rest.contains("-f")));
                } catch (DaemonClient.ApiException e) {
                    err.println("gedcom-shell: " + e.getMessage());
                    yield 1;
                }
                for (int i = 0; i < 50 && DaemonClient.running(home).isPresent(); i++) sleep(100);
                out.println("gedcom-shell daemon stopped");
                yield 0;
            }
            case "status" -> {
                var d = DaemonClient.running(home);
                if (d.isEmpty()) {
                    out.println("gedcom-shell daemon is not running (`gedcom-shell daemon start` starts it)");
                    yield 3;
                }
                var about = d.get().get("daemon");
                out.println("gedcom-shell daemon " + about.get("version") + " at " + d.get().base() + " (pid " + about.get("pid")
                        + ", listening on " + about.get("bind") + ", since " + about.get("started") + ")");
                var files = d.get().get("files");
                if (files.get("files") instanceof List<?> list && !list.isEmpty()) {
                    for (var o : list) {
                        var f = (Map<?, ?>) o;
                        out.println("  " + f.get("id") + "  " + f.get("path")
                                + (Boolean.TRUE.equals(f.get("dirty")) ? "  (" + f.get("dirtyCount") + " unsaved)" : ""));
                    }
                } else {
                    out.println("  no files open");
                }
                out.println("log: " + home.logFile());
                yield 0;
            }
            case "pair" -> {
                DaemonClient d;
                try {
                    d = DaemonClient.ensure(home, Duration.ofSeconds(15));
                } catch (IOException e) {
                    err.println("gedcom-shell: " + e.getMessage());
                    yield 1;
                }
                Optional<String> fileId = Optional.empty();
                if (!rest.isEmpty()) {
                    try {
                        fileId = Optional.of((String) d.post("files", ged.json.Json.obj("path", Path.of(rest.getFirst()).toAbsolutePath().toString())).get("id"));
                    } catch (DaemonClient.ApiException e) {
                        err.println("gedcom-shell: " + e.getMessage());
                        yield 1;
                    }
                }
                out.println("On this computer, open:");
                out.println("  " + d.pairingUrl(fileId));
                out.println();
                out.println("From your phone or another computer over Tailscale, run once:");
                out.println("  tailscale serve --bg " + d.base().getPort());
                out.println("then open the same address with https://YOUR-MACHINE.YOUR-TAILNET.ts.net/ in place of");
                out.println("  " + d.base());
                out.println("The address carries the token: treat it like a password.");
                yield 0;
            }
            case "token" -> {
                if (!rest.contains("--rotate")) {
                    err.println("usage: gedcom-shell daemon token --rotate");
                    yield 2;
                }
                home.rotateToken();
                out.println("new token written to " + home.tokenFile() + "; restart the daemon and pair your devices again");
                yield 0;
            }
            default -> {
                err.println("gedcom-shell: unknown daemon command " + args.getFirst());
                err.print(DAEMON_USAGE);
                yield 2;
            }
        };
    }

    private static int daemonRun(Home home, List<String> args, PrintStream out, PrintStream err) throws IOException {
        var c = Server.Config.of(home);
        String bind = c.bind();
        int port = c.port();
        var keystore = c.keystore();
        Optional<Path> webDir = Optional.empty();
        boolean background = false;
        for (int i = 0; i < args.size(); i++) {
            var a = args.get(i);
            switch (a) {
                case "--port" -> {
                    if (++i >= args.size()) return usage(err, "--port needs a value");
                    try {
                        port = Integer.parseInt(args.get(i));
                    } catch (NumberFormatException e) {
                        return usage(err, "--port needs a number");
                    }
                }
                case "--bind" -> { if (++i < args.size()) bind = args.get(i); else return usage(err, "--bind needs a value"); }
                case "--tls" -> { if (++i < args.size()) keystore = Optional.of(Path.of(args.get(i))); else return usage(err, "--tls needs a value"); }
                case "--web-dir" -> { if (++i < args.size()) webDir = Optional.of(Path.of(args.get(i)).toAbsolutePath().normalize()); else return usage(err, "--web-dir needs a value"); }
                case "--background" -> background = true;
                default -> { return usage(err, "unexpected argument: " + a); }
            }
        }
        if (background) ignoreHangups();
        var server = new Server(home, new Server.Config(bind, port, keystore, c.keystorePassword(), webDir), out);
        try {
            if (!server.start()) {
                out.println("gedcom-shell daemon: another one is already running (see `gedcom-shell daemon status`)");
                return 0;
            }
        } catch (IOException e) {
            err.println("gedcom-shell daemon: can't listen on " + bind + ":" + port + ": " + e.getMessage());
            return 1;
        }
        if (!background) out.println("pair a browser: " + server.url() + "#pair=" + server.token());
        try {
            server.await();
        } catch (InterruptedException e) {
            server.stop();
        }
        return 0;
    }

    /// A daemon started from a terminal must outlive it: ignore the hang-up
    /// and Ctrl-C sent to the terminal's processes (not on Windows).
    private static void ignoreHangups() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) return;
        try {
            var signal = Class.forName("sun.misc.Signal");
            var handler = Class.forName("sun.misc.SignalHandler");
            var ignore = handler.getField("SIG_IGN").get(null);
            var handle = signal.getMethod("handle", signal, handler);
            for (var name : List.of("HUP", "INT")) handle.invoke(null, signal.getConstructor(String.class).newInstance(name), ignore);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // keep the defaults
        }
    }

    // ---- gedcom-shell web ------------------------------------------------------

    private static int web(List<String> args, PrintStream out, PrintStream err) throws IOException {
        var home = Home.fromEnvironment();
        DaemonClient d;
        try {
            d = DaemonClient.ensure(home, Duration.ofSeconds(15));
        } catch (IOException e) {
            err.println("gedcom-shell: " + e.getMessage());
            return 1;
        }
        Optional<String> fileId = Optional.empty();
        if (!args.isEmpty()) {
            try {
                fileId = Optional.of((String) d.post("files", ged.json.Json.obj("path", Path.of(args.getFirst()).toAbsolutePath().toString())).get("id"));
            } catch (DaemonClient.ApiException e) {
                err.println("gedcom-shell: " + e.getMessage());
                return 1;
            }
        }
        var url = d.pairingUrl(fileId);
        if (!browse(url)) out.println("open " + url);
        return 0;
    }

    private static boolean browse(String url) {
        var os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        var cmd = os.startsWith("mac") ? List.of("open", url)
                : os.startsWith("windows") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : List.of("xdg-open", url);
        try {
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int usage(PrintStream err, String problem) {
        err.println("gedcom-shell: " + problem);
        err.print(USAGE);
        return 2;
    }
}
