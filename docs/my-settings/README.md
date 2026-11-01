# Settings merge (fork feature)

> **⚠ Fork-specific.** anki-plus addition. The fork's options used to live on a separate
> **我的设置 / My settings** page (`MySettingsFragment`, `res/xml/preferences_my_settings.xml` —
> both deleted); they now live inside AnkiDroid's own settings, so there is one settings surface.
> The deck picker toolbar stays stripped to the search icon either way.

## Where everything went

| Fork item | Old home (我的设置) | New home |
|-----------|--------------------|----------|
| Theme color | Added features | Settings ▸ Appearance, inside *Themes* |
| Restyle card defaults | Added features | Settings ▸ Appearance ▸ *Card rendering* |
| Card style | Added features | Settings ▸ Appearance ▸ *Card rendering* |
| Switch profile | Added features | Settings root list (below Advanced; hidden unless the developer switch enables it) |
| LAN sync | Added features | Settings ▸ Sync |
| Deck tools (Sync, Create/Restore backup, Import, Export, Check database/media, Empty cards, Note types) | Tools | Settings ▸ **备份与导入导出 / Backup & import/export** |

The More tab (and the tablet drawer) list plain **Settings** only — no separate fork entry.

## The "Backup & import/export" screen

`BackupTransferFragment` + `res/xml/preferences_backup_transfer.xml`. The three categories
(*Sync & backups*, *Import & export*, *Maintenance*) are empty in XML and filled at run time from
`DeckTool.entries`, because most tool labels only exist in the backend translations.

The actions stay DeckPicker behaviour (dialogs, `viewModel`, backup progress), so the settings
page does not re-implement them: it returns to the deck picker with an extra.

1. `BackupTransferFragment` → `DeckPicker.getIntentForTool(context, tool)`
   (`CLEAR_TOP|SINGLE_TOP` + `EXTRA_DECK_TOOL` = enum name), then finishes itself so the settings
   page does not stay under the deck list.
2. `DeckPicker` stores it in `pendingDeckTool` (from `onCreate` for a cold start, `onNewIntent`
   for a warm one).
3. `refreshState()` — which only runs once the drawer is ready and collection storage is granted —
   consumes the field and calls `runDeckTool(tool)`, the single place where all nine actions are
   dispatched.

Sync is therefore still reachable three ways: 备份与导入导出 ▸ Sync, the pull-down gesture
(`pullToSyncWrapper`), and whatever automatic sync the sync settings schedule.

**What the sync icon used to carry is gone**: the pending-changes badge and the one-way /
not-logged-in `!` badge (`updateSyncIconFromState`, `SyncActionProvider`'s tooltip and media-sync
progress bar). `SyncActionProvider` and `BadgeDrawableBuilder` are now unreferenced but were kept
to avoid a needless upstream-merge conflict; their two menu labels
(`sync_menu_title_one_way_sync`, `sync_menu_title_no_account`) carry
`tools:ignore="UnusedResources"` for the same reason — `lintVital` treats `UnusedResources` as
fatal, so a release APK would not assemble otherwise.

## Notes

- The preference **keys did not change** (`themeColor`, `cardStylePreset`, `stockCardCssRethemed`,
  `switchProfileScreen`, `lanSyncScreen`), so existing installs keep their values. Only the XML
  file holding them changed.
- Theme color's listener (`ActivityCompat.recreate` on change) moved with the preference into
  `AppearanceSettingsFragment`; `Prefs.themeColor` and the overlay mechanism are unchanged — see
  [themecolor](../themecolor/README.md).
- *Card style* is stored as `cardStylePreset`; the list's values are the
  `R.string.card_style_*_value` strings that `CardStylePreset` stores. The chip in the card's
  corner while studying and the Appearance entry are two doors to one setting — see
  [ui-polish §7](../ui-polish/README.md).
- Settings search: `HeaderFragment.configureSearchBar` indexes `R.xml.preferences_backup_transfer`
  and `getFragmentFromXmlRes` maps it back to `BackupTransferFragment`; the Appearance and Sync
  entries are found through their own screens. The tool entries are runtime `Preference`s without
  keys, so they are intentionally not searchable.
- `menu-xlarge/deck_picker.xml` (tablet) stays trimmed the same way; the rename/delete-deck
  actions that it declares as buttons are untouched.
