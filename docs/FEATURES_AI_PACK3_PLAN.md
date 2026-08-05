# Plan: AI pack 3 (10 more) + LAZ capability roadmap

**Branch:** `GROKV5` (not merge to `main` until green)  
**Status:** Implementing / shipping on GROKV5  
**Depends on:** AI pack 1 (`FEATURES_AI_PACK.md`), LAZ decode/refine pipeline, local `TerrainIntelligenceEngine`

---

## Already shipped (do not re-plan)

### AI pack 1
Dig brief · Site narrative · Lighting advisor · Sweep plan · Field report · Outcome coach · Find interpreter · Historic correlator · Anomaly deep-dive · Day debrief

### Local / cloud AI already in app
- Local layers (slope, LRM, openness, candidates, homesite, etc.)
- Cloud chat + viewport image + `[MAP_TARGET …]`
- Anomaly region classify (Gemini)
- Ranker train from feedback
- Target refiner / metal-detecting historic targets

### LAZ already in app
- Import LAS/LAZ, sparse preview → exact upgrade
- Manual **Detail** refine on viewport (auto-refine removed)
- Classified ground vs auto-lowest surface
- Vegetation filter, multi-viz modes, GPU scene
- NYS tile picker / library, COPC remote path
- Export GeoTIFF, GIS packages, project archive

---

## Part A — 10 more AI-heavy features (pack 3)

Principle: each feature is **session-grounded** (terrain stats, candidates, finds, trails, digs), uses `TerrainAiGateway` (OpenAI → Gemini), and returns **field-actionable** text or structured tags the app can apply.

| # | Feature | User value | Inputs | Structured outputs (app can act on) | Effort |
|---|---------|------------|--------|-------------------------------------|--------|
| 1 | **Return-trip planner** | Plan next visit from starred finds + unfinished digs + weather-agnostic priorities | Starred signals, open digs, trails, GPS | Ordered stops; optional `NAV_TARGET id=` lines | M |
| 2 | **False-positive autopsy** | Why a rejected candidate looked real | Rejected outcomes + local evidence vectors + viewport crop | Checklist of “never again” cues; viz mode suggestions | M |
| 3 | **Compare-two-sites** | Rank which of two parcels / tiles is better to hunt | Two terrain summaries + candidate counts + find density | Side-by-side scores; pick A/B with reasons | M |
| 4 | **Question the cell** | Tap a cell → AI explains micro-topography in plain language | Cell inspection + neighbors + viz mode + crop | 5-bullet “what am I looking at?” | S |
| 5 | **Evidence chain** | Explainability for one ranked candidate | Ranker contributions + evidence list + image | Numbered chain: observation → inference → field test | S |
| 6 | **Voice → structured find** | Dictate field notes → fill find fields | Freeform text/voice transcript | Suggested metal type, status, outcome, cleaned notes | M |
| 7 | **Photo of find → catalog assist** | Photo of dug object → catalog hints (not dating claims) | Find photo + notes + site context | Suggested labels + “what to photograph next” | M |
| 8 | **Coverage gap AI** | Where you haven’t swept relative to high-value terrain | Breadcrumbs + candidate heat + boundary | Gap polygons as text + map targets | L |
| 9 | **Partner handoff brief** | Short brief for a teammate taking over the detector | Live finds + open digs + nearest find + plan | 1-page handoff; share-sheet ready | S |
| 10 | **Risk & ethics coach** | Private land, cemeteries, modern disturbance caution | User notes + modern-disturbance layer stats + location | “Do / don’t dig” caution list; never invent ownership law | S |

### Pack 3 design notes

- **Extend** `FieldAiFeature` enum (or `FieldAiFeatureV2`) so UI stays the same chip strip.
- Prefer **tags the app already understands**: `LIGHT_AZ`, `[MAP_TARGET …]`, plus new optional tags:
  - `NAV_TARGET id=<signalId>`
  - `VIZ_MODE=<0-8>`
  - `METAL_TYPE=<enum>` / `OUTCOME=<enum>` for voice/photo assist (user confirms before write)
- Always hard-rule in prompts: **LiDAR ≠ metal**; no age/depth claims as fact.
- Offline fallback: packs that only need local math (coverage gaps, return-trip order) should still produce a **local draft** when cloud keys missing.

### Suggested implementation order (pack 3)

1. Question the cell (S)  
2. Evidence chain (S)  
3. Partner handoff brief (S)  
4. Risk & ethics coach (S)  
5. Return-trip planner (M)  
6. False-positive autopsy (M)  
7. Voice → structured find (M)  
8. Compare-two-sites (M)  
9. Photo catalog assist (M)  
10. Coverage gap AI (L — needs better gap geometry)

---

## Part B — What we can do with LAZ files

Grouped by product value. **Now** = already plausible with current code; **Next** = 1–2 sprints; **Later** = larger systems.

### B1. Ingest & library

| Capability | Status | Notes |
|------------|--------|--------|
| Open local LAS/LAZ | **Now** | Library, rename, reopen |
| Sparse preview → full decode | **Now** | Fast first paint |
| Viewport Detail refine | **Now** | Manual only (no auto-zoom refine) |
| Multi-tile mosaic | **Partial / Next** | Mosaic project entities exist; UX completion |
| COPC / remote stream | **Partial** | Coordinator has remote path; harden + UX |
| CRS / units validation report | **Next** | Surface “feet vs meters”, CRS, point count, density |
| Corrupt-file recovery hints | **Next** | AI or rules: “try classified ground / lower res” |

### B2. Ground & surface science

