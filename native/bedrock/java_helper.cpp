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

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    javaVm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_24) != JNI_OK) return JNI_ERR;
    jclass localClass = env->FindClass("dev/seedy/BedrockRecovery");
    if (localClass == nullptr) return JNI_ERR;
    recoveryClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
#ifdef _OPENMP
    int threads = static_cast<int>(std::thread::hardware_concurrency());
    omp_set_num_threads(std::clamp(threads - 2, 1, 6));
#endif
    return JNI_VERSION_24;
}

static JNIEnv *environment(bool &attached) {
    JNIEnv *env = nullptr;
    jint result = javaVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_24);
    if (result == JNI_EDETACHED) {
        if (javaVm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return nullptr;
        attached = true;
    } else if (result != JNI_OK) {
        return nullptr;
    }
    return env;
}

bool matchesRandomState(uint64_t low, uint64_t high) {
    bool attached = false;
    JNIEnv *env = environment(attached);
    if (env == nullptr) return false;
    jmethodID match = env->GetStaticMethodID(recoveryClass, "matchesRandomState", "(JJ)Z");
    bool result = match != nullptr && env->CallStaticBooleanMethod(recoveryClass, match, static_cast<jlong>(low), static_cast<jlong>(high));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = false;
    }
    if (attached) javaVm->DetachCurrentThread();
    return result;
}

bool matchesWorldSeed(uint64_t seed) {
    bool attached = false;
    JNIEnv *env = environment(attached);
    if (env == nullptr) return false;
    jmethodID match = env->GetStaticMethodID(recoveryClass, "matchesWorldSeed", "(J)Z");
    bool result = match != nullptr && env->CallStaticBooleanMethod(recoveryClass, match, static_cast<jlong>(seed));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = false;
    }
    if (attached) javaVm->DetachCurrentThread();
    return result;
}

static bool readContinuation() {
    bool attached = false;
    JNIEnv *env = environment(attached);
    if (env == nullptr) return false;
    jmethodID method = env->GetStaticMethodID(recoveryClass, "shouldContinueSearch", "()Z");
    bool result = method != nullptr && env->CallStaticBooleanMethod(recoveryClass, method);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = false;
    }
    if (attached) javaVm->DetachCurrentThread();
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
    bool attached = false;
    JNIEnv *env = environment(attached);
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
    if (attached) javaVm->DetachCurrentThread();
}

template void sendUpdate<>(const char *, bool);
template void sendUpdate<uint64_t>(const char *, bool, uint64_t);
template void sendUpdate<uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t);
template void sendUpdate<uint64_t, uint64_t, uint64_t>(const char *, bool, uint64_t, uint64_t, uint64_t);
