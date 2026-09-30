# Fork build speed & memory (arm64-only, 5 locale configs)

> **⚠ Fork-specific.** Why `just build-release` costs what it costs on this machine, and which
> three knobs were turned to make it cheaper. Every number below is from a real run on this
> checkout (`./gradlew --profile` reports and log tails), not from upstream docs.

## The short answer

Incrementality is **not** the problem. Two consecutive `just build` runs:

| Run | Result |
|---|---|
| 1st, right after editing `build.gradle` (`resConfigs`) | `BUILD SUCCESSFUL in 2m 46s` — 188 tasks: 56 executed, 2 from cache, 130 up-to-date |
| 2nd, nothing changed | `BUILD SUCCESSFUL in 5s` — 179 tasks, **all** up-to-date, configuration cache entry reused |

`gradle.properties` already turns on `org.gradle.caching`, `org.gradle.parallel`,
`org.gradle.configuration-cache` and `configuration-cache.parallel`. The expensive thing is the
**first build after anything in the task graph changes** — and for a release that is dominated by
two tasks, not by packaging.

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
repo). For **daily** release builds `-x lintVitalPlayRelease` is a legitimate local shortcut; for
a publish it is not.

## The three knobs

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

## Heap

`gradle.properties` ships `org.gradle.jvmargs=-Xmx3072M`, which OOM'd on this fork while the build
was still merging 87 locale configs across 4 ABI splits + the universal twin. `Justfile` overrode
that with `-Xmx8g` (`-Dorg.gradle.jvmargs=…` on the CLI **replaces** the properties value, and a
different value means a different daemon, so expect one cold start after changing it).

Once the locale list was trimmed and one ABI was kept, `just build-release` passed at **`-Xmx4g`**
twice: once with Kotlin + R8 forced to re-execute (`BUILD SUCCESSFUL in 5m 56s`, `rc=0`), once as a
normal incremental release (4m 20s, 13 tasks executed). No OOM, no retry. `Justfile` now uses 4g;
if `merge*Resources` or dexing OOMs again, raise it back — that is a memory ceiling problem, not a
reason to disable the build cache.

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
| `just build-release` at `-Xmx8g` | 4m 19s |
| `just build-release` at `-Xmx4g`, Kotlin + R8 forced | 5m 56s |
| `just build-release` at `-Xmx4g` (current recipe) | 4m 20s |

## How to re-measure

```sh
./gradlew --profile --max-workers=4 -PabiFilter=arm64-v8a :AnkiDroid:assemblePlayRelease
# per-task table lands in build/reports/profile/profile-<date>.html
```

This Gradle (9.7.1) has no `--rerun` for tasks. To force an expensive task to actually run again,
touch one of its inputs and restore it afterwards, e.g. append a newline to
`AnkiDroid/src/main/java/com/ichi2/anki/IntentHandler.kt`, build, then `git checkout --` it.
