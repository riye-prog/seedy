package dev.seedy.recovery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

public final class SeedHash {
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    });
    public static long hash(long seed) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) bytes[i] = (byte)(seed >>> (8 * i));
        byte[] digest = DIGEST.get().digest(bytes);
        long result = 0;
        for (int i = 0; i < 8; i++) result |= (digest[i] & 255L) << (8 * i);
        return result;
    }
    public static Long recover(long structureSeed, long expectedHash, BooleanSupplier cancelled) {
        for (long upper = 0; upper < 65536; upper++) {
            if ((upper & 255) == 0 && cancelled.getAsBoolean()) return null;
            long seed = (upper << 48) | (structureSeed & PlacementRule.MASK);
            if (hash(seed) == expectedHash) return seed;
        }
        return null;
    }
}
