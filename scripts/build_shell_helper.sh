#!/usr/bin/env bash
# 构建 cloudphone shell 助手 dex（app_process 加载，scrcpy 同款范式）
# 产物：source/shellhelper/build/shellhelper.dex（build 目录不入库）
# 用法：scripts/build_shell_helper.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
JDK="${JAVA_HOME:-$(ls -d /opt/jdk/jdk-* 2>/dev/null | sort -V | tail -1)}"
export PATH="$JDK/bin:$PATH"
PLATFORM_JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
D8="$(ls "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1)"

if [[ -z "$PLATFORM_JAR" || -z "$D8" || ! -x "$JDK/bin/javac" ]]; then
  echo "ERROR: 需要 Android SDK（android.jar 与 build-tools/d8）与 JDK: SDK=$SDK JDK=$JDK" >&2
  exit 1
fi

OUT_DIR="$ROOT/source/shellhelper/build"
CLASSES_DIR="$OUT_DIR/classes"
mkdir -p "$CLASSES_DIR"

echo "[build_shell_helper] javac → $CLASSES_DIR"
# 注意：lambda 需要 JDK 的 LambdaMetafactory，不能把 android.jar 设为 bootclasspath；
# 以 -classpath 引入 android.jar（符号来自 classpath，lambda 元工厂来自 JDK）
"$JDK/bin/javac" -source 8 -target 8 -classpath "$PLATFORM_JAR" \
  -d "$CLASSES_DIR" \
  "$ROOT/source/shellhelper/src/cloudphone/shell/ShellHelper.java" 2>&1 | grep -v "bootstrap class path\|deprecat\|warning" || true

echo "[build_shell_helper] d8 → $OUT_DIR/shellhelper.dex"
rm -f "$OUT_DIR/classes.dex" "$OUT_DIR/shellhelper.dex"
# 注意：多个 .class 需按词拆分传给 d8（勿加引号）
"$D8" --min-api 26 --output "$OUT_DIR" \
  $(find "$CLASSES_DIR" -name '*.class')
mv "$OUT_DIR/classes.dex" "$OUT_DIR/shellhelper.dex"

echo "[build_shell_helper] 完成: $OUT_DIR/shellhelper.dex"
ls -la "$OUT_DIR/shellhelper.dex"
