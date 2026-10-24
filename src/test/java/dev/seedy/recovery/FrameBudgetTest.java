package dev.seedy.recovery;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameBudgetTest {
    @Test void yieldsMoreWhenFramesSlowDownAndRecoversGradually() {
        var budget = new FrameBudget();
        long now = 1_000_000_000L;
        budget.frame(8_000_000,now);
        assertEquals(100,budget.dutyPercent(now));
        budget.active(true);
        assertEquals(100,budget.dutyPercent(now));
        for (int i = 0; i < 2; i++) budget.frame(14_000_000,now += 14_000_000);
        assertEquals(60,budget.dutyPercent(now));
        for (int i = 0; i < 6; i++) budget.frame(14_000_000,now += 14_000_000);
        assertEquals(35,budget.dutyPercent(now));
        for (int i = 0; i < 84; i++) budget.frame(8_000_000,now += 8_000_000);
        assertEquals(100,budget.dutyPercent(now));
        budget.active(false);
        assertEquals(100,budget.dutyPercent(now));
    }

    @Test void missingFramesReduceTheBudgetWithoutChangingTheBaseline() {
        var budget = new FrameBudget();
        budget.frame(4_000_000,1_000_000_000L);
        budget.active(true);
        assertEquals(35,budget.dutyPercent(1_300_000_000L));
        budget.frame(4_000_000,1_304_000_000L);
        assertEquals(100,budget.dutyPercent(1_304_000_000L));
        budget.active(false);
        budget.active(true);
        assertEquals(100,budget.dutyPercent(1_304_000_000L));
    }

    @Test void headlessUseDoesNotWaitForFrames() {
        var budget = new FrameBudget();
        budget.active(true);
        assertEquals(100,budget.dutyPercent(System.nanoTime()));
        budget.frame(0,1);
        assertEquals(100,budget.dutyPercent(1));
    }

    @Test void renderingVariationWithinAStableFramePeriodDoesNotThrottleRecovery() {
        var budget = new FrameBudget();
        long now = 1_000_000_000L;
        for (int i = 0; i < 256; i++) budget.frame(2_000_000,now += 8_333_333);
        budget.active(true);
        for (int i = 0; i < 64; i++) budget.frame(i % 2 == 0 ? 2_000_000 : 5_000_000,now += 8_333_333);
        assertEquals(100,budget.dutyPercent(now));
        for (int i = 0; i < 6; i++) budget.frame(2_000_000,now += 15_000_000);
        assertEquals(35,budget.dutyPercent(now));
    }

    @Test void aLowFrameLimitDoesNotLookLikeCpuContention() {
        var budget = new FrameBudget();
        long now = 1_000_000_000L;
        for (int i = 0; i < 256; i++) budget.frame(2_000_000,now += 100_000_000);
        budget.active(true);
        for (int i = 0; i < 64; i++) budget.frame(3_000_000,now += 100_000_000);
        assertEquals(100,budget.dutyPercent(now));
    }
}
