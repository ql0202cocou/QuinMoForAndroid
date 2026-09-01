#!/usr/bin/env bash

source "buildScript/init/env.sh"
export CGO_ENABLED=1
export GO386=softfloat

cd libcore

# Check for Go module updates (report only; upgrades follow the sing-box base manually,
# because the sing-box ecosystem modules are version-locked to each other)
go list -m -u -f '{{if .Update}}{{printf "%s %s -> %s" .Path .Version .Update.Version}}{{end}}' all || true

./build.sh || exit 1
