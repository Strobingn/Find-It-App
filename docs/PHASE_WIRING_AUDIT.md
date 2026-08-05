# Phase Wiring Audit — GROKV5 (main @ 3584351)

**Date:** 2026-08-04  
**Worktree:** `F:\find-it-app\GROKV5` branch `GROKV5`  
**Definition of done (ROADMAP):** production UI + real data + load/error/recovery + persistence + tests + no regression.

## Summary

| Phase | Engines / unit tests | Production UI | Fully wired? |
| ----- | -------------------- | ------------- | ------------ |
| 1 Core workflows | Yes | Yes (Sprint 1 acceptance 2026-08-03) | **Yes** (device release-gate items remain) |
| 2 Tile acquisition / mosaics | Yes | Yes (area picker, queue, resume) | **Mostly** (on-device multi-tile release validation open) |
| 3 Historic-feature analysis | Yes | Yes (AI workspace + evidence) | **Mostly** (field-area false-positive metrics open) |
| 4 Performance architecture | Partial | Partial | **Partial** |
| 5 Field verification | Yes | **Wired this session** | **Yes for offline record path** (AR deferred) |
| 6 Historic-map intelligence | Yes (GeoReferencer, agreement) | Yes (control points, fit, swipe, side-by-side) | **Yes** (auto feature extraction still future) |
| 7 ML ranking | Yes | Yes (Gemini assistant train/activate) | **Mostly** (regional datasets field-data dependent) |
| 8 Advanced terrain tools | Yes | Yes (viewshed, profile, compare) | **Mostly** |
| 9 Interop / cloud | Partial writers | Partial exports (CSV/GPX/KML/SHP/KMZ/PDF/PNG) | **No** — GeoTIFF/QGIS/archive UI + cloud not wired |

## Phase 5 — what was already wired on main

- Breadcrumb GPS trails (persist + map draw)
- Compass / bearing navigation to targets
- Voice notes + photos on finds
- Target visit outcomes (confirmed / rejected / inconclusive) → ranking feedback
- Optimal target route (`TargetRouteOptimizer` + map polyline)
- Sweep coverage from trails on map
- Site clustering on Finds tab

## Phase 5 — engine-only before this session

| Component | Status before | Status after |
| --------- | ------------- | ------------ |
| `ExcavationLogEntry` + Room | Entity/DAO/tests only | ViewModel observe/save/delete + Edit-find dig UI + Tools card |
| `SurveyBoundary` + Room | Entity/DAO/tests only | Create from trail / around GPS, list/delete, Tools card |
| `FieldSyncQueue` + `pending_sync` | Engine/tests only | Enqueue on target/trail/dig/boundary mutations, Finds queue card, Tools status |

## Phase 5 still deferred (documented)

- AR guidance (device-bound future work per ROADMAP)
- Live cloud delivery of the sync queue (Phase 9)

## Phase 6 — wired this session (after Phase 5)

| Component | Before | After |
| --------- | ------ | ----- |
| Control-point georeferencing | Engine + tests only | Map panel: image X/Y crosshair + map tap → Fit → ground overlay + Room |
| Opacity / swipe / side-by-side | Opacity only | Opacity + swipe blend on active map + side-by-side dialog |
| Confidence / RMSE labels | Fit metadata in engine | Always shown on historic map panel |

## Next phase to fully wire

**Phase 9 remaining exports** (GeoTIFF / QGIS project / portable archive UI) or Phase 4 performance polish, depending on priority. Automatic historic-map feature extraction remains future work under Phase 6.

## AI feature TODO list (`TODO LIST.mdown`)

Separate product vision (anomaly ranker, evidence explainer, auto refine, …). Core ranking / evidence / refine already exist in Sprint 3 / terrain refine; treat that list as product enhancements on top of ROADMAP phases, not a parallel phase counter.
