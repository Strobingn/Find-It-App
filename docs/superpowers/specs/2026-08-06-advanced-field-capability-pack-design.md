# Advanced Field Capability Pack — Program Design

**Date:** 2026-08-06  
**Branch:** `GROKV2.5.0`  
**Product:** Find It — offline-capable Android LiDAR / historic-site field app  
**Status:** Design approved in brainstorming; awaiting user review of this written spec before implementation plans  

---

## 1. Purpose

Close four remaining deferred roadmap items as **field-usable sequential slices**, without breaking offline-first defaults, greyscale UI, or honesty rules (LiDAR ≠ metal / age / absolute dig depth; no auto-write finds).

This document is the **umbrella program pack**. Each track later gets its own implementation plan (writing-plans) in delivery order.

---

## 2. Locked decisions

| Decision | Choice |
|----------|--------|
| Packaging | One umbrella program pack, then per-track plans |
| Structure | Sequential field-usable slices |
| Order | (1) Large-mosaic stress QA → (2) Two-epoch change → (3) Neural map vectorization → (4) ARCore Geospatial / VPS |
| Track 2 primary question | Where did the **bare-earth surface** change? |
| Track 2 expansion | Surface Δ **plus** candidate appeared/disappeared/score-changed list |
| Track 3 inference | **Cloud optional when online**; local ink always |
| Track 4 coverage fail | **Graceful degrade** to existing world-anchor + compass/camera AR |

---

## 3. Product rules (non-negotiable)

1. **Offline-first by default** — cloud only for optional neural enhance and Geospatial.  
2. **Confirm-write** — auto extract / candidate delta / Geospatial markers never silently write finds.  
3. **Honesty** — surface change and map vectors are morphology / ink evidence only; no metal identity.  
4. **Greyscale UI** — no earth-tone or rainbow “heat” that implies certainty.  
5. **Relative Z only** — difference maps report relative elevation change, not dig depth to metal.  
6. **Explicit mode labels** — operator always knows Geospatial vs fallback, local vs cloud vectorization, stress pass/fail.

---

## 4. Architecture

### 4.1 Shared spine

Grows as tracks land; not a big-bang platform rewrite.

| Module / concern | Role |
|------------------|------|
| `AppMemoryBudget` | Cap decode / mosaic / dual-grid hold |
| `PerfHarness` | Extended scenarios + JSONL for stress QA |
| Grid align / resample utilities | Shared by Track 2 (and any dual-grid work) |
| `TerrainQuality` | Report data quality on epoch grids |
| Honesty strings + confirm-write patterns | Reuse across new UIs |

### 4.2 Track modules (intended)

```text
Shared spine
    ├── Track1: MosaicStressSuite + PerfHarness scenarios
    ├── Track2: EpochPair, DemAligner, SurfaceChangeDetector, CandidateDelta
    ├── Track3: MapVectorizationGateway (local ink + optional cloud neural)
    └── Track4: GeospatialPoseProvider → ArGuidance degrade path
```

### 4.3 UI entry points

| Track | Entry |
|-------|--------|
| 1 | Tools → Perf harness / Import mosaic open path + stress report |
| 2 | Terrain or Compare: Epoch A / B picker → difference overlay + candidate delta list |
| 3 | Map historic panel: Auto-extract (local) / Enhance online |
| 4 | AR guidance: Geospatial when ready; else world-anchor + camera AR |

### 4.4 Data flow — Track 2 (headline product)

1. Operator selects Epoch A and Epoch B (analyzed datasets or open elevation grids).  
2. System validates CRS/georef; if unaligned, `DemAligner` resamples B into A’s grid (or fails with clear message).  
3. `SurfaceChangeDetector` computes residual / |ΔZ| map and ranked change zones.  
4. Local detectors (or cached candidates) produce sets A and B; `CandidateDelta` matches by spatial proximity + type and emits appeared / disappeared / score-changed.  
5. UI shows greyscale difference layer + lists; optional **suggested** map markers require user confirm to log as finds.  
6. Honesty line always visible.

