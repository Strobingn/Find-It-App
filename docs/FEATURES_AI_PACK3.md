# GROKV5 AI-heavy feature pack 3 (10) — Done

Cloud AI field copilots that extend pack 1 (`FEATURES_AI_PACK.md`). Session-grounded
prompts via `FieldAiCopilot` / `FieldAiFeature` and the existing OpenAI-primary /
Gemini-fallback gateway (`TerrainAiGateway`). Still on branch `GROKV5` until green.

**Status:** Done (shipping on GROKV5)

| # | Feature | What it does | Structured tags (optional) |
|---|---------|--------------|----------------------------|
| 1 | **Return-trip planner** | Ordered next-visit stops from starred finds, open digs, trails, and GPS | `NAV_TARGET id=` |
| 2 | **False-positive autopsy** | Why a rejected candidate looked real; “never again” cues | `VIZ_MODE=` |
| 3 | **Compare-two-sites** | Rank which of two parcels/tiles is better to hunt | Side-by-side scores (text) |
| 4 | **Question the cell** | Tap a cell → plain-language micro-topography (5 bullets) | — |
| 5 | **Evidence chain** | Explainability for one ranked candidate (observation → inference → field test) | — |
| 6 | **Voice → structured find** | Dictate / paste field notes → suggested find fields | `METAL_TYPE=` / `OUTCOME=` (user confirms) |
| 7 | **Photo catalog assist** | Find photo → catalog hints + what to photograph next (not dating claims) | Labels (user confirms) |
| 8 | **Coverage gap AI** | Where you haven’t swept relative to high-value terrain | Map targets / gap text |
| 9 | **Partner handoff brief** | Short teammate handoff from live finds, digs, and plan | Share-sheet ready text |
| 10 | **Risk & ethics coach** | Private land / cemetery / modern-disturbance cautions; never invent ownership law | Do / don’t dig list |

## Design rules (all pack 3)

- **LiDAR ≠ metal** — no age/depth/metal claims as fact from terrain alone.
- Prefer tags the app can apply: `LIGHT_AZ` / `LIGHT_ALT` (pack 1), plus `NAV_TARGET`, `VIZ_MODE`, and confirm-before-write metal/outcome suggestions.
- Offline fallback: local drafts for coverage gaps / return-trip order when cloud keys are missing (follow-up product work).

UI: **AI tab → Cloud panel → AI field pack** (20 chips total with pack 1).
Requires OpenAI and/or Gemini key under Keys.

See plan / LAZ roadmap notes: [FEATURES_AI_PACK3_PLAN.md](FEATURES_AI_PACK3_PLAN.md).
