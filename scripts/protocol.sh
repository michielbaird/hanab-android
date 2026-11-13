#!/usr/bin/env bash
# Keeps the app's types for hanab.live's wire protocol in step with the website's own (macOS / Linux / Git Bash;
# scripts/protocol.ps1 does the same on Windows, and says what each command is for).
#
#   ./scripts/protocol.sh gen          write Protocol.kt from the pinned version
#   ./scripts/protocol.sh check        fail if Protocol.kt is not what gen writes
#   ./scripts/protocol.sh outdated     fail if the website that is live has a protocol the pin does not
#   ./scripts/protocol.sh bump [what]  move the pin, refresh the test cases, run gen. `what` is a crate version (0.1.2), or
#                                      `main` / a commit of the website's repository (a git dependency, for a protocol the
#                                      crate has not published yet); by default the newest published version
#
# Needs cargo (rustup fetches the pinned toolchain), curl and git. GITHUB_TOKEN is used for the GitHub API if it is set.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool="$root/native/protocol"
manifest="$tool/Cargo.toml"
lock="$tool/Cargo.lock"
generated="$root/app/src/main/java/live/hanab/client/protocol/Protocol.kt"
fixtures="$root/app/src/test/resources/protocol/compatibility.json"
repo="Hanabi-Live/hanabi-live"
# The protocol is in server/protocol; the website's generated TypeScript for it is checked in beside the clients, and the frames
# around the payloads (frames.ts, written by hand) are what net/WireFrames.kt follows.
paths=(server/protocol packages/data/src/generated packages/game/src/generated packages/data/src/functions/frames.ts)

# The version of hanabi_protocol that Cargo.lock pins.
pin_version() {
  local version
  version="$(grep -A1 '^name = "hanabi_protocol"' "$lock" | sed -n 's/^version = "\(.*\)"/\1/p')"
  [ -n "$version" ] || { echo "No hanabi_protocol in $lock" >&2; exit 1; }
  echo "$version"
}

# The commit of the website's repository the pin was made from: the generated file says (it is written from the crate's own
# record of the commit it was published from, or from the git dependency's commit).
pin_commit() {
  local rev
  rev="$(sed -n 's/^const val PROTOCOL_REV = "\([0-9a-f]\{40\}\)".*/\1/p' "$generated")"
  [ -n "$rev" ] || { echo "No PROTOCOL_REV in $generated; run scripts/protocol.sh gen" >&2; exit 1; }
  echo "$rev"
}

generate() { (cd "$tool" && cargo run --quiet -- "$1"); }

web() {
  local args=(-fsSL -H "Accept: application/json" -A "hanab-android (github.com/michielbaird/hanab-android)")
  case "$1" in https://api.github.com*) [ -n "${GITHUB_TOKEN:-}" ] && args+=(-H "Authorization: Bearer $GITHUB_TOKEN") ;; esac
  curl "${args[@]}" "$1"
}

github() { web "https://api.github.com/repos/$repo/$1"; }

