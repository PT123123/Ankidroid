# Home tabs (fork feature)

> **⚠ Fork-specific.** anki-plus addition. The deck picker is no longer the only screen on a
> phone: a bottom tab bar is the main screen's navigation, and the calendar heatmap lives in it.

## Why

[我的设置](../my-settings/README.md) emptied the toolbar down to the search icon, which left a single
list as the whole app. AnkiDroid already had a bottom navigation bar, but as an experimental
developer option (`devBottomNav`, off by default). This slice ships it instead of inventing a
parallel one.

## Decision

| Tab | `NavigationItem` | Content |
|-----|------------------|---------|
| 牌组 / Decks | `HOME` | the deck list, the FAB, pull-down sync (unchanged) |
| 浏览 / Browse | `BROWSER` | `CardBrowserFragment`, hosted in place, state kept across switches |
| 统计 / Statistics | `STATS` | `pages/Statistics` — the WebView report, whose first graph is the **calendar heatmap**; its own toolbar keeps the deck picker and *Save as PDF*, only the back arrow is suppressed (`ARG_HIDE_BACK_BUTTON`) |
| 更多 / More | `MORE` | `MoreFragment`: **我的设置** (new), Settings, Help, Support |

- The bar replaces the navigation drawer on phones, exactly as upstream designed it: with it on,
  `DeckPicker` calls `disableDrawerSwipe()` / `disableDrawerIndicator()`, so nothing is listed
  twice. Tablets (`fragmented`) keep the drawer and never show the bar.
- Because the drawer stops being reachable on a phone, **我的设置 gets a row at the top of the
  More page** (`R.id.more_my_settings` → `PreferencesActivity.getIntent(this, MySettingsFragment::class)`).
  The screen itself did not move — see [my-settings](../my-settings/README.md).
- 更多 / 浏览 were English-only: `bottom_nav_more` / `bottom_nav_browse` sit in upstream's
  `12-dont-translate.xml` (they were untranslated while the feature was experimental). The fork
  overrides them in `values-zh-rCN/23-bottom-nav.xml` and `values-zh-rTW/…`. 牌组 and 统计 need no
  work: they come from the backend translations (`TR.actionsDecks()`, `TR.statisticsTitle()`).

## How it is switched on

One default, in two places, because the preference screen and the code each supply their own:

- `Prefs.devBottomNavEnabled` — `getBoolean(dev_bottom_nav_key, **true**)`
- `preferences_developer_options.xml` — the same key, `android:defaultValue="true"`

The toggle itself stays in developer options as an escape hatch; everything keyed off it
(Alt+1…4 shortcuts, the shortcut-help entries, `DeckHierarchyLinesDecoration`, the Reviewer's back
icon, the drawer being disabled) turns on with it, so there is no half-enabled state.

## Notes for later

- `BottomNavController` is dead code except for its nested `NavigationItem` enum, which is what
  `DeckPicker`, the layouts and the tests use; the live behaviour is the extension functions in
  `HomeScreenNavigation.kt`. Both were kept to avoid a merge conflict with upstream.
- Tabs are `show`/`hide`, not replace: the browser keeps its selection and the statistics page
  keeps its loaded WebView when switching back. The selected tab survives recreation and process
  death (`BottomNavigationRestoreTest`).
- Tests: `HomeBottomNavigationTest` (on by default, four labelled tabs, More → 我的设置). The
  upstream bottom-nav tests set `devBottomNav` explicitly, so flipping the default did not change
  them.

## Not done here

The heatmap is the statistics *report*, not a home-screen card. A summary strip on the deck list
(today's reviewed count, streak) would need its own collection query on the startup path and is
deliberately out of scope.
