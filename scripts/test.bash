#!/bin/bash
# Test script for running kompile tests.

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Build first, then run tests
"$SCRIPT_PATH/build.bash" dockerrun.embedded.buildMaven

if [ $? -ne 0 ]; then
  echo "Build failed, skipping tests"
  exit 1
fi

echo "Build successful. No additional tests defined."
