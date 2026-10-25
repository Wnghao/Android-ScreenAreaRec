#!/usr/bin/env bash
# 区域录制：构建 + 安装
# 用法： bash scripts/build.sh [install]
set -e

export JAVA_HOME="D:/ProgramFiles/Java/jdk-17.0.2"
export ANDROID_HOME="D:/py/androad/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export MSYS_NO_PATHCONV=1

ADB="D:/py/androad/tools/scrcpy/scrcpy-win64-v4.1/adb.exe"
GRADLE="D:/py/androad/tools/gradle-8.10.2/bin/gradle.bat"
PROJ="D:/py/androad/apps/AreaRec"

cd "$PROJ"

echo "== 构建 =="
"$GRADLE" assembleDebug --console=plain 2>&1 | grep -E "^e: |^w: .*(deprecat|never used)|BUILD|error:" || true

APK="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "!! 构建失败，没有产出 apk"
  exit 1
fi

if [ "$1" = "install" ]; then
  echo "== 安装 =="
  "$ADB" install -r -g "$APK" 2>&1 | tail -2
fi
