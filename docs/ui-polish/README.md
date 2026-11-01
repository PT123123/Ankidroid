# UI Polish (fork feature)

> **⚠ Fork-specific.** Visual pass on top of the [theme color](../themecolor/README.md) system:
> window transition animations, modern corner radii, soft elevation and translucent "glass" surfaces.
> Sections 1-4 are theme-layer only. Section 5 touches the reviewer footer (layouts + the drawables
> those layouts resolve), section 6 changes the HTML handed to the card WebView, section 7 makes
> the card face and the answer buttons switchable, section 8 brings the deck list and the card
> browser onto the same ramp, section 9 puts the study screen's chrome and its card on one
> surface, and section 10 gives the corners — hairlines, rules and grips — one themed weight.

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
Settings ▸ Appearance ▸ *Restyle card defaults* (`pref_stock_card_css_key`, on by default). Off = the stock
string reaches the WebView untouched.

### 7. Card styles (the card face and the answer buttons)

The study screen's answer half — the sheet the card sits on, its type, the gap around it, the rule
between question and answer, and the four ease buttons — is now one switchable thing with four
presets. The reference family is Material 3 (its shape ramp, its tonal elevation, its three button
families), not a bespoke skin: the presets differ in how much chrome they put around the content,
not in decoration.

| Preset | Card surface | Type | Ease buttons |
|---|---|---|---|
| **Classic** | none — the card fills the screen, as the desktop renders it | the note type's own | filled (saturated, light label) |
| **Modern** | a rounded sheet, 24dp corners, 1dp rim, 2dp shadow, 12dp gap | theme font, 12dp padding | tonal (tinted container, hue label) |
| **Paper** | a warm sheet, 8dp corners, 1dp rim, 1dp shadow, 20dp padding | serif headings | tonal |
| **Minimal** | none — no frame, no shadow | tight, 4dp padding | outlined (no fill, hue outline) |

The four are stored as `CardStylePreset` (`settings/enums/CardStylePreset.kt`), which is the value
in `R.string.card_style_*_value`; `Prefs.cardStylePreset` and the *Card style* list preference in
[Appearance](../my-settings/README.md) read the same store, so the chip in the card and the settings
page never disagree.

**Two halves, and each half has its own owner.**

- The *native* half — rim, corner radius, shadow, the gap between the screen edge and the sheet —
  is `CardStyle.applyTo()`, which puts a `GradientDrawable` on the WebView's background, sets
  `clipToOutline` (so the page is clipped to the rounded corners), puts the rim on `foreground`
  (drawn *above* the page, since a page cannot draw its own rim), sets `elevation` and the margins.
- The *page* half — padding, the serif headings, the rule between question and answer — is a class
  on the card's `<body>`: `ankiplus_modern`, `ankiplus_paper`, `ankiplus_minimal`.

The rules of **all four** styles are in one stylesheet (`CardStyle.stylesheet()`), injected by
`CardAppearance.style` into every card. That is what makes switching cheap: the styles are already
in the page, so a switch is one class swap and the answer that is on screen stays on screen.
`AbstractFlashcardViewer.applyPageStyle()` does that swap with `evaluateJavascript` —
`classList.remove('ankiplus_modern','ankiplus_paper','ankiplus_minimal')` then `classList.add(...)`
— instead of re-rendering the card, which would have thrown the answer away.

The rules are deliberately weak (a class selector, no `!important`) so the note type's own CSS,
which is injected *after* this block, still wins the ties.

**The page paints over the sheet.** The WebView's page has its own background, so the native
`GradientDrawable` is only seen where the card's background is transparent; what the eye reads as
the sheet's colour is the page's. That is why the paper preset carries its colour in the page
(`body.ankiplus_paper { background-color: … }`, resolved from `?attr/cardPaperColor`), and why the
non-sheet presets keep the viewer's historic near-transparent `Color.argb(1, 0, 0, 0)` background
rather than a white one — a white background behind an unstyled card would flash white on every
card change.

