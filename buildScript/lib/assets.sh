#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

get_latest_release() {
  local latest_url
  latest_url=$(curl -fLsS -o /dev/null -w '%{url_effective}' "https://github.com/$1/releases/latest")
  local version=${latest_url##*/}
  if [ -z "$version" ] || [ "$version" = "latest" ]; then
    echo "Failed to resolve latest release for $1" >&2
    return 1
  fi
  printf '%s' "$version"
}

####
VERSION_GEOIP=`get_latest_release "SagerNet/sing-geoip"`
echo VERSION_GEOIP=$VERSION_GEOIP
echo -n $VERSION_GEOIP > geoip.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db
xz -9 geoip.db

####
VERSION_GEOSITE=`get_latest_release "SagerNet/sing-geosite"`
echo VERSION_GEOSITE=$VERSION_GEOSITE
echo -n $VERSION_GEOSITE > geosite.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db
xz -9 geosite.db
