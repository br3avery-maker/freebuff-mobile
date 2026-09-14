#!/usr/bin/env bash
# 生成 Freebuff Mobile 的正式签名 keystore, 并写出 android/keystore.properties。
#
# 用法(在 android/ 目录下):
#   scripts/gen-release-keystore.sh [--force]
#
# 产出(两者都已被 .gitignore 忽略, 切勿提交):
#   android/freebuff-release.jks     ← 签名密钥, 必须离线备份, 丢失后无法对已发布应用做覆盖升级
#   android/keystore.properties      ← 口令, 供 app/build.gradle.kts 读取
#
# 生成后直接 `gradle :app:assembleRelease` 即会使用该 keystore 签名。
set -euo pipefail

KEYSTORE_FILE="freebuff-release.jks"
PROPS_FILE="keystore.properties"
ALIAS="freebuff"
VALIDITY_DAYS="10000"
KEYTOOL=".toolchain/jdk-17.0.20.1+1/bin/keytool.exe"
[ -x "$KEYTOOL" ] || KEYTOOL="keytool"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

cd "$(dirname "$0")/.."

if [ -f "$KEYSTORE_FILE" ] && [ "$FORCE" -ne 1 ]; then
  echo "!! $KEYSTORE_FILE 已存在。覆盖它会让已发布的同包名应用无法升级。" >&2
  echo "   确认要重建请加 --force, 并先备份旧文件。" >&2
  exit 1
fi

echo "== 生成 keystore: $KEYSTORE_FILE (RSA 2048, 有效期 ${VALIDITY_DAYS} 天) =="
read -r -p "  CN/姓名或组织 (例: Freebuff Mobile): " DNAME
DNAME=${DNAME:-Freebuff Mobile}

read -r -s -p "  keystore 口令 (>=6 位): " STORE_PASS; echo
read -r -s -p "  再次输入确认: " STORE_PASS2; echo
[ "$STORE_PASS" = "$STORE_PASS2" ] || { echo "!! 两次输入不一致" >&2; exit 1; }
[ ${#STORE_PASS} -ge 6 ] || { echo "!! keystore 口令至少 6 位" >&2; exit 1; }

read -r -s -p "  key 口令 (直接回车 = 与 keystore 口令相同): " KEY_PASS; echo
KEY_PASS=${KEY_PASS:-$STORE_PASS}

"$KEYTOOL" -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity "$VALIDITY_DAYS" \
  -dname "CN=$DNAME, OU=Mobile, O=Freebuff, L=, ST=, C=CN" \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS"

cat > "$PROPS_FILE" <<EOF
# 由 scripts/gen-release-keystore.sh 生成 —— 含口令, 已被 .gitignore 忽略, 切勿提交。
# storeFile 相对于 app/ 模块解析, 因此指向 android/$KEYSTORE_FILE。
storeFile=../$KEYSTORE_FILE
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF

echo
echo "== 完成 =="
echo "  keystore : android/$KEYSTORE_FILE"
echo "  口令文件 : android/$PROPS_FILE (chmod 600 可进一步收紧)"
echo
echo "接下来:"
echo "  1) 立即离线备份 $KEYSTORE_FILE 与口令 —— 丢失即无法升级已发布应用"
echo "  2) .toolchain/gradle-8.9/bin/gradle.bat :app:assembleRelease"
echo "  3) 校验签名: .toolchain/sdk/build-tools/35.0.0/apksigner.bat verify -v app/build/outputs/apk/release/app-release.apk"
