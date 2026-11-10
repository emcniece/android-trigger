#!/usr/bin/env bash
# Bumps the app version and writes the F-Droid/IzzyOnDroid changelog for the next release.
#
# Usage: .github/scripts/prepare-release.sh <patch|minor|major|X.Y.Z> [notes-file]
#
# Updates versionName/versionCode in app/build.gradle.kts and writes
# fastlane/metadata/android/en-US/changelogs/<versionCode>.txt from notes-file, or, if that
# is missing or empty, a draft listing commit titles since the last release (Dependabot
# bumps left out). Prints name=... and code=... (also to $GITHUB_OUTPUT when set).
set -euo pipefail

gradle=app/build.gradle.kts
changelogs=fastlane/metadata/android/en-US/changelogs

die() { echo "error: $*" >&2; exit 1; }

[[ $# -ge 1 ]] || die "usage: $0 <patch|minor|major|X.Y.Z> [notes-file]"

cur_name=$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' "$gradle")
cur_code=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' "$gradle")
[[ $cur_name =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "can't read versionName from $gradle"
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}

case "$1" in
  patch) patch=$((patch + 1)) ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  major) major=$((major + 1)); minor=0; patch=0 ;;
  *)
    [[ $1 =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "version must be patch, minor, major or X.Y.Z, got '$1'"
    major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
    ;;
esac

# versionCode = MAJOR * 10000 + MINOR * 100 + PATCH, so minor and patch must stay below 100.
(( minor < 100 && patch < 100 )) || die "minor and patch must be below 100"
name="$major.$minor.$patch"
code=$((major * 10000 + minor * 100 + patch))
(( code > cur_code )) || die "new version $name ($code) must be higher than $cur_name ($cur_code)"

sed -i.bak -E \
  -e "s/^( *versionCode = )[0-9]+/\1$code/" \
  -e "s/^( *versionName = )\"[^\"]+\"/\1\"$name\"/" \
  "$gradle"
rm -f "$gradle.bak"

mkdir -p "$changelogs"
changelog="$changelogs/$code.txt"
if [[ -n ${2:-} && -s $2 ]]; then
  cp "$2" "$changelog"
else
  # Draft from commit titles since the last release tag; edit it in the release PR.
  since="v$cur_name"
  git rev-parse -q --verify "refs/tags/$since" >/dev/null || since=$(git rev-list --max-parents=0 HEAD | tail -1)
  git log --no-merges --format='- %s' "$since..HEAD" \
    | grep -vE '^- (Bump |Release v)' \
    | sed -E 's/ \(#[0-9]+\)$//' > "$changelog" || true
  [[ -s $changelog ]] || echo "- Bug fixes and improvements" > "$changelog"
fi
# Make sure the file ends with a newline.
[[ -z $(tail -c1 "$changelog") ]] || echo >> "$changelog"

echo "name=$name"
echo "code=$code"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
  { echo "name=$name"; echo "code=$code"; echo "changelog=$changelog"; } >> "$GITHUB_OUTPUT"
fi
