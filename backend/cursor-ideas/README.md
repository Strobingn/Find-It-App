# Find-It → Cursor coding ideas

Private owner-operated bridge: **Find-It Android → Vercel Function → Cursor Cloud Agent → new branch / pull request**.
No changes to terrain processing, existing update channels, or app identity. The app only sends the typed idea and app/Android version. It does not collect coordinates, finds, terrain files, photos, logs, or existing AI credentials.

## Deploy on Vercel

1. Import `Strobingn/Find-It-App` into a **new** Vercel project. Set **Root Directory** to `backend/cursor-ideas`, Framework Preset to **Other**, Node.js to **24.x**, Build Command to `npm test`, and Output Directory to `public`.
2. Deploy branch `chatgpt/cursor-ideas-20261017` as a preview. This folder is only on that branch until its PR is merged. Do not merge the PR just to deploy the backend.
3. Add the following environment variables in the Vercel dashboard for the environment you deploy. Redeploy after changing them. Keep secrets out of Git, Android build settings, screenshots, and chat.

| Variable | Value |
|---|---|
| `CURSOR_API_KEY` | User API key created in Cursor Dashboard → API Keys. Cursor's GitHub integration must have access to Find-It. An Admin/analytics key is not suitable. |
| `FINDIT_IDEAS_TOKEN` | A separate random token (32–512 characters, e.g. `openssl rand -hex 32`). This is the owner's device-to-backend credential. |
| `FINDIT_BASE_REF` | Existing source branch, default `main`. Cursor creates a new branch from this ref. |
| `FINDIT_ALLOW_IMPLEMENT` | Default `false`. Set `true` to allow the app's Implement option. |

4. If Vercel Deployment Protection intercepts requests to this project, configure an appropriate API-accessible deployment using the dashboard. Do not embed a Vercel automation bypass secret in the APK. The function independently requires the private connection token.
5. Install the branch test APK from the **Cursor ideas integration checks** Actions artifact. Home → **Coding ideas** → connection settings. Enter the deployed HTTPS root URL and the separate `FINDIT_IDEAS_TOKEN`. Never enter the Cursor key in the app.
6. Send an Evaluate idea first. Open its Cursor link to inspect the plan. Enable implementation on the server when ready and send an implementation request. Inspect its pull request and checks before merging.

No Cursor API key was available during development. Automated tests mock Cursor; a live authenticated submission must be verified after configuring your key.

## Branch and execution rules

- Fixed repository allowlist: `Strobingn/Find-It-App`. Devices cannot choose another repository, branch, execution environment, model, tools, or PR target.
- `workOnCurrentBranch: false` is always sent. Implementation requests use `mode: agent` and `autoCreatePR: true`. Nothing in this bridge merges, publishes an APK, or deploys the resulting changes.
- Evaluate uses Cursor's `mode: plan` and disables auto-PR creation. It instructs the agent to evaluate without editing files. Plan mode and prompt restrictions are agent behavior, not a filesystem permission boundary. Use GitHub branch protection for hard restrictions on protected branches.
- Only explicit Send actions launch agents. No automatic telemetry, background suggestion generation, scheduled submissions, or silent coding jobs.
- Status comes from the latest **run**, not the durable agent lifecycle. An `IDLE` agent can have a failed run. The app shows the upstream run status and links to the full plan/conversation/PR in Cursor.

## Reliability and storage

The app persists the exact pending request before sending it. On timeout, **Retry same idea** repeats that request. The backend derives a stable client-supplied Cursor agent UUID using an HMAC of the request and server configuration. Cursor's `409 agent_id_conflict` is recovered by fetching that agent/run; there is no process-local idempotency cache. Do not change the backend token/base ref or endpoint while a request is unresolved: those changes alter the idempotency namespace. If necessary, check your Cursor task list before resetting or rotating anything.

Connection credentials, saved drafts, pending requests and the last 30 task receipts are AES-GCM encrypted using Android Keystore in `noBackupFilesDir`. Save draft persists edits; a pending submission is saved automatically. Uninstalling the app removes this local history. Cursor retains the actual tasks in its account.

Status requests require both the private connection token and a server-signed task receipt. Responses are `no-store`. Upstream error bodies, credentials, idea text and response payloads are not logged. Network requests have timeouts and do not forward credentials across redirects.

## Costs and access

This endpoint is for the owner's devices, not untrusted public users. The connection token authorizes paid Cursor tasks; give it only to trusted devices. Configure Cursor account spending controls and Vercel project spending controls / WAF rate limits before enabling implementation. **This bridge does not enforce a daily token or dollar budget** and has no durable rate-limit database. A short function invocation starts a remote Cursor task; it does not keep a Vercel function open for the coding session. Manual refresh checks the current run.

The backend returns 503 when secrets are missing, 401 for an incorrect device token, 403 when implementation is disabled, 429 for Cursor throttling, and sanitized 502 errors for upstream/configuration/network failures. A lost response is never treated as proof that a task was not created.

## Checks

```sh
cd backend/cursor-ideas
npm test
```

The branch workflow also runs `testDebugUnitTest --tests 'com.example.cursor.*' assembleDebug`. It uploads a test APK artifact without publishing a release. Existing PR checks still run the repository's full Android test suite.

API contract: `POST /api/ideas` with bearer connection token and JSON `{requestId, title, details, mode, appVersion}`. Modes are `plan` and `agent`. Response: `{agentId, receipt, status, agentUrl}`. Refresh with authenticated `GET /api/ideas?agentId=…&receipt=…`.

Primary references:
- [Cursor Cloud Agents API](https://cursor.com/docs/cloud-agent/api/endpoints)
- [Vercel Functions](https://vercel.com/docs/functions)
- [Vercel project root directory](https://vercel.com/docs/builds/configure-a-build#root-directory)
