# Fork build speed & memory (arm64-only, 5 locale configs)

> **⚠ Fork-specific.** Why a playRelease costs what it costs on this machine, which recipe pays for
> which part, and which JVM actually holds the RAM. Every number below is from a real run on this
> checkout (`./gradlew --profile` reports, log tails, per-process queries), not from upstream docs.

## The short answer

Incrementality is **not** the problem. Two consecutive `just build` runs:

| Run | Result |
|---|---|
| 1st, right after editing `build.gradle` (`resConfigs`) | `BUILD SUCCESSFUL in 2m 46s` — 188 tasks: 56 executed, 2 from cache, 130 up-to-date |
| 2nd, nothing changed | `BUILD SUCCESSFUL in 5s` — 179 tasks, **all** up-to-date, configuration cache entry reused |

`gradle.properties` already turns on `org.gradle.caching`, `org.gradle.parallel`,
`org.gradle.configuration-cache` and `configuration-cache.parallel`. The expensive thing is the
**first build after anything in the task graph changes** — and for a release that is dominated by
two tasks, not by packaging. Those two tasks are also ~80 % of the memory: they are analysis (lint,
R8) running inside the Gradle daemon, which is why that daemon carries a 4g heap.

So the release path is split: `just build-release` skips both analyses (no R8, no lint) for quick
local installs; `just release-slow` runs them in two separate invocations and is what `just publish`
depends on. Bare `just` builds the non-release debug APK. See [The two release paths](#the-two-release-paths).

## Where a release build actually goes

`./gradlew --profile`, arm64-only playRelease, forced Kotlin + R8 re-execution
(`build/reports/profile/profile-2026-09-30-11-35-21.html`):

| Task | Time |
|---|---|
| `:AnkiDroid:lintVitalAnalyzePlayRelease` | **4m 24.59s** |
| `:AnkiDroid:minifyPlayReleaseWithR8` | **2m 51.69s** |
| `:AnkiDroid:l8DexDesugarLibPlayRelease` | 31.03s |
| `:AnkiDroid:compilePlayReleaseKotlin` | 21.50s |
| `:AnkiDroid:producePlayReleaseComposeMappingConfig` | 10.44s |
| `:AnkiDroid:expandPlayReleaseArtProfileWildcards` | 9.25s |
| `:AnkiDroid:processPlayReleaseResources` | 7.52s |
| `:AnkiDroid:packagePlayRelease` | 2.90s |
| (aggregate task time) | 9m 3.06s → **5m 56s wall** |

`lintVital` + R8 are ~80 % of the time; `packagePlayRelease` is under 3 s. So if a release feels
slow, the cost is analysis (lint, R8), not assembling the APK.

`lintVitalAnalyzePlayRelease` cannot just be switched off: the beta2 release was blocked by it
(`UnusedResources` is fatal there) and the fix was to clean the resources, not the gate — see the
2026-09-28 entry in the project changelog (`anki/docs/lan-sync/CHANGELOG.md`, kept outside this
repo). What was changed instead is **where** it runs: `lint.gradle` now reads
`checkReleaseBuilds = project.findProperty("skipReleaseLint") != "true"`, so the release recipes
leave it out of their own invocation (R8 and lintVital would otherwise share one 4g heap, which the
machine cannot hold) and `just lint-release` runs the gate separately. `just publish` depends on
`just release-slow`, which is R8 then lint in that order, so the normal publish path cannot skip the gate.

Passing `-x lintVitalPlayRelease` is not the mechanism: with `checkReleaseBuilds=false` the
`lintVital*` tasks are never registered, so there is nothing to exclude by name.

## The two release paths

Those two tasks are ~80 % of a release build and they are both **analysis**, not packaging — so the
fork now has a recipe per intent instead of one recipe for everything:

| Recipe | Runs | What it is for |
|---|---|---|
| `just build-release` | dex + `l8DexDesugarLibPlayRelease` (~31 s) + `packagePlayRelease` (~3 s) | install on a device today. `-PskipMinify=true -PskipReleaseLint=true`: no R8, no lint. |
| `just minify-release` | the above **plus** `minifyPlayReleaseWithR8` (2m 52s, 3703 MB in the daemon) | the artifact a publish needs, without the lint gate |
| `just lint-release` | `lintVitalReportPlayRelease` (5m 49s alone) | the `UnusedResources` gate, in its own JVM lifetime |
| `just release-check` / `just release-slow` | `minify-release` then `lint-release` | what a beta is cut from |
| `just publish` | `release-slow` then `lanpub add` | the LAN F-Droid repo |
| `just release` | `build-release` then `lanpub add` | fast unminified APK; skips R8 and release lint |

`-PskipMinify=true` exists because upstream's switch cannot do this job. `AnkiDroid/build.gradle`
read `MINIFY_ENABLED` through `System.getenv`, and a build script's `System.getenv` returns the
**daemon's** environment — frozen at whatever the daemon was started with — so an env var set on one
`./gradlew` invocation is silently ignored the moment the daemon is warm. The new line keeps the env
var (same meaning) and ORs it with a Gradle property, which is a configuration-cache input read fresh
per invocation — the same mechanism `-PskipReleaseLint` uses.

`tools/release.sh:170` still sets `MINIFY_ENABLED=false` for `assembleFullRelease`, and it is worth
reading how it does that: the line immediately before is `./gradlew --stop`. Upstream kills the
daemon so the variable is in the *new* daemon's environment — that is the confirmation that this
switch cannot be set per invocation. It also builds a second, deliberately unminified universal APK
(`-nominify`, "to help diagnose proguard issues"), which is a different job from the daily path here.
The same line keeps reading the env var, so that script still works; locally, prefer the property.
`-PskipMinify` also reaches `buildTypes.benchmark`, which does `initWith release`
(`AnkiDroid/build.gradle:216`) — do not pass it to a baseline-profile job.

**The fast path costs APK size and skips proving that R8 keep rules are correct.** The beta5 fast
APK built on 2026-10-28 was 50,200,079 B, versus the prior minified beta4 at 35,200,903 B. It was
manually added to the LAN repo at the user's request; the normal `just publish` path remains gated
by `release-slow` and cannot ship whatever `build-release` left on disk.

Keep the versions distinct when deliberately publishing a fast build. `lanpub add` keys on package
name + `versionCode` + content; forcing a different binary under an existing `versionCode` can leave
two builds for the same version, which clients may resolve unpredictably. Beta5 was bumped to
`versionCode 322500205`; beta4 remains `322500204`.

**What the fast path stops proving:** a keep-rule hole is an R8-only failure, so an APK built by
`just build-release` cannot catch it — the app runs fine unminified and dies after shrinking. Before
cutting a beta, install the `just minify-release` artifact once (`just install-release`) and cold
start it; `release-check` produces it but does not run it.

## The packaging knobs

(Three more knobs, for heap and for which task shares an invocation, are in
[Which JVM costs the memory](#which-jvm-costs-the-memory) below.)

### 1. `-PabiFilter=arm64-v8a` (no universal APK, no other ABIs)

`Justfile` passes this to every recipe. Without it `supportedAbis`
(`AnkiDroid/build.gradle`: `armeabi-v7a`, `x86`, `arm64-v8a`, `x86_64`) means the merge/dex/package
pipeline runs once per ABI — three extra times on top of arm64 — and `-Duniversal-apk=true` adds a
fourth, architecture-independent APK on top of those.

**Gotcha:** upstream overrides `versionCode` per ABI split
(`AnkiDroid/build.gradle`), so the arm64 APK reports `322500203` (`3 * 1e8 + 22500203`) while a
universal/unsplit APK carries the plain `22500203`. Do not compare those numbers across recipes.

### 2. `resConfigs` — 87 → 5 locale directories

```groovy
resConfigs "en", "zh", "zh-rCN", "zh-rTW", "ja"
```

**`resConfigs` matches the exact tag. `"zh"` does *not* include `values-zh-rCN` or
`values-zh-rTW`.** The first attempt (`"en", "zh", "ja"`) silently produced an APK in which
Chinese was gone: `aapt2 dump resources` showed 9 bare `(zh)` strings from a dependency and
**zero** of the app's own 1084 `zh-rCN` strings. Nothing failed to build — the only way to catch
it was to read the artifact back.

Verified after the fix (same command, on the built APK):

| Config | String count |
|---|---|
| `(zh-rCN)` | 1426 |
| `(zh-rTW)` | 1415 |
| `(ja)` | 1415 |
| `(en)` | 5 (defaults live in `values/`) |

Unlisted locales fall back to English, so a Korean device still runs the app, just untranslated.
Delete the line to ship all 87 translations again.

### 3. `local.properties` (gitignored, local-only)

```properties
enable_coverage=false
enable_leak_canary=false
```

Upstream reads these from the **debug** build type only, and the branch it takes when the file is
*absent* turns JaCoCo **on** — every debug build then runs `jacocoPlayDebug`. Upstream's own
comment on that path calls it "slow, hung, and wasn't required".
`enable_languages` is deliberately left unset; the locale list lives in `build.gradle` and that
hook would only append `"en"` on top of it.

## Which JVM costs the memory

Attributed per process on 2026-10-28, one task at a time (`--rerun`), reading
`Win32_PerfRawData_PerfProc_Process` for working set and page-file commit:

| Phase (run alone) | Wall | Who grew | Peak |
|---|---|---|---|
| `:AnkiDroid:minifyPlayReleaseWithR8 --rerun` | 3m 27s | **the Gradle daemon** | 3703 MB working set / 3786 MB commit |
| `:AnkiDroid:compilePlayReleaseKotlin --rerun` (cold, 1144 files) | 3m 30s at `-Xmx4g` / 3m 16s at `-Xmx2g` | the Kotlin daemon | 2995 MB commit |
| `just lint-release` (`lintVitalReportPlayRelease`) | 5m 49s | the Gradle daemon again, plus a respawned Kotlin daemon at 2220 MB | survived |
| R8 + lintVital **in one invocation** | died at 1m 51s | both in the same 4g heap | `Gradle build daemon disappeared unexpectedly` |

Three things follow from that table, and they are the reason the knobs look the way they do:

1. **R8 is not a separate process.** AGP runs `minifyWithR8` thread-isolated *inside* the daemon, so
   `org.gradle.jvmargs` is simultaneously the coordination heap and the R8 heap. A release build
   therefore needs the full 4g and the line is load-bearing — do not lower it. A debug build never
   touches it, which is why `just deploy` is the daily path.
2. **The Kotlin daemon inherits whatever it is not told otherwise.** Left alone it reuses the
   daemon's `-Xmx4g -XX:MaxMetaspaceSize=1g` as a second private heap. `kotlin.daemon.jvmargs` bounds
   it at 2g + 512m metaspace, which cost nothing: the cold full recompile was *faster* at 2g (3m 16s)
   than at 4g (3m 30s). 2g is a ceiling, not an observed need, so watch for `Kotlin compiler daemon
   was shut down` on an unusually large module set.
3. **Nothing shrinks either JVM — they have to be killed.** G1 does not uncommit after the R8 spike,
   so the daemon sat at 3992 MB commit long after the task finished. `org.gradle.daemon.idletimeout
   =300000` is the only timer in play, and it releases both: the Kotlin daemon's liveness channel is
   the flag file behind `-Dkotlin.daemon.client.alive.path`, so when the Gradle daemon exits the
   Kotlin one logs `Dying` and follows it. `just stop` does the same immediately.

   What is *not* available is a Kotlin-side timeout. An earlier version of this file claimed
   `-Dkotlin.daemon.client.alive.timeout` — disassembling the Kotlin 2.3.21 jars says it does not
   exist (`CompilerSystemProperties` has `…alive.path` and nothing else with `alive`), and a `-D` the
   daemon never reads is silently inert, so `BuildMemoryBudgetTest` now fails on that key. The real
   self-shutdown knobs are `DaemonOptions.autoshutdownIdleSeconds` / `autoshutdownUnusedSeconds` /
   `autoshutdownMemoryThreshold`: all three default to 0, and each is guarded by an `ifeq` skip in
   `CompileServiceImpl`, so 0 = off. Passing them means `-Dkotlin.daemon.options=<key>=<n>`, whose
   exact quoting is **unverified** — it needs one real build before it belongs in
   `gradle.properties`. Until then, the 2g is held as long as the Gradle daemon is held.

### The machine is the actual constraint

At the moment a build starts this box already reports **61 GB of a 64 GB commit limit**, with
paged pool at 20.8 GB and **nonpaged pool at 12.8 GB** (normal is well under 1 GB) — i.e. ~3 GB of
headroom for the whole build, and ~1 GB of free physical RAM while the sum of all process working
sets is only 7.5 GB. That is why the failures look random and are not a Gradle heap setting:

* a test worker died in `mmap failed to map 1073741824 bytes` because 11 forks pre-committing `-Xms1g`
  asked for 11 GB of commit space in a 3 GB window;
* the daemon was **killed by the OS** (no `OutOfMemoryError` in the log) when R8 and lintVital
  overlapped.

So the Gradle-side answer is to stop asking for memory that is never used — `minHeapSize` 1g → 256m,
local test forks capped by `maxLocalTestForks` (see `testForkCount()` in the root
`build.gradle.kts`), `org.gradle.workers.max=2`, lint in its own invocation — and the machine-side
answer is whatever owns those 33 GB of kernel pool: a reboot clears it, and if it comes back within
hours the leak is in a driver (this box runs Windhawk/Flowtary/微信输入法 injectors and 火绒's
HipsDaemon, all of which sit in the nonpaged path). Neither of those is a build setting.

These budgets are asserted by `AnkiDroid/src/test/java/com/ichi2/anki/BuildMemoryBudgetTest.kt`
(runs inside `just verify`), so a future rebase that silently reintroduces a `-Dorg.gradle.jvmargs`
override, a `minHeapSize = "1g"`, or lint wired back into `assemblePlayRelease` turns the gate red.

## Size

| Build | arm64 playRelease APK |
|---|---|
| 87 locale configs | 41,555,007 B |
| 5 locale configs (correct tags) | **35,200,439 B** |

Read back out of the final APK rather than trusted from the build log
(`aapt2 dump badging` / `aapt2 dump resources`):

```
package: name='com.pt123123.ankiplus' versionCode='322500203' versionName='2.25.0beta3'
(zh-rCN) 1426 strings · (zh-rTW) 1415 · (ja) 1415 · (zh) 9 · no ko/fr/…
```

(The `(zh)` 9 are a dependency's bare-`zh` resources; the app's own Chinese lives in `zh-rCN` and
`zh-rTW`, which is exactly what the first, wrong `resConfigs` attempt dropped.)

## Measured runs

| Command | Wall time |
|---|---|
| `just build` cold (after `build.gradle` edit) | 2m 46s |
| `just build` warm (no changes) | 5s |
| ~~release with R8 **and** lint in-graph, `-Xmx8g`~~ | 4m 19s (before the JVM args were unified) |
| ~~release with R8, Kotlin + R8 forced, `-Xmx4g`~~ | 5m 56s |
| release with R8, `-Xmx4g`, lint in-graph | 4m 20s — and it **died at 1m 51s** once, at 61/64 GB commit |
| first `-Xmx4g` release after the config split (new daemon, config cache invalidated) | 8m 35s, 151 tasks executed, peak 7.26 GB total java RSS, 4 JVMs max |
| `just minify-release` warm (R8 in-graph, `-PskipReleaseLint=true`) | **7s** (210 tasks, 209 up-to-date) |
| `just lint-release` alone | 5m 49s |
| `:AnkiDroid:minifyPlayReleaseWithR8 --rerun` alone | 3m 27s |
| `just build-release` (new `-PskipMinify=true` path) | **not run yet** — the task table above says what it removes, not what it costs |

## What is left on the table

Things that were checked and deliberately not pulled, so the next person does not re-derive them:

* **AGP 9.4.0 knobs — keys *and* defaults read out of the bytecode.** The enum constants in
  `com/android/build/gradle/options/{Boolean,Integer,String}Option.class` (from
  `~/.gradle/caches/.../gradle-9.4.0.jar`, `javap -c` on the static initialiser) set these defaults:

  | Option | Class | Default | Stage |
  |---|---|---|---|
  | `android.r8.maxWorkers` | `IntegerOption` | **1** | Experimental |
  | `android.r8.threadPoolSize` | `IntegerOption` | `R8D8ThreadPoolBuildService.defaultThreadPoolSize()` | Experimental |
  | `android.experimental.runLintInProcess` | `BooleanOption` | **`true`** | Feature flag, Experimental |
  | `android.experimental.lint.heapSize` | **`StringOption`** | none | Experimental |
  | `android.experimental.lint.reservedMemoryPerTask` | **`StringOption`** | none | Experimental |
  | `android.dexingNumberOfBuckets` | `IntegerOption` | no constant default in the enum | Experimental |

  `defaultThreadPoolSize()` is `cores` when `Runtime.availableProcessors() ≤ 16`, else
  `min(48, 16 + round((cores − 16) / 2))` — **19 on this 22-core box**. Read the table as:
  R8's *worker* count is already at the minimum, so `android.r8.maxWorkers` has no headroom to give
  and only goes up (more concurrent workers = more peak in the same 4g). The lever that points the
  other way is `threadPoolSize`: lowering it is the memory-for-wall-time trade, inference from what
  a thread pool does, not a measured number here.
  And `lint.heapSize` is inert on its own — lint runs **inside** the daemon by default, so it only
  means something together with `android.experimental.runLintInProcess=false`, which is the one
  supported way to move that 5m49s peak into a separate JVM with its own (smaller) heap.
  None of these are in `gradle.properties`: each needs its own `--profile` run on the publish path
  before it is worth the risk of a slower or OOM'd release.
* **`android.enableR8.fullMode`** also still exists as a key. Lowering it is an output-affecting
  change (weaker shrinking → bigger APK), so it is not a build-flag decision but a release one.
* **Splitting `:AnkiDroid` into modules** — the only real fix for a 3m16s *cold* Kotlin recompile of
  1144 files, because modules compile in parallel and only changed ones rebuild. Rejected for a
  fork that rebases on upstream: every sync would conflict across the new module boundaries, and the
  cold path is not the daily path (warm `just build` is 5s).
* **Raising `minSdk` above 24** would shrink `coreLibraryDesugaringEnabled` work (`l8DexDesugarLibPlayRelease`,
  31s) since `java.time` is API 26. Rejected: that is an install-base decision, not a build one.
* **Moving R8 out of the daemon** is not available — AGP runs `minifyWithR8` in-process and the option
  registry has no in-process toggle for R8 (it does for lint, `android.experimental.runLintInProcess`).

## How to re-measure

```sh
# the daily path: what is left once R8 and lint are out
just build-release

# per-task table lands in build/reports/profile/profile-<date>.html
./gradlew --profile -PabiFilter=arm64-v8a -PskipReleaseLint=true :AnkiDroid:assemblePlayRelease

# one expensive task, forced to actually run again (Gradle 9.7.1 does support per-task --rerun)
./gradlew -PabiFilter=arm64-v8a -PskipReleaseLint=true :AnkiDroid:minifyPlayReleaseWithR8 --rerun
```

`skipMinify` / `skipReleaseLint` are configuration-cache inputs, so alternating between the two
release paths invalidates that entry and the next run pays configuration again — measure each path
with its own steady-state second run.

To attribute memory per JVM, poll `Win32_PerfRawData_PerfProc_Process` (`PageFileBytes` is the real
commit figure; `Get-Process.PrivateMemorySize64` is only the *private working set* and will
understate a JVM that reserved 4g) and classify by command line:

```powershell
Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'java*' } | ForEach-Object {
  $r = if ($_.CommandLine -match 'GradleDaemon') { 'gradle-daemon' }
       elseif ($_.CommandLine -match 'KotlinCompileDaemon') { 'kotlin-daemon' }
       elseif ($_.CommandLine -match 'GradleWorkerMain') { 'worker' } else { 'other' }
  '  {0,-14} -Xmx {1}' -f $r, ([regex]::Match($_.CommandLine, '-Xmx\S+')).Value
}
```

Two harness traps this measurement walked into, so that the next person does not: filtering
`Win32_PerfRawData_PerfProc_Process` with `Name='java'` silently drops the second and later JVMs
(`java#1`, `java#2`) — use `Name LIKE 'java%'`. And `Win32_OperatingSystem.FreePhysicalMemory` is in
KB, so dividing by `1MB` gives GB, not MB; on Windows "free" is mostly file cache and reading it as
a budget pins a healthy 32 GB machine to one test fork. The budget numbers in this doc are from the
one-shot queries above, not from the sampler that made those two mistakes.
