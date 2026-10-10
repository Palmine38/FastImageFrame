#!/bin/bash
cd "$(dirname "$0")/.."
mkdir -p test-results
versions=("$@")
if [ ${#versions[@]} -eq 0 ]; then
    versions=($(ls versions))
fi
status=0
for name in "${versions[@]}"; do
    dir="versions/$name"
    java_home="$JAVA_HOME"
    if grep -q "^java_version=25" "$dir/gradle.properties" && [ -n "$JAVA25_HOME" ]; then
        java_home="$JAVA25_HOME"
    fi
    extra=()
    jar="$IFC_JARS_DIR/imageframeclient-$name.jar"
    if [ -n "$IFC_JARS_DIR" ] && [ -f "$jar" ]; then
        extra=("-Pimageframeclient_jar=$jar")
    fi
    [ -n "$EXTRA_MODS_DIR" ] && extra+=("-Pextra_mods_dir=$EXTRA_MODS_DIR")
    [ -n "$SHARED_SRC_DIR" ] && extra+=("-Pshared_src_dir=$SHARED_SRC_DIR")
    echo "== $name ${extra[*]}"
    rm -rf "$dir/build/run/clientGameTest"
    log="test-results/$name.log"
    if (cd "$dir" && JAVA_HOME="$java_home" timeout 2700 ./gradlew runClientGameTest --no-daemon "${extra[@]}" > "../../$log" 2>&1); then
        echo "   passed"
    elif grep -q "===== RESULTS" "$log" && ! grep -q "AssertionError" "$log"; then
        echo "   passed (game killed while closing the world)"
    else
        echo "   FAILED (see $log)"
        status=1
    fi
    grep -E "\[(FIF|FH)\]" "$log" | sed -E 's/.*\[(FIF|FH)\] /   /'
    mkdir -p "test-results/$name"
    cp "$dir"/build/run/clientGameTest/screenshots/*.png "test-results/$name/" 2>/dev/null
done
exit $status