latest_version() { web https://crates.io/api/v1/crates/hanabi_protocol | sed -n 's/.*"max_version":"\([^"]*\)".*/\1/p'; }

upstream_head() { git ls-remote "https://github.com/$repo" HEAD | cut -f1; }

# The commit the website is running: its client's script is named for it (that is the version the server checks).
deployed_rev() {
  local rev
  rev="$(curl -fsSL https://hanab.live/ | sed -n 's/.*\/public\/js\/bundles\/main\.\([0-9a-f]\{40\}\)\.min\.js.*/\1/p' | sed -n '1p')"
  [ -n "$rev" ] || { echo "Could not read the website's version" >&2; exit 1; }
  echo "$rev"
}

# sed -i differs between GNU and BSD; write through a temp file.
set_dependency() {
  awk -v line="$1" '/^hanabi_protocol *=/ { print line; next } { print }' "$manifest" > "$manifest.new" && mv "$manifest.new" "$manifest"
}

case "${1:-}" in
  gen)
    mkdir -p "$(dirname "$generated")"
    generate "$generated"
    echo "Wrote $generated (hanabi_protocol $(pin_version))"
    ;;
  check)
    temp="$(mktemp)"
    trap 'rm -f "$temp"' EXIT
    generate "$temp"
    if ! cmp -s "$temp" "$generated"; then
      echo "Protocol.kt is not what the generator writes for the pinned version. Run scripts/protocol.sh gen." >&2
      exit 1
    fi
    echo "Protocol.kt matches the pinned hanabi_protocol $(pin_version)."
    ;;
  outdated)
    version="$(pin_version)"
    pinned="$(pin_commit)"
    head="$(deployed_rev)"
    latest="$(latest_version)"
    problems=0
    if [ "$latest" != "$version" ]; then
      echo "A newer hanabi_protocol is published: $latest (pinned: $version). Run scripts/protocol.sh bump."
      problems=1
    fi
    if [ "$pinned" != "$head" ]; then
      compare="$(github "compare/$pinned...$head")"
      # A pin ahead of the live website (pinned on purpose, to a newer commit) has nothing to catch up on.
      case "$compare" in
        *'"status":"behind"'*) ;;
        *)
          # The files of the protocol that changed between the two commits (the API answers in a single line of JSON).
          changed="$({ echo "$compare" | grep -o '"filename":"[^"]*"' || true; } | sed 's/"filename":"\(.*\)"/\1/' |
            while read -r file; do for p in "${paths[@]}"; do case "$file" in "$p"*) echo "$file"; break ;; esac; done; done)"
          if [ -n "$changed" ]; then
            echo "The website that is live ($head) has a different protocol from hanabi_protocol $version (commit ${pinned}):"
            # The commits between the pin and the live site, by their first eight characters (a commit starts at its own "sha";
            # a file, a tree and a parent have one too, but no node_id beside it).
            between="$(echo "$compare" | sed 's/{"sha":"\([0-9a-f]\{8\}\)[0-9a-f]\{32\}","node_id"/\n@\1@/g' | sed -n 's/^@\([0-9a-f]\{8\}\)@.*/\1/p')"
            for p in "${paths[@]}"; do
              github "commits?path=$p&sha=$head&per_page=100" | sed 's/{"sha":"\([0-9a-f]\{40\}\)","node_id"/\n\1 /g' |
                sed -n 's/^\([0-9a-f]\{8\}\)[0-9a-f]\{32\} .*"message":"\([^"\\]*\).*/\1 \2/p'
            done | sort -u | while read -r short message; do
              # The list has every commit that ever touched the path; only the ones since the pin matter.
              case "$between" in *"$short"*) echo "  $short $message" ;; esac
            done
            echo "$(echo "$changed" | wc -l | tr -d ' ') file(s): $(echo "$changed" | paste -sd, - | sed 's/,/, /g')"
            if [ "$latest" = "$version" ]; then
              echo "The crate has not published these yet: pin the website's commit with scripts/protocol.sh bump main (or the commit), or wait for a new version."
            fi
            problems=1
          fi
          ;;
      esac
    fi
    if [ "$problems" = 1 ]; then
      echo "Then fix what the build and the tests show (./gradlew testDebugUnitTest), and commit the result."
      exit 1
    fi
    echo "The protocol of the website that is live ($head) has not changed since hanabi_protocol $version (commit $pinned)."
    ;;
  bump)
    target="${2:-}"
    [ -n "$target" ] || target="$(latest_version)"
    if echo "$target" | grep -q '^[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*'; then
      set_dependency "hanabi_protocol = \"$target\""
    else
      [ "$target" = main ] && target="$(upstream_head)"
      rev="$(github "commits/$target" | sed -n 's/^{"sha":"\([0-9a-f]\{40\}\)".*/\1/p')"
      [ -n "$rev" ] || { echo "Could not find that commit" >&2; exit 1; }
      set_dependency "hanabi_protocol = { git = \"https://github.com/$repo\", rev = \"$rev\" }"
    fi
    (cd "$tool" && cargo update --quiet --package hanabi_protocol)
    # The website's test cases come with the crate, whichever way it is pinned.
    mkdir -p "$(dirname "$fixtures")"
    (cd "$tool" && cargo run --quiet -- --fixtures "$fixtures")
    generate "$generated"
    echo "Pinned to hanabi_protocol $(pin_version). Build and run the tests (./gradlew testDebugUnitTest): what fails is what changed."
    git -C "$root" diff --stat
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