**The quick switch.** A 32dp chip in the card's top-end corner opens a bottom sheet with the four
presets, each with a one-line description of what it changes. It lives in `flashcard_overlay`, a
fourth `<merge>` child of `include_reviewer_flashcard.xml` declared *after* `whiteboard`, so it is
above `touch_layer`. The overlay itself is not clickable: a tap that misses the chip falls through
to the touch layer and flips the card, as it always did.

**The buttons follow the style immediately.** `Reviewer.applyAnswerButtonFamily()` re-colours the
four ease buttons from `AnswerButtonFamily` (FILLED / TONAL / OUTLINED). It is guarded on
`easeButtonsLayout?.visibility == View.VISIBLE`, so switching style while the *question* is on
screen does not make the answer buttons appear.

The colours come from four theme attrs — `cardSurfaceColor`, `cardSurfaceStrokeColor`,
`cardPaperColor`, `cardPaperStrokeColor` — so e-ink (white sheet, black rim) and the night themes
(dark sheet, translucent rim) get their own values without a code change.

### 8. The same language on the other screens

The deck list and the card browser were the two screens the card style sits next to, so they were
brought onto the same ramp.

- **Deck list** (`item_deck.xml`, `DeckAdapter.bindCount()`): the three counts became *pills* — a
  rounded 9dp container with the count tinted at 15% alpha over the count's own colour, the zero
  counts fading to the secondary text colour with no pill at all, so a deck with nothing due reads
  as empty instead of as three "0"s. The deck name dropped from 20sp bold to 18sp medium: the name
  was shouting over the counts, which are the numbers the user scans for.
- **Card browser** (`CardBrowserFragment.kt`, `view_browser_column_cell.xml`): the rows carried
  **two** divider decorations — the fork's themed one and an upstream `DividerItemDecoration` added
  a few lines later — so every row boundary was drawn twice. The upstream one is gone. The
  remaining hairline was `@color/white` in the night themes, a hard white line between rows; it is
  now a translucent `#26FFFFFF`, matching the rim the night themes use elsewhere. The cells' text
  was inset 4dp, which is tight for a list; it is 12dp, and the column headings were moved to the
  same 12dp so the header still lines up with the text under it.

  Note that `android:divider` / `android:dividerHeight` on the `RecyclerView` in
  `fragment_card_browser.xml` do nothing — those are `ListView` attributes, and `RecyclerView`
  ignores them silently. The decoration is the only thing that draws the line. Both dead
  attributes are now gone from the layout, along with `drawSelectorOnTop`, and the empty-deck
  placeholder's `TextAppearance.AppCompat.*` styles are M3 (`textAppearanceTitleMedium` /
  `textAppearanceBodyMedium`).

**The four tabs.** The home screen's four tabs did not share one chrome: decks and browse put the
theme colour on the app bar (`?attr/appBarColor`, translucent at night), while statistics and more
had a plain `colorSurface` bar — two chrome languages one tap apart. Statistics and more now carry
the same themed bar (`page_statistics.xml`, `fragment_more.xml`), their toolbars wearing
`ActionBarStyle` like `include_toolbar.xml`, so the title, the deck selector and the save-as-PDF
action all read on the colour. The more tab gained a version row on the way
(`more_version_title`, `VersionUtils.pkgVersionName`), and the bottom bar is finally wired to the
fork's tab tokens (`BottomNavigationViewStyle`: the selected indicator from
`tabLayoutBackgroundColor`, the active icon from `tabActiveIconColor` — the same treatment the
template editor's tab bar already had). The selected deck's highlight in the deck list left the
squared slab for the ramp's small step: a 12dp rounded shape over the same
`currentDeckBackgroundColor` (`item_background_selected.xml`). Over the theme gradient (the
default) it has since moved one step further up the ramp —
`deck_row_background_selected.xml` fills with `?attr/colorSurfaceContainerHigh`, the hue-tinted
step above the resting rows' glass — while the wallpaper path keeps the
`currentDeckBackgroundColor` highlight at 45 % alpha. The screens the tabs lead into
were carried along: the settings host (`fragment_settings.xml`), the review-reminder schedules
(`fragment_schedule_reminders.xml`) and the note-type manager (`activity_manage_note_types.xml`)
now paint the same themed bar — the collapsing ones setting `contentScrim` to it as well, so the
collapsed state does not fall back to M3's own surface. The More tab's toolbar also dropped its
`textAppearanceTitleLarge` override, so every tab title renders with the same toolbar default.

