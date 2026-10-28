# Builds the offline-play library (native\offline, Rust) and copies it where Gradle packages it:
#   arm64-v8a (phones)  -> app\src\main\jniLibs, in every build
#   x86_64 (emulators)  -> app\src\debug\jniLibs, in debug builds only
# Needs rustup (it fetches the pinned toolchain itself) and the Android NDK.
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndk = Get-ChildItem "$sdk\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
if (-not $ndk) { Write-Error "No Android NDK under $sdk\ndk (Android Studio > SDK Manager > SDK Tools > NDK)"; exit 1 }
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) { Write-Error "cargo not found: install rustup from https://rustup.rs"; exit 1 }

$bin = "$($ndk.FullName)\toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:AR_aarch64_linux_android = "$bin\llvm-ar.exe"
$env:AR_x86_64_linux_android = "$bin\llvm-ar.exe"
$targets = @(
    @{ Triple = "aarch64-linux-android"; Var = "AARCH64_LINUX_ANDROID"; Clang = "aarch64-linux-android26-clang.cmd"; Dest = "main\jniLibs\arm64-v8a" },
    @{ Triple = "x86_64-linux-android";  Var = "X86_64_LINUX_ANDROID";  Clang = "x86_64-linux-android26-clang.cmd";  Dest = "debug\jniLibs\x86_64" }
)

Push-Location "$PSScriptRoot\offline"
try {
    foreach ($t in $targets) {
        Set-Item "Env:CARGO_TARGET_$($t.Var)_LINKER" "$bin\$($t.Clang)"
        Set-Item "Env:CC_$($t.Triple.Replace('-', '_'))" "$bin\$($t.Clang)"
        cargo build --release --target $t.Triple
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $dest = "$PSScriptRoot\..\app\src\$($t.Dest)"
        New-Item -ItemType Directory -Force $dest | Out-Null
        Copy-Item "target\$($t.Triple)\release\libhanab_offline.so" $dest -Force
        Write-Host "Copied libhanab_offline.so ($($t.Triple)) to $dest"
    }
} finally { Pop-Location }
