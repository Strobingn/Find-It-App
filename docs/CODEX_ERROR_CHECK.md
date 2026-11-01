# Branch error review

Reviewed `wildlife-whisperer-v2` at `30dc57d35888fb3b5b4cc1d9ce9b1266ccc6b4de`.
It had the newest tip commit among all 109 repository branches inspected on 2026-11-01
(2026-10-31 22:24:20 UTC). This branch preserves that exact starting point.
Review scope: the five files changed since merge base
`4c583686ba6a0a649dcaa06ff12b7dfdf3f6cbc6`, their callers, UI, and related tests.

## Findings addressed

1. **P1 — NWS compared the wrong hours.**
   `ThermalWindViewModel.nwsDisagreement` used `parseIsoDate`, which returns noon in
   the device timezone, as though it were midnight UTC. At UTC-4 this shifts the
   comparison by 16 hours. Construct midnight from the calendar date in UTC, then
   apply the forecast site's offset. Tests cover UTC, New York and Tokyo devices,
   both US seasonal offsets, and a half-hour site offset.

2. **P1 — NWS reused wind speeds after they changed.**
   `ThermalWeatherClient.fetchNwsWind` selected speed only at the start of each
   direction interval. A three-hour direction interval could contain three
   different speeds. Intersect both timelines, preserve every overlap, and parse
   day/hour/minute/second durations. Tests cover speed changes, unequal interval
   starts, missing values, and combined day/minute durations.

3. **P1 — Nearby searches could return a differently centered stand grid.**
   `StandFinderService.gridCacheFile` rounded coordinates to three decimal places
   and `readGridCache` returned the stored center without checking the request.
   Two centers nearly 90 m apart could share one entry. Key files by the exact
   coordinate bits and validate the stored center, grid dimensions, spacing, and
   finite elevations. Tests cover the collision, matching reads and corrupt caches.

4. **P2 — Cancellation could continue into another provider.**
   Broad `runCatching` blocks swallowed `CancellationException` in LiDAR and
   Mapbox fallback paths and optional weather requests. Rethrow cancellation and
   check coroutine activity before subsequent requests. Tests use an injected
   cancelling HTTP client to verify that no next elevation request starts.

5. **P2 — Mapbox still downloaded tiles in a parallel burst.**
   The branch's retry waited five seconds, but all tiles still downloaded together,
   so their retries also arrived together. Load tiles sequentially, check
   cancellation between tiles, and retry only HTTP 429 rather than retrying
   permanent errors such as 401/404 immediately.

6. **P2 — Missing EPQS resolution was presented as one-meter LiDAR.**
   An absent resolution left the accumulator at zero and its label coerced that
   to one meter. Require a finite, positive resolution of at most five meters
   before choosing the fine-terrain path; otherwise use the existing fallbacks.
   Regression coverage includes absent, zero, coarse, nodata, and valid samples.

7. **P2 — New wind results had no visible UI and could remain stale.**
   Neither `nwsBadge` nor `quarterHourly` was rendered. Show the disagreement
   message and the selected hour's 15-minute winds, restricted to forecast mode and
   the selected forecast date. Clear both fields when changing locations and do
   not retain old quarter-hour data when its refresh fails. Count each disagreeing
   hour once even if both its speed and direction differ.

## Validation

Added 12 regression tests in `NwsWindRegressionTest`,
`StandGridCacheRegressionTest`, and `ThermalWeatherCancellationTest`.

The branch-specific `CODEX error check` workflow runs:
`./gradlew testDebugUnitTest assembleDebug --stacktrace --no-configuration-cache`.
It uploads test results and does not publish a release.

Local commands could not start because the environment's sandbox configuration
rejects its `/root/.aws` symlink before launching any process. Android/JVM tests
therefore require the GitHub Actions run; this document does not claim they passed.

## Review limits and integration notes

- No physical-device or emulator interaction was possible in this environment.
  Live provider availability and the rendered screens require device verification.
- Blocking OkHttp calls can still take their configured timeout to finish after
  cancellation. The changes prevent subsequent fallback requests after cancellation.
- The source branch is one commit behind `main`, whose later elevation/backoff work
  overlaps these files. This review does not merge or rewrite either source branch.
  Reconcile that overlap when integrating; in particular, reuse main's more complete
  `Retry-After` handling rather than discarding it.
- This is a focused review of the newest changes, not a guarantee about every
  existing feature in the repository.
