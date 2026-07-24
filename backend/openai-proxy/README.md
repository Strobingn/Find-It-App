# Find It OpenAI Proxy

This service keeps `OPENAI_API_KEY` off the Android device. The app sends only terrain-analysis summaries to this HTTPS endpoint; the raw LAS/LAZ point cloud remains on the phone.

## Required environment

```text
OPENAI_API_KEY=your-project-api-key
```

## Optional environment

```text
OPENAI_MODEL=gpt-5
PROXY_AUTH_TOKEN=a-long-random-app-token
PORT=8787
```

`PROXY_AUTH_TOKEN` is not the OpenAI key. It is a separate revocable token that limits casual use of your proxy. Use the same value for the Android build's `OPENAI_PROXY_TOKEN`.

## Run locally

```bash
cd backend/openai-proxy
OPENAI_API_KEY=... npm start
```

Health check:

```bash
curl http://localhost:8787/health
```

Interpretation request:

```bash
curl -X POST http://localhost:8787/interpret \
  -H 'Content-Type: application/json' \
  -H 'X-FindIt-Token: your-optional-token' \
  -d '{
    "terrainSummary": "Imported bare-earth LAZ terrain",
    "analysisType": "Local Relief Model",
    "analysisSummary": "LRM min -1.2 m, mean 0.01 m, max 1.6 m",
    "question": "Explain the strongest terrain patterns."
  }'
```

## Deploy

The included `Dockerfile` works on container hosts that provide HTTPS at the edge. Set the environment variables in the host's secret manager. Do not commit an API key or paste it into `local.properties`.

The Android setting must be the full endpoint, for example:

```text
OPENAI_PROXY_URL=https://your-service.example.com/interpret
OPENAI_PROXY_TOKEN=the-same-value-as-PROXY_AUTH_TOKEN
```

For GitHub Actions, create repository secrets with those two names. The workflow writes them to the temporary CI `local.properties` file before Gradle configuration.

## Controls already included

- 32 KiB request-body cap
- 55-second upstream timeout
- 30 requests per source address per hour
- optional proxy token
- no response caching
- limited prompt field lengths
- OpenAI key read only from the server environment

For a public production deployment, place this service behind a managed gateway or authenticated user backend and replace the in-memory rate limiter with a shared data store.
