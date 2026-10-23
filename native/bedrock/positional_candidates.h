#pragma once
#include <bit>
#include <bitset>
#include <cstdint>
#include <limits>
#include <tuple>
#include <utility>
#include <vector>

struct PositionalRange {
    uint64_t minimum;
    uint64_t extent;
    bool wraps() const { return minimum > std::numeric_limits<uint64_t>::max() - extent; }
    bool possible(uint32_t cutoff, bool expected) const {
        if (cutoff == 0) return !expected;
        if (cutoff >= (1u << 24)) return expected;
        if (wraps()) return true;
        uint64_t boundary = uint64_t(cutoff) << 40;
        return expected ? minimum < boundary : minimum + extent >= boundary;
    }
    bool uncertain(uint32_t cutoff) const {
        if (cutoff == 0 || cutoff >= (1u << 24)) return false;
        uint64_t boundary = uint64_t(cutoff) << 40;
        return wraps() || minimum < boundary && minimum + extent >= boundary;
    }
};

inline PositionalRange positionalRange(uint64_t low, uint64_t high, uint64_t position, unsigned int depth) {
    uint64_t unknown = (uint64_t(1) << (47 - depth)) - 1;
    uint64_t fixedLow = (low ^ position) & ~unknown;
    uint64_t fixedHigh = high & ((uint64_t(1) << 47) - 1) & ~unknown;
    uint64_t minimum = ((fixedLow + fixedHigh) << 17) + fixedLow;
    uint64_t extent = (unknown * 2 << 17) + unknown + ((uint64_t(1) << 17) - 1);
    return {minimum, extent};
}

struct PositionalCandidate {
    uint64_t low, high;
    std::vector<size_t> boundaries;
};

inline std::vector<std::bitset<128>> refinePositionalCandidates(const std::vector<std::tuple<uint64_t, uint32_t, bool>>& observations, const std::vector<std::pair<uint64_t, uint64_t>>& candidates, unsigned int depth, bool (*searching)()) {
    std::vector<PositionalCandidate> current;
    for (auto [low, high] : candidates) {
        if (!searching()) return {};
        PositionalCandidate candidate{low, high, {}};
        bool fits = true;
        for (size_t index = 0; index < observations.size(); index++) {
            auto [position, cutoff, expected] = observations[index];
            auto range = positionalRange(low, high, position, depth);
            if (!range.possible(cutoff, expected)) { fits = false; break; }
            if (range.uncertain(cutoff)) candidate.boundaries.push_back(index);
        }
        if (fits) current.push_back(std::move(candidate));
    }
    while (depth < 32 && !current.empty()) {
        if (!searching()) return {};
        std::vector<PositionalCandidate> next;
        uint64_t bit = uint64_t(1) << (46 - depth);
        bool limit = false;
        for (const auto& parent : current) {
            for (unsigned int choice = 0; choice < 4; choice++) {
                PositionalCandidate child{parent.low | (choice & 2 ? bit : 0), parent.high | (choice & 1 ? bit : 0), {}};
                bool fits = true;
                for (size_t index : parent.boundaries) {
                    auto [position, cutoff, expected] = observations[index];
                    auto range = positionalRange(child.low, child.high, position, depth + 1);
                    if (!range.possible(cutoff, expected)) { fits = false; break; }
                    if (range.uncertain(cutoff)) child.boundaries.push_back(index);
                }
                if (fits) next.push_back(std::move(child));
            }
            if (next.size() > 4096) { limit = true; break; }
        }
        if (limit) break;
        current = std::move(next);
        depth++;
    }
    std::vector<std::bitset<128>> result;
    auto add = [&](uint64_t low, uint64_t high) {
        std::bitset<128> value;
        for (unsigned int bit = 0; bit < 64; bit++) {
            value[bit] = low >> bit & 1;
            value[bit + 64] = high >> bit & 1;
        }
        result.push_back(value);
    };
    uint64_t mask = ((uint64_t(1) << depth) - 1) << (47 - depth);
    add(std::numeric_limits<uint64_t>::max() << (47 - depth), mask);
    for (const auto& candidate : current) add(candidate.low, candidate.high);
    return result;
}
