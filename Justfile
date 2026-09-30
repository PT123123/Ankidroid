# Anki-plus (AnkiDroid fork) build helpers.
# Run `just` for the recipe list.

set shell := ["bash", "-c"]

# gradle.properties ships -Xmx3072M, which OOMed while this fork built 87 locale configs across
# every ABI. arm64-only + resConfigs ("en","zh","zh-rCN","zh-rTW","ja") passed a full release
# (Kotlin + R8 forced) at 4g; raise it back here if merge/dex OOMs again.
# Measured timings: docs/development/fork-build-speed.md
GRADLE_JVMARGS := '-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'

ABI := "arm64-v8a"
APK := "AnkiDroid/build/outputs/apk/play/debug/AnkiDroid-play-" + ABI + "-debug.apk"
RELEASE_APK := "AnkiDroid/build/outputs/apk/play/release/AnkiDroid-play-" + ABI + "-release.apk"

# List recipes
default:
    @just --list

# Build the playDebug APK (arm64 only: -PabiFilter trims splits to one ABI, and the universal
# twin is off unless -Duniversal-apk=true)
build:
    ./gradlew '-Dorg.gradle.jvmargs={{GRADLE_JVMARGS}}' --max-workers=4 -PabiFilter={{ABI}} :AnkiDroid:assemblePlayDebug

# adb-install the built playDebug APK onto the connected device
install:
    adb install -r -t {{APK}}

# Build then install
deploy: build install

# Build the playRelease APK (arm64 only, R8 + signed with ~/.android/debug.keystore).
# Pass -Duniversal-apk=true too when an all-arch APK is actually needed for a release.
build-release:
    ./gradlew '-Dorg.gradle.jvmargs={{GRADLE_JVMARGS}}' --max-workers=4 -PabiFilter={{ABI}} :AnkiDroid:assemblePlayRelease

# adb-install the built playRelease APK (no -t: it is not a test build)
install-release:
    adb install -r {{RELEASE_APK}}

# Build then install the release APK
deploy-release: build-release install-release
