package dev.seedy;

import dev.seedy.recovery.WorldVersionCheck;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldVersionCheckTest {
    @Test void identifiesReportedPaperMismatch() {
        assertTrue(WorldVersionCheck.warning("26.3", "Paper 26.2").contains("Server reports 26.2"));
        assertTrue(WorldVersionCheck.warning("26.3", "26.2.build.134-stable").contains("26.3"));
    }

    @Test void leavesUnknownAndMatchingVersionsAlone() {
        assertEquals("", WorldVersionCheck.warning("26.2", "Paper 26.2"));
        assertEquals("", WorldVersionCheck.warning("26.3", "Not reported"));
        assertEquals("", WorldVersionCheck.warning("26.3", "126.2"));
    }
}
