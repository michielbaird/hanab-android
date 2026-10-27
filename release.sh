#!/usr/bin/env bash
# Builds a release APK (or an AAB with --bundle) into dist/.
# Needs JAVA_HOME (JDK 21; 17 also works) and, for an installable APK, a keystore.properties (see README).
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then echo "Set JAVA_HOME to a JDK 21 (17 also works)" >&2; exit 1; fi
if [ -z "${ANDROID_HOME:-}" ]; then
  case "$(uname -s)" in
    Darwin) export ANDROID_HOME="$HOME/Library/Android/sdk" ;;
    *) export ANDROID_HOME="$HOME/Android/Sdk" ;;
  esac
fi
# The offline-play library (Rust). Without it the app still works, just without "Play offline".
if command -v cargo >/dev/null; then
  native/build.sh
elif [ ! -f app/src/main/jniLibs/arm64-v8a/libhanab_offline.so ]; then
  echo "WARNING: no cargo and no prebuilt library; this build will not have offline play." >&2
fi
[ -f keystore.properties ] || echo "WARNING: no keystore.properties; the output will be UNSIGNED and cannot be installed." >&2

if [ "${1:-}" = "--bundle" ]; then task=bundleRelease; else task=assembleRelease; fi
./gradlew "$task" --console=plain

ver=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)
mkdir -p dist
if [ "$task" = bundleRelease ]; then
  src=app/build/outputs/bundle/release/app-release.aab; dst="dist/hanab-$ver.aab"
elif [ -f app/build/outputs/apk/release/app-release.apk ]; then
  src=app/build/outputs/apk/release/app-release.apk; dst="dist/hanab-$ver.apk"
else
  src=app/build/outputs/apk/release/app-release-unsigned.apk; dst="dist/hanab-$ver-unsigned.apk"
fi
cp "$src" "$dst"
if command -v sha256sum >/dev/null; then sum=$(sha256sum "$dst" | cut -d' ' -f1); else sum=$(shasum -a 256 "$dst" | cut -d' ' -f1); fi
echo "$sum  $(basename "$dst")" > "$dst.sha256"
echo "Built $dst"
echo "SHA-256 $sum"
