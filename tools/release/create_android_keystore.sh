#!/usr/bin/env bash
# Creates the Android upload/release keystore and prints the values for the GitHub secrets the
# Android workflow reads (UZKEY_KEYSTORE_BASE64, UZKEY_KEYSTORE_PASSWORD, UZKEY_KEY_ALIAS, UZKEY_KEY_PASSWORD).
#
# Usage: tools/release/create_android_keystore.sh [output.jks] ["CN=Your Name, O=Org, C=UZ"]
# Requires keytool (any JDK 17+). Keep the .jks file and passwords somewhere safe: losing them means
# the app can no longer be updated under the same signature (unless Play App Signing is used).
set -euo pipefail

OUTPUT="${1:-uzkey-release.jks}"
DNAME="${2:-CN=UzKey, O=UzKey, C=UZ}"
ALIAS="uzkey"

if [[ -e "$OUTPUT" ]]; then
  echo "$OUTPUT already exists; refusing to overwrite it" >&2
  exit 1
fi
if ! command -v keytool > /dev/null; then
  echo "keytool not found: install a JDK (e.g. Temurin 17)" >&2
  exit 1
fi

PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
keytool -genkeypair -v \
  -keystore "$OUTPUT" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "$DNAME" > /dev/null

if base64 --help 2>&1 | grep -q -- "-w"; then
  ENCODED="$(base64 -w 0 "$OUTPUT")"
else
  ENCODED="$(base64 < "$OUTPUT" | tr -d '\n')"
fi

cat <<EOF
Created $OUTPUT (PKCS12, RSA 4096, alias "$ALIAS").

Add these repository secrets (GitHub → Settings → Secrets and variables → Actions):

  UZKEY_KEYSTORE_PASSWORD = $PASSWORD
  UZKEY_KEY_PASSWORD      = $PASSWORD
  UZKEY_KEY_ALIAS         = $ALIAS
  UZKEY_KEYSTORE_BASE64   = (the next line)
$ENCODED

SHA-256 certificate fingerprint (Play Console → App integrity shows the same value):
$(keytool -list -v -keystore "$OUTPUT" -storepass "$PASSWORD" -alias "$ALIAS" | grep "SHA256:" | sed 's/^[[:space:]]*//')
EOF
