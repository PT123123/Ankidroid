# Home tabs (fork feature)

> **⚠ Fork-specific.** anki-plus addition. The deck picker is no longer the only screen on a
> phone: a bottom tab bar is the main screen's navigation, and the calendar heatmap lives in it.

## Why

The settings merge (see [my-settings](../my-settings/README.md)) emptied the toolbar down to the search icon, which left a single
list as the whole app. AnkiDroid already had a bottom navigation bar, but as an experimental
developer option (`devBottomNav`, off by default). This slice ships it instead of inventing a
parallel one.

## Decision

| Tab | `NavigationItem` | Content |
|-----|------------------|---------|
| 牌组 / Decks | `HOME` | the deck list, the FAB, pull-down sync (unchanged) |
| 浏览 / Browse | `BROWSER` | `CardBrowserFragment`, hosted in place, state kept across switches |
| 统计 / Statistics | `STATS` | `pages/Statistics` — a native Compose page (the WebView report is gone), whose first card is the **calendar heatmap**; its toolbar keeps the deck picker, only the back arrow is suppressed (`ARG_HIDE_BACK_BUTTON`) |
| 更多 / More | `MORE` | `MoreFragment`: Settings, Help, and the version |

- The bar replaces the navigation drawer on phones, exactly as upstream designed it: with it on,
  `DeckPicker` calls `disableDrawerSwipe()` / `disableDrawerIndicator()`, so nothing is listed
  twice. Tablets (`fragmented`) keep the drawer and never show the bar.
- The More page holds Settings, Help and the version. It used to also open the fork's own
  settings page; since the [settings merge](../my-settings/README.md) the fork's options live
  inside AnkiDroid's settings, so there is no separate entry.
- 更多 / 浏览 were English-only: `bottom_nav_more` / `bottom_nav_browse` sit in upstream's
  `12-dont-translate.xml` (they were untranslated while the feature was experimental). The fork
  overrides them in `values-zh-rCN/23-bottom-nav.xml` and `values-zh-rTW/…`. 牌组 and 统计 need no
  work: they come from the backend translations (`TR.actionsDecks()`, `TR.statisticsTitle()`).
- The More page's **帮助** section (Anki Manual / Get Help / Community / Privacy) was dropped: its
  four rows only linked to upstream Anki/AnkiDroid pages, so the section header went with them.
  **One row came back in their place** (`R.id.more_help` → `HelpDialog.newHelpInstance()`), because
  with the section gone the manual had no entry point at all and the privacy policy was reachable
  only from the AnkiWeb login screens. The dialog is upstream's own list, so the fork ships no new
  links and no new strings — it just stops hiding the ones the app already has. Upstream's
  `HelpDialogTest` already pins that the dialog contains 手册 and 隐私政策; the fork's
  `HomeBottomNavigationTest` adds that the More page can still *reach* it, so the row cannot be
  dropped again by a later layout edit.

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
- Tests: `HomeBottomNavigationTest` (on by default, four labelled tabs, More → Settings). The
  upstream bottom-nav tests set `devBottomNav` explicitly, so flipping the default did not change
  them.

## Not done here

The heatmap is the statistics *report*, not a home-screen card. A summary strip on the deck list
(today's reviewed count, streak) would need its own collection query on the startup path and is
deliberately out of scope.
