package dev.seedy;

import dev.seedy.recovery.RecoveryBudget;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryBudgetTest {
    @Test void stopsAtDeadline() {
        var budget = new RecoveryBudget(Duration.ofSeconds(10));
        budget.start(200);
        assertTrue(budget.allows(10_000_000_199L));
        assertFalse(budget.allows(10_000_000_200L));
        assertTrue(budget.expired());
    }

    @Test void manualStopDoesNotBecomeTimeout() {
        var budget = new RecoveryBudget(Duration.ofSeconds(10));
        budget.start(0);
        budget.finish();
        assertFalse(budget.allows(30_000_000_000L));
        assertFalse(budget.expired());
    }

    @Test void newSearchGetsNewDeadline() {
        var budget = new RecoveryBudget(Duration.ofSeconds(1));
        budget.start(0);
        assertFalse(budget.allows(1_000_000_000L));
        budget.start(3_000_000_000L);
        assertFalse(budget.expired());
        assertTrue(budget.allows(3_500_000_000L));
        assertFalse(budget.allows(4_000_000_000L));
    }
}