Follow-up on the same theme: the tabs' *content* shows the gradient too. The More tab no longer
paints an opaque `colorBackground` root over it, and the shared-decks downloader dropped its
opaque content slab, while the reminder-troubleshooting toolbar now wears the themed bar. One
deliberate exception: `fragment_schedule_reminders.xml` kept its opaque list background —
removing it was believed to break the status-bar inset behavior the review-reminders tests pin
(`ReviewRemindersInsetsTest`). §10 untangled it: the tests pin the insets, not the colour, so the
slab went. The deck list rows read as glass over the gradient instead of disappearing into it: a
12dp translucent `?attr/colorSurfaceContainer` fill (`deck_row_background.xml`), and the count
pills carry their hue at a strength that survives the dark background
(`DeckAdapter.COUNT_PILL_ALPHA`).

### 9. One surface for the study screen (the chrome and the card)

The study screen was a stack of bands: the themed app bar, the pale counts bar, the theme
gradient, and the card as a white rounded sheet floating on top of it. The chrome read as one
object and the card as another, and the two were only related by being stacked on the same
screen. The back arrow, the ★ mark and the ⋮ menu were on the app bar, and the card was
somewhere below it.

Now the card style's sheet **is** the study screen: it carries the chrome, the card and the
answer area, so the whole screen is one surface with one colour, one rim and one shadow.

**The surface.** `activity_reviewer.xml` gained a `study_surface` View, inside a
`study_surface_holder` FrameLayout which is the **first** child of `front_frame` — the first
child is the one drawn at the back, so the surface is behind the chrome and the card.

The holder is deliberately not the surface itself. A view with an elevation is drawn *above* its
siblings with a lower one (`ViewGroup.buildOrderedChildList()` sorts the children by `getZ()`,
which is the elevation), so a sheet that carries the shadow could not also stay behind the
chrome. The holder keeps the sheet out of that comparison — it is drawn first as a whole — and
it is bigger than the sheet, so the shadow has room to fall on the background instead of being
clipped at the sheet's edge.

**What the surface carries.** `CardStyle.applyTo(surface, webView, context)` puts the style's
fill on the surface's background, the rim on its `foreground` (a page cannot draw its own rim),
the shadow on its `elevation`, and the corner radii on the drawable. Only the **bottom** corners
are rounded (`setCornerRadii(0, 0, 0, 0, r, r, r, r)`): the top of the sheet runs off the top of
the screen, so there is no top corner to round. The WebView then becomes only the page: the
style's fill as its background, no rim of its own, no `clipToOutline` and no elevation — the card
is *inside* the sheet, not a sheet of its own. The viewer insets it by the rim's width, so that
the rim at the sheet's edge shows — and only when the style has a rim: Classic and Minimal have
none, so their page runs to the sheet's edge rather than keeping a 1dp sliver clear for nothing.
The whiteboard is inset the same way (`insetCardBand`), so the ink lands on the card instead of
offset from it by the gap.

**The chrome.** The toolbar's and the counts bar's own backgrounds are cleared at runtime
(`toolbar`, `top_bar`, and `toolbar_container` in the default layout) and inset by the style's
horizontal gap, so that the chrome and the card share the sheet's edges. They are cleared at
runtime rather than in the XML because `include_toolbar.xml` and `include_reviewer_topbar.xml`
are shared with `activity_reviewer_fullscreen*.xml`. There the toolbar is an overlay pulled over
the sheet when immersive mode is interrupted, so it keeps an opaque fill instead — the XML guesses
`?attr/cardSurfaceColor`, and `layoutStudySurface` repaints it with the style's own sheet colour
(`CardStyle.studySurfaceColour`), so the overlay reads as part of the sheet whichever style is on
it, Paper's paper included.
The chrome is inset in every layout: the fullscreen ones put the toolbar in a
`CoordinatorLayout`, so `insetChrome` takes any `MarginLayoutParams`.

