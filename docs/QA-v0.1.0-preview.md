# NekoBox Enhanced v0.1.0-preview QA Report

## Build identity

- Source commit: `3e73a80953e617308d2b3e1e796613502c62cb48`
- Enhanced release application ID: `moe.nb4a.enhanced`
- QA debug application ID: `moe.nb4a.enhanced.debug`
- Core: Matsuri sing-box `1.12.19-neko-1` at `aed32ee3066cdbc7d471e3e0415c5134088962df`

## Clean build results

JitPack clean-environment validation completed successfully:

- Native `libcore.aar`: built for armeabi-v7a, arm64-v8a, x86, and x86_64.
- `:app:testPreviewReleaseUnitTest`: PASS.
- `:app:assemblePreviewRelease`: PASS.
- `:app:assemblePreviewDebug`: PASS.
- Final JitPack commit status: `ok`.

## QA artifact

- File: `NekoBox-Enhanced-v0.1.0-preview-arm64-v8a.apk`
- Size: 23,651,361 bytes.
- SHA-256: `99e097bb69be3b9baa86ad234edefed5dc91ded60e8f1a4955196fab3f075ba1`.
- Archive integrity: PASS (`unzip -t`).
- Native ABI in this QA APK: arm64-v8a.
- Signature certificate: Android Debug, RSA with SHA-256.
- Artifact downloaded after publication matches the SHA-256 printed during the clean build.

## Scope note

This is an installable preview/QA artifact. The debug signing identity is not intended to become the permanent production update key. A production release should be signed with a dedicated Enhanced keystore that is kept outside the repository so future updates retain signature continuity.
