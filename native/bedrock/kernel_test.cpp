#include "java_helper.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string_view>
#include <vector>

extern "C" bool referenceKernel(uint64_t, uint64_t, uint64_t, bool);
extern "C" bool optimizedKernel(uint64_t, uint64_t, uint64_t, bool);
extern "C" uint64_t referenceWidth();
extern "C" uint64_t optimizedWidth();

static std::vector<std::array<uint64_t, 3>> candidates;

bool matchesWorldSeed(uint64_t) { return false; }
bool shouldContinueSearch() { return true; }

template<typename... Args>
void sendUpdate(const char *key, bool, Args... args) {
    if constexpr (sizeof...(Args) == 3) {
        if (std::string_view(key) == "commands.bedrockcrack.foundCandidate") candidates.push_back({static_cast<uint64_t>(args)...});
    }
}

template void sendUpdate<>(const char *, bool);
template void sendUpdate<uint64_t>(const char *, bool, uint64_t);
template void sendUpdate<uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t);
template void sendUpdate<uint64_t, uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t, uint64_t);

static uint64_t mix(uint64_t value) {
    value = (value ^ value >> 30) * 0xbf58476d1ce4e5b9ull;
    value = (value ^ value >> 27) * 0x94d049bb133111ebull;
    return value ^ value >> 31;
}

struct Round {
    uint64_t low;
    uint64_t high;
    uint64_t carry;
    uint64_t sum;
    uint64_t outputCarry;
    uint64_t output;

    Round(uint64_t first, uint64_t second) : low(first), high(second) {
        sum = low + high;
        carry = (low & high) | ((low | high) & ~sum);
        uint64_t rotated = std::rotl(sum, 17);
        output = rotated + low;
        outputCarry = (rotated & low) | ((rotated | low) & ~output);
    }
};

static uint64_t guessFor(const Round& first, const Round& second) {
    std::array<uint64_t, 40> bits = {
        first.low >> 51, first.low >> 19, first.high >> 47, first.high >> 36, first.high >> 33, first.high >> 1,
        second.low >> 50, second.low >> 40, second.low >> 39, second.low >> 33, second.low >> 32, second.low >> 15,
        second.high >> 60, second.high >> 58, second.high >> 45, second.high >> 43, second.high >> 41, second.high >> 18,
        second.high >> 16, second.high >> 12, second.high,
        first.carry >> 46, first.carry >> 29, first.carry >> 14, first.carry >> 12,
        first.sum >> 63, first.sum >> 50, first.sum >> 49, first.sum >> 47, first.sum >> 35, first.sum >> 31, first.sum >> 14, first.sum,
        second.carry >> 32, second.carry >> 14, second.sum >> 48, second.sum >> 18,
        second.outputCarry >> 49, second.outputCarry >> 31, second.outputCarry >> 14
    };
    uint64_t guess = 0;
    for (size_t bit = 0; bit < bits.size(); ++bit) guess |= (bits[bit] & 1) << bit;
    return guess;
}

static void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

int main() {
    require(referenceWidth() == optimizedWidth(), "kernel widths differ");
    uint64_t width = optimizedWidth();
    uint64_t random = 0x5eedc0decafef00dull;
    size_t checked = 0;
    for (uint64_t index = 0; index < 256; ++index) {
        random += 0x9e3779b97f4a7c15ull;
        uint64_t firstSeed = mix(random) ^ 7640891576956012809ull;
        Round first(mix(firstSeed), mix(firstSeed + 0x9e3779b97f4a7c15ull));
        uint64_t difference = first.low ^ first.high;
        Round second(std::rotl(first.low, 49) ^ difference ^ difference << 21, std::rotl(difference, 28));
        uint64_t guess = guessFor(first, second) & ~(width - 1);
        require(referenceKernel(first.output, second.output, guess, false), "reference lost the known state");
        require(optimizedKernel(first.output, second.output, guess, false), "optimized kernel lost the known state");
        candidates.clear();
        referenceKernel(first.output, second.output, guess, true);
        auto expected = candidates;
        std::sort(expected.begin(), expected.end());
        candidates.clear();
        optimizedKernel(first.output, second.output, guess, true);
        std::sort(candidates.begin(), candidates.end());
        require(candidates == expected, "candidate states differ");
        require(std::any_of(candidates.begin(), candidates.end(), [&](const auto& candidate) { return candidate[0] == first.low && candidate[1] == first.high; }), "known state was not emitted");
        for (uint64_t sample = 0; sample < 64; ++sample) {
            random += 0x9e3779b97f4a7c15ull;
            uint64_t window = mix(random) & ((uint64_t{1} << 40) - width);
            require(referenceKernel(first.output, second.output, window, false) == optimizedKernel(first.output, second.output, window, false), "rejection results differ");
            checked++;
        }
    }
    std::cout << "validated 256 known states and " << checked << " search windows at width " << width << '\n';
}
