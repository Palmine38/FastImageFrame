#!/bin/bash
cd "$(dirname "$0")/.."
tasks="compileJava"
if [ "$1" = "-t" ]; then
    tasks="compileJava compileGametestJava"
    shift
fi
export JAVA25_HOME="${JAVA25_HOME:-$(cygpath -w ~/.gradle/jdks/jdk-25.0.4.1+1)}"
for name in "$@"; do
    dir="versions/$name"
    java_home="$JAVA_HOME"
    if grep -q "^java_version=25" "$dir/gradle.properties"; then
        java_home="$JAVA25_HOME"
    fi
    echo "== $name"
    (cd "$dir" && JAVA_HOME="$java_home" ./gradlew $tasks --no-daemon -q 2>&1) | grep -E -A2 "error:|FAILURE|What went wrong" | sed 's/.*preprocessed.main.java.fastheads.//; s/.*preprocessed.gametest.java.fastheads.//' | grep -v "^--$" | head -60
done
