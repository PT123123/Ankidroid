# Fork Identity (anki-plus)

> **⚠ Fork-specific.** Everything that makes this build a *separate app* rather than a
> rebranded copy of upstream AnkiDroid: application id, launcher label, release signing key
> and launcher icon palette. Read this before bumping `versionCode`, changing the icon, or
> wondering why an APK will not install over the Play Store one.

## What changed

| Aspect | Upstream | This fork |
|---|---|---|
| `applicationId` | `com.ichi2.anki` (+ `.debug`) | `com.pt123123.ankiplus` (+ `.debug`) |
| Launcher label (`app_name`) | `AnkiDroid` | `Anki Plus` |
| Release signing | upstream release key / `tools/fallback-release-keystore.jks` | `~/.android/debug.keystore` |
| Launcher icon | gray plate `#434748` + blue spark `#29B6F6→#0288D1` | emerald→navy plate `#0F4938→#0A2442` + mint→teal spark `#6EE7B7→#0D9488` |

`android.namespace` stays `com.ichi2.anki`, so **no Kotlin/Java package moved** — only the
application id differs. That is what lets the fork install next to the official app.

### 1. Application id and label

`AnkiDroid/build.gradle` → `defaultConfig`. The debug build type keeps its `.debug` suffix, so
`just build` produces `com.pt123123.ankiplus.debug`.

Everything package-derived already went through `${applicationId}` / `packageName` upstream, so
the change is self-contained:

- `<permission android:name="${applicationId}.permission.READ_WRITE_DATABASE">`
- `<provider android:authorities="${applicationId}.flashcards">` and `${applicationId}.apkgfileprovider`
- `AnkiActivity`, `MultimediaFragment`, `NoteEditorFragment`, … build file URIs from
  `packageName + ".apkgfileprovider"`

The `:api` library module is the one exception: it hardcodes
`<uses-permission android:name="com.ichi2.anki.permission.READ_WRITE_DATABASE"/>` because it is
published for third-party clients of the *official* app. `AnkiDroid/src/main/AndroidManifest.xml`
drops it again with `tools:node="remove"` — otherwise the fork would request a permission owned by
the upstream app.

**Consequence:** the content API of this build lives at `content://com.pt123123.ankiplus.flashcards`.
Clients written against the official id will not find it, and vice versa.

### 2. Release signing

`signingConfigs.release` keeps the `KEYSTOREPATH` / `KEYSTOREPWD` / `KEYALIAS` / `KEYPWD` env
override (CI), and the fallback now points at the local Android debug key:

```groovy
storeType = "PKCS12"
storeFile file("${homePath}/.android/debug.keystore")
storePassword "android"
keyAlias "androiddebugkey"
keyPassword "android"
```

Cert SHA-256 for the key on this machine:
`E0:BB:84:3A:91:92:A9:57:97:27:BE:09:08:35:40:A2:77:B1:5B:AC:96:7D:49:CF:26:A8:F3:0D:75:0A:64:00`.

- Release and debug APKs are signed with the **same** key, so `just install` and
  `just install-release` can replace each other without uninstalling.
- They cannot replace an APK signed by upstream (different id *and* different key anyway).
- If the keystore is ever regenerated (`rm ~/.android/debug.keystore` → AGP creates a new one),
  previously installed release builds must be uninstalled.

### 3. Launcher icon

Shape is upstream's; only the palette is forked.

- `res/drawable/ic_launcher_background.xml`: the flat `#434748` plate is now a vertical linear
  gradient `#FF0F4938` → `#FF0A2442` (the same endpoint the [theme gradients](../themecolor/README.md)
  use, matching the default Emerald theme).
- `res/drawable/ic_launcher_foreground.xml` reads `@color/anki_foreground_icon_color_0/1`, which are
  `resValue`s per build type: release `#FF6EE7B7` → `#FF0D9488`, debug stays the upstream red marker
  so a nightly build is still recognisable on the home screen.
- `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png` are the pre-Android-8 bitmaps (minSdk is 24,
  so API 24–25 still use them). `tools/launcher-icon-recolor.py` re-tints them from the upstream
  artwork: gray plate → background gradient, blue spark → spark gradient, white/black detail and the
  alpha channel untouched. Re-run it only after restoring the originals
  (`git checkout -- AnkiDroid/src/main/res/mipmap-*`), otherwise the second pass finds no blue pixels
  to map.

## Build

```sh
just build-release     # :AnkiDroid:assemblePlayRelease (R8 on, plus the universal APK)
just install-release   # adb install -r the arm64 APK
```

Outputs land in `AnkiDroid/build/outputs/apk/play/release/`:
`AnkiDroid-play-arm64-v8a-release.apk` and `AnkiDroid-play-universal-release.apk`. Note that
upstream overrides `versionCode` per ABI split (`322500203` for arm64 = `3 * 1e8 + 22500203`);
only the universal APK carries the plain `versionCode` from `defaultConfig`.

## Deliberately unchanged

In-app branding still says AnkiDroid: `drawable-nodpi/nav_drawer_logo*.png`,
`drawable-*/ankidroid_txt.png`, `drawable-*/logo_star_144dp.png`, the intro logo and the About
screen text. Swapping those means redrawing bitmaps, not just recolouring — open a separate
change if the drawer logo should match the new identity.
