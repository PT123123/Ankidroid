# My settings + bare main screen (fork feature)

> **⚠ Fork-specific.** anki-plus addition. It moves the fork's own options out of AnkiDroid's
> stock settings list and strips the deck picker toolbar down to the search icon.

## Decision

Three surfaces changed together, on request:

| Surface | Before | After |
|---------|--------|---------|
| Deck picker toolbar | search + **Sync** icon + overflow (Check ▸ database/media/empty cards, Create backup, Restore from backup, Manage note types, Import, Export) | search only (+ the deck-scoped items Study Options adds back, which stay) |
| Settings list (`HeaderFragment`) | upstream categories **plus** Theme color (inside Appearance), Switch profile, LAN sync | upstream categories only |
| Navigation drawer | Settings | Settings + **我的设置 / My settings** |

On a phone the drawer has since been replaced by the [bottom tab bar](../home-tabs/README.md), which
is why 我的设置 also sits at the top of the *More* tab; on a tablet the drawer is still the entry.

**我的设置 (`MySettingsFragment`, `res/xml/preferences_my_settings.xml`)** now holds:

- *Added features*: Theme color, Switch profile, LAN sync (the latter two are
  `HeaderPreference`s pointing at their existing fragments, so those screens did not move).
- *Tools*: one entry per `DeckTool`, built in code from the enum rather than from XML.

## How the tools still run

The actions are DeckPicker behaviour (dialogs, `viewModel`, backup progress), so the settings
page does not re-implement them: it returns to the deck picker with an extra.

1. `MySettingsFragment` → `DeckPicker.getIntentForTool(context, tool)`
   (`CLEAR_TOP|SINGLE_TOP` + `EXTRA_DECK_TOOL` = enum name), then finishes itself so the settings
   page does not stay under the deck list.
2. `DeckPicker` stores it in `pendingDeckTool` (from `onCreate` for a cold start, `onNewIntent`
   for a warm one).
3. `refreshState()` — which only runs once the drawer is ready and collection storage is granted —
   consumes the field and calls `runDeckTool(tool)`, the single place where all nine actions are
   dispatched. It replaces the nine `onOptionsItemSelected` branches.

Sync is therefore still reachable three ways: 我的设置 ▸ Sync, the pull-down gesture
(`pullToSyncWrapper`), and whatever automatic sync the sync settings schedule.

**What the sync icon used to carry is gone**: the pending-changes badge and the one-way /
not-logged-in `!` badge (`updateSyncIconFromState`, `SyncActionProvider`'s tooltip and media-sync
progress bar). `SyncActionProvider` and `BadgeDrawableBuilder` are now unreferenced but were kept
to avoid a needless upstream-merge conflict.

## Notes for later

- Theme color's listener (`ActivityCompat.recreate` on change) moved with the preference;
  `Prefs.themeColor` and the overlay mechanism are unchanged — see
  [themecolor](../themecolor/README.md).
- The preference **keys did not change** (`themeColor`, `switchProfileScreen`, `lanSyncScreen`),
  so existing installs keep their values. Only the XML file holding them changed.
- Settings search still finds Theme color: `HeaderFragment.configureSearchBar` indexes
  `R.xml.preferences_my_settings` and `getFragmentFromXmlRes` maps it back to `MySettingsFragment`.
  The tool entries are runtime `Preference`s without keys, so they are intentionally not searchable.
- `menu-xlarge/deck_picker.xml` (tablet) was trimmed the same way; the rename/delete-deck actions
  that it declares as buttons are untouched.
- Wide screens: `PreferencesActivity` opens the initial fragment passed by
  `PreferencesActivity.getIntent(context, MySettingsFragment::class)`, so 我的设置 is a page of its
  own rather than a category inside the settings list.
