package ged.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import ged.json.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static ged.json.Json.obj;

/// The daemon: one process that holds the open GEDCOM files and serves them
/// over HTTP to the shell, the web app and the desktop app.
///
/// - **One at a time.** It holds `daemon.lock` (an OS lock, freed if the
///   process dies) for its whole life; a second one exits at once.
/// - **Found by clients** through `daemon.json`: its port, address and pid.
/// - **Token always.** Every API call needs `Authorization: Bearer TOKEN`,
///   even from this computer: `tailscale serve` and other proxies make
///   remote requests look local. Only `/api/v1/ping`, the web app's own
///   files and signed picture addresses go without it.
/// - **Nothing lost.** Stopped with unsaved edits (`stop` with force, or a
///   signal), it writes them to `recovery/` first.
public final class Server {
    public static final int DEFAULT_PORT = 7380;
    public static final String VERSION = version();

    private static String version() {
        try (var in = Server.class.getResourceAsStream("/ged/version.properties")) {
            var p = new java.util.Properties();
            if (in != null) p.load(in);
            var v = p.getProperty("version", "dev");
            return v.startsWith("${") ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }

    /// Where and how to listen. `bind` is an address (`127.0.0.1`,
    /// `0.0.0.0`, a Tailscale address); `keystore` turns on HTTPS.
    public record Config(String bind, int port, Optional<Path> keystore, Optional<String> keystorePassword, Optional<Path> webDir) {
        public static Config of(Home home) {
            var s = home.settings();
            int port;
            try {
                port = Integer.parseInt(s.getProperty("port", "" + DEFAULT_PORT).strip());
            } catch (NumberFormatException e) {
                port = DEFAULT_PORT;
            }
            var ks = Optional.ofNullable(s.getProperty("tls.keystore")).filter(x -> !x.isBlank()).map(x -> Path.of(Api.expandHome(x.strip())));
            var pw = Optional.ofNullable(System.getenv("GEDCOM_SHELL_TLS_PASSWORD")).or(() -> Optional.ofNullable(s.getProperty("tls.password")));
            return new Config(s.getProperty("bind", "127.0.0.1").strip(), port, ks, pw, Optional.empty());
        }

        boolean tls() { return keystore.isPresent(); }
    }

    private final Home home;
    private final Config config;
    private final PrintStream log;
    private final String token;
    private final byte[] signingKey;
    private final Workspace workspace;
    private final ShellSessions sessions = new ShellSessions();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private HttpServer http;
    private FileChannel lockChannel;
    private FileLock lock;
    private Api api;
    private int port;

    public Server(Home home, Config config, PrintStream log) throws IOException {
        this.home = home;
        this.config = config;
        this.log = log;
        this.token = home.token();
        this.signingKey = sha256("pictures:" + token);
        this.workspace = new Workspace(home);
    }

    public int port() { return port; }
    public String token() { return token; }

    /// Takes the lock and starts listening. Returns false, having done
    /// nothing, if another daemon has the lock.
    ///
    /// @throws IOException if the port can't be had
    public boolean start() throws IOException {
        home.create();
        lockChannel = FileChannel.open(home.lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            return false;
        }
        try {
            var address = new InetSocketAddress(InetAddress.getByName(config.bind()), config.port());
            http = config.tls() ? https(address) : HttpServer.create(address, 64);
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            http.createContext("/", this::exchange);
            http.start();
            port = http.getAddress().getPort();
        } catch (IOException | RuntimeException e) {
            releaseLock();
            throw e;
        }
        // a new one each start: revisions start over with the daemon, and clients must know
        var instance = java.util.HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(8).putLong(new java.security.SecureRandom().nextLong()).array());
        var about = obj("app", "gedcom-shell", "api", Api.VERSION, "version", VERSION, "instance", instance,
                "pid", ProcessHandle.current().pid(), "started", Instant.now().toString(),
                "bind", config.bind(), "port", port, "scheme", config.tls() ? "https" : "http",
                "home", home.dir().toString());
        api = new Api(workspace, sessions, this::sign, this::stop, about);
        var state = new LinkedHashMap<>(about);
        state.put("connect", connectHost());
        config.keystore().ifPresent(k -> state.put("keystore", k.toAbsolutePath().toString()));
        home.writePrivate(home.stateFile(), Json.write(state) + "\n");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "gedcom-shell-daemon-stop"));
        log.println("gedcom-shell daemon " + VERSION + " listening on " + url() + " (pid " + ProcessHandle.current().pid() + ")");
        if (!isLoopback(config.bind()) && !config.tls()) {
            log.println("note: listening beyond this computer without TLS. Over Tailscale the traffic is encrypted;"
                    + " on other networks set tls.keystore (see docs/daemon.md).");
        }
        return true;
    }

    /// The address clients on this computer use.
    private String connectHost() {
        var b = config.bind();
        if (b.equals("0.0.0.0") || b.equals("::") || b.equals("[::]") || isLoopback(b)) return "127.0.0.1";
        return b;
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    public String url() {
        return (config.tls() ? "https" : "http") + "://" + connectHost() + ":" + port + "/";
    }

    /// Waits until the daemon stops.
    public void await() throws InterruptedException { stopped.await(); }

    /// Stops listening, saves recovery copies of unsaved edits, and lets
    /// the next daemon start. Safe to call more than once.
    public void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        try {
            if (http != null) http.stop(1);
            var copies = workspace.recover();
            for (var c : copies) log.println("unsaved edits written to " + c);
            try {
                // only ours: a newer daemon may have written its own
                var text = Files.readString(home.stateFile());
                if (text.contains("\"pid\":" + ProcessHandle.current().pid() + ",")) Files.deleteIfExists(home.stateFile());
            } catch (IOException e) {
                // gone already
            }
            log.println("gedcom-shell daemon stopped");
        } finally {
            releaseLock();
            stopped.countDown();
        }
    }

    private void releaseLock() {
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException e) {
            // the OS frees it as the process ends
        }
    }

    private HttpsServer https(InetSocketAddress address) throws IOException {
        var ks = config.keystore().orElseThrow();
        var password = config.keystorePassword().orElse("").toCharArray();
        try (var in = Files.newInputStream(ks)) {
            var store = KeyStore.getInstance(ks.toString().toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12");
            store.load(in, password);
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);
            var server = HttpsServer.create(address, 64);
            server.setHttpsConfigurator(new HttpsConfigurator(ssl));
            return server;
        } catch (GeneralSecurityException e) {
            throw new IOException("can't use " + ks + " for TLS: " + e.getMessage(), e);
        }
    }

    // ---- requests ------------------------------------------------------------

    private static final int MAX_BODY = 4 << 20;

    private void exchange(HttpExchange ex) throws IOException {
        try (ex) {
            var headers = ex.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Frame-Options", "DENY");
            var path = ex.getRequestURI().getRawPath();
            if (path.startsWith("/api/")) api(ex, path);
            else staticFile(ex, path);
        } catch (RuntimeException e) {
            log.println("error serving " + ex.getRequestURI() + ": " + e);
        }
    }

    private void api(HttpExchange ex, String rawPath) throws IOException {
        var segments = new ArrayList<String>();
        for (var s : rawPath.substring(1).split("/")) {
            if (!s.isEmpty()) segments.add(URLDecoder.decode(s, StandardCharsets.UTF_8));
        }
        // api, v1, ...
        if (segments.size() < 2 || !segments.get(1).equals("v" + Api.VERSION)) {
            json(ex, 404, error("this daemon speaks /api/v" + Api.VERSION));
            return;
        }
        var path = segments.subList(2, segments.size());
        var query = query(ex.getRequestURI().getRawQuery());
        var method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if (path.size() == 1 && path.getFirst().equals("ping")) {
            json(ex, 200, obj("app", "gedcom-shell", "api", Api.VERSION, "version", VERSION, "pid", ProcessHandle.current().pid()));
            return;
        }
        boolean signed = method.equals("GET") && query.containsKey("sig")
                && MessageDigest.isEqual(sign(rawPath).getBytes(StandardCharsets.UTF_8),
                        (rawPath + "?sig=" + query.get("sig")).getBytes(StandardCharsets.UTF_8));
        if (!signed && !authorized(ex)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            json(ex, 401, error("a token is needed: open the address `gedcom-shell daemon pair` prints"));
            return;
        }
        Map<String, Object> body = Map.of();
        if (method.equals("POST") || method.equals("PUT")) {
            try {
                body = Json.parseObject(readBody(ex.getRequestBody()));
            } catch (IllegalArgumentException e) {
                json(ex, 400, error("bad JSON: " + e.getMessage()));
                return;
            }
        }
        try {
            var reply = api.handle(new Api.Request(method, path, query, body));
            switch (reply) {
                case Api.JsonReply j -> json(ex, j.status(), j.body());
                case Api.FileReply f -> {
                    if (signed || authorized(ex)) file(ex, f.file(), "private, max-age=3600");
                    else json(ex, 401, error("not allowed"));
                }
            }
        } catch (Api.Failure e) {
            var err = error(e.getMessage());
            err.putAll(e.extra);
            json(ex, e.status, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            json(ex, 503, error("the daemon is stopping"));
        } catch (RuntimeException e) {
            log.println("internal error on " + method + " " + rawPath + ": " + e);
            e.printStackTrace(log);
            json(ex, 500, error("internal error (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    + "); nothing was lost, the edits are still held"));
        }
    }

    private boolean authorized(HttpExchange ex) {
        var h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return MessageDigest.isEqual(h.substring(7).strip().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /// `path?sig=…`: lets that one address be fetched without the token.
    String sign(String path) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            var sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(path.getBytes(StandardCharsets.UTF_8)));
            return path + "?sig=" + sig.substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        var bytes = in.readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new IllegalArgumentException("request too large");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String raw) {
        var out = new LinkedHashMap<String, String>();
        if (raw == null) return out;
        for (var pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            var k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            var v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static Map<String, Object> error(String message) {
        return obj("error", message);
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        var bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void file(HttpExchange ex, Path file, String cache) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.getResponseHeaders().set("Cache-Control", cache);
        ex.sendResponseHeaders(200, Files.size(file));
        try (var in = Files.newInputStream(file)) {
            in.transferTo(ex.getResponseBody());
        }
    }

    static String contentType(String name) {
        var n = name.toLowerCase(Locale.ROOT);
        var ext = n.substring(n.lastIndexOf('.') + 1);
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "webmanifest" -> "application/manifest+json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "heic" -> "image/heic";
            case "ico" -> "image/x-icon";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    // ---- the web app ---------------------------------------------------------

    private static final String CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'; "
            + "connect-src 'self'; manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private void staticFile(HttpExchange ex, String rawPath) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
            json(ex, 405, error("use GET"));
            return;
        }
        var path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..") || path.contains("\\") || path.contains("\0")) {
            json(ex, 404, error("not found"));
            return;
        }
        byte[] bytes = null;
        if (config.webDir().isPresent()) {
            var f = config.webDir().get().resolve(path.substring(1)).normalize();
            if (f.startsWith(config.webDir().get()) && Files.isRegularFile(f)) bytes = Files.readAllBytes(f);
        } else {
            try (var in = Server.class.getResourceAsStream("/ged/web" + path)) {
                if (in != null) bytes = in.readAllBytes();
            }
        }
        if (bytes == null) {
            // the app routes in the page (#/...), so any other address gets it too
            if (!path.contains(".")) {
                staticFile(ex, "/");
                return;
            }
            json(ex, 404, error("not found"));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", contentType(path));
        ex.getResponseHeaders().set("Cache-Control", path.equals("/sw.js") || path.equals("/index.html") ? "no-cache" : "public, max-age=300");
        ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        ex.sendResponseHeaders(200, ex.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
        if (!ex.getRequestMethod().equals("HEAD")) ex.getResponseBody().write(bytes);
    }
}
