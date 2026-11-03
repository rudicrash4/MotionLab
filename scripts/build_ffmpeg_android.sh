#!/usr/bin/env bash
# Builds a minimal LGPL FFmpeg (shared libraries, arm64-v8a) for MotionLab's optional FFmpeg metadata backend.
#
# STATUS: written from FFmpeg's documented configure options and Media3's decoder_ffmpeg build script, but NOT yet
# executed (the authoring sandbox had no Android NDK). Run it in CI (workflow "build-apk-ffmpeg") or locally and
# fix any configure error it reports.
#
# License: no --enable-gpl and no --enable-nonfree, so the result is LGPL v2.1+. Because the libraries are SHARED
# (.so files that the user can replace), LGPL dynamic-linking requirements are met; you must still ship FFmpeg's
# license text and offer its source (see OPEN_SOURCE_LICENSES.md).
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk scripts/build_ffmpeg_android.sh
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK directory}"
FFMPEG_TAG="${FFMPEG_TAG:-n8.1.3}"   # tag verified to exist on github.com/FFmpeg/FFmpeg
API="${ANDROID_API:-23}"
ABI="arm64-v8a"
ARCH="aarch64"
HOST_TAG="linux-x86_64"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native/cpp-core/src/main"
WORK="$ROOT/build/ffmpeg-work"
SRC="$WORK/ffmpeg"
INSTALL="$WORK/install-$ABI"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"

mkdir -p "$WORK"
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg "$SRC"
fi

cd "$SRC"
make distclean >/dev/null 2>&1 || true

./configure \
  --prefix="$INSTALL" \
  --target-os=android --arch="$ARCH" --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang" \
  --cxx="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++" \
  --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --enable-shared --disable-static \
  --disable-programs --disable-doc --disable-debug \
  --disable-avdevice --disable-swscale --disable-postproc --disable-avfilter --disable-network \
  --disable-everything \
  --enable-protocol=file \
  --enable-demuxer=mov,matroska,avi,mp3,ogg,flac,wav,aac,mpegts,flv \
  --enable-parser=h264,hevc,aac,mpegaudio,vp9,av1,opus,vorbis,flac \
  --enable-decoder=h264,hevc,vp9,aac,mp3,opus,vorbis,flac,pcm_s16le,pcm_s24le \
  --extra-ldflags="-Wl,-z,max-page-size=16384"

# Android cannot load versioned sonames (libavcodec.so.62): produce plain libavcodec.so etc.
sed -i \
  -e 's/^SLIBNAME_WITH_MAJOR=.*/SLIBNAME_WITH_MAJOR=$(SLIBNAME)/' \
  -e 's/^LIB_INSTALL_EXTRA_CMD=.*/LIB_INSTALL_EXTRA_CMD=/' \
  -e 's/^SLIB_INSTALL_NAME=.*/SLIB_INSTALL_NAME=$(SLIBNAME)/' \
  -e 's/^SLIB_INSTALL_LINKS=.*/SLIB_INSTALL_LINKS=/' \
  ffbuild/config.mak

make -j"$(nproc)"
make install

# Headers for CMake, shared libraries for Gradle's jniLibs packaging.
mkdir -p "$NATIVE/cpp/third_party/ffmpeg/$ABI" "$NATIVE/jniLibs/$ABI"
rm -rf "$NATIVE/cpp/third_party/ffmpeg/$ABI/include"
cp -r "$INSTALL/include" "$NATIVE/cpp/third_party/ffmpeg/$ABI/include"
cp "$INSTALL"/lib/lib*.so "$NATIVE/jniLibs/$ABI/"

echo "FFmpeg $FFMPEG_TAG built for $ABI:"
ls -la "$NATIVE/jniLibs/$ABI"
