# Anki-plus (AnkiDroid fork) build helpers.
# Run `just` for the recipe list.

set shell := ["bash", "-c"]

# gradle.properties ships -Xmx3072M, which OOMs on this fork's dex/resource merge.
GRADLE_JVMARGS := '-Xmx8g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'

ABI := "arm64-v8a"
APK := "AnkiDroid/build/outputs/apk/play/debug/AnkiDroid-play-" + ABI + "-debug.apk"

# List recipes
default:
    @just --list

# Build the playDebug APK
build:
    ./gradlew '-Dorg.gradle.jvmargs={{GRADLE_JVMARGS}}' --max-workers=4 :AnkiDroid:assemblePlayDebug

# adb-install the built playDebug APK onto the connected device
install:
    adb install -r -t {{APK}}

# Build then install
deploy: build install
