# Fork UI stack boundary (what can be unified, and what cannot)

> **⚠ Fork-specific.** This page exists because the fork was asked to "unify the UI on Compose, as
> long as native Anki data still renders". It records the measured split of the codebase, the layer
> where unification is impossible, and the convention the fork follows instead of a migration.
> Upstream's own direction lives in [compose.md](compose.md) — read that first; this page does not
> override it.

## Where the code actually is (measured 2026-10-28)

Counts are `:AnkiDroid/src/main` only, excluding `build/`:

| Surface | Count | What it means |
|---|---|---|
| Files declaring a `Fragment`/`DialogFragment` subclass | **99** | the app is still View-based |
| `res/layout/*.xml` | **202** | the screens' UI definitions |
| `res/xml/*.xml` | **25** | `PreferenceFragmentCompat` hierarchies (XML-only DSL) |
| Files containing `@Composable` | **8** | the fork's own pages (我的设置 etc.) |
| Files touching `RemoteViews` | **4** | app widgets — cannot be Compose without Glance |
| Whole repo, `.kt` / `.java` | 1437 / **0** | AnkiDroid is fully Kotlin; `java` is gone |

Re-measure (no build needed):

```sh
cd AnkiDroid/src/main
ls res/layout/*.xml | wc -l; ls res/xml/*.xml | wc -l
grep -rl "@Composable" --include="*.kt" . | wc -l
grep -rlE "class .*: .*(Fragment|DialogFragment)" --include="*.kt" . | wc -l
```

So "unify on Compose" is not a cleanup pass: it is a rewrite of roughly nine-tenths of the UI.

## The four layers, and which one is the wall

1. **`rslib` (Rust) — the data model, scheduler and sync.** Not in this repo. It arrives as the Maven
   artifact `io.github.david-allison:anki-android-backend` (version in
   [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml), `0.1.68-anki26.05`), i.e. a
   cross-compiled `arm64-v8a` `.so` plus protobuf/JNI bindings. **This is the layer Anki Desktop,
   AnkiMobile and this fork already share.**
2. **The Kotlin shell** — activities, fragments, view models, preferences, permission flows.
3. **The card surface is a WebView, not a widget tree.** `assets/card_template.html`,
   `flashcard.css`, `mathjax/`, `scripts/` — the note type's front/back templates are *user-authored
   HTML/CSS/JS*, executed by Chromium inside `AbstractFlashcardViewer` / `cardviewer/CardTemplate.kt`
   and driven by a JS bridge (`AnkiDroidJsAPI.kt`).
   [`scripts/ankidroid-cardviewer.js`](../../AnkiDroid/src/main/assets/scripts/ankidroid-cardviewer.js)
   line 7 points straight back at anki's `ts/reviewer/index.ts`, and Anki Desktop runs that same
   `ts/reviewer` bundle inside QtWebEngine. **The card is HTML on every platform, by design.**
4. **XML-forced surfaces.** Preference screens (`res/xml`, XML DSL only), app widgets
   (`RemoteViews`), and the theme/`attr` system the fork's theme-colour feature is built on — see
   [theming-modularization.md](theming-modularization.md).

**Consequence.** The thing the request wants protected — "native Anki data renders correctly" — lives
in layer 3, and layer 3 cannot be Compose. Re-rendering a user's template with Compose would mean
reimplementing the template engine plus MathJax plus the JS hooks, and from then on nothing
guarantees the fork's cards match what AnkiDesktop/AnkiMobile show for the same collection. That is
the one line this fork should not cross: it is a data-compatibility break dressed as a UI choice.

What *can* be unified is layer 2: a screen can be a Compose surface with the WebView embedded through
`AndroidView`, leaving the bridge untouched. That is the only form of "all Compose" that keeps
layer 3 intact.

## What a Compose rewrite does **not** buy: build cost

R8 and `lintVital` charge by the size of the program graph they have to analyse, not by which UI
idiom produced it. Replacing a Fragment with a `@Composable` is roughly a one-for-one swap of files
in that graph, and Compose adds a compiler-plugin pass (IR rewriting of every `@Composable` into
slot/group state) on top. The 202 layouts and 25 preference XMLs do not disappear either — aapt2
linking and lint's resource-reachability rules still run over whatever remains.

The measured wins this round came from removing the two whole-program analyses from the daily path,
which is orthogonal to the UI stack. See [fork-build-speed.md](fork-build-speed.md).

## Fork convention (what we do instead)

- **New screens default to Compose**, and the fork's own features (我的设置, home tabs) stay Compose.
  This matches upstream's direction in [compose.md](compose.md) — including what upstream explicitly
  refuses: no migration mandate, no roadmap, no "migrate screen X" PR without another reason to touch
  it, and no `:ui-compose` module yet.
- **Only hot screens get replaced**, and only as a shell: 牌组界面 / 卡片编辑器 / 复习页外壳. The
  reviewer keeps its WebView inside `AndroidView`; `AnkiDroidJsAPI` and `assets/scripts/*` are not
  touched by a UI slice.
- **Every UI slice deletes its own orphan resources in the same change.** `UnusedResources` is fatal
  under `lintVitalPlayRelease` in this fork (it blocked a beta before — see the 2026-09-28 entry in
  the project changelog, kept outside this repo), so a half-migrated screen leaves dead layouts behind
  and the release gate goes red.
- **Module boundaries are where the fork's own code should grow**, not because of UI style but because
  upstream is moving that way anyway (`:common:android`, `:api`, `:compat`, `:libanki` exist;
  `theming-modularization.md` plans `:feature:` modules). Fork-added code that does not need internal
  `:AnkiDroid` APIs belongs in its own module, where a rebase cannot conflict with it.
