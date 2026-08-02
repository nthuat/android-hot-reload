#!/usr/bin/env bash
# E2E: golden reload path + incompatible-change path. Requires a booted emulator/device and ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
ADB="${ANDROID_HOME}/platform-tools/adb"
PKG="dev.hotreload.sample"
GREETING="sample/feature/src/main/kotlin/dev/hotreload/sample/feature/Greeting.kt"
AGENT_SO_DIR="$ROOT/agent/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"

cleanup() { git checkout -- "$GREETING" 2>/dev/null || true; }
trap cleanup EXIT

fail() { echo "E2E FAIL: $1"; exit 1; }

# uiautomator's accessibility bridge is occasionally not ready right after an
# activity launch/recompose and dumps an empty tree with "ERROR: null root
# node" on stdout (dump command itself still exits 0). Retry the dump on that.
dump_ui() {
  local attempt out
  for attempt in 1 2 3 4 5; do
    out=$("$ADB" shell uiautomator dump /sdcard/ui.xml 2>&1)
    if [[ "$out" != *ERROR* ]]; then
      "$ADB" shell cat /sdcard/ui.xml
      return 0
    fi
    sleep 1
  done
  echo "uiautomator dump kept failing: $out" >&2
  return 1
}

# Recomposition after cold start / a reload isn't instant either, so a clean
# dump can still be a snapshot taken before the expected text landed. Retry
# the whole dump+search a few times before treating it as a real mismatch.
ui_contains() {
  local attempt
  for attempt in 1 2 3 4 5 6 7 8; do
    if dump_ui | grep -qF "$1"; then return 0; fi
    sleep 1
  done
  return 1
}

echo "== build tool + agent + sample =="
./gradlew :agent:assembleDebug :cli:installDist
(cd sample && ../gradlew :app:assembleDebug -x lint)

echo "== install + launch =="
"$ADB" install -r sample/app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n "$PKG/.MainActivity"
sleep 3
ui_contains "Hello, World!" || fail "baseline UI not visible"

echo "== click counter twice (state probe) =="
BOUNDS=$(dump_ui \
  | grep -o 'text="Count: 0"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
  | grep -o '\[[0-9]*,[0-9]*\]' | head -1 | tr -d '[]')
X=$(echo "$BOUNDS" | cut -d, -f1); Y=$(echo "$BOUNDS" | cut -d, -f2)
"$ADB" shell input tap "$((X+20))" "$((Y+20))"
"$ADB" shell input tap "$((X+20))" "$((Y+20))"
sleep 1
ui_contains "Count: 2" || fail "counter did not reach 2"

echo "== bootstrap =="
HR="$ROOT/cli/build/install/cli/bin/cli"
"$HR" bootstrap --project "$ROOT/sample" --package "$PKG" --agent-so-dir "$AGENT_SO_DIR" \
  || fail "bootstrap exited $?"

echo "== golden path: edit composable body, cycle, assert new text + preserved state =="
sed -i.bak 's/Hello, \$name!/Reloaded, \$name!/' "$GREETING" && rm -f "$GREETING.bak"
"$HR" cycle --project "$ROOT/sample" --package "$PKG" --file "$ROOT/$GREETING" \
  --agent-so-dir "$AGENT_SO_DIR" \
  || fail "cycle exited $?"
sleep 2
ui_contains "Reloaded, World!" || fail "reloaded text not visible"
ui_contains "Count: 2" || fail "counter state lost after reload"

echo "== incompatible path: add a function, expect exit 2 and clean error =="
cat >> "$GREETING" <<'EOF'

fun extraTopLevel(): Int = 7
EOF
set +e
"$HR" cycle --project "$ROOT/sample" --package "$PKG" --file "$ROOT/$GREETING" \
  --agent-so-dir "$AGENT_SO_DIR"
CODE=$?
set -e
[ "$CODE" -eq 2 ] || fail "expected exit 2 (incompatible), got $CODE"
ui_contains "Reloaded, World!" || fail "app corrupted by rejected change"

echo "E2E PASS"
