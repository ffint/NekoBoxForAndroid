#!/usr/bin/env bash
set -euo pipefail

export BUILD_PLUGIN=none

echo "== NekoBox Enhanced JitPack QA =="
echo "commit: ${GIT_COMMIT:-unknown}"
echo "branch: ${GIT_BRANCH:-unknown}"

git submodule update --init --recursive

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
echo "ANDROID_HOME=${ANDROID_HOME:-}"

NDK_VERSION="25.0.8775105"
NDK_DIR="${ANDROID_HOME}/ndk/${NDK_VERSION}"
if [ ! -f "${NDK_DIR}/source.properties" ]; then
  SDKMANAGER="$(find "${ANDROID_HOME}" -type f -name sdkmanager 2>/dev/null | head -n 1 || true)"
  if [ -z "${SDKMANAGER}" ]; then
    echo "sdkmanager not found under ANDROID_HOME" >&2
    exit 2
  fi
  yes | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true
  "${SDKMANAGER}" "ndk;${NDK_VERSION}"
fi

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
