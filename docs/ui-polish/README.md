# UI Polish (fork feature)

> **⚠ Fork-specific.** Visual pass on top of the [theme color](../themecolor/README.md) system:
> window transition animations, modern corner radii, soft elevation and translucent "glass" surfaces.
> All of it is theme-layer only — no layout or Activity code changed per screen.

## What changed

### 1. Activity transition animations

`Animation.App.WindowTransitions` (`res/values/styles.xml`) is wired into
`android:windowAnimationStyle` on `Base.Theme.Light` / `Base.Theme.Dark` (black and plain
inherit it). A shared-axis feel: new screen slides in from the right (22%) with fade + a
0.97→1 scale; the outgoing screen recedes -8% and shrinks to 0.94; back navigation mirrors it.

- Anim files: `res/anim/window_open_enter|open_exit|close_enter|close_exit.xml`
  (280 ms, `fast_out_slow_in`).
- **E-Ink opts out**: `Base.Theme.Light.Eink` sets `windowAnimationStyle` to `@null`
  (paper-flat, no motion).

### 2. Corner radii (shape ramp)

Theme-wide `shapeAppearance*Component` overrides in both base themes, styles in
`styles.xml`: Small 12dp / Medium 16dp / Large 24dp (no `shapeAppearanceExtraLargeComponent`
attr exists in the current Material lib version, so 28dp extraLarge only lives on the Compose
side). Plus direct dimens (`res/values/dimens.xml`):

| Token | Before | After |
|---|---|---|
| `answer_button_corner_radius` | 12dp | 16dp |
| `dialog_corner_radius` | 8dp | 20dp |
| `popup_corner_radius` | 4dp | 12dp |
| `mtrl_snackbar_background_corner_radius` | 8dp | 14dp |

The Compose bridge (`compose/theme/Theme.kt`) passes a matching `AnkiDroidShapes`
(8/12/16/24/28dp) into `MaterialTheme`, so Compose screens (e.g. switch-profiles) match the
View screens.

### 3. Elevation + crisp strokes (层次)

In light/dark base themes (eink keeps its own 2dp black stroke, 0dp elevation):

- `studyScreenElevation`: 0.8dp → **2dp** (answer buttons, viewer card, whiteboard card).
- `widgetStrokeWidth`: 0dp → **1dp**; `widgetStrokeColor`: transparent →
  day `#14000000`, night `#26FFFFFF` — a thin glass rim on buttons, cards and popups
  (`bg_popup.xml` consumes the same attrs).

### 4. Translucent surfaces (晶莹剔透)

Everything sits on the theme-color window gradient, so alpha now lets it bleed through:

- `colorSurfaceContainer` alpha 0F→1A in base themes **and** in `tc_*_surface_container`
  (day + night `theme_colors.xml`).
- Night app bar / status bar: `tc_*_appbar` got `#E6` alpha (values-night only).
- Popups: day `popupBackgroundColor` → `@color/popup_background_theme_light` (`#F8FFFFFF`),
  night `popup_background_theme_dark` → `#F0434343` (attr is `format="reference"`, so the day
  value must be a color resource, not a literal).
- Progress dialogs: day `dialogBackground` → `#F2FFFFFF`, night → `#F0303030`.

## Things to keep in mind

- Contrast on the night app bar was re-checked at 90% alpha (E6); white toolbar text stays
  readable. If a future palette is very light, revisit.
- `ThemeOverlay.AnkiDroid.AlertDialog` still uses the Material3 library default 28dp radius —
  intentionally untouched.
- Transitions are window-level; per-widget motion (RecyclerView etc.) stays default.
