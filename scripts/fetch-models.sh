#!/bin/sh
# 下载角色模型（不放进仓库，每个约 40～95 MB），放到 app/src/main/assets/live2d/models/。
# 三套衣服（校服、洋装、运动装），同一个角色，放在公开数据集 langhua1998/yuki-model（和 iPhone 版一样）。
set -e
cd "$(dirname "$0")/../app/src/main/assets/live2d"
mkdir -p models
BASE=https://huggingface.co/datasets/langhua1998/yuki-model/resolve/main
for f in Yuki_outfit1.glb Yuki_outfit2.glb Yuki_sporty.glb; do
  [ -s "models/$f" ] && continue
  curl -fL --retry 3 -o "models/$f" "$BASE/$f"
done
ls -l models
