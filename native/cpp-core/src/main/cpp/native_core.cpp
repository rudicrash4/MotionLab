#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

#include "jni_util.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, motionlab::kLogTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, motionlab::kLogTag, __VA_ARGS__)

namespace motionlab {

void ThrowNative(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(kNativeExceptionClass);
    if (cls == nullptr) return;  // FindClass already left a NoClassDefFoundError pending
    env->ThrowNew(cls, message.c_str());
    env->DeleteLocalRef(cls);
}

namespace {

constexpr int kApiVersion = 1;
constexpr int64_t kMaxAllocation = 1LL << 30;  // 1 GiB: reject absurd sizes instead of risking an OOM kill

// Tracks every live native allocation so leaks and double frees are detectable from Kotlin tests.
struct Registry {
    std::mutex mutex;
    std::unordered_map<void*, int64_t> live;  // pointer -> size
    int64_t liveBytes = 0;
};

Registry& GetRegistry() {
    static Registry registry;
    return registry;
}

std::atomic<bool> g_initialized{false};

}  // namespace
}  // namespace motionlab

extern "C" {

JNIEXPORT jint JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeInit(JNIEnv*, jclass) {
    bool expected = false;
    if (motionlab::g_initialized.compare_exchange_strong(expected, true)) {
        LOGI("MotionLab native core initialized (api %d)", motionlab::kApiVersion);
    }
    return motionlab::kApiVersion;
}

JNIEXPORT jstring JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("motionlab-native 1");
}

JNIEXPORT jlong JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeAllocate(JNIEnv* env, jclass, jlong size) {
    if (size <= 0 || size > motionlab::kMaxAllocation) {
        motionlab::ThrowNative(env, "Invalid allocation size: " + std::to_string(size));
        return 0;
    }
    void* ptr = std::calloc(1, static_cast<size_t>(size));
    if (ptr == nullptr) {
        motionlab::ThrowNative(env, "Out of native memory allocating " + std::to_string(size) + " bytes");
        return 0;
    }
    auto& reg = motionlab::GetRegistry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    reg.live[ptr] = size;
    reg.liveBytes += size;
    return reinterpret_cast<jlong>(ptr);
}

JNIEXPORT void JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeFree(JNIEnv* env, jclass, jlong handle) {
    void* ptr = reinterpret_cast<void*>(handle);
    {
        auto& reg = motionlab::GetRegistry();
        std::lock_guard<std::mutex> lock(reg.mutex);
        auto it = reg.live.find(ptr);
        if (it == reg.live.end()) {
            motionlab::ThrowNative(env, "nativeFree: unknown or already freed handle");
            return;
        }
        reg.liveBytes -= it->second;
        reg.live.erase(it);
    }
    std::free(ptr);
}

JNIEXPORT void JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeFill(JNIEnv* env, jclass, jlong handle, jint value) {
    auto& reg = motionlab::GetRegistry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    auto it = reg.live.find(reinterpret_cast<void*>(handle));
    if (it == reg.live.end()) {
        motionlab::ThrowNative(env, "nativeFill: unknown handle");
        return;
    }
    std::memset(it->first, static_cast<int>(value & 0xFF), static_cast<size_t>(it->second));
}

JNIEXPORT jlong JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeSum(JNIEnv* env, jclass, jlong handle) {
    auto& reg = motionlab::GetRegistry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    auto it = reg.live.find(reinterpret_cast<void*>(handle));
    if (it == reg.live.end()) {
        motionlab::ThrowNative(env, "nativeSum: unknown handle");
        return 0;
    }
    const auto* bytes = static_cast<const uint8_t*>(it->first);
    int64_t sum = 0;
    for (int64_t i = 0; i < it->second; ++i) sum += bytes[i];
    return static_cast<jlong>(sum);
}

JNIEXPORT jobject JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeAsByteBuffer(JNIEnv* env, jclass, jlong handle) {
    auto& reg = motionlab::GetRegistry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    auto it = reg.live.find(reinterpret_cast<void*>(handle));
    if (it == reg.live.end()) {
        motionlab::ThrowNative(env, "nativeAsByteBuffer: unknown handle");
        return nullptr;
    }
    return env->NewDirectByteBuffer(it->first, it->second);
}

JNIEXPORT jint JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeLiveAllocationCount(JNIEnv*, jclass) {
    auto& reg = motionlab::GetRegistry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    return static_cast<jint>(reg.live.size());
}

JNIEXPORT jlong JNICALL
Java_id_motionlab_nativecore_NativeCore_nativeLiveBytes(JNIEnv*, jclass) {
    auto& reg = motionlab::GetRegistry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    return static_cast<jlong>(reg.liveBytes);
}

}  // extern "C"
