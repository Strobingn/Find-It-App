# Field Closure Pack (20) + Phase 0 Wiring Design

**Date:** 2026-08-05  
**Status:** Approved for spec review (user approved approach C + feature list)  
**Branch (implementation):** `GROKV6` or `feature/field-closure-20` from `main`  
**Product:** Find It — offline-capable Android LiDAR / historic-site field app  

## Goal

1. **Phase 0 — Wiring audit:** Prove Site Package Pack features and the Home hub nav are fully wired end-to-end (not orphan domain code).  
2. **Phase 1–4 — Twenty new features** from roadmap gaps, shipped in four waves of five, each wave independently testable.

Does **not** rehash: AI packs 1 & 3 field chips, field UX packs 1–2, Site Package Pack (10), or built-in Homestead/Fort/Villa demos.

## Design rules (all work)

- **LiDAR ≠ metal** — no age, composition, or absolute dig depth as fact from terrain.  
- **Relative surface Z only** when elevation context is shown.  
- **Writes only on explicit user action** (confirm cards, export launchers, mark/dig).  
- **Greyscale UI only** — no brand hue in Material chrome.  
- **Offline-first** where possible; cloud never required for field use.  
- **Parallel agents** with non-overlapping file ownership when implementing.  
- **Honest uncertainty** — low-confidence / non-georeferenced states labeled.

## Approach

**Approach C (approved):** One design pack = Phase 0 wiring gate + 20 features in four waves.  
Rejected: A (big-bang 20 without gate), B (wiring-only then separate 20-cycle).

---

## Phase 0 — Wiring audit (must pass before Wave 1 ships)

### Scope

Audit and **fix** gaps only; do not invent new product features in Phase 0.

### Checklist

| ID | Feature | Pass criteria |
|----|---------|----------------|
| W1 | Dual surface + class filter | Analyze chips re-decode; failure message; disk cache key includes `allowedClasses` |
| W2 | Refine to survey boundary | Requires boundary + georef; progress UI; does not auto-reset zoom |
| W3 | Surface Z under find | `find_surface_z_card` when lat/lon present; disclaimer not dig depth |
| W4 | Mosaic open UX | `MosaicOpenUx` labels; open/resume/retry still loads terrain |
| W5 | Site package + clipped LAS + PDF | Zip contains summary/targets/optional LAS/PDF; CreateDocument works |
| W6 | Boundary GPS banner | Shows for NEAR_EDGE/OUTSIDE; clears without GPS/boundaries |
| W7 | AI confirm-write | Parses tags; Confirm writes via `applyAiFindSuggestions`; Dismiss never writes |
| W8 | Hub navigation | Home cards + 5 bottom tabs reach all destinations; secondary back → Home |

### Deliverables

- Short `docs/WIRING_AUDIT_FIELD_CLOSURE.md` with pass/fail per row and fix commits if needed.  
- Device smoke (optional but preferred): open LAZ, boundary, GPS, export, AI tag confirm.

### Exit

All W1–W8 **Pass** (or **Pass with known limitation** documented). Then Wave 1 may start.

---

## The 20 features

**Pack name:** Field Closure Pack (20)  
**Doc (shipping tracker):** `docs/FEATURES_FIELD_CLOSURE_PACK.md` (created at implement start)

### Wave 1 — Field day (5)

| # | Feature | User-visible outcome | Primary surface | Domain / notes |
|---|---------|----------------------|-----------------|----------------|
| 1 | Dig photo timeline | Per dig: ordered photos with timestamps | Field → dig detail | Reuse dig/photo storage; no new cloud |
| 2 | Voice note on dig | Record/play voice on dig log (parity with finds) | Field → dig detail | `VoiceNoteRecorder`; dig entity URIs if needed (Room migration) |
| 3 | Boundary edit vertices | Add/move/remove vertices; save | Field → boundaries | `SurveyBoundary` + DAO; no law claims |
| 4 | Offline basemap status on Home | Home card: ready / downloading / none | Home | Read existing offline basemap state |
| 5 | Session end debrief card | One-tap day summary + share text | Field or Tools | `FieldSessionStats` + share sheet |

### Wave 2 — Export / handoff (5)

| # | Feature | User-visible outcome | Primary surface | Domain / notes |
|---|---------|----------------------|-----------------|----------------|
| 6 | GeoPackage export | Write `.gpkg` of finds (and digs if simple) | Tools / Field export | New writer; Phase 9 gap; unit-test bytes/header |
| 7 | Annotated map image bundle | Zip: annotated PNG + simple HTML/README | Tools export | Builds on `ProjectExport` PNG |
| 8 | QR project share | Show QR encoding archive path/hash or content URI handoff | Tools | Payload = existing project archive; size limits documented |
| 9 | Import site package / archive | Pick zip → restore finds/trails/summary into project | Library | Validate manifest; never silent overwrite without confirm |
| 10 | Share site package (share sheet) | Share zip via Android chooser | Tools (beside CreateDocument) | `Intent.ACTION_SEND` + FileProvider |

### Wave 3 — LAZ / analysis honesty (5)