---

## 5. Track scopes and exit criteria

### Track 1 — Large-mosaic stress QA

**In scope**

- Stress suite: multi-tile open, memory under `AppMemoryBudget`, cancel mid-open, reopen from cache, dual surface on mosaic if already supported.  
- Device-runnable report (UI + optional `perf-harness.jsonl` append).  
- Document “large” fixture definition (tile count / approx points / target device class: e.g. modern flagship like S24 Ultra).

**Out of scope**

- New tile download providers.  
- Full rewrite of mosaic seam blending for aesthetics only.

**Exit criteria**

- [ ] Suite runs from Tools or debug entry with pass/fail per scenario.  
- [ ] No process kill / unhandled OOM on defined large fixture (or hard fail with recovery message if device too small).  
- [ ] Cancel mid-open restores prior terrain.  
- [ ] Reopen valid mosaic avoids full recompute when cache hit.  
- [ ] Report shareable as text.

### Track 2 — Two-epoch surface change + candidate delta

**In scope**

- Epoch pair selection and persistence of last pair keys (optional, lightweight).  
- DEM alignment / resample B→A.  
- Bare-earth difference visualization (greyscale).  
- Ranked change zones (area, mean |ΔZ|, centroid grid %).  
- Candidate delta list: appeared / disappeared / score-changed.  
- Empty, misaligned CRS, and missing analysis states.

**Out of scope**

- Metal/age/depth claims.  
- Auto-writing finds from deltas.  
- Three-or-more epoch timelines in v1.  
- Full AI essay “what changed” (may be a later AI chip).

**Exit criteria**

- [ ] Operator can load A/B and see difference layer when grids align.  
- [ ] Misalignment fails with actionable message (not blank map).  
- [ ] Candidate delta list populates when local analysis exists for both epochs (or runs on demand with progress).  
- [ ] No auto-find writes; honesty line present.  
- [ ] Unit tests for align residual and candidate matching rules.

### Track 3 — Neural map vectorization (cloud optional)

**In scope**

- Keep and improve local ink path (`HistoricMapFeatureExtractor` or successor) as default offline.  
- Optional cloud enhance when API key + network available (gateway pattern like `TerrainAiGateway`).  
- Results as draft `HistoricMapFeature` rows with “Auto” / “Cloud enhance” notes; user reviews/deletes.  
- Requires georeference transform (same as current auto-extract).

**Out of scope**

- Mandatory on-device heavy DL weights in v1.  
- Auto-commit without review.  
- Full OCR of map text labels (optional later).

**Exit criteria**

- [ ] Offline: local extract still works without network.  
- [ ] Online: enhance path produces draft features or clear failure.  
- [ ] Mode label shows Local vs Cloud.  
- [ ] Drafts never become finds without existing confirm paths.

### Track 4 — ARCore Geospatial / VPS

**In scope**

- Integrate ARCore Geospatial API when Play Services AR + coverage allow.  
- `GeospatialPoseProvider` feeds AR guidance reticle / anchor.  
- Degrade path: existing `ArCoreWorldAnchor` + camera AR + compass.  
- Clear mode label: Geospatial | World anchor | Compass fallback.

**Out of scope**

- Hard-require VPS for navigation.  
- Full Geospatial Creator content pipeline.  
- Guaranteed accuracy under canopy (must disclose).

**Exit criteria**

- [ ] On device with Geospatial support and coverage: pose used when valid.  
- [ ] Without coverage/install: no crash; world-anchor/compass path works.  
- [ ] Honesty line retained.  
- [ ] Unit tests for degrade decision logic (pure functions).

---

## 6. Error handling

