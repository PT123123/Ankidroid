# UI Polish (fork feature)

> **⚠ Fork-specific.** Visual pass on top of the [theme color](../themecolor/README.md) system:
> window transition animations, modern corner radii, soft elevation and translucent "glass" surfaces.
> Sections 1-4 are theme-layer only. Section 5 touches the reviewer footer (layouts + the drawables
> those layouts resolve), and section 6 changes the HTML handed to the card WebView.

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

### 5. Answer buttons (legacy reviewer footer)

The shipped reviewer is the legacy one (`Prefs.isNewStudyScreenEnabled` is off), so the footer is
`include_reviewer_answer_buttons.xml` + `EaseButton`, not the new `view_answer_area.xml`. It was
four flat rectangles: no radius, no gap, flush with the screen edge, and the flip bar reused the
"hard" drawable. Nine drawables encoded that per ease and per theme.

**The background is set at runtime**, from `AnswerButtons.getBackgroundColors()` →
`EaseButton.setup()` → `layout.setBackgroundResource()`, so editing a colour in XML does nothing
visible. The levers are the attrs `*ButtonRef` / `*ButtonRippleRef` (the ripple pair is chosen when
`animationEnabled()`), and the fills they read are the per-theme `*ButtonBackground` attrs.

Now seven files carry the whole ramp:

| File | Role |
|---|---|
| `footer_button_shape.xml`, `footer_button_show_answer_shape.xml` | `?attr/answerButtonBackground` (resp. `showAnswerButtonBackground`) + `answer_button_corner_radius` + the `?attr/widgetStroke*` rim from §3 |
| `footer_button_sheen.xml` | 270° `#22FFFFFF → transparent`, so the fill reads as the fork's gradient rather than flat paint |
| `footer_button_rounded.xml`, `footer_button_show_answer.xml` | layer-list: pressed / focused overlays over the shape, sheen only in the default state |
| `footer_button_rounded_ripple.xml`, `footer_button_show_answer_ripple.xml` | the same under a `<ripple>` with a rounded mask and `?attr/answerButtonRippleColor` |

Fifteen drawables went (`footer_button_{again,hard,good,easy}[_dark]`, `footer_button_ripple`,
`footer_button_all_{plain,eink,black}[_ripple]`) plus the unused `answerButtonTextColor` attr; the
four per-ease `*ButtonTextColor` attrs are what still sets label colour.

Colour and identity, per theme:

- **light**: `goodButtonBackground` green_500 → **green_800**, `easyButtonBackground`
  light_blue_500 → **light_blue_800**. White labels on the 500s measured 2.8:1 and 2.5:1; the 800s
  keep the hue and clear 4.5:1. Ripple `#33FFFFFF`.
- **plain**: all five fills pinned to `theme_plain_primary_dark` (#616161) — the grey that holds
  white labels at 4.5:1 — so plain stays monochrome instead of inheriting red/grey/green/blue.
- **black**: all four fills pinned to `theme_black_primary`; the fill stays true black and the
  tinted labels carry the ease identity.
- **eink**: white fills, black labels, 2dp black stroke, and its own `answerButtonRippleColor`
  `#33000000` because a white ripple on a white button is invisible.
- **Show-answer**: `@color/white` is hardcoded in the layout (as `view_answer_area.xml` does) —
  eink's `showAnswerButtonBackground` is black, so the theme's label attr would have been black on
  black.

Geometry, in `include_reviewer_answer_buttons.xml`: `ease_buttons` gets
`answer_button_vertical_gap` on top and `reviewer_side_margin` left/right, each ease gets
`answer_button_margin_horizontal`, and the flip bar spans `answer_button_outer_margin` (11dp =
side margin + horizontal margin) so its two ends line up with the outer edges of the ease row
rather than with the buttons' inner gaps. `layout_marginHorizontal` is API 26 and minSdk is 24, so
it is `marginStart` + `marginEnd`.

### 6. Stock note types follow the theme

An unrestyled note type ships this at the top of the card HTML, and it is *content*, not theme:

```css
.card { font-family: arial; font-size: 20px; line-height: 1.5; text-align: center;
        color: black; background-color: white; }
```

`.card` matches the `<body class="card card1 …">` element, so as a class selector it outranks the
app's `BODY {font-family: 'OpenSans'}` injection (0,0,1) and the `background-size: cover` gradient
setup in `flashcard.css`: every untouched deck rendered as an opaque white Arial slab, whatever
theme colour was picked. (`color: black` was already losing at night — `body.night_mode` is (0,1,1).)

`cardviewer/StockCardCss.kt` rewrites that block inside `AndroidCardRenderContext.renderCard()` —
it sits on the rendered field text, which is why the matcher accepts `.card` after the start of the
sheet, after `}` or after the `>` of its `<style>` tag. `font-size`, `line-height` and `text-align`
are kept; the three the theme owns are dropped. The rewrite fires **only** while every declaration
in the block is verbatim stock, so changing one value (`font-size: 24px`, a serif font), adding a
property, or writing a selector which is not exactly `.card` (`.card1`, `.card, .night_mode`,
`html .card`) all pass through byte for byte.

Measured in a headless Chromium against the real `flashcard.css`, with the theme gradient behind
the transparent WebView:

| arm | body background | first font family | text colour |
|---|---|---|---|
| day, stock | `rgb(255,255,255)` — the slab | `arial` | `rgb(0,0,0)` |
| day, rethemed | `rgba(0,0,0,0)` — gradient shows | `OpenSans` | `rgb(0,0,0)` |
| night, stock | `rgb(48,48,48)` | `arial` | `rgb(255,255,255)` |
| night, rethemed | `rgb(48,48,48)` | `OpenSans` | `rgb(255,255,255)` |

So the day slab and the stuck font are the wins; night colours were already surviving, because
`body.night_mode` (0,1,1) outranks `.card` (0,1,0) — the font was the only thing wrong there.

The rewrite changes what the phone shows for stock note types versus desktop, so it is a switch:
我的设置 ▸ *Restyle card defaults* (`pref_stock_card_css_key`, on by default). Off = the stock
string reaches the WebView untouched.

## Keeping §5 and §6 honest

- `StockCardCssTest` pins the remap: current and legacy stock blocks rewritten, customised and
  grouped selectors untouched, the switch off leaving the HTML alone, and — as the control against
  these fixtures drifting — one case which renders a note through the backend bundled here.
- Any new ease-button drawable state must come back through `?attr/*ButtonRef`, not through a
  per-ease drawable — the runtime overwrite in `EaseButton.setup()` is the reason.
