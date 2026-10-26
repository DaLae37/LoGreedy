#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RUNTIME=${1:-jdk21}
if [ "$#" -gt 0 ]; then shift; fi
case "$RUNTIME" in
    jdk8|jdk21) ;;
    *) echo "Usage: $0 [jdk8|jdk21] [--config file | --help | --version | --status]" >&2; exit 2 ;;
esac
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
if [ "$#" -eq 0 ]; then set -- --config "$ROOT/config/LoGreedy.properties"; fi
# JAVA_OPTS is a trusted, whitespace-separated list of JVM flags (no eval).
exec "$JAVA" ${JAVA_OPTS:-} -jar "$ROOT/build/distributions/all/LoGreedy-$RUNTIME.jar" "$@"
