#pragma once

#include <jni.h>

#include <string>

namespace motionlab {

constexpr const char* kLogTag = "MotionLabNative";
constexpr const char* kNativeExceptionClass = "id/motionlab/nativecore/NativeException";

// Throws id.motionlab.nativecore.NativeException(message). Safe to call when an exception is already pending
// (the earlier one is kept).
void ThrowNative(JNIEnv* env, const std::string& message);

}  // namespace motionlab
