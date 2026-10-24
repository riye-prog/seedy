package dev.seedy.recovery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

public final class SeedHash {
    private static final class HashState {
        final byte[] input = new byte[8], output = new byte[32];
        final MessageDigest digest;
        HashState() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
    private static final ThreadLocal<HashState> DIGEST = ThreadLocal.withInitial(HashState::new);
    public static long hash(long seed) {
        var state = DIGEST.get();
        for (int i = 0; i < 8; i++) state.input[i] = (byte)(seed >>> (8 * i));
        state.digest.update(state.input);
        try { state.digest.digest(state.output,0,state.output.length); }
        catch (java.security.DigestException failure) { throw new IllegalStateException(failure); }
        long result = 0;
        for (int i = 0; i < 8; i++) result |= (state.output[i] & 255L) << (8 * i);
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