| Capability | Status | Notes |
|------------|--------|--------|
| Classified ground raster | **Now** | ASPRS class 2 + fallbacks |
| Auto-lowest ground estimate | **Now** | Isolated-return rejection |
| Highest-return / canopy height | **Now** | Viz mode |
| Ground quality scorecard | **Next** | % classified ground, density/m², holes, spike count |
| Dual-surface compare (ground vs first-return) | **Next** | Side-by-side or swipe (Compare tab) |
| Building / bridge filter | **Later** | Class 6 handling + AI morphology assist |
| Time-of-flight / multi-return diagnostics | **Later** | For quality control, not field UI first |

### B3. Visualization & measurement

| Capability | Status | Notes |
|------------|--------|--------|
| Hillshade multi-az, LRM, slope, curvature… | **Now** | Full local stack |
| Contours / profile / viewshed | **Now / Partial** | Profile + viewshed wired; contours vary by branch |
| Measure distance/area on terrain | **Partial** | Roadmap measure tools |
| GPU 3D mesh flyover | **Partial** | GpuTerrainSurface exists; polish navigation |
| Clip LAZ to survey boundary | **Next** | Use `SurveyBoundary` + re-raster focus |
| Export subset LAZ (clipped) | **Later** | Write LAZ via laszip or external |

### B4. Feature detection (deterministic + AI)

| Capability | Status | Notes |
|------------|--------|--------|
| Local candidates (cellar, wall, road…) | **Now** | `TerrainIntelligenceEngine` |
| Cloud viewport analysis + map targets | **Now** | AI pack + chat |
| AI pack 1 field copilots | **Now** | 10 chips |
| AI pack 3 (this plan) | **Plan** | Above |
| Class-aware feature filters | **Next** | Only analyze ground; mask veg |
| Multi-scale “feature size” AI brief | **Next** | Pair feature-scale slider with LLM explanation |
| Change detection (two LAZ epochs) | **Later** | Needs two-date tiles for same extent |

### B5. Field integration

| Capability | Status | Notes |
|------------|--------|--------|
| GPS on LAZ map, trails, mark, digs | **Now** | Finds + navigation |
| Nearest find / star / proximity | **Now** | Pack 2 |
| AI sweep / dig / debrief | **Now** | Pack 1 |
| Snap dig to refined ground Z | **Next** | Sample DEM under find for relative depth context (surface Z only) |
| Offline basemap + LAZ overlay | **Now** | Basemap regions |

### B6. Export & collaboration

| Capability | Status | Notes |
|------------|--------|--------|
| GeoTIFF, GPX, KML, SHP, QGIS, PDF-ish project | **Now** | Project export |
| AI field report → share sheet | **Now / Next** | Pack 1 report + share |
| Cloud project sync | **Later** | Pending sync queue exists; backend optional |
| Publish “site package” (LAZ hash + analysis + finds) | **Later** | Reproducible research bundle |

### B7. Performance & scale

| Capability | Status | Notes |
|------------|--------|--------|
| Sparse preview + cache (memory/disk) | **Now** | Hot path work on GROKV5 |
| Zoom LOD / GPU tile clamp | **Now** | Ushort-safe tiles |
| Background pre-decode next tile | **Next** | When browsing library |
| Device-class budgets | **Now** | `AppMemoryBudget` |
| Cloud LAZ processing | **Out of scope (mobile-first)** | Keep heavy work local unless user opts in |

---

## Part C — Combined product narrative

**What Find-It becomes with pack 3 + LAZ depth:**

1. **Acquire** the right LAZ (picker / library / mosaic).  
2. **Decode** fast (preview) then **trust** ground (quality scorecard).  
3. **See** morphology (local layers + lighting AI).  
4. **Rank** targets (local + ranker + cloud deep-dive).  
5. **Plan** field time (return-trip, sweep gaps, partner handoff).  
6. **Record** outcomes (voice/photo structured assist).  
7. **Learn** (false-positive autopsy, outcome coach).  
8. **Export** (GIS + AI report).

---

## Part D — Acceptance criteria (pack 3)

For each of the 10 AI features:

- [ ] Chip on AI field pack (or sub-section “Pack 3”)  
- [ ] Uses `FieldAiSessionPack` (+ feature-specific extras)  
- [ ] Works with OpenAI or Gemini key; clear error if neither  
- [ ] Text-only fallback when viewport image missing (if image preferred)  
- [ ] No fabricated metal/age/depth claims in system prompt  
- [ ] Unit tests for any new parsers (NAV_TARGET, METAL_TYPE, etc.)  
- [ ] Documented in `FEATURES_AI_PACK3.md` when shipped  

For first LAZ “Next” slice (suggested with pack 3):

- [ ] Ground quality scorecard card on Import or Terrain  
- [ ] CRS / units / density one-liner on open  

---

## Part E — Effort estimate

| Track | Scope | Rough effort |
|-------|--------|--------------|
| AI pack 3 (10 features) | Prompts + ViewModel + chips + 2–3 parsers + tests | ~1 focused sprint |
| LAZ ground scorecard + CRS report | Reader metadata + small UI | 2–4 days |
| Clip-to-boundary refine | Boundary → focus bounds → refine | 3–5 days |
| Mosaic UX completion | Wire existing mosaic entities | 1 week |
| Dual-surface compare | Raster both surfaces + Compare UX | 1 week |

---

## Open decisions (need your call before implement)

1. **Pack 3 only** vs **pack 3 + ground scorecard** in the same pass?  
2. Voice/photo features: **on-device speech** (Android) vs **text-only** first?  
3. Photo assist: stay on **Gemini/OpenAI vision** only (no new provider)?  
4. Compare-two-sites: same session two datasets, or pick from **analyzed dataset** Room table?
