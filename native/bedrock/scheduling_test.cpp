#include "recovery_scheduling.h"
#include <cassert>

static int checks = 0;
static bool continuing() { return ++checks < 3; }

int main() {
#ifdef __APPLE__
    qos_class_t originalClass;
    int originalRelative;
    assert(pthread_get_qos_class_np(pthread_self(),&originalClass,&originalRelative) == 0);
    {
        RecoveryThreadPriority priority;
        qos_class_t currentClass;
        int currentRelative;
        assert(pthread_get_qos_class_np(pthread_self(),&currentClass,&currentRelative) == 0);
        assert(currentClass == originalClass);
        assert(currentRelative == QOS_MIN_RELATIVE_PRIORITY);
    }
    qos_class_t restoredClass;
    int restoredRelative;
    assert(pthread_get_qos_class_np(pthread_self(),&restoredClass,&restoredRelative) == 0);
    assert(restoredClass == originalClass && restoredRelative == originalRelative);
#elif defined(_WIN32)
    int originalPriority = GetThreadPriority(GetCurrentThread());
    {
        RecoveryThreadPriority priority;
        assert(GetThreadPriority(GetCurrentThread()) <= THREAD_PRIORITY_BELOW_NORMAL);
    }
    assert(GetThreadPriority(GetCurrentThread()) == originalPriority);
#endif
    setRecoveryBudget(0,0);
    assert(recoveryWorkerCount() == 1);
    assert(recoveryDuty.load() == 1);
    setRecoveryBudget(200,200);
    assert(recoveryWorkerCount() == 6);
    assert(recoveryDuty.load() == 100);
    setRecoveryBudget(35,2);
    RecoveryTimeSlice slice;
    std::this_thread::sleep_for(std::chrono::milliseconds(3));
    auto started = std::chrono::steady_clock::now();
    assert(!slice.checkpoint(continuing));
    assert(checks == 3);
    assert(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(100));
}