**The icons.** The chrome used to sit on the app bar, where white icons were right. It now sits
on the surface, whose colour follows the theme, so the navigation icon, the overflow icon, the
toolbar title, the ★ in the counts bar and the toolbar's action icons are re-tinted with
`?attr/studySurfaceIconColor` (black in the day themes, white at night). The flag keeps its own
colour when the card carries one — the flag says *which* flag the card has, so it is not the
surface's business. `Reviewer.tintStudyChrome()` runs in `onCreateOptionsMenu` **and** in
`onPrepareOptionsMenu`, because the overflow icon is only installed when the menu is shown.

**Why the page does not clash with the sheet.** The WebView's page is painted over the sheet, so
the sheet's colour is only seen where the page is transparent — and where the page does paint, it
paints the same colour: by day the body is transparent (§6), at night
`body.ankidroid_dark_mode` is `#303030` and `body.night_mode` is black, which are exactly the
`cardSurfaceColor` of the night and black themes, and the Paper preset paints its body with
`?attr/cardPaperColor`, the same colour as its sheet.

**The status bar** follows the surface rather than the app bar: `SystemBarStyle.auto`'s lambda
was `currentTheme != DayTheme.EINK` and is now `Themes.isNightTheme`.

**The fallback.** `CardStyle.applyTo(null, webView, context)` — the web view carrying the sheet
itself, as §7 did it — stays as the fallback for any screen without a surface. The two fullscreen
layouts used to be that case; they now carry a surface of their own. In
`activity_reviewer_fullscreen_noanswers.xml` the holder is the first child of the **outer**
frame, not of `front_frame` — that frame is drawn *over* the card there, so a surface inside it
would cover the card. The fullscreen flashcard includes also gained the chip's overlay, which
they were missing (the viewer looks the overlay up unconditionally).

**The trade-off.** The themed app bar no longer paints the study screen: the screen *is* the
sheet, and the theme's gradient shows only around it — the 12dp slivers at the sides and the 8dp
gap at the bottom. The answer area was first left outside the sheet, which kept the screen
reading as two objects; the sheet now runs behind it as well, and the answer area's own
`showAnswerColor` band is gone with it — as is the `answerAreaDark` rule that decided the
navigation bar's icon colour from that band, the navigation bar now following the surface's
day/night like the status bar. Every other screen keeps its themed app bar, and the
experimental "new study screen" is untouched.

### 10. The corners (hairlines, rules and grips)

The edges of the interface were the last place where the fork spoke in literals. A hairline was
`#66888888` in one place, `#44888888` in another, `#dddddd` in a third — each one opaque, each one
a fixed grey in every theme, and each one a slightly different weight from the others. Nothing in
the theme said what a rule should look like; every screen spelled it out on its own.

Two tokens say it now.

- **`?attr/hairlineColor`** — the weight of a rule: 20 % black (`#CCCCCC`) in the day themes,
  20 % white (`#33FFFFFF`) in the night themes, black in E-ink. It is a sibling of
  `cardBrowserDivider`, which is the same `#CCCCCC` by day and a shade fainter (`#26FFFFFF`) at
  night — the browser's rows are denser than a settings list, so their rule can be lighter.
- **`?attr/dividerHandleColor`** — the grip on the divider between the two panes of the tablet
  layout. It has to read as something you can grab, so it is stronger than the hairline beside it:
  30 % black (`#4D4D4D`) by day, 70 % white (`#B3FFFFFF`) at night, black in E-ink.

**The contrast, measured.** The weights were computed, not eyeballed — WCAG relative luminance
against each theme's *own* background. The day themes' window background is the gradient, so the
day range runs from its tint to white; the night themes' are `#303030` (dark) and black (black).

| | day | night (dark) | night (black) |
| --- | --- | --- | --- |
| `hairlineColor` | 1.61:1 on white, 1.43:1 on the gradient's tint | 1.88:1 | 1.66:1 |
| `dividerHandleColor` | 8.45:1 | 7.33:1 | 10.02:1 |
| the drag line (`colorPrimary`) | 2.63:1 | 4.99:1 | 7.93:1 |

