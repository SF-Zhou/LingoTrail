#!/usr/bin/env bash
#
# Local one-shot release for LingoTrail.
#
# Builds the minified, signed release APK, verifies its signature and zip
# alignment, writes dist/LingoTrail-<version>.apk plus a .sha256 file, and with
# --publish creates a GitHub Release (requires an authenticated gh CLI).
#
# Usage: scripts/release.sh [version] [--publish] [--dry-run]
#
# The version may be given with or without the leading "v". When omitted, the
# git tag pointing at HEAD is used. Signing credentials are read from the
# git-ignored signing/keystore.properties (created by scripts/generate-keystore.sh)
# or from the ANDROID_KEYSTORE_* environment variables.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VERSION_ARG=""
PUBLISH=0
DRY_RUN=0

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '==> %s\n' "$*"
}

usage() {
  cat <<'USAGE'
Usage: scripts/release.sh [version] [--publish] [--dry-run]

Build a signed LingoTrail release APK, verify it, and optionally publish a
GitHub Release. Artifacts are written to dist/.

Arguments:
  version        Release version, with or without a leading "v"
                 (for example 1.2.0 or v1.2.0). Defaults to the git tag
                 pointing at HEAD.

Options:
  --publish      Push the git tag and create a GitHub Release with the APK
                 and its SHA-256 checksum. Requires an authenticated gh CLI.
  --dry-run      Build, verify and write dist/ artifacts, then stop before
                 tagging or publishing.
  -h, --help     Show this help and exit.

Prerequisites:
  * JDK 17 (JAVA_HOME or PATH)
  * Android SDK build-tools, found via ANDROID_HOME, ANDROID_SDK_ROOT, or
    sdk.dir in local.properties
  * signing/keystore.properties and signing/lingotrail-release.jks, created
    by scripts/generate-keystore.sh (or the four ANDROID_KEYSTORE_* env vars)
  * for --publish: the GitHub CLI, authenticated with `gh auth login`

Examples:
  scripts/release.sh --dry-run
  scripts/release.sh 1.1.0 --dry-run
  scripts/release.sh v1.0.0 --publish
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --publish)
      PUBLISH=1
      ;;
    --dry-run)
      DRY_RUN=1
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    -*)
      printf 'error: unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
    *)
      [[ -z "$VERSION_ARG" ]] || die "Only one version argument is accepted."
      VERSION_ARG="$1"
      ;;
  esac
  shift
done

if (( PUBLISH == 1 && DRY_RUN == 1 )); then
  die "--publish and --dry-run are mutually exclusive."
fi

# ---------------------------------------------------------------------------
# Version.
# ---------------------------------------------------------------------------
resolve_version() {
  local raw="${VERSION_ARG:-}"
  if [[ -z "$raw" ]]; then
    raw="$(git describe --tags --exact-match 2>/dev/null || true)"
    if [[ -z "$raw" ]]; then
      die "No version given and HEAD is not tagged. Pass one, e.g. scripts/release.sh 1.1.0 --dry-run"
    fi
  fi
  raw="${raw#v}"
  if [[ ! "$raw" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]; then
    die "'$raw' is not a semantic version (expected X.Y.Z, optionally with a -suffix)."
  fi
  printf '%s\n' "$raw"
}

VERSION="$(resolve_version)"
TAG="v$VERSION"
IS_PRERELEASE=0
case "$VERSION" in
  *-*)
    IS_PRERELEASE=1
    ;;
esac

info "Release version: $VERSION (tag $TAG)"