| # | Feature | User-visible outcome | Primary surface | Domain / notes |
|---|---------|----------------------|-----------------|----------------|
| 11 | Ground quality scorecard on Home + Terrain | Banner/card: valid %, canopy, CRS, georef | Home + Terrain | Wire `TerrainQuality` if present on main; else port scorecard |
| 12 | COPC open harden | Soft fail, progress, no crash on bad COPC | Library / open path | Harden existing COPC paths; cancelable |
| 13 | Two-surface blink compare | Toggle/blink ground ↔ DSM without full re-import each blink if both cached | Terrain Analyze | Prefer dual cached rasters; else debounce re-decode |
| 14 | Map-rectangle focus clip | Draw/use rectangle AOI → refine clip (not only survey polygon) | Terrain / Map | Normalize rect → `NormalizedRasterBounds` |
| 15 | Candidate penalty badges | UI badges: natural / modern / low density reasons | AI or Terrain candidate list | Display existing evidence fields; no new “proof” claims |

### Wave 4 — Navigation / finds polish (5)

| # | Feature | User-visible outcome | Primary surface | Domain / notes |
|---|---------|----------------------|-----------------|----------------|
| 16 | AR-lite compass ring | 2D compass + bearing ring to nav target (not full ARCore) | Terrain / Field HUD | Uses magnetometer + GPS already in app |
| 17 | Multi-stop NAV_TARGET playlist | Ordered list from AI tags; next/skip; handoff to nav | Field + AI | `pendingNavTargetIds` + route UI |
| 18 | Find filter: this trip only | Time-window chip (e.g. since session start / last 8h) | Field filters | Client-side filter on `timestamp` |
| 19 | Sticky ethics on mark + dig | Same ethics copy on GPS mark and dig start | Field / Terrain mark | Never invent ownership law |
| 20 | Last-opened project strip on Home | One-tap reopen last N LAZ projects | Home | `TerrainSessionStore` / saved library list |

### Explicitly out of pack

- Full ARCore guidance  
- Cloud multi-device sync  
- Two-epoch change detection  
- New 20 AI field-pack feature chips (packs 1+3 already)  
- Automatic historic-map feature extraction from imagery  
- GeoPackage interactive map viewer  

---

## Architecture

### Layers

```text
UI (Home / Terrain / Field / AI / Library / Tools)
    ↓ StateFlow + actions
HillshadeViewModel (+ small helpers if file size demands)
    ↓
Domain (pure Kotlin): export writers, boundary edit, stats, quality
    ↓
Room / files / share intents
```

### Isolation rules

- Each feature has **one primary UI surface** and optional Home/Tools status card.  
- New domain types live in focused files (`export/GeoPackageWriter.kt`, etc.).  
- Room migrations only when dig voice/photo schema needs it; version bump documented.  
- Cache keys for dual-surface/class already include options; dual-surface blink must not corrupt overview cache.

### Data flow examples

**Site package share (10):**  
`Tools → buildSitePackageBytes → FileProvider temp → ACTION_SEND`.

**Boundary edit (3):**  
`Field → edit vertices → SurveyBoundary copy → DAO upsert → map redraw`.

**Scorecard (11):**  
`ElevationGrid + GeoMetadata → TerrainQuality.from → StateFlow → Home card + Terrain banner`.

### Error handling

- Export/import: user-visible message; no crash on corrupt zip.  
- COPC/LAZ open: cancelable; prior terrain remains until success.  
- GPS/boundary: hide banners when sensors/data missing.  
- QR: if payload too large, offer file share instead of QR.

### Testing

| Layer | Requirement |
|-------|-------------|
| Domain writers / parsers | Unit tests (header magic, round-trip, filter logic) |
| ViewModel actions | Unit or focused tests where pure |
| Phase 0 | Checklist doc + optional device smoke |
| Regression | `assembleDebug` green each wave |

---

## Implementation waves (agent split)

| Wave | Features | Suggested agent split |
|------|----------|------------------------|
| 0 | W1–W8 audit/fix | 1 explore + fix agent, non-overlapping files |
| 1 | 1–5 | Domain dig/boundary / UI Field / Home card / tests |
| 2 | 6–10 | Export writers / import / Tools UI / tests |
| 3 | 11–15 | Quality banner / LAZ open / dual surface blink / badges |
| 4 | 16–20 | HUD / playlist / filters / ethics / Home strip |

After each wave: compile, unit tests for new modules, update `FEATURES_FIELD_CLOSURE_PACK.md` statuses.

---

## Success criteria

- [ ] Phase 0 all Pass  
- [ ] Features 1–20 each have UI entry + domain backing  
- [ ] Greyscale theme unchanged  
- [ ] No new metal/depth claims from LiDAR  
- [ ] ROADMAP.md section for Field Closure Pack  
- [ ] `docs/FEATURES_FIELD_CLOSURE_PACK.md` shipping table  

---

## Open decisions (defaults locked for implementers)

| Topic | Default |
|-------|---------|
| Branch | Prefer `GROKV6` continuing, or `feature/field-closure-20` from `main` if cleaner |
| GeoPackage scope | Points (finds) first; digs optional in same file |
| QR payload | Hash + project name + “open archive” if zip too large for QR bytes |
| Session “this trip” window | Default last 8 hours; user can clear filter |
| Dual-surface blink | Prefer two in-memory grids when both modes already decoded |

---

## Spec self-review

1. **Placeholders:** None remaining (no TBD).  
2. **Consistency:** Phase 0 gates Waves; features do not duplicate Site Package Pack.  
3. **Scope:** One pack doc; four implementation waves — appropriate for one design, multiple execution plans if needed.  
4. **Ambiguity:** Defaults locked in table above.

---

## Next step after user reviews this file

Invoke **writing-plans** to produce `docs/superpowers/plans/2026-08-05-field-closure-pack-20.md` (or wave-scoped plans), then implement Phase 0 first.
