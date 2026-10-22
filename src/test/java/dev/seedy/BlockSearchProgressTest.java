package dev.seedy;

import dev.seedy.recovery.BlockSearchProgress;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockSearchProgressTest {
    @Test void candidateUpdatesCannotGoBackToAnEarlierStage() {
        var progress = started();
        progress.candidates(800, 1000, seconds(10));
        progress.candidates(200, 1000, seconds(11));
        progress.update("commands.bedrockcrack.startingZ3Search", seconds(12));
        var view = progress.view(seconds(13));
        assertEquals(800, view.checkedCandidates());
        assertEquals(0.8, view.fraction());
        assertEquals(8, view.stage());
        assertEquals("Checking world seed candidates", view.phase());
    }

    @Test void fallbackHasANewPassAndKeepsItsReason() {
        var progress = started();
        progress.passResult("Block evidence conflicts");
        progress.beginPass(2, "full sample, cautious checks", seconds(21));
        var view = progress.view(seconds(24));
        assertEquals(2, view.pass());
        assertEquals(4, view.passCount());
        assertEquals("full sample, cautious checks", view.sample());
        assertEquals("Block evidence conflicts", view.previousPass());
        assertEquals(23, view.elapsedSeconds());
        assertEquals(3, view.stageSeconds());
        assertEquals(-1, view.fraction());
        assertThrows(IllegalStateException.class, () -> progress.beginPass(2, "duplicate pass", seconds(25)));
    }

    @Test void finishingFreezesElapsedTimeAndIgnoresLateCallbacks() {
        var progress = started();
        progress.candidates(900, 1000, seconds(10));
        progress.finish(seconds(31));
        progress.candidates(0, 1000, seconds(40));
        progress.update("commands.bedrockcrack.completedBitSearch1", seconds(50));
        var view = progress.view(seconds(80));
        assertFalse(view.running());
        assertEquals(30, view.elapsedSeconds());
        assertEquals(900, view.checkedCandidates());
        assertEquals(8, view.stage());
        assertEquals(-1, view.etaSeconds());
    }

    @Test void repeatedStageMessagesKeepTheStageClock() {
        var progress = started();
        progress.update("commands.bedrockcrack.completedBitSearch5", seconds(10));
        progress.update("commands.bedrockcrack.startingZ3Search", seconds(15));
        assertEquals(10, progress.view(seconds(20)).stageSeconds());
        progress.update("commands.bedrockcrack.stateCandidateRejected", seconds(21));
        assertEquals(1, progress.view(seconds(22)).rejectedStates());
    }

    @Test void parallelCallbacksKeepTheLargestCompletedCount() throws Exception {
        var progress = started();
        try (var workers = Executors.newFixedThreadPool(4)) {
            for (int i = 1; i <= 1000; i++) {
                int checked = i;
                workers.submit(() -> progress.candidates(checked, 1000, seconds(10)));
            }
        }
        assertEquals(1000, progress.view(seconds(20)).checkedCandidates());
        assertEquals(1, progress.view(seconds(20)).fraction());
    }

    private static BlockSearchProgress started() {
        var progress = new BlockSearchProgress();
        progress.start(4, seconds(1));
        progress.beginPass(1, "reduced sample, cautious checks", seconds(1));
        return progress;
    }

    private static long seconds(long value) { return value * 1_000_000_000L; }
}
