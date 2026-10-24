package dev.seedy.recovery;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryWorkloadTest {
    @Test void leavesProcessorsForTheGame() {
        assertEquals(1,RecoveryWorkload.workersFor(1));
        assertEquals(1,RecoveryWorkload.workersFor(2));
        assertEquals(2,RecoveryWorkload.workersFor(4));
        assertEquals(4,RecoveryWorkload.workersFor(8));
        assertEquals(4,RecoveryWorkload.workersFor(128));
    }

    @Test void workersHaveTheirOwnLowPriorityAndStopWhileYielding() throws Exception {
        var started = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        RecoveryWorkload.frameStarted();
        RecoveryWorkload.frameFinished();
        RecoveryWorkload.active(true);
        int callerPriority = Thread.currentThread().getPriority();
        var worker = RecoveryWorkload.threads("test-recovery").newThread(() -> {
            assertTrue(Thread.currentThread().isDaemon());
            assertTrue(Thread.currentThread().getPriority() < Thread.NORM_PRIORITY);
            started.countDown();
            while (RecoveryWorkload.checkpoint(cancelled::get)) Thread.onSpinWait();
        });
        try {
            worker.start();
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
            cancelled.set(true);
            assertTimeoutPreemptively(Duration.ofMillis(300),() -> { worker.join(); });
            assertFalse(worker.isAlive());
            assertEquals(callerPriority,Thread.currentThread().getPriority());
        } finally {
            cancelled.set(true);
            worker.interrupt();
            RecoveryWorkload.active(false);
        }
    }

    @Test void anInterruptedWorkerDoesNotContinue() throws Exception {
        var checked = new AtomicBoolean(true);
        var worker = RecoveryWorkload.threads("test-interruption").newThread(() -> {
            Thread.currentThread().interrupt();
            checked.set(RecoveryWorkload.checkpoint(() -> false));
        });
        worker.start();
        worker.join(1000);
        assertFalse(checked.get());
    }
}
