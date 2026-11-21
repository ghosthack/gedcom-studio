package ged.desktop;

import ged.client.DaemonClient;
import ged.daemon.Home;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// The app's line to the daemon. Holds the current [DaemonClient] (replaced
/// when the daemon comes back after going away) and tells listeners, on the
/// EDT, whether it is up.
///
/// Every call goes through here so that a dropped connection is noticed once,
/// wherever it happens; [ChangeWatcher] then brings it back.
final class Connection {

    enum State { CONNECTED, LOST, RECONNECTING }

    record Status(State state, String text) {
    }

    private final Home home;
    private volatile DaemonClient client;
    private volatile Status status;
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();

    Connection(Home home, DaemonClient client) {
        this.home = home;
        this.client = client;
        this.status = connectedStatus(client);
    }

    Home home() {
        return home;
    }

    Status status() {
        return status;
    }

    boolean connected() {
        return status.state() == State.CONNECTED;
    }

    void onStatus(Consumer<Status> listener) {
        listeners.add(listener);
    }

    DaemonClient client() throws IOException {
        var c = client;
        if (c == null) throw new IOException("not connected to the daemon");
        return c;
    }

    // ---- calls ------------------------------------------------------------------

    Map<String, Object> get(String path) throws IOException {
        try {
            return client().get(path);
        } catch (DaemonClient.ApiException e) {
            throw e;
        } catch (IOException e) {
            lost(e);
            throw e;
        }
    }

    Map<String, Object> getLong(String path) throws IOException {
        try {
            return client().getLong(path);
        } catch (DaemonClient.ApiException e) {
            throw e;
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) lost(e);
            throw e;
        }
    }

    Map<String, Object> post(String path, Map<String, ?> body) throws IOException {
        try {
            return client().post(path, body);
        } catch (DaemonClient.ApiException e) {
            throw e;
        } catch (IOException e) {
            lost(e);
            throw e;
        }
    }

    Optional<byte[]> bytes(String absolutePath) throws IOException {
        try {
            return client().bytes(absolutePath);
        } catch (IOException e) {
            lost(e);
            throw e;
        }
    }

    // ---- state ------------------------------------------------------------------

    /// A call failed without an answer: the daemon has gone (or is unreachable).
    void lost(IOException cause) {
        if (status.state() == State.CONNECTED) {
            setStatus(new Status(State.LOST, "Lost the daemon: " + cause.getMessage()));
        }
    }

    void reconnecting(String text) {
        setStatus(new Status(State.RECONNECTING, text));
    }

    /// Looks for the daemon again; with `start`, starts one if none is running.
    /// True once connected.
    boolean reconnect(boolean start) {
        Optional<DaemonClient> found;
        if (start) {
            try {
                found = Optional.of(DaemonClient.ensure(home, Duration.ofSeconds(20)));
            } catch (IOException e) {
                found = Optional.empty();
            }
        } else {
            found = DaemonClient.running(home);
        }
        if (found.isEmpty()) return false;
        client = found.get();
        setStatus(connectedStatus(client));
        return true;
    }

    private static Status connectedStatus(DaemonClient c) {
        var host = c == null ? "" : c.base().getHost() + ":" + c.base().getPort();
        return new Status(State.CONNECTED, "Connected to the daemon at " + host + (c == null ? "" : " (pid " + c.pid() + ")"));
    }

    private void setStatus(Status s) {
        status = s;
        SwingUtilities.invokeLater(() -> listeners.forEach(l -> l.accept(s)));
    }
}
