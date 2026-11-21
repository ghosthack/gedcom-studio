package ged.client;

import ged.daemon.Home;
import ged.json.Json;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Talks to the daemon over its API: finds the running one through
/// `daemon.json`, or starts one from this same jar ([#ensure]).
///
/// Used by the shell when it attaches to the daemon and by the desktop app.
public final class DaemonClient {
    /// A refusal or failure from the daemon, with its HTTP status and the
    /// whole error object (for extras such as `conflict`).
    public static final class ApiException extends IOException {
        public final int status;
        public final Map<String, Object> body;

        ApiException(int status, String message, Map<String, Object> body) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }

    private final URI base;
    private final String token;
    private final HttpClient http;
    private final long pid;

    private DaemonClient(URI base, String token, HttpClient http, long pid) {
        this.base = base;
        this.token = token;
        this.http = http;
        this.pid = pid;
    }

    /// The daemon's own address, such as `http://127.0.0.1:7380/`.
    public URI base() { return base; }
    public long pid() { return pid; }
    public String token() { return token; }

    /// The address that pairs a browser: the web app, with the token in the
    /// part of the address that never leaves the browser (after `#`).
    public String pairingUrl(Optional<String> fileId) {
        return base + "#pair=" + token + fileId.map(id -> "&open=" + id).orElse("");
    }

    /// The daemon that is running now, if any.
    public static Optional<DaemonClient> running(Home home) {
        Map<String, Object> state;
        try {
            state = Json.parseObject(Files.readString(home.stateFile(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        try {
            var scheme = String.valueOf(state.getOrDefault("scheme", "http"));
            var host = String.valueOf(state.getOrDefault("connect", "127.0.0.1"));
            int port = ((Number) state.get("port")).intValue();
            long pid = ((Number) state.get("pid")).longValue();
            var http = httpClient(scheme, state, home);
            var c = new DaemonClient(URI.create(scheme + "://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/"),
                    home.token(), http, pid);
            var ping = c.get("ping");
            if (!"gedcom-shell".equals(ping.get("app")) || ((Number) ping.get("pid")).longValue() != pid) return Optional.empty();
            return Optional.of(c);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static HttpClient httpClient(String scheme, Map<String, Object> state, Home home) throws IOException {
        var b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        if (scheme.equals("https") && state.get("keystore") instanceof String ks) {
            // trust exactly the daemon's own certificate, whatever name it carries
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            try (var in = Files.newInputStream(Path.of(ks))) {
                var pw = Optional.ofNullable(System.getenv("GEDCOM_SHELL_TLS_PASSWORD"))
                        .or(() -> Optional.ofNullable(home.settings().getProperty("tls.password"))).orElse("");
                var store = KeyStore.getInstance(ks.toLowerCase(java.util.Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12");
                store.load(in, pw.toCharArray());
                var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(store);
                var ssl = SSLContext.getInstance("TLS");
                ssl.init(null, tmf.getTrustManagers(), null);
                b.sslContext(ssl);
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("can't read the daemon's certificate: " + e.getMessage(), e);
            }
        }
        return b.build();
    }

    /// The running daemon, starting one from this jar if there is none.
    ///
    /// @throws IOException if it didn't come up within `timeout` (the
    ///         message ends with the last lines of its log)
    public static DaemonClient ensure(Home home, Duration timeout) throws IOException {
        var found = running(home);
        if (found.isPresent()) return found.get();
        home.create();
        var javaBin = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        var classpath = new ArrayList<String>();
        for (var entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) classpath.add(Path.of(entry).toAbsolutePath().toString());
        }
        var command = new ArrayList<>(List.of(javaBin.toString()));
        if (System.getProperty("gedcom.shell.home") != null) command.add("-Dgedcom.shell.home=" + home.dir().toAbsolutePath());
        command.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, classpath), "ged.Main", "daemon", "run", "--background"));
        var process = new ProcessBuilder(command)
                .directory(new java.io.File(System.getProperty("user.home")))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                .start();
        process.getOutputStream().close();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            found = running(home);
            if (found.isPresent()) return found.get();
            if (!process.isAlive() && process.exitValue() != 0) {
                throw new IOException("the daemon did not start:\n" + logTail(home));
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("the daemon did not answer within " + timeout.toSeconds() + " s; see " + home.logFile() + "\n" + logTail(home));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while starting the daemon");
            }
        }
    }

    private static String logTail(Home home) {
        try {
            var lines = Files.readAllLines(home.logFile(), StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 8), lines.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    // ---- calls -----------------------------------------------------------------

    public Map<String, Object> get(String path) throws IOException {
        return send(HttpRequest.newBuilder(uri(path)).GET(), Duration.ofSeconds(30));
    }

    /// A GET that may wait a while: the daemon holds `changes` up to a minute.
    public Map<String, Object> getLong(String path) throws IOException {
        return send(HttpRequest.newBuilder(uri(path)).GET(), Duration.ofSeconds(90));
    }

    public Map<String, Object> post(String path, Map<String, ?> body) throws IOException {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)), Duration.ofHours(7));
    }

    public Map<String, Object> delete(String path) throws IOException {
        return send(HttpRequest.newBuilder(uri(path)).DELETE(), Duration.ofSeconds(30));
    }

    /// Raw bytes of an address the API gave (a picture), or empty.
    public Optional<byte[]> bytes(String absolutePath) throws IOException {
        var req = HttpRequest.newBuilder(base.resolve(absolutePath)).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60)).GET().build();
        try {
            var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return res.statusCode() == 200 ? Optional.of(res.body()) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private URI uri(String path) {
        return base.resolve("/api/v1/" + path);
    }

    /// `a/b` with each part made safe for an address.
    public static String path(String... parts) {
        var sb = new StringBuilder();
        for (var p : parts) {
            if (!sb.isEmpty()) sb.append('/');
            sb.append(URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private Map<String, Object> send(HttpRequest.Builder b, Duration timeout) throws IOException {
        var req = b.header("Authorization", "Bearer " + token).timeout(timeout).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new IOException("the daemon is not running (or not reachable at " + base + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(res.body());
        } catch (IllegalArgumentException e) {
            throw new ApiException(res.statusCode(), "unreadable reply from the daemon (HTTP " + res.statusCode() + ")", Map.of());
        }
        if (res.statusCode() >= 400) {
            throw new ApiException(res.statusCode(), String.valueOf(body.getOrDefault("error", "HTTP " + res.statusCode())), body);
        }
        return body;
    }
}
