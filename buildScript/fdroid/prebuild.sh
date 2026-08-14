#!/bin/bash

buildScript/init/action/gradle.sh

# Build libcore
buildScript/lib/core.sh

# Download built-in plugin cores (xray / mihomo)
buildScript/lib/plugins.sh
