#!/bin/bash
# 构建「AI答题」APK
#   用法: bash build.sh [versionCode] [versionName]
#   例:   bash build.sh 2 1.0.1
#
# 工具链路径按下面顺序取，仓库里不放任何本机路径：
#   1) 环境变量 JAVA_HOME / ANDROID_BT
#   2) 同目录 build.local.sh（本机自用，已在 .gitignore 里，不会随仓库分发）
#   3) 兜底默认值 $HOME/jdk-17、$HOME/android-sdk/build-tools/34.0.0
set -e
cd "$(dirname "$0")"

VC="${1:-1}"
VN="${2:-1.0.0}"

if [ -f build.local.sh ]; then . ./build.local.sh; fi

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -f "构建/AI答题-v${VN}.apk"
./gradlew assembleRelease --no-daemon --console=plain -Pvc="$VC" -Pvn="$VN"

BT="${ANDROID_BT:-$HOME/android-sdk/build-tools/34.0.0}"
APK="构建/AI答题-v${VN}.apk"
if [ -f "$APK" ]; then
  echo
  echo "== 产物 =="
  ls -lh "$APK"
  "$BT/aapt2.exe" dump badging "$APK" 2>/dev/null | head -1
  echo -n "so 个数: "; unzip -l "$APK" | grep -c "\.so$"
fi
