# Find It Roadmap — Wiring Status (GROKV2.5.0)

**Audited:** 2026-08-06 (refreshed after AI polish + AR guidance)  
**Branch:** `GROKV2.5.0`  
**Tip context:** AI field pack polish (`9830f5c` era); AR camera guidance ships with this refresh.  
**Definition of “fully wired”:** production UI entry + real domain path + failure/empty states + (where needed) persistence/tests. Domain-only or stub UI = **partial**. Not in codebase = **missing**.

---

## Executive summary

| Area | Status |
|------|--------|
| Core import / LAZ / terrain render | **Wired** |
| Tile download / mosaics | **Mostly wired** |
| Historic analysis / candidates | **Mostly wired** |
| Field verification offline | **Wired** |
| AR field guidance | **Wired** (camera + compass reticle; non-AR fallback; not Google ARCore plane mesh) |
| Historic map georef | **Wired** (auto feature extract **missing**) |
| ML ranker engine | **Partial** (engine + train UI + feedback folds; regional field datasets open) |
| Advanced tools (viewshed, profile, horizon, compare) | **Wired** |
| Interop export | **Mostly wired** (GeoPackage/share/archive; **cloud multi-device sync missing**) |
| Site Package Pack (10) | **Wired** |
| Field Closure Pack (20) | **Wired** ([FEATURES_FIELD_CLOSURE_PACK.md](FEATURES_FIELD_CLOSURE_PACK.md)) |
| AI packs 1 + 3 (20 field chips) | **Wired** (chips, `runFieldAiFeature`, structured apply tags, offline drafts, focus candidate, secondary compare, pack readiness) |
| Product pack (scorecard, ethics, offline return-trip/dig/gaps) | **Wired** |

---

## Core workflow (10 steps)

| Step | Status | Notes |
|------|--------|-------|
| 1 Select area | **Wired** | LidarAreaPickerMapScreen, NYS/USGS picker, rectangle |
| 2 Download LAS/LAZ | **Wired** | LazDownloadQueue, progress, retry |
| 3 Bare-earth model | **Wired** | Ground modes + dual surface + class filter |
| 4 Analysis layers | **Wired** | Hillshade, slope, LRM, curvature, canopy, etc. |
| 5 Detect features | **Wired** | TerrainIntelligenceEngine / AI local analysis |
| 6 Rank targets | **Mostly** | Multi-signal score + ML ranker; continuous field calibration ongoing |
| 7 Review candidates | **Wired** | AI workspace evidence, cell inspect, Focus AI |
| 8 Navigate | **Wired** | Compass HUD, playlist, route optimizer, **AR camera guidance** + non-AR fallback |
| 9 Record outcomes | **Wired** | Finds, digs, photos, voice, boundaries, sync queue |
| 10 Feedback to ranking | **Wired** | VerificationOutcome → FeatureTypeCalibration / ReviewedExampleStore / train folds |

---

## Phases 1–9

| Phase | Fully wired? | Gaps |
|-------|--------------|------|
| **1** Core workflows | **Mostly** | Release-gate device validation on every path still optional |
| **2** Tile/mosaics | **Mostly** | On-device large mosaic stress open |
| **3** Historic analysis | **Mostly** | Measured FP reduction on verified sites open |
| **4** Performance | **Partial** | Diagnostics/benchmark suite incomplete; cancel/cache present |
| **5** Field verification | **Yes** | Offline path complete; **AR camera guidance wired** (sensor+GPS; not ARCore world mesh) |
| **6** Historic maps | **Yes** | Auto image extraction of roads/walls deferred |
| **7** ML ranking | **Partial** | Train/activate + ReviewedExamples/folds exist; Hudson Valley / regional datasets field-dependent |
| **8** Advanced tools | **Yes** | Viewshed, horizon, profile, compare, multi-dataset |
| **9** Interop/cloud | **Partial** | CSV/GPX/KML/GeoJSON/SHP/KMZ/PDF/PNG/GeoTIFF/QGIS/archive/GeoPackage/share; **no cloud multi-device sync**; QR is text handoff not full camera-scan product |

---

## Feature packs

### Site Package Pack — **Wired**
Dual surface, clip refine, Z-under-find, class filter, mosaic UX, clipped LAS, site package, PDF, boundary GPS, AI confirm-write.

### Field Closure Pack — **Wired**
Dig media timeline, voice digs, boundary edit, Home basemap/debrief, GeoPackage, annotated bundle, QR text, archive inspect/import, share package, ground quality scorecard, COPC soft-fail, dual-surface blink, viewport clip, penalty badges, AR-lite compass ring, multi-stop playlist, this-trip filter, ethics sticky, last-opened strip.

### AI pack 1 + 3 — **Wired**
Twenty specialist chips (Pack 1 + Pack 3), OpenAI primary / Gemini fallback, LIGHT_AZ/ALT + VIZ_MODE + NAV_TARGET + structured find fields, confirm-write, dictate STT, offline dig brief / return-trip / coverage gaps, inspected cell + focused candidate + secondary dataset picker + pack readiness chips + terrain quality in session pack.

### Product pack (scorecard / offline / ethics) — **Wired**
Home + Terrain quality banners; ethics on mark/dig; offline assist drafts; VIZ/NAV/lighting apply from AI replies.

---

## Original AI vision list (`TODO LIST.mdown`) — mapping

| Vision item | Reality |
|-------------|---------|
| Terrain anomaly ranker | **Wired** (local analysis + historic targets) |
| Multi-layer evidence explainer | **Wired** (evidence / layer verdicts / Focus AI → evidence chain) |
| Zoom-aware auto refine | **Removed by product choice** (manual refine / viewport clip only) |
| Homesite probability map | **Mostly** (engine + optional overlay; not a first-class always-on layer product) |
| Field verification assistant | **Wired** |
| Search grid / sweep plan | **Wired** (AI Sweep + offline drafts + route optimizer) |
| Before/after layer comparator | **Partial** (compare workspace + dual blink; no dedicated AI “what changed” essay) |
| False-positive detector | **Mostly** (cautions, FP autopsy, penalty badges) |
| Natural language terrain query | **Partial** (freeform chat + 20 specialists; no structured spatial filter DSL) |
| AI report builder | **Wired** (field report / debrief / partner handoff + share) |

---

## Explicitly deferred (still honest)

| Item | Reason |
|------|--------|
| Google ARCore plane / world-mesh anchors | Poor fit under canopy; GPS+compass camera AR is the field product path |
| Cloud multi-device sync delivery | External service; local queue + conflict resolver ready |
| Auto historic-map feature extraction | CV future work (manual feature entry only) |
| Two-epoch change detection | Not started |
| Regional ML training corpora | Field-dependent data collection |
| Full performance benchmark harness | Phase 4 incomplete |
| QR camera-scan import product | Text/payload handoff only |

---

## Acceptance notes

**Recently completed (this branch line):** dead-code wiring (ML store/folds/map agreement/horizon/historic features/voice STT/directional photos), star UI, AI pack polish (focus candidate, photo inventory, offline dig brief, secondary picker), **AR field guidance**.

**Still open for a “release gate” push:** device smoke on S24, large-mosaic stress, measured FP reduction, optional cloud backend when multi-device is required.
