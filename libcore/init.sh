#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install gomobile (vendored in ./gomobile, MatsuriDayo/gomobile @ master2)
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
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
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

GOBIND=gobind-matsuri "$GOPATH/bin/gomobile-matsuri" init
