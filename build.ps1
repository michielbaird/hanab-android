# Builds the debug APK. Gradle fails here with "Unable to establish loopback connection"
# when TEMP is a long path, so point TEMP at a short directory first.
# JAVA_HOME defaults to a JDK 21 in ~\.jdks; JDK 17 also works (Studio's bundled JBR 25 does not).
param([string]$Task = "assembleDebug")
if (-not $env:JAVA_HOME) {
    # Default to the JetBrains Runtime 21 that IntelliJ / Android Studio downloads.
    $jbr = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jbr-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jbr) { $env:JAVA_HOME = $jbr.FullName } else { Write-Error "Set JAVA_HOME to a JDK 21 (17 also works)"; exit 1 }
}
New-Item -ItemType Directory -Force C:\gtmp | Out-Null
$env:TEMP = "C:\gtmp"; $env:TMP = "C:\gtmp"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
Set-Location $PSScriptRoot
& "$PSScriptRoot\gradlew.bat" $Task --console=plain