# ---------------------------------------------------------------------------
# Signing configuration.
# ---------------------------------------------------------------------------
PROPERTIES_FILE="signing/keystore.properties"
HAS_SIGNING=0
if [[ -f "$PROPERTIES_FILE" ]]; then
  for key in storeFile storePassword keyAlias keyPassword; do
    grep -Eq "^[[:space:]]*${key}[[:space:]]*=[[:space:]]*[^[:space:]]+" "$PROPERTIES_FILE" ||
      die "$PROPERTIES_FILE is missing '$key'. Re-run scripts/generate-keystore.sh or fix the file."
  done
  store_file="$(sed -n 's/^[[:space:]]*storeFile[[:space:]]*=[[:space:]]*//p' "$PROPERTIES_FILE" | tail -n 1)"
  store_file="${store_file%$'\r'}"
  if [[ ! -f "$store_file" ]]; then
    # storeFile is resolved relative to the repository root by Gradle.
    die "Keystore '$store_file' referenced by $PROPERTIES_FILE does not exist."
  fi
  HAS_SIGNING=1
  info "Signing with $PROPERTIES_FILE"
elif [[ -n "${ANDROID_KEYSTORE_PATH:-}" && -n "${ANDROID_KEYSTORE_PASSWORD:-}" &&
  -n "${ANDROID_KEY_ALIAS:-}" && -n "${ANDROID_KEY_PASSWORD:-}" ]]; then
  [[ -f "$ANDROID_KEYSTORE_PATH" ]] ||
    die "ANDROID_KEYSTORE_PATH points to a file that does not exist: $ANDROID_KEYSTORE_PATH"
  HAS_SIGNING=1
  info "Signing with ANDROID_KEYSTORE_PATH=$ANDROID_KEYSTORE_PATH"
fi

if (( HAS_SIGNING == 0 )); then
  die "No release signing configured. Run scripts/generate-keystore.sh, or export ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
fi

