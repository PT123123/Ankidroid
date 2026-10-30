# Gradle Daemon JVM & build toolchain

Toolchain standardization supports:

- Building old branches using the correct JVM.
- Standardizing contributors on a working JVM.
- Standardizing CLI/IDE Gradle usage.
- Handling automatic JVM downloads (via [`foojay-resolver-convention`](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention)).

## Default: [Gradle Daemon JVM Criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria)

Criteria are defined in [`gradle/gradle-daemon-jvm.properties`](../../gradle/gradle-daemon-jvm.properties).

These default values are recommended by Android Studio, which produces them via `./gradlew updateDaemonJvm`:

```properties
toolchainVendor=JETBRAINS
toolchainVersion=21
```

## Compiling and testing on a different JDK

To test compatibility with newer JDKs, the root `build.gradle.kts` reads `ANKIDROID_JVM`:

```bash
# as an alias
alias gradle25='./gradlew updateDaemonJvm --jvm-version=25 && ./gradlew'
gradle25 :AnkiDroid:testPlayDebugUnitTest
# revert when done
git checkout gradle/gradle-daemon-jvm.properties

# An environment variable can be added to existing scripts as a guard
# This breaks ./gradlew until a temporary updateDaemonJvm command is run
ANKIDROID_JVM=25 ./gradlew :AnkiDroid:testPlayDebugUnitTest
# example
alias jdk25='export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home && export PATH=$JAVA_HOME/bin:$PATH && export ANKIDROID_JVM=25'
```

To support a newer daemon JDK long-term, raise `jvmVersionUpperBound` in `build.gradle.kts`.

## just shortcuts

On this Windows workspace, `Justfile` runs recipes with PowerShell and calls `gradlew.bat`, so builds do not rely on WSL Bash.

The heap lives in `gradle.properties` and nowhere else. Passing `-Dorg.gradle.jvmargs=…` on the CLI
**replaces** that value, which spawns a second daemon (a different JVM per value) and leaves both
retaining their high-water marks — so the `Justfile` deliberately does not pass it any more:

```bash
just verify         # the offline gates only: no APK, no R8, no release lint
just test DeckPickerTest  # one unit-test class
just build          # ./gradlew -PabiFilter=arm64-v8a :AnkiDroid:assemblePlayDebug
just install        # adb install -r -t the arm64-v8a playDebug APK
just deploy         # build + install
just build-release  # fast playRelease with NO R8 (-PskipMinify) and NO lint - local only
just deploy-release # …then adb install it
just minify-release # the publish artifact: R8 runs, lint still does not share the invocation
just lint-release   # lintVital on its own (5m 49s)
just release-check  # minify-release then lint-release - what a beta is cut from
just release-slow   # named slow/verified release path
just publish        # release-slow, then lanpub add into the LAN F-Droid repo
just release        # fast unminified build-release, then lanpub add (skips R8 + release lint)
just stop           # hand the daemon + Kotlin daemon RAM back now
```

The 4g daemon heap exists for one task: `minifyPlayReleaseWithR8` puts 3703 MB inside the daemon.
Nothing in bare `just`, `just build`, `just verify` or `just build-release` needs it — see
[the two release paths](fork-build-speed.md#the-two-release-paths).

Budgets, the measured peaks behind them, and `kotlin.daemon.jvmargs` are in
[fork-build-speed.md](fork-build-speed.md); `BuildMemoryBudgetTest` fails the build if they drift.

Override the ABI with `just ABI=x86_64 install`.
