# Keeps the app's types for hanab.live's wire protocol in step with the website's own.
#
# The website's server owns every message in its Rust crate `hanabi_protocol`, published on crates.io (the source is
# server/protocol in Hanabi-Live/hanabi-live). native/protocol depends on that crate and writes Kotlin types for it
# (app/src/main/java/live/hanab/client/protocol/Protocol.kt). The website's shared test cases, which the crate carries, are
# kept in app/src/test/resources/protocol, and a unit test runs them against those types.
#
#   .\scripts\protocol.ps1 gen          write Protocol.kt from the pinned version
#   .\scripts\protocol.ps1 check        fail if Protocol.kt is not what gen writes (it was edited, or the pin moved without it)
#   .\scripts\protocol.ps1 outdated     fail if the website that is live has a protocol the pin does not, and say what changed
#   .\scripts\protocol.ps1 bump [what]  move the pin, refresh the test cases, run gen. `what` is a crate version (0.1.2), or
#                                       `main` / a commit of the website's repository (a git dependency, for a protocol the
#                                       crate has not published yet); by default the newest published version
#
# Needs cargo (rustup fetches the pinned toolchain). `outdated` and `bump` also use the crates.io and GitHub APIs
# (GITHUB_TOKEN if set).
param(
    [Parameter(Mandatory = $true, Position = 0)][ValidateSet('gen', 'check', 'outdated', 'bump')][string]$Command,
    [Parameter(Position = 1)][string]$Target
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tool = Join-Path $root 'native/protocol'
$manifest = Join-Path $tool 'Cargo.toml'
$lock = Join-Path $tool 'Cargo.lock'
$generated = Join-Path $root 'app/src/main/java/live/hanab/client/protocol/Protocol.kt'
$fixtures = Join-Path $root 'app/src/test/resources/protocol/compatibility.json'
$repo = 'Hanabi-Live/hanabi-live'
# The protocol lives in server/protocol; the website's generated TypeScript for it is checked in beside the clients, and
# the frames around the payloads (frames.ts, written by hand) are what net/WireFrames.kt follows.
$paths = 'server/protocol', 'packages/data/src/generated', 'packages/game/src/generated', 'packages/data/src/functions/frames.ts'

# The version of hanabi_protocol that Cargo.lock pins.
function Get-PinVersion {
    $text = Get-Content $lock -Raw
    if ($text -notmatch '(?s)name = "hanabi_protocol"\s+version = "([^"]+)"') { throw "No hanabi_protocol in $lock" }
    $Matches[1]
}

# The commit of the website's repository the pin was made from: the generated file says (it is written from the crate's own
# record of the commit it was published from, or from the git dependency's commit).
function Get-PinCommit {
    $line = Get-Content $generated | Where-Object { $_ -match '^const val PROTOCOL_REV' } | Select-Object -First 1
    if ($line -notmatch '"([0-9a-f]{40})"') { throw "No PROTOCOL_REV in $generated; run scripts/protocol.ps1 gen" }
    $Matches[1]
}

function Invoke-Generator([string]$Out) {
    Push-Location $tool
    try {
        cargo run --quiet -- $Out
        if ($LASTEXITCODE -ne 0) { throw "The generator failed" }
    } finally { Pop-Location }
}

function Invoke-Web([string]$Url) {
    $headers = @{ 'User-Agent' = 'hanab-android (github.com/michielbaird/hanab-android)'; 'Accept' = 'application/json' }
    if ($env:GITHUB_TOKEN -and $Url.StartsWith('https://api.github.com')) { $headers['Authorization'] = "Bearer $env:GITHUB_TOKEN" }
    Invoke-RestMethod -Uri $Url -Headers $headers
}

function Invoke-GitHub([string]$Path) { Invoke-Web "https://api.github.com/repos/$repo/$Path" }

function Get-LatestVersion { (Invoke-Web 'https://crates.io/api/v1/crates/hanabi_protocol').crate.max_version }

function Get-UpstreamHead {
    # `git ls-remote` needs no API token.
    $line = git ls-remote "https://github.com/$repo" HEAD
    if (-not $line) { throw "Could not read the website's repository" }
    ($line -split '\s+')[0]
}

# The commit the website is running: its client's script is named for it (that is the version the server checks).
function Get-DeployedRev {
    $html = (Invoke-WebRequest -Uri 'https://hanab.live/' -UseBasicParsing).Content
    if ($html -notmatch '/public/js/bundles/main\.([0-9a-f]{40})\.min\.js') { throw "Could not read the website's version" }
    $Matches[1]
}

function Set-Dependency([string]$Line) {
    $text = (Get-Content $manifest -Raw) -replace '(?m)^hanabi_protocol\s*=.*$', $Line
    [IO.File]::WriteAllText($manifest, $text)
}

switch ($Command) {
    'gen' {
        New-Item -ItemType Directory -Force (Split-Path $generated) | Out-Null
        Invoke-Generator $generated
        Write-Host "Wrote $generated (hanabi_protocol $(Get-PinVersion))"
    }
    'check' {
        $temp = Join-Path ([IO.Path]::GetTempPath()) "Protocol-$([guid]::NewGuid()).kt"
        try {
            Invoke-Generator $temp
            if ((Get-Content $temp -Raw) -ne (Get-Content $generated -Raw)) {
                Write-Error "Protocol.kt is not what the generator writes for the pinned version. Run scripts/protocol.ps1 gen."
                exit 1
            }
            Write-Host "Protocol.kt matches the pinned hanabi_protocol $(Get-PinVersion)."
        } finally { Remove-Item $temp -ErrorAction SilentlyContinue }
    }
    'outdated' {
        $version = Get-PinVersion
        $pin = Get-PinCommit
        $head = Get-DeployedRev
        $latest = Get-LatestVersion
        $problems = $false
        if ($latest -ne $version) {
            Write-Host "A newer hanabi_protocol is published: $latest (pinned: $version). Run scripts/protocol.ps1 bump."
            $problems = $true
        }
        if ($pin -ne $head) {
            $compare = Invoke-GitHub "compare/$pin...$head"
            # A pin ahead of the live website (pinned on purpose, to a newer commit) has nothing to catch up on.
            if ($compare.status -ne 'behind') {
                $touching = foreach ($path in $paths) {
                    Invoke-GitHub "commits?path=$path&sha=$head&per_page=100" | Where-Object { $_.sha -ne $pin }
                }
                $changed = @($compare.files | Where-Object {
                    $name = $_.filename
                    $paths | Where-Object { $name.StartsWith($_) }
                })
                if ($changed.Count -gt 0) {
                    $commits = $compare.commits | Where-Object { $sha = $_.sha; $touching | Where-Object { $_.sha -eq $sha } }
                    Write-Host "The website that is live ($head) has a different protocol from hanabi_protocol $version (commit ${pin}):"
                    $commits | ForEach-Object { Write-Host ("  {0} {1}" -f $_.sha.Substring(0, 8), ($_.commit.message -split "`n")[0]) }
                    Write-Host "$($changed.Count) file(s): $(($changed | ForEach-Object filename) -join ', ')"
                    if ($latest -eq $version) {
                        Write-Host "The crate has not published these yet: pin the website's commit with scripts/protocol.ps1 bump main (or the commit), or wait for a new version."
                    }
                    $problems = $true
                }
            }
        }
        if ($problems) {
            Write-Host "Then fix what the build and the tests show (.\build.ps1 testDebugUnitTest), and commit the result."
            exit 1
        }
        Write-Host "The protocol of the website that is live ($head) has not changed since hanabi_protocol $version (commit $pin)."
    }
    'bump' {
        if (-not $Target) { $Target = Get-LatestVersion }
        if ($Target -match '^\d+\.\d+\.\d+') {
            Set-Dependency "hanabi_protocol = `"$Target`""
        } else {
            if ($Target -eq 'main') { $Target = Get-UpstreamHead }
            $rev = (Invoke-GitHub "commits/$Target").sha
            Set-Dependency "hanabi_protocol = { git = `"https://github.com/$repo`", rev = `"$rev`" }"
        }
        Push-Location $tool
        try {
            cargo update --quiet --package hanabi_protocol
            if ($LASTEXITCODE -ne 0) { throw "cargo update failed" }
        } finally { Pop-Location }
        # The website's test cases come with the crate, whichever way it is pinned.
        New-Item -ItemType Directory -Force (Split-Path $fixtures) | Out-Null
        Push-Location $tool
        try { cargo run --quiet -- --fixtures $fixtures; if ($LASTEXITCODE -ne 0) { throw "Could not copy the test cases" } } finally { Pop-Location }
        Invoke-Generator $generated
        Write-Host "Pinned to hanabi_protocol $(Get-PinVersion). Build and run the tests (.\build.ps1 testDebugUnitTest): what fails is what changed."
        git -C $root diff --stat
    }
}
