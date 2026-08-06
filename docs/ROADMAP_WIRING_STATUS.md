# Find It Roadmap — Wiring Status (GROKV2.5.0)

**Audited:** 2026-08-06  
**Branch:** `GROKV2.5.0`  
**Definition of “fully wired”:** production UI entry + real domain path + failure/empty states + (where needed) persistence/tests. Domain-only or stub UI = **partial**. Not in codebase = **missing**.

---

## Executive summary

| Area | Status |
|------|--------|
| Core import / LAZ / terrain render | **Wired** |
| Tile download / mosaics | **Mostly wired** |
| Historic analysis / candidates | **Mostly wired** |
| Field verification offline | **Wired** (AR **missing**) |
| Historic map georef | **Wired** (auto feature extract **missing**) |
| ML ranker engine | **Partial** (engine + train UI; field datasets deferred) |
| Advanced tools (viewshed, profile, compare) | **Wired** |
| Interop export | **Mostly wired** (GeoPackage/share added; cloud **missing**) |
| Site Package Pack (10) | **Wired** |
| Field Closure Pack (20) | **Wired** (tracker: FEATURES_FIELD_CLOSURE_PACK.md) |
| AI packs 1 + 3 (20 field chips) | **Wiring in progress** on this branch (domain restored from main; UI integration) |
| Product pack (scorecard, tags, offline return-trip) | **Partial → wiring** |

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
| 7 Review candidates | **Wired** | AI workspace evidence, cell inspect |
| 8 Navigate | **Wired** | Compass HUD, playlist, route optimizer (AR **missing**) |
| 9 Record outcomes | **Wired** | Finds, digs, photos, voice, boundaries, sync queue |
| 10 Feedback to ranking | **Wired** | VerificationOutcome → FeatureTypeCalibration / reviewed examples |

---

## Phases 1–9

| Phase | Fully wired? | Gaps |
|-------|--------------|------|
| **1** Core workflows | **Mostly** | Release-gate device validation; area picker on every path mostly yes |
| **2** Tile/mosaics | **Mostly** | On-device large mosaic stress open |
| **3** Historic analysis | **Mostly** | Measured FP reduction on verified sites open |
| **4** Performance | **Partial** | Diagnostics/benchmark suite incomplete; cancel/cache present |
| **5** Field verification | **Yes offline** | **AR guidance** deferred |
| **6** Historic maps | **Yes** | Auto image extraction of roads/walls deferred |
| **7** ML ranking | **Partial** | Train/activate exists; Hudson Valley datasets field-dependent |
| **8** Advanced tools | **Yes** | Viewshed, profile, compare, multi-dataset |
| **9** Interop/cloud | **Partial** | CSV/GPX/KML/GeoJSON/SHP/KMZ/PDF/PNG/GeoTIFF/QGIS/archive/GeoPackage/share; **no cloud multi-device sync**; QR is text handoff not full camera scan product |

---

## Feature packs

### Site Package Pack — **Wired**
Dual surface, clip refine, Z-under-find, class filter, mosaic UX, clipped LAS, site package, PDF, boundary GPS, AI confirm-write.

### Field Closure Pack — **Wired**
See FEATURES_FIELD_CLOSURE_PACK.md (dig media, exports, Home cards, blink, playlist, ethics, etc.).

### AI pack 1 + 3 — **Must re-wire on GROKV2.5.0**
Domain files present after restore from main; UI chips/`runFieldAiFeature` were **absent** on pure GROKV6 lineage and are being re-integrated.

### Product pack (Next 10) — **Partial**
Scorecard on Home; ethics sticky; confirm-write; VIZ/NAV apply and offline return-trip need full AI panel integration.

---

## Explicitly not fully wireable as product (roadmap deferred)

| Item | Reason |
|------|--------|
| AR guidance | Device-bound future work |
| Cloud multi-device sync | External service; local queue ready |
| Auto historic-map feature extraction | CV future work |
| Two-epoch change detection | Not started |
| Full ARCore | Out of scope for field-closure |

---

## Acceptance: “fully wire everything” in this session

**In scope:** restore AI pack UI, terrain quality banner, export Tools completeness, share AI reply, coverage-gap entry, NAV/VIZ apply, offline return-trip, docs updated.

**Out of scope for full completion:** AR, cloud sync, auto map extraction, regional ML datasets, full performance benchmark harness.
