#!/usr/bin/env bash
# Android 一键发版：bump 版本 → 同步 app_update.json → 构建签名包 → 上传服务器 → md5 校验
#
# 用法（在 Git Bash 中执行）:
#   ./release.sh              # versionName 自动 +0.0.1，versionCode 自动 +1
#   ./release.sh 1.2.0        # 指定 versionName，versionCode 仍自动 +1
#   ./release.sh --no-upload  # 只构建不上传（本地测试）
#   ./release.sh --same       # 不 bump 版本，仅重打覆盖上传（配合 App 内五连击强更测试）
#
# 依赖: sed/python（改版本）、Android SDK（local.properties）、SSH 免密到 $SERVER

set -euo pipefail
cd "$(dirname "$0")"

SERVER="${SERVER:-root@777713.xyz}"
REMOTE_DIR="${REMOTE_DIR:-/home/hgej_update}"
JDK17="${JDK17:-$HOME/.sdkman/candidates/java/17.0.20.1-tem}"
GRADLE_FILE="app/build.gradle"
JSON_FILE="server/app_update.json"

MODE_UPLOAD=1; MODE_BUMP=1
case "${1:-}" in
  --no-upload) MODE_UPLOAD=0 ;;
  --same)      MODE_BUMP=0 ;;
  "") ;;
  *)  NEW_VER="$1" ;;
esac

CUR_CODE=$(grep -oE 'versionCode [0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+')
CUR_NAME=$(grep -oE 'versionName "[^"]+"' "$GRADLE_FILE" | cut -d'"' -f2)

if [ "$MODE_BUMP" = 1 ]; then
  NEW_CODE=$((CUR_CODE + 1))
  NEW_VER="${NEW_VER:-$(echo "$CUR_NAME" | awk -F. -v c="$((`echo "$CUR_NAME" | awk -F. '{print $3}'` + 1))" '{print $1"."$2"."c}')}"
  sed -i "s/versionCode $CUR_CODE/versionCode $NEW_CODE/; s/versionName \"$CUR_NAME\"/versionName \"$NEW_VER\"/" "$GRADLE_FILE"
  echo "== 版本: $CUR_NAME($CUR_CODE) → $NEW_VER($NEW_CODE)"
else
  NEW_CODE=$CUR_CODE; NEW_VER=$CUR_NAME
  echo "== 版本不变: $NEW_VER($NEW_CODE)，仅重打覆盖"
fi

echo "== 同步 app_update.json"
python - "$NEW_CODE" "$NEW_VER" "$JSON_FILE" <<'PYEOF'
import json, sys
code, ver, path = int(sys.argv[1]), sys.argv[2], sys.argv[3]
with open(path, encoding="utf-8") as f: d = json.load(f)
d["versionCode"], d["versionName"] = code, ver
d["downloadUrl"] = f"https://777713.xyz/update/杭工助手-v{ver}-release.apk"
with open(path, "w", encoding="utf-8") as f:
    json.dump(d, f, ensure_ascii=False, indent=2); f.write("\n")
print("  changelog:", (d.get("changelog") or "")[:40].replace("\n", " / "), "...")
PYEOF
echo "  （发布前记得编辑 $JSON_FILE 更新 changelog！按回车继续，Ctrl+C 取消）"
read -r

echo "== 构建签名包"
export JAVA_HOME="$JDK17"
./gradlew assembleRelease --console=plain | tail -3

APK="app/build/outputs/apk/release/杭工助手-v${NEW_VER}-release.apk"
LOCAL_MD5=$(md5sum "$APK" | cut -d' ' -f1)
echo "== 本地包: $APK  md5=$LOCAL_MD5"

if [ "$MODE_UPLOAD" = 1 ]; then
  echo "== 上传到 $SERVER:$REMOTE_DIR"
  scp -q -o BatchMode=yes "$APK" "$JSON_FILE" "$SERVER:$REMOTE_DIR/"
  REMOTE_MD5=$(ssh -o BatchMode=yes "$SERVER" "md5sum '$REMOTE_DIR/杭工助手-v${NEW_VER}-release.apk'" | cut -d' ' -f1)
  if [ "$LOCAL_MD5" = "$REMOTE_MD5" ]; then
    echo "== md5 校验一致，发布完成: https://777713.xyz/update/app_update.json"
    echo "== 清理服务器旧包（保留当前版本）"
    ssh -o BatchMode=yes "$SERVER" "cd '$REMOTE_DIR' && for f in 杭工助手-*.apk; do [ \"\$f\" != '杭工助手-v${NEW_VER}-release.apk' ] && rm -f \"\$f\"; done; ls"
  else
    echo "!! md5 不一致: 本地=$LOCAL_MD5 远端=$REMOTE_MD5" >&2; exit 1
  fi
fi

echo "== 写入 git（changelog 确认无误后手动提交即可）"
git add -u . && git status --short | head -5
