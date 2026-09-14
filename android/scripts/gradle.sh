#!/usr/bin/env bash
# 统一构建入口: 把 Java 固定到仓库自带 / 指定的 JDK 17。
#
# 为什么需要它: 本机系统 JAVA_HOME 是 JDK 8, 而 AGP 8.5 需要 JVM 11+;
# 早期把绝对路径写死在 gradle.properties 里虽然能跑, 但会让 CI(ubuntu)构建失败,
# 所以改成由这个脚本在调用时注入。
#
# 用法(在 android/ 目录下):
#   scripts/gradle.sh :app:assembleDebug
#   scripts/gradle.sh :app:assembleRelease
#   scripts/gradle.sh :core:model:test :core:data:testDebugUnitTest
#
# 覆盖项:
#   FREE_BUFF_JDK=<jdk17 路径>        不使用 .toolchain 自带的 JDK
#   FREE_BUFF_GRADLE=<gradle 启动脚本> 不使用 .toolchain 自带的 Gradle(例如 ./gradlew)
set -euo pipefail
cd "$(dirname "$0")/.."

JDK="${FREE_BUFF_JDK:-}"
if [ -z "$JDK" ] && { [ -x ".toolchain/jdk-17.0.20.1+1/bin/java.exe" ] || [ -x ".toolchain/jdk-17.0.20.1+1/bin/java" ]; }; then
  JDK=".toolchain/jdk-17.0.20.1+1"
fi
if [ -z "$JDK" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JDK="$JAVA_HOME"
  else
    echo "!! 找不到 JDK 17。请把工具链放到 android/.toolchain/,或设置 FREE_BUFF_JDK=<JDK17 路径>。" >&2
    echo "   见 docs/build-and-release.md 第 1 节。" >&2
    exit 1
  fi
fi

JDK_ABS="$(cd "$JDK" && pwd)"
# 传给 .bat 的 JAVA_HOME 必须是 Windows 形式路径; 在 Git Bash 下用 pwd -W 转换。
JDK_FOR_ENV="$(cd "$JDK" && pwd -W 2>/dev/null || echo "$JDK_ABS")"
export JAVA_HOME="$JDK_FOR_ENV"

GRADLE="${FREE_BUFF_GRADLE:-}"
if [ -z "$GRADLE" ]; then
  if [ -f ".toolchain/gradle-8.9/bin/gradle.bat" ]; then
    GRADLE=".toolchain/gradle-8.9/bin/gradle.bat"
  else
    GRADLE="./gradlew"
  fi
fi

echo "== JAVA_HOME=$JAVA_HOME"
echo "== gradle   =$GRADLE"
exec "$GRADLE" -Dorg.gradle.java.home="$JDK_FOR_ENV" "$@"
