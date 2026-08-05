# GROKV5 AI-heavy feature pack (10)

Cloud AI features for field LiDAR / metal-detecting reconnaissance. Uses existing
OpenAI-primary / Gemini-fallback gateway (`TerrainAiGateway`). Offline local
analysis remains independent.

| # | Feature | What it does |
|---|---------|--------------|
| 1 | **Dig brief** | Structured next-dig briefing from terrain + candidates + finds + GPS |
| 2 | **Site narrative** | Story of occupation / scatter from clustered finds and outcomes |
| 3 | **Lighting advisor** | Recommends hillshade sun az/alt; can emit `LIGHT_AZ=` / `LIGHT_ALT=` for one-tap apply |
| 4 | **Sweep plan** | Priority zones and walk order from coverage gaps + candidates |
| 5 | **Field report** | Multi-section session report (terrain, finds, digs, trails, next steps) |
| 6 | **Outcome coach** | Learns patterns from verification outcomes / false positives |
| 7 | **Find interpreter** | Interprets notes, status, metal types for logged finds |
| 8 | **Historic correlator** | Correlates local terrain candidates with historic-occupation patterns |
| 9 | **Anomaly deep-dive** | Deep analysis of top candidates with optional `[MAP_TARGET …]` markers |
| 10 | **Day debrief** | End-of-day structured debrief from freeform notes + session data |

UI: **AI tab → Cloud panel → AI field pack** (10 action chips).
Requires OpenAI and/or Gemini key under Keys.
