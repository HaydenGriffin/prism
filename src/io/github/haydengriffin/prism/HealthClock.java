package io.github.haydengriffin.prism;

import java.util.concurrent.TimeUnit;

/**
 * Milliseconds for link and route health. On the Shield it must count deep sleep (SystemClock.elapsedRealtime):
 * nanoTime and uptimeMillis stop while the CPU is suspended, so after a night asleep the last message from HA
 * would look seconds old and a dead link healthy. Key timing stays on uptimeMillis, in KeyEvent's own base.
 */
interface HealthClock {

    /** Host JVM only: does not count suspend. */
    HealthClock MONOTONIC = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

    long now();
}
