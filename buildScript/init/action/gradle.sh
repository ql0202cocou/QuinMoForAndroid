#!/bin/bash
# Prepare script for Actions Gradle build
set -e

#### Download assets
bash buildScript/lib/assets.sh

#### Download built-in plugin cores (xray / mihomo)
bash buildScript/lib/plugins.sh

