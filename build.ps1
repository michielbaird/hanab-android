# Builds the debug APK. Gradle fails here with "Unable to establish loopback connection"
# when TEMP is a long path, so point TEMP at a short directory first.
# Set JAVA_HOME to a JDK 17 (Studio's bundled JBR 25 is not supported by this Gradle version).
param([string]$Task = "assembleDebug")
if (-not $env:JAVA_HOME) { Write-Error "Set JAVA_HOME to a JDK 17"; exit 1 }
New-Item -ItemType Directory -Force C:\gtmp | Out-Null
$env:TEMP = "C:\gtmp"; $env:TMP = "C:\gtmp"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
Set-Location $PSScriptRoot
& "$PSScriptRoot\gradlew.bat" $Task --console=plain
