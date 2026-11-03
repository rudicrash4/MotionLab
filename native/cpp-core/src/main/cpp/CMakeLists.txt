cmake_minimum_required(VERSION 3.22.1)
project(motionlab_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(motionlab_native SHARED
    native_core.cpp
    ffmpeg_probe.cpp)

find_library(log-lib log)
target_link_libraries(motionlab_native ${log-lib})

# Android 15+ devices may use 16 KB pages; keep ELF segments aligned for them.
target_link_options(motionlab_native PRIVATE -Wl,-z,max-page-size=16384)

# ---------------------------------------------------------------------------------------------
# Optional FFmpeg (LGPL, shared libraries). Built by scripts/build_ffmpeg_android.sh, which writes
#   third_party/ffmpeg/<abi>/include/...   (headers)
#   ../jniLibs/<abi>/libav*.so             (shared libraries, packaged into the APK by Gradle)
# If they are absent the module still builds; the FFmpeg backend then reports "unavailable".
# ---------------------------------------------------------------------------------------------
set(MOTIONLAB_FFMPEG_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg/${ANDROID_ABI}/include)
set(MOTIONLAB_FFMPEG_LIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

if(EXISTS ${MOTIONLAB_FFMPEG_INCLUDE}/libavformat/avformat.h
   AND EXISTS ${MOTIONLAB_FFMPEG_LIBS}/libavformat.so
   AND EXISTS ${MOTIONLAB_FFMPEG_LIBS}/libavcodec.so
   AND EXISTS ${MOTIONLAB_FFMPEG_LIBS}/libavutil.so)
    message(STATUS "MotionLab: building with FFmpeg for ${ANDROID_ABI}")
    foreach(lib avutil avcodec avformat)
        add_library(${lib} SHARED IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${MOTIONLAB_FFMPEG_LIBS}/lib${lib}.so)
    endforeach()
    target_include_directories(motionlab_native PRIVATE ${MOTIONLAB_FFMPEG_INCLUDE})
    target_compile_definitions(motionlab_native PRIVATE MOTIONLAB_HAS_FFMPEG=1)
    target_link_libraries(motionlab_native avformat avcodec avutil)
else()
    message(STATUS "MotionLab: FFmpeg not found for ${ANDROID_ABI}; building without it")
endif()
