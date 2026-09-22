# Theme Colors (fork feature)

> **⚠ Fork-specific.** This is an anki-plus addition on top of upstream AnkiDroid's
> theming system; upstream docs (see [theming-modularization](../development/theming-modularization.md))
> describe the base theme layers this feature plugs into.

## What it is

Settings → Appearance → **Theme color** (主题色) offers 10 accent choices. Each theme color:

- recolors primary/accent/surfaces/app bar/FAB/tabs/status bar across nearly every screen, and
- paints the window background with a **vertical gradient** (deep tone of the chosen color at
  the top → deep blue-teal `#0A2442` at the bottom). In night mode the gradient starts dark
  (e.g. emerald `#0F4938`); in day mode it fades from a pale tint to white.

The navigation drawer and the top app bar pick this up automatically (the drawer background is
`?android:attr/windowBackground`; most layouts use `?attr/appBarColor`).

Default theme color: **Emerald (翡翠绿)**.

## How it works

No layout or Activity code changes per color. Everything is a `ThemeOverlay` applied at
`setTheme` time:

1. **Preference** — `Prefs.themeColor` (`Prefs.kt`) is a `PrefEnum` (`enumPref`, key `themeColor`,
   default `EMERALD`). The enum is `settings/enums/ThemeColor.kt`; each entry carries its
   `entryResId` (string value from `constants.xml`) and an `overlayResId` style.
2. **Application** — `Themes.setTheme()` calls `applyThemeColor()`, which does
   `context.theme.applyStyle(overlayResId, true)` after the base day/night theme is set.
   `DayTheme.PLAIN` and `DayTheme.EINK` are **skipped** deliberately (accessibility/paper-white
   themes must keep their own colors).
3. **Overlay** — `res/values/theme_color_overlays.xml` defines
   `ThemeOverlay.App.ThemeColor.<Name>` per color, each overriding 14 attributes:
   `colorPrimary`, `colorAccent`, `colorSecondaryContainer`, `colorSurfaceContainer`,
   `colorSurfaceContainerHigh`, `preferenceCategoryTitleTextColor`, `fab_normal`, `fab_pressed`,
   `tabActiveIconColor`, `appBarColor`, `actionModeBackground`, `tabLayoutBackgroundColor`,
   `android:statusBarColor`, `android:windowBackground`.
4. **Colors** — `res/values/theme_colors.xml` (day) and `res/values-night/theme_colors.xml`
   (night) hold the `tc_<color>_*` color resources the overlays reference, so a single overlay
   resolves correctly in both modes via qualified resources.
5. **Gradient** — `res/drawable/theme_gradient_<color>.xml` is a linear shape
   (`android:angle="270"`, top→bottom) from `@color/tc_<color>_gradient_top` to
   `tc_<color>_gradient_bottom`, wired in as `android:windowBackground`.

Changing the value at runtime recreates the current Activity
(`AppearanceSettingsFragment.setOnPreferenceChangeListener` → `ActivityCompat.recreate`), which
re-runs `Themes.setTheme`.

## The 10 colors

| Enum       | zh-CN     | en        | Night gradient top | Night appbar |
|------------|-----------|-----------|--------------------|--------------|
| SKY_BLUE   | 天真蓝    | Sky Blue  | `#0E3A52`          | dark sky     |
| INDIGO     | 星海蓝    | Indigo    | `#1B2350`          | dark indigo  |
| TEAL       | 湖水青    | Teal      | `#0C3B38`          | dark teal    |
| EMERALD ★  | 翡翠绿    | Emerald   | `#0F4938`          | `#1F4A28`    |
| LIME       | 青柠绿    | Lime      | `#2E3A12`          | dark lime    |
| AMBER      | 鎏金棕    | Amber     | `#3F2E12`          | dark amber   |
| SUNSET     | 落日橙    | Sunset    | `#45250F`          | dark sunset  |
| ROSE       | 玫瑰红    | Rose      | `#43172B`          | dark rose    |
| LAVENDER   | 薰衣草紫  | Lavender  | `#241A45`          | dark lavender|
| GRAPHITE   | 石墨灰    | Graphite  | `#1C2B33`          | dark graphite|

★ default. All gradients end at `#0A2442` (bottom). Day gradients use a pale tint → `#FFFFFF`.
SKY_BLUE day values intentionally replicate upstream's original default blue so opting into it
restores the classic look.

Night app-bar colors are kept at low brightness in the same hue family so white toolbar text
stays readable; since the [UI polish pass](../ui-polish/README.md) they carry a `#E6` alpha so
the window gradient shows through the bar.

## Adding a new color

1. Add `tc_<name>_*` entries to **both** `values/theme_colors.xml` and `values-night/theme_colors.xml`.
2. Add a `ThemeOverlay.App.ThemeColor.<Name>` block to `values/theme_color_overlays.xml` overriding the 14 attributes.
3. Add `drawable/theme_gradient_<name>.xml` (copy an existing one, swap the two `@color/` refs).
4. Add the enum entry in `ThemeColor.kt`, the string value + entries/entryValues items in `constants.xml`, and labels in `11-arrays.xml` (en + zh-rCN).

## Related fork changes

- The navigation drawer (`res/menu/navigation_drawer.xml`) no longer shows **Help** or
  **Support AnkiDroid**; only Settings remains in that group.
