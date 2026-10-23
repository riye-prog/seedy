package dev.seedy.recovery;

import java.time.Duration;

public final class RecoveryBudget {
    private final long limit;
    private volatile long started;
    private volatile boolean active;
    private volatile boolean expired;

    public RecoveryBudget(Duration duration) {
        limit = duration.toNanos();
        if (limit <= 0) throw new IllegalArgumentException("Search time limit must be positive");
    }

    public void start(long now) {
        started = now;
        expired = false;
        active = true;
    }

    public boolean allows(long now) {
        if (!active) return false;
        if (now - started >= limit) expired = true;
        return !expired;
    }

    public boolean expired() { return expired; }
    public void finish() { active = false; }
}
