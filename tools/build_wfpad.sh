#!/bin/bash
# Build wfpad (fx/wfpad.c) — the input layer's native passthrough — as a 64-bit Android
# executable into the app's assets (copied to the app's files and run as root by the helper).
set -e
cd "$(dirname "$0")/.."
NDK="$LOCALAPPDATA/Android/Sdk/ndk/27.2.12479018"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
"$CC" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o app/src/main/assets/fx/wfpad fx/wfpad.c -Wl,-z,max-page-size=16384
ls -la app/src/main/assets/fx/wfpad
# tools/build_wfpad.sh test → the mapping engine's unit tests, run ON the Thor (fx/wfmap_test.c)
if [ "$1" = "test" ]; then
  "$CC" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o tools/wfmap_test fx/wfmap_test.c
  ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
  MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$PWD/tools/wfmap_test")" /data/local/tmp/wfmap_test >/dev/null
  # dev build 16 (WP4): a JVM test's re-spelled own controls (that test is not in this repository: skipped when absent) — the same map both ways
  PAIRS=""
  if [ -f app/build/wp4/spec_pairs.txt ]; then
    MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$PWD/app/build/wp4/spec_pairs.txt")" /data/local/tmp/wp4_spec_pairs.txt >/dev/null
    PAIRS=" /data/local/tmp/wp4_spec_pairs.txt"
  fi
  MSYS_NO_PATHCONV=1 "$ADB" shell "chmod 755 /data/local/tmp/wfmap_test; /data/local/tmp/wfmap_test$PAIRS" </dev/null
  # dev build 18f: the merged feed — AYN's pad and a controller that plays as the Thor's, one engine (fx/wfmerge.h)
  "$CC" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o tools/wfmerge_test fx/wfmerge_test.c
  MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$PWD/tools/wfmerge_test")" /data/local/tmp/wfmerge_test >/dev/null
  MSYS_NO_PATHCONV=1 "$ADB" shell 'chmod 755 /data/local/tmp/wfmerge_test; /data/local/tmp/wfmerge_test' </dev/null
  # the UHID copy's descriptor and reports (fx/wfuhid.h)
  "$CC" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o tools/wfuhid_test fx/wfuhid_test.c
  MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$PWD/tools/wfuhid_test")" /data/local/tmp/wfuhid_test >/dev/null
  MSYS_NO_PATHCONV=1 "$ADB" shell 'chmod 755 /data/local/tmp/wfuhid_test; /data/local/tmp/wfuhid_test' </dev/null
fi
