#pragma once
#include <atomic>
#include <chrono>
#include <system_error>
#include <thread>
#include <z3++.h>

inline z3::solver makeRecoverySolver(z3::context& context) {
    return z3::tactic(context, "qfbv").mk_solver();
}

class SolverTimeBudget {
    std::chrono::steady_clock::time_point deadline;
public:
    explicit SolverTimeBudget(std::chrono::milliseconds duration) : deadline(std::chrono::steady_clock::now() + duration) { }
    bool expired() const { return std::chrono::steady_clock::now() >= deadline; }
    unsigned int timeoutMillis(unsigned int maximum) const {
        if (maximum == 0) maximum = 1;
        auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        if (remaining <= 0) return 1u;
        return remaining < maximum ? static_cast<unsigned int>(remaining) : maximum;
    }
};

class SolverCancellation {
    std::atomic<bool> finished{false};
    std::thread worker;
public:
    SolverCancellation(z3::context& context, bool (*shouldContinue)()) {
        try {
            worker = std::thread([this, &context, shouldContinue] {
                while (!finished.load(std::memory_order_relaxed)) {
                    if (!shouldContinue()) context.interrupt();
                    std::this_thread::sleep_for(std::chrono::milliseconds(20));
                }
            });
        } catch (const std::system_error&) { }
    }
    ~SolverCancellation() {
        finished.store(true, std::memory_order_relaxed);
        if (worker.joinable()) worker.join();
    }
};

inline z3::check_result cancellableSolverCheck(z3::solver& solver, bool (*shouldContinue)()) {
    if (!shouldContinue()) return z3::unknown;
    SolverCancellation cancellation(solver.ctx(), shouldContinue);
    return solver.check();
}
