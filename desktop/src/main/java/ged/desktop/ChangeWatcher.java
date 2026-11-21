package ged.desktop;

import javax.swing.SwingUtilities;
import java.io.IOException;

/// Follows the current file's revision with the daemon's `changes` long poll,
/// so edits from other front ends (the shell, the web app, another window)
/// show up here; and, when the daemon goes away, brings the connection back
/// with growing pauses: first looking for a running daemon, then starting one.
///
/// Callbacks run on the EDT.
final class ChangeWatcher {

    /// What the watcher reports.
    interface Listener {
        /// The file's revision passed the one known.
        void changed(String fileId, long revision);

        /// The daemon no longer has the file (closed elsewhere).
        void gone(String fileId, String message);

        /// The connection is back (perhaps to a new daemon): reload everything.
        void reconnected();
    }

    /// Pauses between reconnection attempts, in seconds.
    static final int[] BACKOFF = {1, 2, 4, 8, 15, 30};

    /// Attempts that only look for a running daemon before starting one.
    static final int LOOK_BEFORE_START = 2;

    private final Api api;
    private final Listener listener;
    private final Thread thread;
    private final Object lock = new Object();
    private volatile String fileId;
    private volatile long known;
    private volatile boolean stopped;

    ChangeWatcher(Api api, Listener listener) {
        this.api = api;
        this.listener = listener;
        this.thread = Thread.ofVirtual().name("gedcom-changes").unstarted(this::loop);
    }

    void start() {
        thread.start();
    }

    void stop() {
        stopped = true;
        thread.interrupt();
    }

    /// Follows `id` from `revision` on (or nothing, with null).
    void watch(String id, long revision) {
        synchronized (lock) {
            boolean other = id == null ? fileId != null : !id.equals(fileId);
            fileId = id;
            known = revision;
            lock.notifyAll();
            if (other) thread.interrupt();
        }
    }

    /// The window learnt a newer revision itself (its own edit): don't report it again.
    void saw(long revision) {
        if (revision > known) known = revision;
    }

    long known() {
        return known;
    }

    private void loop() {
        int failures = 0;
        while (!stopped) {
            var conn = api.connection();
            if (!conn.connected()) {
                failures = reconnect(failures);
                continue;
            }
            var id = fileId;
            if (id == null) {
                synchronized (lock) {
                    try {
                        if (fileId == null && !stopped) lock.wait(30_000);
                    } catch (InterruptedException e) {
                        // a new file, or stopping
                    }
                }
                continue;
            }
            try {
                long after = known;
                long rev = api.changes(id, after, 25);
                if (id.equals(fileId) && rev > known) {
                    known = rev;
                    SwingUtilities.invokeLater(() -> listener.changed(id, rev));
                } else if (rev < after && id.equals(fileId)) {
                    // the daemon started afresh: revisions count from the file's reopening
                    known = rev;
                }
                failures = 0;
            } catch (ged.client.DaemonClient.ApiException e) {
                if (e.status == 404 && id.equals(fileId)) {
                    SwingUtilities.invokeLater(() -> listener.gone(id, e.getMessage()));
                    synchronized (lock) {
                        if (id.equals(fileId)) fileId = null;
                    }
                } else {
                    pause(2000);
                }
            } catch (IOException e) {
                if (Thread.interrupted()) continue; // switched files, or stopping
                if (conn.connected()) pause(1000);
            }
        }
    }

    /// One reconnection attempt after the right pause; returns the new failure count.
    private int reconnect(int failures) {
        var conn = api.connection();
        int wait = BACKOFF[Math.min(failures, BACKOFF.length - 1)];
        for (int s = wait; s > 0 && !stopped; s--) {
            conn.reconnecting("The daemon is not answering; trying again in " + s + " s…");
            if (!pause(1000)) return failures;
        }
        conn.reconnecting(failures >= LOOK_BEFORE_START ? "Starting the daemon…" : "Looking for the daemon…");
        if (conn.reconnect(failures >= LOOK_BEFORE_START)) {
            SwingUtilities.invokeLater(listener::reconnected);
            return 0;
        }
        return failures + 1;
    }

    private boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /// For tests: the pause before attempt `n` (0-based).
    static int backoff(int n) {
        return BACKOFF[Math.min(n, BACKOFF.length - 1)];
    }
}
