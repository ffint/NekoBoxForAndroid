#!/usr/bin/env bash
set -euo pipefail

export BUILD_PLUGIN=none

echo "== NekoBox Enhanced JitPack QA =="
echo "commit: ${GIT_COMMIT:-unknown}"
echo "branch: ${GIT_BRANCH:-unknown}"

git submodule update --init --recursive
find buildScript libcore -type f -name '*.sh' -exec chmod +x {} +
chmod +x run gradlew

if ! command -v go >/dev/null 2>&1 || ! go version | grep -q "go1.23.6"; then
  GO_ROOT="${HOME}/.cache/nekobox-go1.23.6"
  if [ ! -x "${GO_ROOT}/bin/go" ]; then
    mkdir -p "${HOME}/.cache"
    curl -fsSL "https://go.dev/dl/go1.23.6.linux-amd64.tar.gz" -o /tmp/go.tar.gz
    rm -rf "${GO_ROOT}"
    mkdir -p "${GO_ROOT}"
    tar -xzf /tmp/go.tar.gz -C "${GO_ROOT}" --strip-components=1
  fi
  export PATH="${GO_ROOT}/bin:${PATH}"
fi

echo "Go: $(go version)"
echo "JitPack ANDROID_HOME=${ANDROID_HOME:-}"

BASE_ANDROID_HOME="${ANDROID_HOME}"
NDK_VERSION="25.0.8775105"
NDK_DIR="${BASE_ANDROID_HOME}/ndk/${NDK_VERSION}"
if [ ! -f "${NDK_DIR}/source.properties" ]; then
  EXISTING_NDK="$(
    find "${BASE_ANDROID_HOME}" -maxdepth 4 -type f -name source.properties 2>/dev/null |
      while read -r source; do
        if grep -q 'Pkg.Desc.*Android NDK' "${source}"; then
          dirname "${source}"
          break
        fi
      done
  )"
  if [ -n "${EXISTING_NDK}" ]; then
    NDK_DIR="${EXISTING_NDK}"
    echo "Using preinstalled NDK: ${NDK_DIR}"
  else
    NDK_CACHE="${HOME}/.cache/android-ndk-r25"
    if [ ! -f "${NDK_CACHE}/source.properties" ]; then
      mkdir -p "${HOME}/.cache"
      curl -fL --retry 3 \
        "https://dl.google.com/android/repository/android-ndk-r25-linux.zip" \
        -o /tmp/android-ndk-r25-linux.zip
      rm -rf "${NDK_CACHE}"
      unzip -q /tmp/android-ndk-r25-linux.zip -d "${HOME}/.cache"
      mv "${HOME}/.cache/android-ndk-r25" "${NDK_CACHE}"
    fi
    NDK_DIR="${NDK_CACHE}"
    echo "Downloaded NDK: ${NDK_DIR}"
  fi
fi

SDK_ROOT="${HOME}/.cache/nekobox-android-sdk"
CMDLINE_ROOT="${HOME}/.cache/nekobox-cmdline-tools"
SDKMANAGER="${CMDLINE_ROOT}/latest/bin/sdkmanager"
if [ ! -x "${SDKMANAGER}" ]; then
  CMDLINE_ZIP="/tmp/android-commandlinetools.zip"
  curl -fL --retry 3 \
    "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" \
    -o "${CMDLINE_ZIP}"
  echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  ${CMDLINE_ZIP}" |
    sha256sum -c -
  rm -rf "${CMDLINE_ROOT}"
  mkdir -p "${CMDLINE_ROOT}/latest"
  unzip -q "${CMDLINE_ZIP}" -d /tmp/android-commandlinetools
  mv /tmp/android-commandlinetools/cmdline-tools/* "${CMDLINE_ROOT}/latest/"
fi

mkdir -p "${SDK_ROOT}"
if [ ! -f "${SDK_ROOT}/platforms/android-35/android.jar" ] ||
   [ ! -x "${SDK_ROOT}/build-tools/35.0.1/aapt2" ]; then
  yes | "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --licenses >/dev/null 2>&1 || true
  "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" \
    "platforms;android-35" \
    "build-tools;35.0.1"
fi

export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"
export ANDROID_NDK_HOME="${NDK_DIR}"
export NDK="${NDK_DIR}"
echo "QA ANDROID_HOME=${ANDROID_HOME}"
printf 'sdk.dir=%s\nndk.dir=%s\n' "${ANDROID_HOME}" "${NDK_DIR}" > local.properties

./run init action gradle
./run lib core

./gradlew --no-daemon app:testPreviewReleaseUnitTest --stacktrace
./gradlew --no-daemon app:assemblePreviewRelease app:assemblePreviewDebug --stacktrace

APK="$(find app/build/outputs/apk -type f -name '*arm64-v8a*.apk' | grep -i preview | grep -i debug | head -n 1 || true)"
if [ -z "${APK}" ]; then
  APK="$(find app/build/outputs/apk -type f -name '*arm64-v8a*.apk' | grep -i debug | head -n 1 || true)"
fi
if [ -z "${APK}" ]; then
  echo "arm64 debug APK not found" >&2
  find app/build/outputs/apk -type f -name '*.apk' -print || true
  exit 3
fi

GROUP_PATH="${GROUP//.//}"
M2_DIR="${HOME}/.m2/repository/${GROUP_PATH}/${ARTIFACT}/${VERSION}"
mkdir -p "${M2_DIR}"
cp "${APK}" "${M2_DIR}/${ARTIFACT}-${VERSION}.apk"
cat > "${M2_DIR}/${ARTIFACT}-${VERSION}.pom" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP}</groupId>
  <artifactId>${ARTIFACT}</artifactId>
  <version>${VERSION}</version>
  <packaging>apk</packaging>
  <name>NekoBox Enhanced QA APK</name>
</project>
EOF

echo "QA APK: ${APK}"
sha256sum "${APK}"
