#!/bin/bash
# Build wfhid (fx/wfhid.c) — external controllers read from hidraw — as a 64-bit
# Android executable into the app's assets (copied to the app's files and run as root by the helper).
set -e
cd "$(dirname "$0")/.."
NDK="$LOCALAPPDATA/Android/Sdk/ndk/27.2.12479018"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
"$CC" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o app/src/main/assets/fx/wfhid fx/wfhid.c -Wl,-z,max-page-size=16384
ls -la app/src/main/assets/fx/wfhid
# tools/build_wfhid.sh test → the external controllers' unit tests, run ON the Thor (fx/wfhid_test.c)
if [ "$1" = "test" ]; then
  "$CC" --target=aarch64-linux-android30 -O2 -Wall -Wextra -fPIE -pie -o tools/wfhid_test fx/wfhid_test.c
  ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
  MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w "$PWD/tools/wfhid_test")" /data/local/tmp/wfhid_test >/dev/null
  MSYS_NO_PATHCONV=1 "$ADB" shell 'chmod 755 /data/local/tmp/wfhid_test; /data/local/tmp/wfhid_test' </dev/null
fi
