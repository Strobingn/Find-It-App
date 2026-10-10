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

## Private-repo updater token

The repo is expected to be **private**. Unauthenticated Releases API calls 404.

Create a **fine-grained** personal access token:

- Resource owner: the owner of `Strobingn/Find-It-App`
- Repository: **Strobingn/Find-It-App only**
- Permissions: **Contents: Read** (nothing else)
- Expiration: pick a date and store it

| Name | Where |
| --- | --- |
| `FINDIT_UPDATE_TOKEN` | Actions **secret** (baked into the APK at build time) |
| `FINDIT_UPDATE_TOKEN_EXPIRY` | Actions **variable** (written into each release as `update-token-expiry:`) |

If the token is missing, the in-app updater says so. HTTP 401/403 shows **Update token expired or invalid** (not “no updates”).

Releases stay in this private repo (the APK embeds API keys).

## Keys that were in the public tree / BuildConfig

These are still compiled into the APK (owner request: keep them). They were readable while the repo was public if a local.properties/.env ever landed in git or if CI injected them into a distributed APK. **Rotate them if they were ever committed or shipped in a public artifact:**

- `OPENAI_API_KEY`
- `OPENAI_PROXY_TOKEN`
- `GEMINI_API_KEY`
- `MAPS_API_KEY`
- `SENTRY_DSN`

Model/URL fields (`OPENAI_MODEL`, `OPENAI_BASE_URL`, `GEMINI_MODEL`) are not secrets but are also in BuildConfig.

## When APKs build

- **push/merge to `main`**: unit tests, then signed release APK + `findit-v*` GitHub Release
- **pull requests**: unit tests only (no APK, no release)
