#!/usr/bin/env bash
# Run from an unpacked archive: the command answers its help, which needs the launcher jar and a Java 25 runtime.
set -euo pipefail
HOME_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
"$HOME_DIR/bin/jenrepo" help | grep -q 'Usage: jenrepo'
echo "jenrepo: the archive runs"
