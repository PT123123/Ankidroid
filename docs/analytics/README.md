# No outbound reporting in Anki Plus

Upstream AnkiDroid ships two reporting paths. Anki Plus removes both, because a fork
that keeps them posts its own identity — the app name "Anki Plus" and the application
id `com.pt123123.ankiplus` — into someone else's infrastructure:

| Upstream | Endpoint | Fork state |
|---|---|---|
| ACRA crash reports (plus the manual "Send troubleshooting report") | `PUT https://ankidroid.org/acra/report` | removed |
| GA4 usage analytics, screen views, setting changes, exception hits | `G-XY625S5VVE` via the Measurement Protocol | removed |

## What was taken out

**ACRA.** `AcraCrashReporter`, `AnkiDroidCrashReportDialog` and the `ch.acra:*`
dependencies are gone, along with the `ACRA_URL` build field, the `:acra` sender
process in the manifest, the *Settings → General → Error reporting mode* list and the
*Help → Send troubleshooting report* entry.

`AnkiDroidApp` now registers `LoggingCrashReporter`, which writes the throwable to
Timber and drops it. Every `CrashReportService.sendExceptionReport(...)` call site in
the app is unchanged: the interface has a log-only implementation instead of an
uploading one. Fatal crashes go to logcat and kill the process like any other Android
app.

**Google Analytics.** `AnkiDroidUsageAnalytics`, `AnalyticsConstants`,
`AnalyticsExceptionHandler`, `AcraAnalyticsInteraction`, the opt-in dialog and the
`io.github.criticalay:google-analytics-kt` dependency are gone, along with
`ANALYTICS_API_KEY`, `ga_trackingId`, `ga_sampleFrequency` and the *Share feature
usage* switch.

The `Analytics` / `AnalyticsEvent` facade in `:common` stays, and its default
implementation is `Unregistered`, whose sends are no-ops. Hundreds of call sites
(`Analytics.send(...)`, `sendAnalyticsScreenView(...)`, `AnalyticsDialogFragment`)
therefore keep compiling and now do nothing. Nothing can register an implementation
because there is no transport left to register.

## What is deliberately untouched

- **Sync and media** (`ankiweb.net`) — the user's own account traffic, not reporting.
- **LAN sync** — peers on the local network only.
- **Help → Report a bug / manual** — opens a browser to a URL; the app uploads nothing.
- **Timber logs** — local only, and the *debug info* block that About copies no longer
  prints the old `ACRA UUID` / `Crash Reports Enabled` lines.
- **Desktop** (`../anki-desktop`) is a separate tree: its *check for updates* ping to
  `ankiweb.net/svc/desktop/check-for-update` (version, buildhash, install id) still
  runs when *Preferences → Check for updates* is on. That is an official Anki service,
  not the fork's reporting, so this change leaves it alone.

## Verifying

```
./gradlew :AnkiDroid:testPlayDebugUnitTest --tests "com.ichi2.anki.NoOutboundReportingTest"
```

`NoOutboundReportingTest` is the gate: it scans the build files, the default resources
and the manifest for the removed endpoints and ids, probes the classpath for
`org.acra.ACRA` and `com.criticalay.GoogleAnalytics`, and checks that `AnkiDroidApp`
registers no uploading reporter. Each check has a control that proves it can fail, so
re-adding either transport turns the suite red rather than silently shipping a build
that phones home.
