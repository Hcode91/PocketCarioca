#!/usr/bin/env bash
# Builds libpocketcarioca.so for Android and assembles the Kotlin app around it.
#
#   ./frontends/pocketcarioca/build.sh          # just the .so
#   ./frontends/pocketcarioca/build.sh --apk    # the .so and the APKs (debug + release)
#
# Uses the same toolchain as frontends/android (see its README): rustup with the
# aarch64-linux-android target, cargo-ndk, an NDK, JDK 17 and Gradle.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
: "${ANDROID_SDK_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}"
# Same NDK as upstream's Android frontend: the C++ in the core (dynarmic) is tested with it.
ANDROID_NDK_DEFAULT=$(grep -oP 'ANDROID_NDK_DEFAULT=\K[0-9.]+' "$ROOT/frontends/android/build.sh")
: "${ANDROID_NDK_VERSION:=$ANDROID_NDK_DEFAULT}"
: "${ZEEBX_ANDROID_ABI:=arm64-v8a}"
: "${ZEEBX_ANDROID_PLATFORM:=android-35}"

export ANDROID_HOME="$ANDROID_SDK_HOME"
export ANDROID_NDK_HOME="$ANDROID_SDK_HOME/ndk/$ANDROID_NDK_VERSION"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export ANDROID_NDK="$ANDROID_NDK_HOME"
export ZEEBX_ANDROID_ABI ZEEBX_ANDROID_PLATFORM
# Reuses upstream's toolchain file for the core's CMake builds (dynarmic, unicorn).
export CMAKE_TOOLCHAIN_FILE="$ROOT/frontends/android/ndk-toolchain.cmake"
export CMAKE_MAKE_PROGRAM="$(command -v ninja)"

cd "$ROOT"
JNI="$HERE/app/app/src/main/jniLibs"
mkdir -p "$JNI"
cargo ndk -t "$ZEEBX_ANDROID_ABI" --platform "${ZEEBX_ANDROID_PLATFORM#android-}" \
  -o "$JNI" build --release --locked -p pocketcarioca-jni

# dynarmic's C++ needs the NDK's shared STL next to our library; Core.kt loads it first.
SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
cp "$SYSROOT/aarch64-linux-android/libc++_shared.so" "$JNI/$ZEEBX_ANDROID_ABI/"

# GPL: the license texts travel inside the APK.
LICENSES="$HERE/app/app/src/main/assets/licenses"
mkdir -p "$LICENSES"
cp "$ROOT/LICENSE" "$ROOT/LICENSE-GPL3" "$ROOT/THIRD-PARTY-NOTICES.txt" "$LICENSES/"

ls -lh "$JNI/$ZEEBX_ANDROID_ABI/"

if [ "${1:-}" = "--apk" ]; then
  GRADLE="${GRADLE:-$(command -v gradle || echo "$HOME/Android/gradle/bin/gradle")}"
  cd "$HERE/app"
  "$GRADLE" --no-daemon assembleDebug assembleRelease
  OUT="$HERE/app/app/build/outputs/apk"
  cp "$OUT/debug/app-debug.apk" "$OUT/PocketCarioca-debug.apk"
  cp "$OUT/release/app-release.apk" "$OUT/PocketCarioca-release.apk"
  ls -lh "$OUT"/PocketCarioca-*.apk
fi
