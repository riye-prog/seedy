#include "recovery_scheduling.h"
#include <cassert>

static int checks = 0;
static bool continuing() { return ++checks < 3; }

int main() {
    setRecoveryBudget(0,0);
    assert(recoveryWorkerCount() == 1);
    assert(recoveryDuty.load() == 1);
    setRecoveryBudget(200,200);
    assert(recoveryWorkerCount() == 4);
    assert(recoveryDuty.load() == 100);
    setRecoveryBudget(35,2);
    RecoveryTimeSlice slice;
    std::this_thread::sleep_for(std::chrono::milliseconds(3));
    auto started = std::chrono::steady_clock::now();
    assert(!slice.checkpoint(continuing));
    assert(checks == 3);
    assert(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(100));
}