# ---------------------------------------------------------------------------
# Android SDK build-tools.
# ---------------------------------------------------------------------------
find_android_sdk() {
  local candidate
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  if [[ -f local.properties ]]; then
    candidate="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties | tail -n 1)"
    candidate="${candidate%$'\r'}"
    if [[ -n "$candidate" && -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  return 1
}

find_build_tools_dir() {
  local sdk="$1" dir
  [[ -d "$sdk/build-tools" ]] || return 1
  while IFS= read -r dir; do
    if [[ -n "$dir" ]]; then
      printf '%s\n' "$dir"
      return 0
    fi
  done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -Vr)
  return 1
}

SDK="$(find_android_sdk)" ||
  die "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or set sdk.dir in local.properties."
BUILD_TOOLS_DIR="$(find_build_tools_dir "$SDK")" ||
  die "No Android build-tools found under $SDK/build-tools. Install them with sdkmanager."
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
[[ -x "$APKSIGNER" ]] || die "apksigner not found in $BUILD_TOOLS_DIR."
[[ -x "$ZIPALIGN" ]] || die "zipalign not found in $BUILD_TOOLS_DIR."
info "Using build-tools: $BUILD_TOOLS_DIR"

# ---------------------------------------------------------------------------
# Build.
# ---------------------------------------------------------------------------
if (( PUBLISH == 1 )) && [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  printf 'warning: the working tree has uncommitted changes; the release will not match the tagged commit.\n' >&2
fi

info "Building release APK"
./gradlew assembleRelease -PversionName="$VERSION" --console=plain

BUILT_APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$BUILT_APK" ]]; then
  die "Expected signed APK at $BUILT_APK but it does not exist. The build produced an unsigned APK — check the signing configuration."
fi

mkdir -p dist
ARTIFACT="dist/LingoTrail-$VERSION.apk"
CHECKSUM="$ARTIFACT.sha256"
cp "$BUILT_APK" "$ARTIFACT"

# ---------------------------------------------------------------------------
# Verify signature + alignment.
# ---------------------------------------------------------------------------
info "Verifying zip alignment"
if ! "$ZIPALIGN" -c -v 4 "$ARTIFACT"; then
  die "APK is not zip-aligned."
fi

info "Verifying APK signature"
if ! "$APKSIGNER" verify --verbose --print-certs "$ARTIFACT"; then
  die "APK signature verification failed."
fi

# ---------------------------------------------------------------------------
# Checksum.
# ---------------------------------------------------------------------------
(
  cd dist
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "LingoTrail-$VERSION.apk" > "LingoTrail-$VERSION.apk.sha256"
  else
    shasum -a 256 "LingoTrail-$VERSION.apk" > "LingoTrail-$VERSION.apk.sha256"
  fi
)
info "Wrote $ARTIFACT"
info "Wrote $CHECKSUM"
cat "$CHECKSUM"


if (( DRY_RUN == 1 )); then
  echo
  info "Dry run: stopping before tagging or publishing."
  exit 0
fi
if (( PUBLISH == 0 )); then
  echo
  info "Built and verified locally. Re-run with --publish to create the GitHub Release."
  exit 0
fi

# ---------------------------------------------------------------------------
# Publish.
# ---------------------------------------------------------------------------
command -v gh >/dev/null 2>&1 ||
  die "GitHub CLI not found. Install it from https://cli.github.com/ to use --publish."
gh auth status >/dev/null 2>&1 ||
  die "GitHub CLI is not authenticated. Run: gh auth login"
command -v git >/dev/null 2>&1 || die "git is required to tag and publish a release."

if gh release view "$TAG" >/dev/null 2>&1; then
  die "GitHub Release $TAG already exists. Delete it first (gh release delete $TAG) or choose another version."
fi

HEAD_COMMIT="$(git rev-parse HEAD)"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null 2>&1; then
  TAG_COMMIT="$(git rev-list -n 1 "$TAG")"
  if [[ "$TAG_COMMIT" != "$HEAD_COMMIT" ]]; then
    die "Tag $TAG already exists but does not point at HEAD. Check out the release commit or choose another version."
  fi
  info "Using existing tag $TAG"
else
  info "Creating annotated tag $TAG at $(git rev-parse --short HEAD)"
  git tag -a "$TAG" -m "LingoTrail $TAG"
fi

if ! git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  info "Pushing tag $TAG to origin"
  git push origin "refs/tags/$TAG"
else
  info "Tag $TAG is already present on origin"
fi

NOTES_DIR="${TMPDIR:-/tmp}"
NOTES_DIR="${NOTES_DIR%/}"
NOTES_FILE="$(mktemp "$NOTES_DIR/lingotrail-release-notes.XXXXXX")"
cleanup() {
  rm -f "$NOTES_FILE"
}
trap cleanup EXIT

if [[ -f CHANGELOG.md ]]; then
  awk -v ver="$VERSION" '
    /^## \[/ {
      if (capture) exit
      heading = $0
      sub(/^## \[/, "", heading)
      sub(/\].*/, "", heading)
      if (heading == ver) { capture = 1 }
      next
    }
    capture && /^## / { exit }
    capture && /^\[[^]]+\]:/ { exit }
    capture { print }
  ' CHANGELOG.md >"$NOTES_FILE"
fi
awk 'NF { started = 1 } started' "$NOTES_FILE" >"$NOTES_FILE.tmp" && mv "$NOTES_FILE.tmp" "$NOTES_FILE"

GH_ARGS=(--title "LingoTrail $VERSION")
if [[ -s "$NOTES_FILE" ]]; then
  info "Using the CHANGELOG.md section for $VERSION as release notes"
  GH_ARGS+=(--notes-file "$NOTES_FILE")
else
  info "No CHANGELOG.md section for $VERSION; using GitHub generated notes"
  GH_ARGS+=(--generate-notes)
fi
if (( IS_PRERELEASE == 1 )); then
  GH_ARGS+=(--prerelease)
fi

info "Publishing GitHub Release $TAG"
gh release create "$TAG" "$ARTIFACT" "$CHECKSUM" "${GH_ARGS[@]}"
gh release view "$TAG" --json url --jq .url 2>/dev/null || true
info "Release $TAG published."
