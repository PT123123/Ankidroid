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

CLI builds on this fork need a larger Gradle heap than `gradle.properties` sets (3g), so the
[`Justfile`](../../Justfile) at the repository root wraps the invocation:

```bash
just build    # ./gradlew -Dorg.gradle.jvmargs=-Xmx8g :AnkiDroid:assemblePlayDebug
just install  # adb install -r -t the arm64-v8a playDebug APK
just deploy   # build + install
```

Override the ABI with `just ABI=x86_64 install`.
