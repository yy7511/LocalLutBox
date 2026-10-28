#!/usr/bin/env bash
# Commits the source to main and uploads the APK to the GitHub Release for its version.
# Usage: tools/publish.sh [path/to/LocalLutBox-x.y.z.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OWNER=${OWNER:-yy7511}
REPO=${REPO:-LocalLutBox}
URL="https://github.com/$OWNER/$REPO.git"
APK=${1:-}

if ! gh auth status >/dev/null 2>&1; then
  echo "publish: skipped (gh not logged in)"
  exit 0
fi

cd "$ROOT"
if [ ! -d .git ]; then
  git init -q -b main
fi
git config user.name >/dev/null 2>&1 || git config user.name "$OWNER"
git config user.email >/dev/null 2>&1 || git config user.email "$OWNER@users.noreply.github.com"

if gh repo view "$OWNER/$REPO" >/dev/null 2>&1; then
  gh repo edit "$OWNER/$REPO" --visibility public --accept-visibility-change-consequences >/dev/null 2>&1 || true
else
  gh repo create "$OWNER/$REPO" --public \
    --description "Offline LUT box for Android / HarmonyOS 4.x: folder watcher, .cube/.xmp LUTs, histogram & vectorscope (GPLv3)" \
    >/dev/null
fi

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$URL"
else
  git remote add origin "$URL"
fi

# Source only: the APK is distributed through Releases, not the main tree.
git add -A
if git diff --cached --quiet; then
  echo "publish: no source changes"
else
  git commit -q -m "LocalLutBox ${VERSION_NAME:-update}: source update"
fi
# GitHub side edits (README, etc.) may have landed since the last build: rebase first.
git fetch -q origin main 2>/dev/null || true
if git rev-parse --verify -q origin/main >/dev/null; then
  if ! git merge-base --is-ancestor origin/main HEAD; then
    git pull --rebase -q --autostash origin main
  fi
fi
git push -q -u origin main

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "publish: no APK given, source pushed only"
  exit 0
fi

NAME="$(basename "$APK")"
VER="${NAME#LocalLutBox-}"
VER="${VER%.apk}"
TAG="v$VER"
NOTES=$(cat <<EOF
LocalLutBox $VER

- 完全离线的 LUT 调色：.cube / .xmp / .3dl 导入，多图编辑，直方图与矢量示波器
- 文件夹自动处理新增照片，按 ICC 自动转换到 LUT 目标色彩空间，输出统一 sRGB
- 可选：输出成功后删除输入文件夹中的原文件（默认关闭，开启前有确认提示）
- 输出保留原始 EXIF，默认原图尺寸 + JPEG 质量 100（不压缩）
- R8 混淆 + 资源名折叠，LUT 缓存 AES-256-GCM 加密
- 支持 Android 7.0+ 与鸿蒙 4.x；鸿蒙需在“应用启动管理”中允许后台活动

安装：下载 APK，允许“安装未知来源应用”即可，无需联网。
EOF
)

if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$APK" --clobber
  gh release edit "$TAG" --title "LocalLutBox $VER" --notes "$NOTES" >/dev/null
else
  gh release create "$TAG" "$APK" --title "LocalLutBox $VER" --notes "$NOTES" >/dev/null
fi
echo "publish: $OWNER/$REPO (public) main=$(git rev-parse --short HEAD) release=$TAG"
