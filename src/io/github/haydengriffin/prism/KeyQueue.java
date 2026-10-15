package io.github.haydengriffin.prism;

import java.util.HashMap;
import java.util.Map;

/** Ordered, bounded presses. Never replay stale taps or continue a hold after an uncertain command. */
final class KeyQueue {

    static final int MAX_PENDING = 3;
    static final long REPEAT_GAP_MS = 150;
    static final long MAX_AGE_MS = 600;

    private static final class Press {
        final String key;
        final boolean repeat;
        final long at;
        boolean released;

        Press(String key, boolean repeat, long at) {
            this.key = key;
            this.repeat = repeat;
            this.at = at;
        }
    }

    private final Map<Long, Press> pending = new HashMap<>();
    private long lastSeq;
    private long flushedThrough;
    private long lastRepeatAt = Long.MIN_VALUE / 2;
    private boolean repeatsBlocked;

    /** @return a sequence number for the press, or 0 if it must not be sent */
    synchronized long admit(String key, boolean repeat, long now) {
        if (repeat) {
            if (!repeatAllowed(key, now)) return 0;
            lastRepeatAt = now;
        } else {
            if (pending.size() >= MAX_PENDING) return 0;
            repeatsBlocked = false;
        }
        long seq = ++lastSeq;
        pending.put(seq, new Press(key, repeat, now));
        return seq;
    }

    synchronized boolean shouldSend(long seq, long now) {
        Press press = pending.get(seq);
        if (press == null || seq <= flushedThrough || press.released) return false;
        return now >= press.at && now - press.at <= MAX_AGE_MS;
    }

    synchronized void release(String key) {
        for (Press press : pending.values()) {
            if (press.repeat && press.key.equals(key)) press.released = true;
        }
    }

    synchronized void done(long seq, boolean failed) {
        // A completion from a cancelled session must not flush presses in its replacement.
        if (pending.remove(seq) == null) return;
        if (failed) {
            flushedThrough = lastSeq;
            repeatsBlocked = true;
        }
    }

    synchronized void clear() {
        pending.clear();
        flushedThrough = lastSeq;
        repeatsBlocked = false;
        lastRepeatAt = Long.MIN_VALUE / 2;
    }

    synchronized int pending() {
        return pending.size();
    }

    private boolean repeatAllowed(String key, long now) {
        return !repeatsBlocked && arrow(key) && pending.isEmpty() && now - lastRepeatAt >= REPEAT_GAP_MS;
    }

    private static boolean arrow(String key) {
        return key.equals("up") || key.equals("down") || key.equals("left") || key.equals("right");
    }
}
