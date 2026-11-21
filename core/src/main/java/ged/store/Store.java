package ged.store;

import ged.model.Genealogy;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/// The one door to a [Genealogy] that several front ends share: the shell,
/// the web app and the desktop app all reach the same records through it.
///
/// [Genealogy] is not thread-safe, so every use goes through [#read] (any
/// number at once) or [#write] (one at a time, nobody reading). The
/// genealogy's [Genealogy#revision] says whether anything changed;
/// [#awaitChange] lets a front end wait for that instead of polling.
public final class Store {
    private final Genealogy g;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Object changed = new Object();
    /// The revision last published to waiters; read without the lock.
    private volatile long published;

    public Store(Genealogy g) {
        this.g = g;
        this.published = g.revision();
    }

    /// The genealogy itself, for code that already holds the lock (a shell
    /// command runs inside [#write]).
    public Genealogy genealogy() { return g; }

    /// Runs `f` with the records unchanging; others may read meanwhile.
    public <T> T read(Function<Genealogy, T> f) {
        lock.readLock().lock();
        try {
            return f.apply(g);
        } finally {
            lock.readLock().unlock();
        }
    }

    /// Runs `f` alone; it may change the records. Waiters in
    /// [#awaitChange] wake once it returns, if it changed anything.
    public <T> T write(Function<Genealogy, T> f) {
        lock.writeLock().lock();
        try {
            return f.apply(g);
        } finally {
            long now = g.revision();
            lock.writeLock().unlock();
            publish(now);
        }
    }

    /// For a long wait inside [#write], such as a person editing a form in
    /// their text editor: lets everyone else in meanwhile, then takes the
    /// lock back. What the caller read before may be stale afterwards.
    public <T> T outside(Callable<T> wait) throws Exception {
        if (!lock.isWriteLockedByCurrentThread()) return wait.call();
        int holds = lock.getWriteHoldCount();
        long now = g.revision();
        for (int i = 0; i < holds; i++) lock.writeLock().unlock();
        publish(now);
        try {
            return wait.call();
        } finally {
            for (int i = 0; i < holds; i++) lock.writeLock().lock();
        }
    }

    /// The current revision (see [Genealogy#revision]).
    public long revision() { return published; }

    /// Waits until the revision is past `after`, or `timeout` passes;
    /// returns the revision then.
    public long awaitChange(long after, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (changed) {
            while (published <= after) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                changed.wait(Math.max(1, left / 1_000_000));
            }
            return published;
        }
    }

    private void publish(long revision) {
        if (revision == published) return;
        synchronized (changed) {
            published = revision;
            changed.notifyAll();
        }
    }
}
