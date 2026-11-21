package ged.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/// The daemon's state directory, `~/.gedcom-shell` unless
/// `GEDCOM_SHELL_HOME` (or `-Dgedcom.shell.home`) says otherwise:
///
/// | file | what |
/// |---|---|
/// | `token` | the secret every client sends (owner-only permissions) |
/// | `daemon.properties` | settings: `port`, `bind`, `tls.keystore`, `tls.password` |
/// | `daemon.lock` | held by the running daemon; the OS frees it if the daemon dies |
/// | `daemon.json` | where the running daemon listens, for clients to find it |
/// | `daemon.log` | its output when it runs in the background |
/// | `recent.json` | files opened lately, so their addresses keep working |
/// | `recovery/` | copies of unsaved edits, written if the daemon is stopped with them |
public record Home(Path dir) {

    public static Home fromEnvironment() {
        var prop = System.getProperty("gedcom.shell.home");
        if (prop != null && !prop.isBlank()) return new Home(Path.of(prop));
        var env = System.getenv("GEDCOM_SHELL_HOME");
        if (env != null && !env.isBlank()) return new Home(Path.of(env));
        return new Home(Path.of(System.getProperty("user.home"), ".gedcom-shell"));
    }

    public Path tokenFile() { return dir.resolve("token"); }
    public Path lockFile() { return dir.resolve("daemon.lock"); }
    public Path stateFile() { return dir.resolve("daemon.json"); }
    public Path logFile() { return dir.resolve("daemon.log"); }
    public Path recentFile() { return dir.resolve("recent.json"); }
    public Path settingsFile() { return dir.resolve("daemon.properties"); }
    public Path recoveryDir() { return dir.resolve("recovery"); }

    /// Creates the directory, readable only by its owner where the system
    /// has POSIX permissions.
    public void create() throws IOException {
        if (Files.isDirectory(dir)) return;
        try {
            Files.createDirectories(dir.getParent());
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        } catch (FileAlreadyExistsException e) {
            // made meanwhile by another process
        }
    }

    /// The token, made on first use. It stays the same across restarts so
    /// paired browsers and apps keep working; [#rotateToken] replaces it.
    public String token() throws IOException {
        var file = tokenFile();
        if (Files.isRegularFile(file)) {
            var t = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (t.length() >= 32) return t;
        }
        return rotateToken();
    }

    public String rotateToken() throws IOException {
        create();
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        writePrivate(tokenFile(), token + "\n");
        return token;
    }

    /// Writes `text` atomically to a file only its owner can read.
    public void writePrivate(Path file, String text) throws IOException {
        create();
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
        }
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /// `daemon.properties`, or no settings if it is missing or unreadable.
    public Properties settings() {
        var p = new Properties();
        try (var r = Files.newBufferedReader(settingsFile(), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            // defaults
        }
        return p;
    }
}
