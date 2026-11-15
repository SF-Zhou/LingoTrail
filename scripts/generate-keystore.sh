#!/usr/bin/env bash
#
# Generate LingoTrail's release signing material.
#
# Creates:
#   signing/lingotrail-release.jks   PKCS#12 keystore (RSA 4096, ~27 years)
#   signing/keystore.properties      Gradle signing config (mode 600, git-ignored)
#
# A strong random password is generated unless KEYSTORE_PASSWORD is set. The same
# password is used for the keystore and the key (PKCS#12 requires it). After
# generating, the script prints the base64-encoded keystore and the exact
# `gh secret set` commands for the four GitHub Actions secrets.
#
# Usage: scripts/generate-keystore.sh [--force]
#
# Options:
#   --force   Replace existing signing files (the old ones are backed up first).
#   -h, --help
#
# Environment:
#   KEYSTORE_PASSWORD   Use this password instead of a generated one.
#   KEY_ALIAS           Key alias. Default: lingotrail.
#   VALIDITY_DAYS       Certificate validity in days. Default: 10000.
#   DNAME               Certificate distinguished name.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SIGNING_DIR="signing"
KEYSTORE_FILE="$SIGNING_DIR/lingotrail-release.jks"
PROPERTIES_FILE="$SIGNING_DIR/keystore.properties"
ALIAS="${KEY_ALIAS:-lingotrail}"
VALIDITY_DAYS="${VALIDITY_DAYS:-10000}"
DNAME="${DNAME:-CN=LingoTrail, OU=Release, O=LingoTrail, C=US}"
FORCE=0

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/generate-keystore.sh [--force]

Generate the LingoTrail release keystore (signing/lingotrail-release.jks) and
the Gradle signing properties (signing/keystore.properties). Both files are
git-ignored. Refuses to overwrite existing files unless --force is passed;
with --force the previous files are backed up first.

Environment variables:
  KEYSTORE_PASSWORD   use this password instead of a randomly generated one
  KEY_ALIAS           key alias (default: lingotrail)
  VALIDITY_DAYS       certificate validity in days (default: 10000)
  DNAME               certificate distinguished name
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)
      FORCE=1
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      printf 'error: unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

find_keytool() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    printf '%s\n' "$JAVA_HOME/bin/keytool"
  elif command -v keytool >/dev/null 2>&1; then
    command -v keytool
  else
    return 1
  fi
}

generate_password() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    # Fallback: 1024 random bytes -> ~250 alphanumerics -> first 48 characters.
    head -c 1024 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-48
  fi
}

# Quote a value for safe pasting into a shell command.
shell_quote() {
  if [[ "$1" =~ ^[A-Za-z0-9._~+=@%:,/-]+$ ]]; then
    printf "'%s'" "$1"
  else
    printf '%q' "$1"
  fi
}

KEYTOOL="$(find_keytool)" || die "keytool not found. Install a JDK 17 and set JAVA_HOME, or put keytool on PATH."
if ! "$KEYTOOL" -help >/dev/null 2>&1; then
  die "keytool at $KEYTOOL is not usable. Point JAVA_HOME at a JDK 17 installation or put a working keytool on PATH."
fi

# ---------------------------------------------------------------------------
# Refuse to destroy existing signing material.
# ---------------------------------------------------------------------------
existing=()
[[ -e "$KEYSTORE_FILE" ]] && existing+=("$KEYSTORE_FILE")
[[ -e "$PROPERTIES_FILE" ]] && existing+=("$PROPERTIES_FILE")

if (( ${#existing[@]} > 0 )); then
  if (( FORCE == 0 )); then
    {
      printf 'error: release signing material already exists:\n'
      printf '  %s\n' "${existing[@]}"
      printf '\nRefusing to overwrite it. Re-run with --force to replace it (the old files will be backed up).\n'
    } >&2
    exit 1
  fi
  stamp="$(date +%Y%m%d-%H%M%S)"
  for file in "${existing[@]}"; do
    mv "$file" "$file.bak-$stamp"
    printf 'Backed up %s -> %s\n' "$file" "$file.bak-$stamp"
  done
fi

# ---------------------------------------------------------------------------
# Password.
# ---------------------------------------------------------------------------
if [[ -n "${KEYSTORE_PASSWORD:-}" ]]; then
  PASSWORD="$KEYSTORE_PASSWORD"
  printf 'Using KEYSTORE_PASSWORD from the environment.\n'
else
  PASSWORD="$(generate_password)"
  [[ ${#PASSWORD} -ge 32 ]] || die "Could not generate a strong password."
fi

# ---------------------------------------------------------------------------
# Keystore + properties.
# ---------------------------------------------------------------------------
mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR"

printf 'Generating %s ...\n' "$KEYSTORE_FILE"
if ! "$KEYTOOL" -genkeypair \
  -keystore "$KEYSTORE_FILE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "$DNAME"; then
  die "keytool failed to generate the keystore. Check that JAVA_HOME points at a JDK 17 installation."
fi

[[ -f "$KEYSTORE_FILE" ]] || die "keytool did not create $KEYSTORE_FILE."
chmod 600 "$KEYSTORE_FILE"

umask 077
cat >"$PROPERTIES_FILE" <<EOF
# LingoTrail release signing — generated by scripts/generate-keystore.sh.
# This file is git-ignored. Never commit it.
storeFile=$KEYSTORE_FILE
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF
chmod 600 "$PROPERTIES_FILE"

"$KEYTOOL" -list -keystore "$KEYSTORE_FILE" -storepass "$PASSWORD" -alias "$ALIAS" >/dev/null 2>&1 ||
  die "The generated keystore could not be read back with the generated password."

# ---------------------------------------------------------------------------
# Report.
# ---------------------------------------------------------------------------
BASE64="$(base64 <"$KEYSTORE_FILE" | tr -d '\n')"

cat <<EOF

============================================================================
 Release signing material created
============================================================================

  Keystore    $KEYSTORE_FILE
  Properties  $PROPERTIES_FILE  (mode 600, git-ignored)
  Alias       $ALIAS
  Passwords   stored in $PROPERTIES_FILE

Back up the keystore and its passwords somewhere safe and offline. If they
are lost you can no longer publish updates signed for the same app.

The values below are secrets. Do not paste them into issues, chats, or commits.

1) ANDROID_KEYSTORE_BASE64 — base64 of the keystore

$BASE64

2) Configure the four GitHub Actions secrets
   (run from the repository root with an authenticated gh CLI):

   base64 < $KEYSTORE_FILE | tr -d '\\n' | gh secret set ANDROID_KEYSTORE_BASE64

   gh secret set ANDROID_KEYSTORE_PASSWORD --body $(shell_quote "$PASSWORD")

   gh secret set ANDROID_KEY_ALIAS --body $(shell_quote "$ALIAS")

   gh secret set ANDROID_KEY_PASSWORD --body $(shell_quote "$PASSWORD")

   Or add them under Settings -> Secrets and variables -> Actions in GitHub.

3) Local builds and scripts/release.sh read $PROPERTIES_FILE automatically.
   Nothing needs to be exported for local release builds.

Commit nothing from signing/ — it is already in .gitignore.
EOF
