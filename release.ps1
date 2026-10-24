# Builds a release APK (or an AAB with -Bundle) into dist\.
# Uses JAVA_HOME, defaulting to a JDK 21 in ~\.jdks (JDK 17 also works) and, for an installable APK, a keystore.properties (see README).
param([switch]$Bundle)
if (-not $env:JAVA_HOME) {
    # Default to the JetBrains Runtime 21 that IntelliJ / Android Studio downloads.
    $jbr = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jbr-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jbr) { $env:JAVA_HOME = $jbr.FullName } else { Write-Error "Set JAVA_HOME to a JDK 21 (17 also works)"; exit 1 }
}
New-Item -ItemType Directory -Force C:\gtmp | Out-Null
$env:TEMP = "C:\gtmp"; $env:TMP = "C:\gtmp"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
Set-Location $PSScriptRoot
if (-not (Test-Path keystore.properties)) {
    Write-Warning "No keystore.properties: the output will be UNSIGNED and cannot be installed."
}
$task = if ($Bundle) { "bundleRelease" } else { "assembleRelease" }
& "$PSScriptRoot\gradlew.bat" $task --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$ver = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force dist | Out-Null
if ($Bundle) {
    $src = "app\build\outputs\bundle\release\app-release.aab"; $dst = "dist\hanab-$ver.aab"
} else {
    $signed = "app\build\outputs\apk\release\app-release.apk"
    $src = if (Test-Path $signed) { $signed } else { "app\build\outputs\apk\release\app-release-unsigned.apk" }
    $dst = if ($src -like "*unsigned*") { "dist\hanab-$ver-unsigned.apk" } else { "dist\hanab-$ver.apk" }
}
Copy-Item $src $dst -Force
$hash = (Get-FileHash $dst -Algorithm SHA256).Hash.ToLower()
"$hash  $(Split-Path $dst -Leaf)" | Set-Content "$dst.sha256"
Write-Host "Built $dst"
Write-Host "SHA-256 $hash"
