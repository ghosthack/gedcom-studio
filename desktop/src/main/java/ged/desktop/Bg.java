package ged.desktop;

import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/// Runs blocking work (daemon calls, image decoding) on virtual threads and
/// hands the result back on the EDT. Nothing in the UI waits on the daemon.
final class Bg {

    @FunctionalInterface
    interface Work<T> {
        T run() throws Exception;
    }

    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    /// Runs `work` in the background, then `ok` or `failed` on the EDT.
    static <T> void run(Work<T> work, Consumer<T> ok, Consumer<Exception> failed) {
        POOL.execute(() -> {
            try {
                T result = work.run();
                SwingUtilities.invokeLater(() -> ok.accept(result));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> failed.accept(e));
            }
        });
    }

    static void execute(Runnable r) {
        POOL.execute(r);
    }

    private Bg() {
    }
}
