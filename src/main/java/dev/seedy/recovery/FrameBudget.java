package dev.seedy.recovery;

public final class FrameBudget {
    private volatile boolean active;
    private volatile long lastFrame;
    private volatile int pressure;
    private volatile long frameLimitPeriod;
    private double baseline;
    private int healthyFrames;

    public void frameLimit(int framesPerSecond) {
        long period = framesPerSecond <= 0 ? 0 : 1_000_000_000L / framesPerSecond;
        if (period == frameLimitPeriod) return;
        frameLimitPeriod = period;
        pressure = 0;
        healthyFrames = 0;
    }

    public void active(boolean value) {
        if (active == value) return;
        pressure = 0;
        healthyFrames = 0;
        active = value;
    }

    public void frame(long elapsed, long now) {
        if (elapsed <= 0) return;
        long period = lastFrame == 0 || now <= lastFrame ? elapsed : Math.max(elapsed,now - lastFrame);
        lastFrame = now;
        if (!active) {
            double sample = Math.clamp(period,500_000L,250_000_000L);
            baseline = baseline == 0 ? sample : baseline + (sample - baseline) / 32;
            return;
        }
        if (baseline == 0) baseline = Math.clamp(period,500_000L,250_000_000L);
        double expected = Math.max(baseline,frameLimitPeriod);
        if (period > Math.max(expected * 1.3,expected + 1_500_000)) {
            pressure = Math.min(8,pressure + 1);
            healthyFrames = 0;
        } else if (++healthyFrames >= 12) {
            pressure = Math.max(0,pressure - 1);
            healthyFrames = 0;
        }
    }

    public int dutyPercent(long now) {
        if (!active || lastFrame == 0) return 100;
        if (now - lastFrame > 250_000_000L || pressure >= 6) return 35;
        return pressure >= 2 ? 60 : 100;
    }
}
