#pragma once
#include <cstdint>

template<typename... Args>
void sendUpdate(const char *update, bool onlyLog, Args... args);

bool matchesWorldSeed(uint64_t seed);
bool shouldContinueSearch();
