#include SEEDY_KERNEL_SOURCE

extern "C" bool SEEDY_TEST_ENTRY(uint64_t first, uint64_t second, uint64_t guess, bool full) {
    nextlong1 = first;
    nextlong2 = second;
    return full ? bruteKernel<true>(guess) : bruteKernel<false>(guess);
}

extern "C" uint64_t SEEDY_TEST_WIDTH() {
    return SIMD_BITS;
}
