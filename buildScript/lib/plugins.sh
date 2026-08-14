#!/bin/bash

# Downloads the built-in plugin cores (Xray / mihomo) from upstream releases
# into app/executableSo/<abi>/lib*.so (gitignored, packaged via jniLibs).
# Pinned versions; bump here when upgrading.

set -e

XRAY_VERSION="v26.3.27"
MIHOMO_VERSION="v1.19.29"

DIR=app/executableSo
mkdir -p $DIR/arm64-v8a $DIR/x86_64

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd $TMP

check_sha256() {
  # $1: file, $2: dgst content with "SHA2-256= <hash>"
  local expect=$(echo "$2" | grep 'SHA2-256=' | awk '{print $2}')
  [ -n "$expect" ] || { echo "no SHA2-256 in dgst"; exit 1; }
  local actual=$(shasum -a 256 "$1" | awk '{print $1}')
  [ "$actual" = "$expect" ] || { echo "sha256 mismatch for $1: $actual != $expect"; exit 1; }
}

check_elf() {
  [ "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ] || {
    echo "$1 is not an ELF binary"; exit 1; }
}

#### Xray (arm64-v8a / x86_64)

for abi in arm64-v8a x86_64; do
  case $abi in
    arm64-v8a) xray_abi=arm64-v8a ;;
    x86_64) xray_abi=amd64 ;;
  esac
  zip="Xray-android-$xray_abi.zip"
  curl -fLSsO "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip"
  dgst=$(curl -fLSs "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip.dgst")
  check_sha256 "$zip" "$dgst"
  unzip -o -q "$zip" xray
  check_elf xray
  cp xray "$OLDPWD/$DIR/$abi/libxray.so"
  rm -f xray "$zip"
done

#### mihomo (arm64-v8a / x86_64)
# upstream publishes no checksums; verify gzip integrity + ELF magic instead

for abi in arm64-v8a x86_64; do
  case $abi in
    arm64-v8a) mihomo_abi=arm64-v8 ;;
    x86_64) mihomo_abi=amd64 ;;
  esac
  gz="mihomo-android-$mihomo_abi-$MIHOMO_VERSION.gz"
  curl -fLSsO "https://github.com/MetaCubeX/mihomo/releases/download/$MIHOMO_VERSION/$gz"
  gzip -t "$gz"
  gunzip -f "$gz"
  bin="${gz%.gz}"
  check_elf "$bin"
  cp "$bin" "$OLDPWD/$DIR/$abi/libmihomo.so"
  rm -f "$bin"
done

echo ">> plugin cores installed to $DIR (xray $XRAY_VERSION, mihomo $MIHOMO_VERSION)"
