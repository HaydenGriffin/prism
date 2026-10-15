package io.github.haydengriffin.prism;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The remote service's hook for the "Continue on the LG" card: notes a different LG app in front and syncs the rows
 * at most once a minute, on its own thread, never on the key path.
 */
final class ChannelWatch {
    private static final long GAP_MS = 60_000;
    private static final HomeRows.Throttle throttle = new HomeRows.Throttle(GAP_MS);
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "lg-channel-watch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Link thread, on every pushed TV state: cheap, and nothing here can throw into the service. */
    static void observe(Context service, String target, String shieldApp) {
        try {
            if (!HomeRows.lgApp(target, shieldApp)) return;
            long delay = throttle.observe(target, SystemClock.elapsedRealtime());
            if (delay < 0) return;
            Context app = service.getApplicationContext();
            timer.schedule(() -> sync(app), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Never shut down; nothing to do.
        } catch (RuntimeException e) {
            Log.w("Prism", "Channels: watch: " + e.getClass().getSimpleName());
        }
    }

    /** Timer thread: records the newest LG app and syncs when it differs from the one the rows show. */
    private static void sync(Context app) {
        try {
            throttle.running(SystemClock.elapsedRealtime());
            String seen = throttle.seen();
            if (seen == null || seen.equals(ChannelSync.lastApp(app))) return;
            ChannelSync.lastApp(app, seen);
            ChannelSync.request(app, "lg app");
        } catch (Throwable e) { // Escaping would take the process, and the remote, down.
            Log.w("Prism", "Channels: watch: " + e.getClass().getSimpleName());
        }
    }

    private ChannelWatch() {}
}
