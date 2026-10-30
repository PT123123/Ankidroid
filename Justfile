# Anki-plus (AnkiDroid fork) build helpers.
#
# `just --list` shows only the LAST comment line above a recipe, so each block below ends with the
# one-liner you want in that list and keeps the detail above it.
#
# Heap, worker count and daemon idle timeout all live in gradle.properties. Do not re-specify them
# on the CLI: a different -Dorg.gradle.jvmargs starts a *second* daemon (cold JVM every time) and
# the two would fight for the same RAM. Measured timings: docs/development/fork-build-speed.md

set shell := ["powershell.exe", "-NoLogo", "-NoProfile", "-Command"]

ABI := "arm64-v8a"
APK := "AnkiDroid/build/outputs/apk/play/debug/AnkiDroid-play-" + ABI + "-debug.apk"
RELEASE_APK := "AnkiDroid/build/outputs/apk/play/release/AnkiDroid-play-" + ABI + "-release.apk"

# The no-argument build is the quick, non-release debug APK. Use `just --list` to see recipes.
default:
    @just build

# Compiles main + unit test sources, then runs the fork's two offline gates (NoOutboundReportingTest,
# BuildMemoryBudgetTest). No APK, no R8, no release lint.
# The cheapest "did I break anything".
verify:
    .\gradlew.bat -PabiFilter={{ABI}} :AnkiDroid:testPlayDebugUnitTest --tests "com.ichi2.anki.NoOutboundReportingTest" --tests "com.ichi2.anki.BuildMemoryBudgetTest"

# e.g. `just test DeckPickerTest`
test FILTER:
    .\gradlew.bat -PabiFilter={{ABI}} :AnkiDroid:testPlayDebugUnitTest --tests "com.ichi2.anki.{{FILTER}}"

# -PabiFilter trims splits to one ABI; the universal twin is off unless -Duniversal-apk=true
build:
    .\gradlew.bat -PabiFilter={{ABI}} :AnkiDroid:assemblePlayDebug

# -t because it is a debug build
install:
    adb install -r -t {{APK}}

# Build then install the debug APK
deploy: build install

# The fast local release APK. `-PskipMinify=true` is what makes it cheap: `minifyPlayReleaseWithR8`
# measured 3m 27s and a 3703 MB working set inside the Gradle daemon - the one reason that daemon
# has to carry a 4g heap - and `-PskipReleaseLint=true` keeps lintVital (5m 49s) out too. What is
# left is dexing + ~31s l8 desugaring + package (~3s).
# It has to be a Gradle property, not upstream's MINIFY_ENABLED env var: a build script's
# System.getenv reads the *daemon's* environment, frozen at the moment the daemon started, so
# setting it on one `.\gradlew.bat` call does nothing to a warm daemon. See AnkiDroid/build.gradle.
# The artifact is unminified and unshrunk - the debug arm64 APK here is 61,487,057 B against the
# minified release's 35,109,547 B, so budget ~+25 MB (estimate from that pair, not measured on a
# bare release). And it proves nothing about keep rules: an R8-only crash only appears after
# shrinking, so install the `minify-release` APK once before cutting a beta.
# PlayRelease with no R8 and no lint - for quick local builds only.
build-release:
    .\gradlew.bat -PabiFilter={{ABI}} -PskipMinify=true -PskipReleaseLint=true :AnkiDroid:assemblePlayRelease

# R8 runs here, lint still does not share the invocation (lintVital wants several GB more inside the
# same 4g heap; the two together got the daemon killed at 1m 51s). This is the binary users get.
# PlayRelease with R8, without the lint gate - run before anything leaves the machine.
minify-release:
    .\gradlew.bat -PabiFilter={{ABI}} -PskipReleaseLint=true :AnkiDroid:assemblePlayRelease

# Note the absence of -PskipReleaseLint: that property keeps the lintVital* tasks unregistered, so
# asking for them without it is the only way to make them exist.
# The release lint gate (UnusedResources is fatal) on its own - ~5m 49s, its own invocation, its own peak.
lint-release:
    .\gradlew.bat -PabiFilter={{ABI}} :AnkiDroid:lintVitalReportPlayRelease

# Minified APK, then the UnusedResources gate, as two serial peaks instead of one overlapping one.
release-check: minify-release lint-release

# Release slow: run R8 and the release lint gate in separate invocations before publishing.
release-slow: release-check

# no -t: it is not a test build
install-release:
    adb install -r {{RELEASE_APK}}

# Fast build then install the release APK (unminified - see build-release)
deploy-release: build-release install-release

# The daemon keeps its high-water mark after R8 because G1 does not uncommit, so it is still holding
# ~4g between builds. Killing it takes the Kotlin daemon with it (its client-alive flag file goes
# stale and it exits).
# .\gradlew.bat --stop - give the build RAM back now instead of in five minutes.
stop:
    .\gradlew.bat --stop

# Depends on `release-slow`, so a publish always rebuilds through R8 and clears lintVital - an
# unminified APK left over from `build-release` cannot be published by accident. `lanpub` resolves
# the repo path itself.
# Into the LAN F-Droid repo: build the minified APK, run the lint gate, then lanpub add.
publish: release-slow
    lanpub add {{RELEASE_APK}}

# Fast release: builds the unminified APK without R8 or release lint, then adds it to the configured LAN repository.
release: build-release
    lanpub add {{RELEASE_APK}}
