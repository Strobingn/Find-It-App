import http from "node:http";

const port = Number(process.env.PORT || 8787);
const openAiApiKey = process.env.OPENAI_API_KEY || "";
const model = process.env.OPENAI_MODEL || "gpt-5";
const proxyAuthToken = process.env.PROXY_AUTH_TOKEN || "";
const requestsByAddress = new Map();
const MAX_BODY_BYTES = 32_768;
const MAX_REQUESTS_PER_HOUR = 30;

function json(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(body);
}

function clientAddress(request) {
  const forwarded = request.headers["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    return forwarded.split(",")[0].trim();
  }
  return request.socket.remoteAddress || "unknown";
}

function allowedByRateLimit(address) {
  const now = Date.now();
  const oneHourAgo = now - 60 * 60 * 1000;
  const recent = (requestsByAddress.get(address) || []).filter((timestamp) => timestamp > oneHourAgo);
  if (recent.length >= MAX_REQUESTS_PER_HOUR) {
    requestsByAddress.set(address, recent);
    return false;
  }
  recent.push(now);
  requestsByAddress.set(address, recent);
  return true;
}

async function readJson(request) {
  const chunks = [];
  let received = 0;
  for await (const chunk of request) {
    received += chunk.length;
    if (received > MAX_BODY_BYTES) {
      throw new Error("Request body is too large.");
    }
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString("utf8");
  return JSON.parse(text || "{}");
}

function outputText(responseJson) {
  const parts = [];
  for (const item of responseJson.output || []) {
    for (const content of item.content || []) {
      if (content.type === "output_text" && typeof content.text === "string") {
        parts.push(content.text);
      }
    }
  }
  return parts.join("\n").trim();
}

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    json(response, 200, { ok: true });
    return;
  }

  if (request.method !== "POST" || request.url !== "/interpret") {
    json(response, 404, { error: "Not found." });
    return;
  }

  if (!openAiApiKey) {
    json(response, 503, { error: "OPENAI_API_KEY is not configured on the proxy." });
    return;
  }

  if (proxyAuthToken) {
    const suppliedToken = request.headers["x-findit-token"];
    if (suppliedToken !== proxyAuthToken) {
      json(response, 401, { error: "Invalid proxy access token." });
      return;
    }
  }

  const address = clientAddress(request);
  if (!allowedByRateLimit(address)) {
    json(response, 429, { error: "Hourly AI interpretation limit reached." });
    return;
  }

  try {
    const body = await readJson(request);
    const terrainSummary = String(body.terrainSummary || "").slice(0, 4_000);
    const analysisType = String(body.analysisType || "Unknown analysis").slice(0, 200);
    const analysisSummary = String(body.analysisSummary || "").slice(0, 4_000);
    const question = String(body.question || "Explain the terrain patterns.").slice(0, 1_000);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 55_000);
    const openAiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        authorization: `Bearer ${openAiApiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model,
        store: false,
        instructions: [
          "You are a LiDAR terrain interpretation assistant for responsible metal-detecting field research.",
          "Treat computed layers as screening evidence, never proof of archaeological origin.",
          "Explain uncertainty, likely natural alternatives, and practical ground-truth checks.",
          "Do not invent coordinates, historical claims, ownership, access permission, or legal status.",
          "Use concise headings: Interpretation, Priority areas, Alternative explanations, Field checks.",
        ].join(" "),
        input: [
          `Terrain source: ${terrainSummary}`,
          `Selected layer: ${analysisType}`,
          `Computed statistics: ${analysisSummary}`,
          `User request: ${question}`,
        ].join("\n\n"),
        max_output_tokens: 900,
      }),
      signal: controller.signal,
    }).finally(() => clearTimeout(timeout));

    const responseJson = await openAiResponse.json();
    if (!openAiResponse.ok) {
      const upstreamMessage = responseJson?.error?.message || `OpenAI returned HTTP ${openAiResponse.status}.`;
      json(response, 502, { error: upstreamMessage });
      return;
    }

    const text = outputText(responseJson);
    if (!text) {
      json(response, 502, { error: "OpenAI returned no text output." });
      return;
    }
    json(response, 200, { text });
  } catch (error) {
    const message = error?.name === "AbortError"
      ? "OpenAI request timed out."
      : error?.message || "Unexpected proxy error.";
    json(response, 400, { error: message });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Find It OpenAI proxy listening on port ${port}`);
});
