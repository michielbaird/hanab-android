# Builds the offline-play library (native\offline, Rust) for 64-bit ARM phones and copies it where Gradle
# packages it. Needs rustup (it fetches the pinned toolchain itself) and the Android NDK.
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndk = Get-ChildItem "$sdk\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
if (-not $ndk) { Write-Error "No Android NDK under $sdk\ndk (Android Studio > SDK Manager > SDK Tools > NDK)"; exit 1 }
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) { Write-Error "cargo not found: install rustup from https://rustup.rs"; exit 1 }

$bin = "$($ndk.FullName)\toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$bin\aarch64-linux-android26-clang.cmd"
$env:CC_aarch64_linux_android = "$bin\aarch64-linux-android26-clang.cmd"
$env:AR_aarch64_linux_android = "$bin\llvm-ar.exe"

Push-Location "$PSScriptRoot\offline"
try {
    cargo build --release --target aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }

$dest = "$PSScriptRoot\..\app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item "$PSScriptRoot\offline\target\aarch64-linux-android\release\libhanab_offline.so" $dest -Force
Write-Host "Copied libhanab_offline.so to $dest"
