#!/usr/bin/env bash
# Builds the offline-play library (native/offline, Rust) and copies it where Gradle packages it:
#   arm64-v8a (phones)  -> app/src/main/jniLibs, in every build
#   x86_64 (emulators)  -> app/src/debug/jniLibs, in debug builds only
# Needs rustup (it fetches the pinned toolchain itself) and the Android NDK.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

if [ -z "${ANDROID_HOME:-}" ]; then
  case "$(uname -s)" in
    Darwin) ANDROID_HOME="$HOME/Library/Android/sdk" ;;
    *) ANDROID_HOME="$HOME/Android/Sdk" ;;
  esac
fi
ndk="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort | tail -1 || true)"
[ -n "$ndk" ] || { echo "No Android NDK under $ANDROID_HOME/ndk (Android Studio > SDK Manager > SDK Tools > NDK)" >&2; exit 1; }
command -v cargo >/dev/null || { echo "cargo not found: install rustup from https://rustup.rs" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) host=darwin-x86_64 ;;
  *) host=linux-x86_64 ;;
esac
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
export AR_aarch64_linux_android="$bin/llvm-ar"
export AR_x86_64_linux_android="$bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$bin/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$bin/x86_64-linux-android26-clang"
export CC_x86_64_linux_android="$bin/x86_64-linux-android26-clang"

build() { # triple destination
  (cd "$here/offline" && cargo build --release --target "$1")
  mkdir -p "$here/../app/src/$2"
  cp "$here/offline/target/$1/release/libhanab_offline.so" "$here/../app/src/$2/"
  echo "Copied libhanab_offline.so ($1) to app/src/$2"
}
build aarch64-linux-android main/jniLibs/arm64-v8a
build x86_64-linux-android debug/jniLibs/x86_64
