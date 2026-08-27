#!/bin/bash

# Downloads the built-in plugin cores (Xray / mihomo) from upstream releases
# into app/executableSo/<abi>/lib*.so (gitignored, packaged via jniLibs).
# Pinned versions; bump here when upgrading.

set -e

XRAY_VERSION="v26.3.27"
MIHOMO_VERSION="v1.19.29"

ABIS="arm64-v8a x86_64"
DIR=app/executableSo
STAMP="$DIR/.versions"
WANT="xray=$XRAY_VERSION mihomo=$MIHOMO_VERSION"

is_elf() {
  [ "$(head -c 4 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

# skip when the installed cores already match the pinned versions
# (ELF magic check, so an interrupted previous run's truncated .so is not
# mistaken for an up-to-date install)
if [ "$(cat "$STAMP" 2>/dev/null)" = "$WANT" ] \
  && is_elf "$DIR/arm64-v8a/libxray.so" && is_elf "$DIR/x86_64/libxray.so" \
  && is_elf "$DIR/arm64-v8a/libmihomo.so" && is_elf "$DIR/x86_64/libmihomo.so"; then
  echo ">> plugin cores up to date ($WANT)"
  exit 0
fi

mkdir -p $DIR/arm64-v8a $DIR/x86_64
rm -f "$STAMP"

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
  is_elf "$1" || { echo "$1 is not an ELF binary"; exit 1; }
}

upstream_arch() {
  # $1: abi, $2: the upstream's name for arm64-v8a (both use amd64 for x86_64)
  if [ "$1" = x86_64 ]; then echo amd64; else echo "$2"; fi
}

fetch_xray() {
  local abi zip dgst
  for abi in $ABIS; do
    zip="Xray-android-$(upstream_arch $abi arm64-v8a).zip"
    curl -fLSsO "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip"
    dgst=$(curl -fLSs "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$zip.dgst")
    check_sha256 "$zip" "$dgst"
    unzip -o -q "$zip" xray
    check_elf xray
    cp xray "$OLDPWD/$DIR/$abi/libxray.so"
    rm -f xray "$zip"
  done
}

# upstream publishes no checksums; verify gzip integrity + ELF magic instead
fetch_mihomo() {
  local abi gz bin
  for abi in $ABIS; do
    gz="mihomo-android-$(upstream_arch $abi arm64-v8)-$MIHOMO_VERSION.gz"
    curl -fLSsO "https://github.com/MetaCubeX/mihomo/releases/download/$MIHOMO_VERSION/$gz"
    gzip -t "$gz"
    gunzip -f "$gz"
    bin="${gz%.gz}"
    check_elf "$bin"
    cp "$bin" "$OLDPWD/$DIR/$abi/libmihomo.so"
    rm -f "$bin"
  done
}

# the two upstreams are independent; download them concurrently
fetch_xray & xray_pid=$!
fetch_mihomo & mihomo_pid=$!
status=0
wait $xray_pid || status=1
wait $mihomo_pid || status=1
[ $status -eq 0 ] || exit 1

echo "$WANT" > "$OLDPWD/$STAMP"
echo ">> plugin cores installed to $DIR (xray $XRAY_VERSION, mihomo $MIHOMO_VERSION)"
