#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
# Use a project-selected JDK without changing shell profiles or global defaults.
if [ -n "${KANDONG_JAVA_HOME:-}" ]; then
  kandong_jdk="$KANDONG_JAVA_HOME"
elif [ "$(uname -s)" = Darwin ]; then
  kandong_jdk="$(/usr/libexec/java_home -v 17)"
else
  kandong_jdk="${JAVA_HOME:?Set JAVA_HOME or KANDONG_JAVA_HOME to JDK 17}"
fi
exec env JAVA_HOME="$kandong_jdk" ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :fixture:assembleDebug --console=plain "$@"
