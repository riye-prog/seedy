#pragma once
#include <chrono>
#include <system_error>
#include <thread>
#include <z3++.h>

inline z3::check_result cancellableSolverCheck(z3::solver& solver, bool (*shouldContinue)()) {
    if (!shouldContinue()) return z3::unknown;
    auto& context = solver.ctx();
    std::jthread cancellation;
    try {
        cancellation = std::jthread([&](std::stop_token stop) {
            while (!stop.stop_requested()) {
                if (!shouldContinue()) context.interrupt();
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
        });
    } catch (const std::system_error&) { }
    auto result = solver.check();
    cancellation.request_stop();
    return result;
}
