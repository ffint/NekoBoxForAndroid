#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
PATCH_SING_BOX="$(pwd)/buildScript/lib/core/patches/sing-box-1.14-neko-routed-flow.patch"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git reset --hard "$COMMIT_SING_BOX"
if [ -s "$PATCH_SING_BOX" ]; then
  git apply --check "$PATCH_SING_BOX"
  git apply "$PATCH_SING_BOX"
fi
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git reset --hard "$COMMIT_LIBNEKO"
popd

####

popd
