# Sentry AI monitoring (Find It Android)

## What is configured

| Piece | Behavior |
|-------|----------|
| SDK | `io.sentry:sentry-android:8.12.0` |
| Init | `FindItApplication` when `SENTRY_DSN` is non-empty |
| Tracing | `tracesSampleRate` from `BuildConfig.SENTRY_TRACES_SAMPLE_RATE` (default `1.0` for AI visibility) |
| AI spans | **Manual** `gen_ai.*` via `SentryAiMonitor` around `TerrainAiGateway` |
| Content | **Metadata only** — no prompts, map context, or model text |

## Why manual?

OpenAI/Gemini are custom OkHttp clients, not the JS/Python SDKs that auto-enable Sentry integrations. Android uses the same [gen_ai conventions](https://getsentry.github.io/sentry-conventions/attributes/gen_ai/) with `Sentry.startTransaction` / child spans.

## Attributes recorded (no PII content)

- `gen_ai.request.model`
- `gen_ai.operation.name` (`chat` / `invoke_agent`)
- `gen_ai.system` (`openai` / `gemini`)
- `gen_ai.agent.name` = `find-it-terrain`
- `gen_ai.conversation.id` (rotated on Clear conversation)
- `gen_ai.request.has_image`
- `gen_ai.request.message_count`
- `gen_ai.request.feature` (e.g. `DIG_BRIEF`, `chat`)
- `gen_ai.prompt_capture` = `disabled`
- success / error type (class name only)

## Enable in your environment

1. Create a Sentry Android project and copy the DSN.
2. Add to `local.properties` or CI secrets (never commit real DSN):

```properties
SENTRY_DSN=https://<key>@o<org>.ingest.sentry.io/<project>
```

3. Rebuild the app. Empty DSN = Sentry stays off (local default).

## Verify

1. Set `SENTRY_DSN`, install debug APK.
2. Open AI tab, run a chat or field-pack chip with a valid OpenAI/Gemini key.
3. In Sentry: **Explore → Traces** (and **Conversations** if you later enable content capture).
4. Look for `invoke_agent find-it-terrain` and child `gen_ai.chat` spans.

## Privacy

Field notes, GPS, and map analysis text are **not** sent. To enable Conversations with full message reconstruction you would need prompt/output capture — **not** enabled for this product without a separate privacy review.

## Related code

- `app/src/main/java/com/example/FindItApplication.kt`
- `app/src/main/java/com/example/ai/SentryAiMonitor.kt`
- `app/src/main/java/com/example/ai/TerrainAiGateway.kt`
