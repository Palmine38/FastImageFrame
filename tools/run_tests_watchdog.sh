#!/bin/bash
OUT="$1"
shift
cd "$(dirname "$0")/.." || exit 1
mkdir -p "$(dirname "$OUT")" test-results
export JAVA25_HOME="${JAVA25_HOME:-$(cygpath -w ~/.gradle/jdks/jdk-25.0.4.1+1)}"
export IFC_JARS_DIR="${IFC_JARS_DIR:-$(pwd)/test-jars}"
STALL_SECONDS=420
START=$(date +%s)

bash tools/test_all.sh "$@" > "$OUT" 2>&1 &
TEST_PID=$!

kill_game() {
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -match 'fabric.client.gametest' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }; Get-Process WerFault,WerFaultSecure -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue" > /dev/null 2>&1
}

while kill -0 $TEST_PID 2>/dev/null; do
    sleep 20
    log=$(ls -t test-results/*.log 2>/dev/null | head -1)
    [ -z "$log" ] && continue
    modified=$(stat -c %Y "$log")
    [ "$modified" -lt "$START" ] && continue
    age=$(( $(date +%s) - modified ))
    reason=""
    if grep -qE "AssertionError|Minecraft has crashed|Unreported exception thrown|ReportedException" "$log" && [ $age -gt 40 ]; then
        reason="error in $log, game still open ${age}s later"
    elif grep -q "===== RESULTS" "$log" && [ $age -gt 90 ]; then
        reason="results logged but the game did not close for ${age}s"
    elif [ $age -gt $STALL_SECONDS ]; then
        reason="$log not written for ${age}s"
    fi
    if [ -n "$reason" ]; then
        echo "WATCHDOG: $reason, killing the test game" >> "$OUT.watchdog"
        kill_game
        sleep 60
        START=$(date +%s)
    fi
done
wait $TEST_PID
echo "ALL DONE" >> "$OUT"
