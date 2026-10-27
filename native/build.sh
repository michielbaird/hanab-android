#!/usr/bin/env bash
# Builds the offline-play library (native/offline, Rust) for 64-bit ARM phones and copies it where Gradle
# packages it. Needs rustup (it fetches the pinned toolchain itself) and the Android NDK.
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
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$bin/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$bin/llvm-ar"

(cd "$here/offline" && cargo build --release --target aarch64-linux-android)

dest="$here/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$dest"
cp "$here/offline/target/aarch64-linux-android/release/libhanab_offline.so" "$dest/"
echo "Copied libhanab_offline.so to $dest"