A rule is a decorative separator, not a control boundary, so it sits below the 3:1 that WCAG asks
of the latter. That is deliberate, and it is where Material 3 puts its own `outlineVariant`
(1.66:1 in the light theme). The grip is a control, and it clears 3:1 everywhere by a wide margin.
The drag line clears the fork's own border bar (≥ 2.2:1) in every theme.

What the fixed greys measured, for comparison: the old preference separator (`#66888888`) was
1.56:1 by day but 1.74:1 on black; the old pane-divider line (`#C7C7C7`) was 1.69:1 by day and
**7.81:1** at night; the old grip (`#4D4D4D`) was 8.45:1 by day and **1.56:1** at night. A fixed
grey does not keep its weight when the theme changes the page under it — that is the whole reason
these became tokens. The new pair keeps its weight in every theme, and the grip, which used to
disappear at night, is now the strongest thing about the divider.

**What moved onto them.**

| Was | Now | Where |
| --- | --- | --- |
| `#66888888` | `?attr/hairlineColor` | the separator between a preference and its switch (`preference_widget_switch_with_separator.xml`) |
| `#44888888` | `?attr/hairlineColor` | the 1.5dp stroke around a preference's text box (`bg_preference_widget_text.xml`) |
| `#dddddd` | `?attr/hairlineColor` | the 2dp rule under the intro screen's title (portrait and landscape) |
| `@color/divider_handle_color` | `?attr/dividerHandleColor` | the grip on the pane divider (`divider_handle_background.xml`) |
| `@color/idle_divider_color` | `?attr/hairlineColor` | the pane divider's idle line (`ResizingDivider.kt`) |
| `@color/drag_divider_color` | `?attr/colorPrimary` | the pane divider's drag line (`ResizablePaneManager.kt`) |
| `#CABBBBBB` | `#33000000` | the drop shadow of the audio-recording popup (`bg_rounded_drop_shadow.xml`) |
| `#CABBBBBB` | `?attr/hairlineColor` | the 1dp rim on that popup's sheet |
| `#898f9d` | `?android:attr/textColorSecondary` | the "slide to cancel" hint (`view_audio_record.xml`) |

Three of those are more than a recolour.

**The shadow now darkens.** `#CABBBBBB` was a *light* grey at 79 % alpha: a halo, not a shadow.
It was drawn one pixel down and right, so it read as a glow around a sheet that was lighter than
the page — fine on the day themes, and wrong at night, where it was the brightest thing on the
screen. The shadow is `#33000000` now: 20 % black, so it darkens whatever is behind it and stays
out of the way of the content. In the night themes it disappears — a drop shadow has nothing to
darken on a black page — and the rim (`?attr/hairlineColor`) carries the edge instead. That is
Material 3's own trade: elevation is tonal, not a shadow.

**The drag line follows the theme.** `@color/drag_divider_color` was `#1A90FF`, a blue that
matched no theme in the app; it is `?attr/colorPrimary` now, so the divider belongs to whichever
theme is running and its drag state is the accent the user chose. The numbers move with it: the
old blue measured 3.24:1 by day and 4.07:1 at night, the theme's primary 2.63:1 by day and
4.99:1 at night — both above the fork's 2.2:1 bar for a border, and neither of them the right
colour for the ten accent overlays, where the drag line used to stay blue while the whole app
was emerald.

**E-ink is the exception.** There, both tokens are solid black. That theme is deliberately flat —
"no motion, no shadows, flat paper look": its `colorSurfaceContainer` is a solid `#F0F0F0` rather
than a 10 % tint, and its own `cardBrowserDivider` is black too. A 20 % rule has no place on a
page that has no translucency in it.

**The three colours are gone.** `idle_divider_color`, `drag_divider_color` and
`divider_handle_color` were deleted from `values/colors.xml`: a colour nothing points at any more
is a fatal `UnusedResources` at the release gate.

