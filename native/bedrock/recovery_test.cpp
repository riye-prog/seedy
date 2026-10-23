#include "raw_constraints.h"
#include "solver_control.h"
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

static std::atomic<bool> searching{true};

static bool shouldContinue() { return searching.load(); }

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static void advance(uint64_t& low, uint64_t& high) {
    uint64_t difference = low ^ high;
    low = std::rotl(low, 49) ^ difference ^ difference << 21;
    high = std::rotl(difference, 28);
}

static uint64_t output(uint64_t low, uint64_t high) { return std::rotl(low + high, 17) + low; }

static uint64_t mix(uint64_t value) {
    value = (value ^ value >> 30) * 0xbf58476d1ce4e5b9ull;
    value = (value ^ value >> 27) * 0x94d049bb133111ebull;
    return value ^ value >> 31;
}

static void rawRngMatchesFloatCalls() {
    z3::context context;
    size_t accepted = 0;
    for (uint64_t seed = 0; seed < 64; seed++) {
        uint64_t low = mix(seed), high = mix(seed + 0x9e3779b97f4a7c15ull);
        uint64_t secondLow = low, secondHigh = high;
        advance(secondLow, secondHigh);
        uint64_t thirdLow = secondLow, thirdHigh = secondHigh;
        advance(thirdLow, thirdHigh);
        std::array<z3::expr, 6> states{
            context.bv_val(low, 64u), context.bv_val(high, 64u),
            context.bv_val(secondLow, 64u), context.bv_val(secondHigh, 64u),
            context.bv_val(thirdLow, 64u), context.bv_val(thirdHigh, 64u)
        };
        std::array<uint64_t, 6> numericStates{low, high, secondLow, secondHigh, thirdLow, thirdHigh};
        for (uint64_t sample = 0; sample < 256; sample++) {
            uint64_t position = mix(seed * 256 + sample);
            uint32_t cutoff = static_cast<uint32_t>((sample * 65537) & 0xffffff);
            bool below = (output(low ^ position, high) >> 40) < cutoff;
            require(positionalChanceConstraint(states[0], states[1], position, cutoff, true).simplify().is_true() == below, "positive positional constraint differs from RNG output");
            require(positionalChanceConstraint(states[0], states[1], position, cutoff, false).simplify().is_true() != below, "negative positional constraint differs from RNG output");
            uint64_t atLow = low ^ position, atHigh = high;
            float first = static_cast<float>(output(atLow, atHigh) >> 40) * 0x1.0p-24f;
            advance(atLow, atHigh);
            float second = static_cast<float>(output(atLow, atHigh) >> 40) * 0x1.0p-24f;
            advance(atLow, atHigh);
            float third = static_cast<float>(output(atLow, atHigh) >> 40) * 0x1.0p-24f;
            bool expected = first <= 0.7f && second < 0.3f && third < 0.02f;
            bool actual = rawOreConstraint(states, position).simplify().is_true();
            require(actual == expected, "raw ore constraints differ from float RNG calls");
            require(rawOreMatches(numericStates, position) == expected, "raw ore validation differs from float RNG calls");
            accepted += actual;
        }
    }
    require(accepted > 0, "no positive raw RNG cases were checked");
    for (size_t call = 0; call < rawOreLimits.size(); call++) {
        for (int offset : {-1, 0, 1}) {
            std::array<z3::expr, 6> states{
                context.bv_val(0, 64u), context.bv_val(0, 64u), context.bv_val(0, 64u),
                context.bv_val(0, 64u), context.bv_val(0, 64u), context.bv_val(0, 64u)
            };
            uint64_t target = (rawOreLimits[call] + offset) << 40;
            states[call * 2 + 1] = context.bv_val(std::rotr(target, 17), 64u);
            require(rawOreConstraint(states, 0).simplify().is_true() == (offset < 0), "raw ore threshold boundary differs");
        }
    }
}

static void cancellationInterruptsTheSolver() {
    z3::context context;
    z3::solver solver(context);
    constexpr size_t pigeons = 33, holes = 32;
    std::vector<std::vector<z3::expr>> assignments;
    for (size_t pigeon = 0; pigeon < pigeons; pigeon++) {
        std::vector<z3::expr> row;
        z3::expr_vector choices(context);
        for (size_t hole = 0; hole < holes; hole++) {
            auto name = "p" + std::to_string(pigeon) + "h" + std::to_string(hole);
            row.push_back(context.bool_const(name.c_str()));
            choices.push_back(row.back());
        }
        solver.add(z3::mk_or(choices));
        assignments.push_back(std::move(row));
    }
    for (size_t hole = 0; hole < holes; hole++) {
        for (size_t first = 0; first < pigeons; first++) {
            for (size_t second = first + 1; second < pigeons; second++) solver.add(!assignments[first][hole] || !assignments[second][hole]);
        }
    }
    searching = true;
    solver.set("timeout", 1u);
    require(cancellableSolverCheck(solver, shouldContinue) == z3::unknown, "solver time limit was not applied");
    require(solver.reason_unknown() == "timeout" || solver.reason_unknown() == "canceled", "unexpected solver time-limit result");
    z3::solver retry(context);
    retry.add(context.bool_val(true));
    require(cancellableSolverCheck(retry, shouldContinue) == z3::sat, "solver did not recover after a time limit");
    solver.set("timeout", 0u);
    std::jthread stop([] { std::this_thread::sleep_for(std::chrono::milliseconds(25)); searching = false; });
    auto started = std::chrono::steady_clock::now();
    auto result = cancellableSolverCheck(solver, shouldContinue);
    require(result == z3::unknown, "cancelled solver did not stop");
    require(std::chrono::steady_clock::now() - started < std::chrono::seconds(2), "solver cancellation took too long");
    require(cancellableSolverCheck(solver, shouldContinue) == z3::unknown, "stopped search restarted the solver");
}

int main() {
    SolverTimeBudget expired(std::chrono::milliseconds(0));
    require(expired.expired(), "expired solver budget was treated as unlimited");
    require(expired.timeoutMillis(60000) == 1, "expired solver budget disables Z3 timeout");
    SolverTimeBudget bounded(std::chrono::milliseconds(1000));
    require(!bounded.expired() && bounded.timeoutMillis(60000) <= 1000, "solver timeout exceeds remaining budget");
    require(bounded.timeoutMillis(25) <= 25, "solver timeout exceeds per-check limit");
    rawRngMatchesFloatCalls();
    cancellationInterruptsTheSolver();
    std::cout << "validated 16384 raw RNG cases, threshold boundaries, and solver cancellation\n";
}
