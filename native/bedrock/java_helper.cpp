#include "java_helper.h"
#include "ore_validation.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include "cancellation.h"
#include <cstdint>
#include <jni.h>
#ifdef _OPENMP
#include <omp.h>
#endif
#include <thread>

static JavaVM *javaVm = nullptr;
static jclass recoveryClass = nullptr;
static jmethodID stateMethod = nullptr, seedMethod = nullptr, continuationMethod = nullptr;
static std::atomic<uint64_t> attachedThreads{0};

extern "C" JNIEXPORT void seedy_recovery_budget(int duty, int workers) { setRecoveryBudget(duty,workers); }
extern "C" JNIEXPORT uint64_t seedy_recovery_attachments() { return attachedThreads.load(std::memory_order_relaxed); }

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    javaVm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_24) != JNI_OK) return JNI_ERR;
    jclass localClass = env->FindClass("dev/seedy/BedrockRecovery");
    if (localClass == nullptr) return JNI_ERR;
    recoveryClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    stateMethod = env->GetStaticMethodID(recoveryClass,"matchesRandomState","(JJ)Z");
    seedMethod = env->GetStaticMethodID(recoveryClass,"matchesWorldSeed","(J)Z");
    continuationMethod = env->GetStaticMethodID(recoveryClass,"shouldContinueSearch","()Z");
    if (!stateMethod || !seedMethod || !continuationMethod) return JNI_ERR;
#ifdef _OPENMP
    omp_set_num_threads(recoveryWorkerCount());
#endif
    return JNI_VERSION_24;
}

struct RecoveryJniThread {
    JNIEnv *env = nullptr;
    bool attached = false;
    RecoveryJniThread() {
        jint result = javaVm->GetEnv(reinterpret_cast<void **>(&env),JNI_VERSION_24);
        if (result == JNI_EDETACHED) {
            attached = javaVm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env),nullptr) == JNI_OK;
            if (attached) attachedThreads.fetch_add(1,std::memory_order_relaxed);
            else env = nullptr;
        } else if (result != JNI_OK) env = nullptr;
    }
    ~RecoveryJniThread() { if (attached) javaVm->DetachCurrentThread(); }
};

static JNIEnv *environment() {
    thread_local RecoveryJniThread thread;
    return thread.env;
}

bool matchesRandomState(uint64_t low, uint64_t high) {
    JNIEnv *env = environment();
    if (env == nullptr) return false;
    bool result = env->CallStaticBooleanMethod(recoveryClass,stateMethod,static_cast<jlong>(low),static_cast<jlong>(high));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = false;
    }
    return result;
}

bool matchesWorldSeed(uint64_t seed) {
    JNIEnv *env = environment();
    if (env == nullptr) return false;
    bool result = env->CallStaticBooleanMethod(recoveryClass,seedMethod,static_cast<jlong>(seed));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = false;
    }
    return result;
}

static bool readContinuation() {
    JNIEnv *env = environment();
    if (env == nullptr) return false;
    bool result = env->CallStaticBooleanMethod(recoveryClass,continuationMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = false;
    }
    return result;
}

static std::atomic<bool> continuation{false};
static std::atomic<bool> monitoring{false};
static std::thread cancellationMonitor;
static std::atomic<bool> monitorStopped{true};

void startRecoveryCancellation() {
    continuation.store(readContinuation(), std::memory_order_relaxed);
    try {
        monitoring.store(true, std::memory_order_release);
        monitorStopped.store(false, std::memory_order_relaxed);
        cancellationMonitor = std::thread([] {
            RecoveryThreadPriority priority;
            while (!monitorStopped.load(std::memory_order_relaxed) && continuation.load(std::memory_order_relaxed)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                if (!readContinuation()) continuation.store(false, std::memory_order_relaxed);
            }
        });
    } catch (const std::system_error&) {
        monitoring.store(false, std::memory_order_release);
    }
}

void stopRecoveryCancellation() {
    if (cancellationMonitor.joinable()) {
        monitorStopped.store(true, std::memory_order_relaxed);
        cancellationMonitor.join();
    }
    monitoring.store(false, std::memory_order_release);
}

bool shouldContinueSearch() {
    return monitoring.load(std::memory_order_acquire) ? continuation.load(std::memory_order_relaxed) : readContinuation();
}

static void addArgument(JNIEnv *env, jobjectArray values, int index, uint64_t value, jclass longClass, jmethodID valueOf) {
    jobject boxed = env->CallStaticObjectMethod(longClass, valueOf, static_cast<jlong>(value));
    env->SetObjectArrayElement(values, index, boxed);
    env->DeleteLocalRef(boxed);
}

template<typename... Args>
void sendUpdate(const char *key, bool logOnly, Args... args) {
    JNIEnv *env = environment();
    if (env == nullptr) return;
    jclass objectClass = env->FindClass("java/lang/Object");
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID valueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    jmethodID update = env->GetStaticMethodID(recoveryClass, "sendUpdate", "(Ljava/lang/String;Z[Ljava/lang/Object;)V");
    jobjectArray values = env->NewObjectArray(sizeof...(Args), objectClass, nullptr);
    int index = 0;
    (addArgument(env, values, index++, args, longClass, valueOf), ...);
    jstring name = env->NewStringUTF(key);
    env->CallStaticVoidMethod(recoveryClass, update, name, static_cast<jboolean>(logOnly), values);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(values);
    env->DeleteLocalRef(longClass);
    env->DeleteLocalRef(objectClass);
}

template void sendUpdate<>(const char *, bool);
template void sendUpdate<uint64_t>(const char *, bool, uint64_t);
template void sendUpdate<uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t);
template void sendUpdate<uint64_t, uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t, uint64_t);
