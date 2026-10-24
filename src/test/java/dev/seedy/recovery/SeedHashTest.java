package dev.seedy.recovery;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedHashTest {
    private static long reference(long seed) throws Exception {
        byte[] input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(seed).array();
        return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(input)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    @Test void reusedBuffersMatchFreshSha256AcrossWorkers() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 4; i++) {
                final int worker = i;
                tasks.add(() -> {
                    for (long seed : new long[]{0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE,-7619526468459309251L,765906787396911863L}) {
                        for (int repeat = 0; repeat < 100; repeat++) assertEquals(reference(seed + worker + repeat),SeedHash.hash(seed + worker + repeat));
                    }
                    return null;
                });
            }
            for (var result : workers.invokeAll(tasks)) result.get();
        }
    }

    @Test void candidateChecksDoNotAllocatePerSeed() {
        var bean = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        long result = 0;
        for (int i = 0; i < 50_000; i++) result ^= SeedHash.hash(i);
        long before = bean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 100_000; i++) result ^= SeedHash.hash(i);
        long allocated = bean.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 64_000,"allocated " + allocated + " bytes for 100000 candidates; checksum " + result);
    }
}
