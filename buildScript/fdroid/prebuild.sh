#!/bin/bash

set -e

buildScript/init/action/gradle.sh

# Build libcore
buildScript/lib/core.sh
