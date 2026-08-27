#!/bin/bash

set -e

chmod -R 777 .build 2>/dev/null || true
rm -rf .build 2>/dev/null || true

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install gomobile (vendored in ./gomobile, MatsuriDayo/gomobile @ master2).
# Always reinstall: go install is cached and cheap, and this picks up vendored
# source updates that the old [ ! -f ] guard would have kept stale forever.
pushd gomobile
pushd cmd
pushd gomobile
go install -v
popd
pushd gobind
go install -v
popd
popd
popd
mv -f "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
mv -f "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"

GOBIND=gobind-matsuri "$GOPATH/bin/gomobile-matsuri" init
