#pragma once
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <thread>
#ifdef __APPLE__
#include <pthread/qos.h>
#elif defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

inline std::atomic<unsigned int> recoveryDuty{100};
inline std::atomic<unsigned int> recoveryWorkers{std::clamp(std::thread::hardware_concurrency() / 2,1u,4u)};

inline void setRecoveryBudget(unsigned int duty, unsigned int workers) {
    recoveryDuty.store(std::clamp(duty,1u,100u),std::memory_order_relaxed);
    recoveryWorkers.store(std::clamp(workers,1u,4u),std::memory_order_relaxed);
}

inline unsigned int recoveryWorkerCount() { return recoveryWorkers.load(std::memory_order_relaxed); }

class RecoveryThreadPriority {
#ifdef __APPLE__
    qos_class_t previous = qos_class_self();
    bool changed = false;
#elif defined(_WIN32)
    int previous = GetThreadPriority(GetCurrentThread());
    bool changed = false;
#endif
public:
    RecoveryThreadPriority() {
#ifdef __APPLE__
        if (previous == QOS_CLASS_UNSPECIFIED || previous > QOS_CLASS_UTILITY) changed = pthread_set_qos_class_self_np(QOS_CLASS_UTILITY,0) == 0;
#elif defined(_WIN32)
        if (previous > THREAD_PRIORITY_BELOW_NORMAL) changed = SetThreadPriority(GetCurrentThread(),THREAD_PRIORITY_BELOW_NORMAL) != 0;
#endif
    }
    ~RecoveryThreadPriority() {
#ifdef __APPLE__
        if (changed) pthread_set_qos_class_self_np(previous == QOS_CLASS_UNSPECIFIED ? QOS_CLASS_DEFAULT : previous,0);
#elif defined(_WIN32)
        if (changed) SetThreadPriority(GetCurrentThread(),previous);
#endif
    }
};

class RecoveryTimeSlice {
    std::chrono::steady_clock::time_point started = std::chrono::steady_clock::now();
public:
    bool checkpoint(bool (*continuing)()) {
        if (!continuing()) return false;
        auto now = std::chrono::steady_clock::now();
        auto work = std::chrono::duration_cast<std::chrono::nanoseconds>(now - started).count();
        if (work < 2'000'000) return true;
        unsigned int duty = recoveryDuty.load(std::memory_order_relaxed);
        auto rest = std::min<int64_t>(8'000'000,work * (100 - duty) / duty);
        auto until = now + std::chrono::nanoseconds(rest);
        while (now < until) {
            std::this_thread::sleep_for(std::min(std::chrono::nanoseconds(1'000'000),std::chrono::duration_cast<std::chrono::nanoseconds>(until - now)));
            if (!continuing()) return false;
            now = std::chrono::steady_clock::now();
        }
        started = now;
        return true;
    }
};
