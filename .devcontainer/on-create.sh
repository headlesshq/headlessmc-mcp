#!/bin/bash

set -eo pipefail

sudo chown "$(id -u):$(id -g)" "$GRADLE_USER_HOME"
