#!/bin/bash
# 构建「AI答题」APK
#   用法: bash build.sh [versionCode] [versionName]
#   例:   bash build.sh 2 1.0.1
set -e
cd "$(dirname "$0")"

VC="${1:-1}"
VN="${2:-1.0.0}"

export JAVA_HOME="/d/AI/workbuddy/tools/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"

rm -f "构建/AI答题-v${VN}.apk"
./gradlew assembleRelease --no-daemon --console=plain -Pvc="$VC" -Pvn="$VN"

BT="/d/AI/workbuddy/tools/android-sdk/build-tools/34.0.0"
APK="构建/AI答题-v${VN}.apk"
if [ -f "$APK" ]; then
  echo
  echo "== 产物 =="
  ls -lh "$APK"
  "$BT/aapt2.exe" dump badging "$APK" 2>/dev/null | head -1
  echo -n "so 个数: "; unzip -l "$APK" | grep -c "\.so$"
fi