| Situation | Behavior |
|-----------|----------|
| Mosaic OOM / budget exceed | Abort open; restore previous terrain; message with memory tip |
| Mosaic cancel | Cooperative cancel; prior terrain remains |
| Epoch CRS / size mismatch | Do not invent alignment; offer resample only when safe; else fail clearly |
| Epoch missing local analysis | Candidate delta empty with “run analysis on both epochs” |
| Cloud neural offline / no key | Silent fall through to local ink; status text |
| Cloud neural API error | Message + local result unchanged |
| Geospatial unavailable | Automatic degrade; mode label updates |
| GPS poor for AR | Accuracy warning (existing pattern) |

---

## 7. Testing strategy

| Layer | Tracks | Focus |
|-------|--------|--------|
| Unit | All | Align math, residual threshold, candidate match distance, degrade decision, stress scenario definitions |
| Instrumented / device | 1, 4 | Mosaic stress on physical device; Geospatial availability probe |
| Manual field smoke | 2, 3 | Two real DEMs or fixtures; historic map enhance online/offline |
| Regression | All | `assembleDebug` green; existing AI/AR/export tests still pass |

**Track 2 matching rules (explicit):**

- Match candidates if same `type` (or mapped type) and Euclidean grid distance ≤ **3%** of map diagonal (or configurable constant `CANDIDATE_MATCH_PERCENT = 3f`).  
- Appeared: in B only. Disappeared: in A only. Score-changed: matched and \|scoreB − scoreA\| ≥ **0.1**.  
- Unmatched types listed separately; no forced merges across types.

---

## 8. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Large mosaics still OOM on low RAM | Hard budget + soft-fail; document min device; don’t claim universal support |
| Two-epoch misregistration looks like “change” | RMSE/residual QA gate; low-confidence banner when alignment weak |
| Woods kill Geospatial | Degrade is primary product path; Geospatial is enhancement |
| Cloud neural cost / PII | Map crops only; no auto-upload of finds; user-initiated enhance |
| Scope creep across four tracks | Sequential exits; no parallel full rewrites |

---

## 9. Implementation plan sequencing (post-spec)

1. **writing-plans** for Track 1 only (mosaic stress QA).  
2. Implement Track 1 → verify.  
3. writing-plans Track 2 → implement → verify.  
4. writing-plans Track 3 → implement → verify.  
5. writing-plans Track 4 → implement → verify.  
6. Refresh `docs/ROADMAP_WIRING_STATUS.md` after each track exit.

No implementation starts until this umbrella design is reviewed and approved, and Track 1 has its own plan.

---

## 10. Relationship to current codebase

| Existing | Reuse |
|----------|--------|
| `MosaicOpenUx`, mosaic project Room, download queue | Track 1 stress targets |
| `PerfHarness`, `AppMemoryBudget` | Track 1 + metrics |
| `ElevationGrid`, dual surface, local analysis | Track 2 grids + candidates |
| `DatasetComparison`, Compare UI | Possible UI host for epoch pick |
| `HistoricMapFeatureExtractor`, georef, feature DAO | Track 3 base + storage |
| `TerrainAiGateway` / credential vault | Track 3 cloud pattern |
| `ArGuidanceScreen`, `ArCoreWorldAnchor`, `ArCoreAvailability` | Track 4 degrade stack |

---

## 11. Non-goals for this program

- Cloud multi-device sync product.  
- Two-epoch as proof of buried metal.  
- Full on-device foundation model for maps.  
- Geospatial-only field workflow.  
- Rewriting hub UI theme.

---

## 12. Approval record

| Item | Status |
|------|--------|
| §1 Goals & rules | Approved in session |
| §2 Architecture | Approved in session |
| §3 Scopes (Track 2 expanded: surface Δ + candidate delta) | Approved in session |
| §4 Errors / testing / risks | Approved in session |
| Written spec (this file) | **Pending user file review** |

---

## Spec self-review (2026-08-06)

| Check | Result |
|-------|--------|
| Placeholders | None remaining (no TBD) |
| Consistency | Order and Track 2 expansion match locked decisions |
| Scope | Umbrella only; per-track plans deferred — explicit |
| Ambiguity | Candidate match rules and degrade behavior specified numerically / behaviorally |
