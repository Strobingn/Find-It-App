# Find It APK builds and in-app updates

Workflow name: **Find-it-appAPK** (`.github/workflows/android-build.yml`).

`applicationId`: **`com.aistudio.lidardetector.pkrxtz`** (stable — do not change).

## Signing

Current CI artifacts on `main` were **debug-signed**. `debug.keystore` is gitignored; the old workflow generated a fresh debug key on each runner unless a local file happened to exist. Those APKs will **not** update in place over a release-signed build.

Release APKs use **one keystore stored in GitHub Actions secrets**. Create it once and **never rotate** it:

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 findit-release.jks` |
| `STORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias (Gradle default `upload` if unset locally) |
| `KEY_PASSWORD` | key password |

```bash
keytool -genkeypair -keystore findit-release.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 findit-release.jks
```

The first release-signed install over a debug-signed copy requires uninstall. **Tools → App updates → Export app backup** first (Room finds/settings, historic-map index, LAZ library index). Restore after reinstall.

## Versioning and tags

`versionCode = 1000000 + github.run_number`  
`versionName = 2.0.<run_number>`  
Release tag: **`findit-v<versionCode>`** (example `findit-v1000001`).

The updater pages `/repos/Strobingn/Find-It-App/releases` (not `/latest`) and keeps only `findit-v*` tags.

## First release APK URL

Created on the next **push/merge to `main`** after the signing secrets exist:

`https://github.com/Strobingn/Find-It-App/releases/download/findit-v<versionCode>/findit-<versionCode>.apk`

Example for run_number `1`:  
`https://github.com/Strobingn/Find-It-App/releases/download/findit-v1000001/findit-1000001.apk`

Release notes always include:

```
app-id: com.aistudio.lidardetector.pkrxtz
sha256: <hex>
update-token-expiry: <date>
```

The workflow fails if the APK, `app-id:` line, or sha256 is missing.

## Updater token (optional while the repo is public)

The repo is **public for now**. The updater uses the unauthenticated Releases API and
`browser_download_url` (does not require a token). GitHub allows **60 unauthenticated
requests/hour**; the app reports that limit instead of “no updates”.

`FINDIT_UPDATE_TOKEN` is **optional**. Use it only when going private later:

- Resource owner: the owner of `Strobingn/Find-It-App`
- Repository: **Strobingn/Find-It-App only**
- Permissions: **Contents: Read** (nothing else)
- Expiration: pick a date and store it as `FINDIT_UPDATE_TOKEN_EXPIRY`

| Name | Where |
| --- | --- |
| `FINDIT_UPDATE_TOKEN` | Actions **secret** (optional now; required after the repo is private) |
| `FINDIT_UPDATE_TOKEN_EXPIRY` | Actions **variable** (written into each release as `update-token-expiry:`) |

If a token **is** set and GitHub returns 401/403: **Update token expired or invalid**.

Releases stay on Strobingn/Find-It-App (not a public-releases sidecar). API keys stay in the APK.

Embedded API keys stay in the APK (owner request). Do not strip `BuildConfig` / manifest injection.

## Secret audit (rotate if a public APK was ever shipped)

No literal key material is in git. `.env.example` uses `YOUR_*` placeholders; `local.properties` / `.env` are gitignored. CI still **injects** these into `BuildConfig` and the manifest when the matching Actions secret is set, so anyone who downloaded a CI APK while the repo was public could extract them.

| Field | Where it lands | Runtime alternative |
| --- | --- | --- |
| `OPENAI_API_KEY` | `BuildConfig` | AI screen device vault (already preferred) |
| `OPENAI_PROXY_TOKEN` / `PROXY_AUTH_TOKEN` | `BuildConfig` | same vault / proxy |
| `GEMINI_API_KEY` | `BuildConfig` | AI screen “paste a key” (already preferred) |
| `MAPS_API_KEY` | `BuildConfig` + manifest `com.google.android.geo.API_KEY` | Maps SDK reads the manifest at process start |
| `SENTRY_DSN` | `BuildConfig` + manifest `io.sentry.dsn` | empty DSN disables Sentry |
| `FINDIT_UPDATE_TOKEN` | `BuildConfig` (new) | required for private Releases API |

Also in BuildConfig (not secrets): `OPENAI_MODEL`, `OPENAI_BASE_URL`, `GEMINI_MODEL`.

**Rotate** `OPENAI_API_KEY`, `OPENAI_PROXY_TOKEN`, `GEMINI_API_KEY`, `MAPS_API_KEY`, and `SENTRY_DSN` if any debug/release APK left this repo while it was public.

## When APKs build

- **push/merge to `main`**: unit tests, then signed release APK + `findit-v*` GitHub Release
- **pull requests**: unit tests only (no APK, no release)
