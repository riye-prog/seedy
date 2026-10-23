#pragma once
#include <array>
#include <bit>
#include <cstdint>
#include <z3++.h>

inline constexpr uint64_t floatProbabilityLimit(uint64_t numerator, uint64_t denominator) {
    return ((numerator << 24) + denominator - 1) / denominator;
}

inline constexpr std::array<uint64_t, 3> rawOreLimits{
    floatProbabilityLimit(7, 10), floatProbabilityLimit(3, 10), floatProbabilityLimit(1, 50)
};

inline z3::expr positionalChanceConstraint(const z3::expr& low, const z3::expr& high, uint64_t position, uint32_t cutoff, bool expected) {
    auto& context = low.ctx();
    auto first = low ^ context.bv_val(position, 64u);
    auto output = (first + high).rotate_left(17) + first;
    auto result = z3::ult(z3::lshr(output, 40), context.bv_val(static_cast<uint64_t>(cutoff), 64u));
    return expected ? result : !result;
}

inline bool rawOreMatches(const std::array<uint64_t, 6>& states, uint64_t position) {
    uint64_t low = position, high = 0;
    for (size_t call = 0; call < rawOreLimits.size(); call++) {
        uint64_t first = states[call * 2] ^ low;
        uint64_t second = states[call * 2 + 1] ^ high;
        if ((std::rotl(first + second, 17) + first) >> 40 >= rawOreLimits[call]) return false;
        uint64_t difference = low ^ high;
        low = std::rotl(low, 49) ^ difference ^ difference << 21;
        high = std::rotl(difference, 28);
    }
    return true;
}

inline z3::expr rawOreConstraint(const std::array<z3::expr, 6>& states, uint64_t position) {
    auto& context = states[0].ctx();
    auto result = context.bool_val(true);
    uint64_t low = position, high = 0;
    for (size_t call = 0; call < rawOreLimits.size(); call++) {
        auto first = states[call * 2] ^ context.bv_val(low, 64u);
        auto second = states[call * 2 + 1] ^ context.bv_val(high, 64u);
        auto output = (first + second).rotate_left(17) + first;
        result = result && z3::ult(z3::lshr(output, 40), context.bv_val(rawOreLimits[call], 64u));
        uint64_t difference = low ^ high;
        low = std::rotl(low, 49) ^ difference ^ difference << 21;
        high = std::rotl(difference, 28);
    }
    return result;
}
