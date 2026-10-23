#pragma once
#include <algorithm>
#include <bitset>
#include <cstdint>
#include <span>
#include <utility>
#include <vector>
#include <z3++.h>

inline std::pair<uint64_t, uint64_t> candidateWords(const std::bitset<128>& bits) {
    uint64_t low = 0, high = 0;
    for (unsigned int bit = 0; bit < 64; bit++) {
        low |= uint64_t(bits[bit]) << bit;
        high |= uint64_t(bits[bit + 64]) << bit;
    }
    return {low, high};
}

inline void constrainStateCandidates(z3::solver& solver, std::span<const std::bitset<128>> candidates, const z3::expr& low, const z3::expr& high) {
    auto& context = low.ctx();
    if (candidates.size() < 2) {
        solver.add(context.bool_val(false));
        return;
    }
    auto [maskLow, maskHigh] = candidateWords(candidates[0]);
    auto [firstLow, firstHigh] = candidateWords(candidates[1]);
    uint64_t differencesLow = 0, differencesHigh = 0;
    std::vector<std::pair<uint64_t, uint64_t>> values;
    for (size_t index = 1; index < candidates.size(); index++) {
        auto [candidateLow, candidateHigh] = candidateWords(candidates[index]);
        differencesLow |= candidateLow ^ firstLow;
        differencesHigh |= candidateHigh ^ firstHigh;
        values.emplace_back(candidateLow, candidateHigh);
    }
    uint64_t commonLow = maskLow & ~differencesLow;
    uint64_t commonHigh = maskHigh & ~differencesHigh;
    solver.add((low & context.bv_val(commonLow, 64u)) == context.bv_val(firstLow & commonLow, 64u));
    solver.add((high & context.bv_val(commonHigh, 64u)) == context.bv_val(firstHigh & commonHigh, 64u));
    maskLow &= ~commonLow;
    maskHigh &= ~commonHigh;
    for (auto& [candidateLow, candidateHigh] : values) {
        candidateLow &= maskLow;
        candidateHigh &= maskHigh;
    }
    std::sort(values.begin(), values.end());
    values.erase(std::unique(values.begin(), values.end()), values.end());
    auto maskedLow = low & context.bv_val(maskLow, 64u);
    auto maskedHigh = high & context.bv_val(maskHigh, 64u);
    z3::expr_vector choices(context);
    for (auto [candidateLow, candidateHigh] : values)
        choices.push_back(maskedLow == context.bv_val(candidateLow, 64u) && maskedHigh == context.bv_val(candidateHigh, 64u));
    solver.add(z3::mk_or(choices));
}
