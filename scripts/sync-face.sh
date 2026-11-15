#!/bin/sh
# 把 3D 角色网页从 xiaoju-remote 仓库同步过来（那边的 Robot/live2d 是源头，这边只放编译好的文件）。
# 用法：sh scripts/sync-face.sh <xiaoju-remote 仓库的路径>
# 那边改了 Robot/live2d（并且重新 npm run build 了 yuki.js）以后跑一次，提交 app/src/main/assets/live2d 的变化。
# 模型（models/*.glb）很大，不进仓库，编译前用 scripts/fetch-models.sh 下载。
set -e
SRC="${1:?要给 xiaoju-remote 仓库的路径}/Robot/live2d"
DST="$(dirname "$0")/../app/src/main/assets/live2d"
[ -f "$SRC/yuki.js" ] || { echo "找不到 $SRC/yuki.js"; exit 1; }
mkdir -p "$DST"
find "$DST" -mindepth 1 -maxdepth 1 ! -name models -exec rm -rf {} +
cp "$SRC/index.html" "$SRC/yuki.js" "$SRC/bg.jpg" "$DST/"
cp -r "$SRC/bg" "$SRC/dances" "$DST/"
( cd "$SRC/../.." && git rev-parse --short HEAD 2>/dev/null ) > "$DST/FROM_XIAOJU_REMOTE" || true
echo "已同步：$(cat "$DST/FROM_XIAOJU_REMOTE" 2>/dev/null)"
ls "$DST"