**The reminders screen stopped being the odd one out.** §8 had left
`fragment_schedule_reminders.xml` with an opaque `?android:attr/colorBackground` list, because
removing it "silently breaks the status-bar inset behavior the review-reminders tests pin". That
was a misreading of what the tests pin: `ReviewRemindersInsetsTest` pins the *insets* — the
app bar's padding, the toolbar's position, the button's margin, the list's bottom padding — and
none of that depends on the list's colour. The list now wears `?attr/colorSurfaceContainer` — a
10 % accent tint in every theme but E-ink, which stays flat by design — the same translucent
surface the deck list's rows sit on, so the theme's own background shows through it instead of
being covered by an opaque slab; the app bar above it wears `?attr/appBarColor` with the
collapsing toolbar's scrim and both of its title colours following the theme, exactly as
`fragment_settings.xml` does. The screen is now themed like the home tab that leads to it.

**What is still a literal, and why.** The launcher widgets (`widget_small.xml`,
`widget_bg_*`) cannot use `?attr/*`: a `RemoteViews` is inflated by the launcher, which has no
theme of ours. The intro screen's coloured buttons keep their upstream accents — no accent in the
palette clears 4.5:1 against a white label, so tinting them would trade a contrast failure for a
cosmetic win, and the intro is upstream branding that
[`docs/fork-identity/README.md`](../fork-identity/README.md) lists as deliberately
unchanged. The flag colours in `bg_flag_*` are pictures of flags, not theme.

## Keeping §5, §6, §7, §9 and §10 honest

- `StockCardCssTest` pins the remap: current and legacy stock blocks rewritten, customised and
  grouped selectors untouched, the switch off leaving the HTML alone, and — as the control against
  these fixtures drifting — one case which renders a note through the backend bundled here.
- Any new ease-button drawable state must come back through `?attr/*ButtonRef`, not through a
  per-ease drawable — the runtime overwrite in `EaseButton.setup()` is the reason.
- A new card style needs **two** things, or the switch will show a half-applied style: a body
  class (so the page half applies) **and** the class in `CardStyle.bodyClasses` (so the swap can
  take the old one off). The stylesheet is written for all four styles at once, so a style added
  later must add its rules to `CardStyle.stylesheet()` as well.
- The body class is read for **every card** (`AndroidCardRenderContext` → `CardAppearance`), not
  only when the viewer is created. That is what lets the chip change the style of the card already
  on screen and of the next one alike.
- The sheet's fill and the page's background have to be the **same colour**, or the card shows a
  seam against the chrome (§9). A style whose page paints its own background — Paper does, with
  `?attr/cardPaperColor` — has to give its sheet that same colour, and the night themes'
  `cardSurfaceColor` has to stay equal to the page's `body.ankidroid_dark_mode` (#303030) and
  `body.night_mode` (black).
- The sheet is drawn by the **surface**, not by the web view: the web view keeps only the page.
  A new style that wants a different sheet changes `CardStyle`, not the viewer's layout.
- The page and the whiteboard are inset by the **rim's width** only when the style has a rim;
  a rimless style keeps its page and its ink at the sheet's edge. The fullscreen toolbar
  overlay's fill is repainted with `CardStyle.studySurfaceColour`, so a style with its own sheet
  colour needs no XML change for the pulled-down toolbar either.
- Two gates cover §9 rather than one screenshot each: `CardStyleSurfaceTest` runs every style
  against every theme **without** an activity — the sheet's fill and the page's background have
  to come out the same colour, a rim appears only on a style that declares one, the shadow sits
  on the sheet and never on the page, and `?attr/studySurfaceIconColor` has to clear 3:1 against
  the sheet it is drawn on. `StudySurfaceTest` then launches the real screen in each of the three
  study layouts and checks what only shows once views are placed: the holder is the first child
  of its parent (§9's z-order trap), the sheet and the page keep the style's gap and rim, every
  piece of chrome shares the sheet's left and right edges, the fullscreen overlay is painted the
  style's colour, and the chip is there to switch the style with.
- The chip is **one** file, `include_card_style_chip.xml`, included by all three flashcard
  containers. Three copies of the same `ImageButton` were how a layout ended up without the
  overlay the viewer looks up unconditionally; a new style attribute on the chip belongs in that
  file, not in a layout.
