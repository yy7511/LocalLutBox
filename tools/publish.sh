#!/usr/bin/env bash
# Commits the whole project and pushes it to the public GitHub repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OWNER=${OWNER:-yy7511}
REPO=${REPO:-LocalLutBox}
URL="https://github.com/$OWNER/$REPO.git"

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

git add -A
if git diff --cached --quiet; then
  echo "publish: nothing to commit"
else
  git commit -q -m "LocalLutBox 1.1.0: package io.github.yy7511.locallutbox, R8 obfuscation, encrypted LUT cache, GPLv3"
fi
git push -q -u origin main
echo "publish: $OWNER/$REPO (public) -> $(git rev-parse --short HEAD)"
