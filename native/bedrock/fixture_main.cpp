#include "blocks.h"
#include "java_helper.h"
#include <atomic>
#include <bit>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string_view>
#include <vector>

static std::atomic<bool> foundSeed{false};
static uint64_t expectedSeed;
static bool stopAfterState;

bool matchesWorldSeed(uint64_t seed) {
    return expectedSeed == 0 || seed == expectedSeed;
}

bool shouldContinueSearch() {
    static const auto started = std::chrono::steady_clock::now();
    const char *limit = std::getenv("SEEDY_CANCEL_AFTER_SECONDS");
    return !foundSeed.load() && (limit == nullptr || std::chrono::steady_clock::now() - started < std::chrono::seconds(std::atoi(limit)));
}

template<typename... Args>
void sendUpdate(const char *key, bool, Args... args) {
    if (std::string_view(key) == "commands.bedrockcrack.foundWorldSeed") foundSeed = true;
    std::cout << key;
    ((std::cout << ' ' << args), ...);
    std::cout << std::endl;
    if (std::string_view(key) == "commands.bedrockcrack.crackedRandomStateRandom" && stopAfterState) std::exit(0);
}

template void sendUpdate<>(const char *, bool);
template void sendUpdate<uint64_t>(const char *, bool, uint64_t);
template void sendUpdate<uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t);
template void sendUpdate<uint64_t, uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t, uint64_t);

int main(int argc, char **argv) {
    if (argc < 2 || argc > 5) return 2;
    if (argc >= 3) expectedSeed = std::stoull(argv[2]);
    int mode = argc >= 4 ? std::stoi(argv[3]) : 31;
    stopAfterState = argc == 5 && std::string_view(argv[4]) == "state";
    std::ifstream input(argv[1], std::ios::binary);
    if (!input) return 2;
    std::vector<block_entry> entries;
    uint32_t values[4];
    while (input.read(reinterpret_cast<char *>(values), sizeof(values))) {
        entries.push_back({
            static_cast<int32_t>(std::byteswap(values[0])),
            static_cast<int32_t>(std::byteswap(values[1])),
            static_cast<int32_t>(std::byteswap(values[2])),
            static_cast<int32_t>(std::byteswap(values[3]))
        });
    }
    std::cout << "entries " << entries.size() << std::endl;
    auto started = std::chrono::steady_clock::now();
    bedrockseed$crack(static_cast<int>(entries.size()), entries.data(), mode);
    auto seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    std::cout << "seconds " << seconds << " found " << foundSeed << std::endl;
    return foundSeed ? 0 : 1;
}
