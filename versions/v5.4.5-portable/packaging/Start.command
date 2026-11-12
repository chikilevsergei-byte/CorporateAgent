#!/bin/bash
set -eu
cd -- "$(dirname -- "$0")"
ROOT="$(pwd -P)"
EXPECTED="@ARCH@"
ACTUAL="$(uname -m)"
if [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "Wrong package for this Mac: expected $EXPECTED, got $ACTUAL."
  exit 1
fi
if ! "$ROOT/jre/Contents/Home/bin/java" -Dfile.encoding=UTF-8 -cp "$ROOT/launcher.jar:$ROOT/dist/CorporateAgent.jar" PortableLauncher "$ROOT" "$@"; then
  echo "Launch failed. See launch-logs. Press Enter to close."
  read -r unused
  exit 1
fi
