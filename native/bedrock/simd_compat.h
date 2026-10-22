#pragma once

#include <cstddef>
#include <functional>
#include <type_traits>
#include <utility>

namespace std::experimental {

template<typename T>
class native_simd {
    static_assert(std::is_same_v<T, unsigned long long> || std::is_same_v<T, unsigned long>);
#if defined(__AVX512F__)
    static constexpr std::size_t LANES = 8;
#elif defined(__AVX2__)
    static constexpr std::size_t LANES = 4;
#else
    static constexpr std::size_t LANES = 2;
#endif
    using Storage = T __attribute__((vector_size(sizeof(T) * LANES)));
    Storage values{};

    template<typename Generator, std::size_t... Index>
    void assign(Generator generator, std::index_sequence<Index...>) {
        ((values[Index] = generator(std::integral_constant<std::size_t, Index>{})), ...);
    }

public:
    native_simd() = default;
    native_simd(T value) {
        for (std::size_t index = 0; index < LANES; index++) values[index] = value;
    }
    template<typename Generator> requires (!std::is_integral_v<Generator>)
    native_simd(Generator generator) {
        assign(generator, std::make_index_sequence<LANES>{});
    }

    static constexpr std::size_t size() { return LANES; }
    T operator[](std::size_t index) const { return values[index]; }
    native_simd &operator&=(native_simd other) { values &= other.values; return *this; }
    native_simd &operator|=(native_simd other) { values |= other.values; return *this; }
    native_simd &operator^=(native_simd other) { values ^= other.values; return *this; }
    friend native_simd operator~(native_simd input) { input.values = ~input.values; return input; }
    friend native_simd operator&(native_simd left, native_simd right) { left &= right; return left; }
    friend native_simd operator|(native_simd left, native_simd right) { left |= right; return left; }
    friend native_simd operator^(native_simd left, native_simd right) { left ^= right; return left; }
};

template<typename T, typename Operation>
T reduce(native_simd<T> input, Operation operation) {
    T result = 0;
    for (std::size_t index = 0; index < native_simd<T>::size(); index++) result = operation(result, input[index]);
    return result;
}

}
