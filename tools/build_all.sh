#!/bin/bash
cd "$(dirname "$0")/.."
mkdir -p dist
status=0
for dir in versions/*/; do
    name=$(basename "$dir")
    java_home="$JAVA_HOME"
    if grep -q "^java_version=25" "$dir/gradle.properties" && [ -n "$JAVA25_HOME" ]; then
        java_home="$JAVA25_HOME"
    fi
    echo "== $name"
    if (cd "$dir" && JAVA_HOME="$java_home" ./gradlew build copyJarToDist --no-daemon -q "$@"); then
        echo "   ok"
    else
        echo "   FAILED"
        status=1
    fi
done
exit $status
