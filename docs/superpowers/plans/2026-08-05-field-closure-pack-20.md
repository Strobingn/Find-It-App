# Field Closure Pack 20 Implementation Plan

> **For agentic workers:** Execute wave-by-wave. Prefer parallel agents with non-overlapping files. Phase 0 wiring call sites already present — document Pass and only fix real gaps.

**Goal:** Ship Phase 0 audit doc + 20 Field Closure features fully UI-wired.

**Architecture:** Domain modules + HillshadeViewModel StateFlows + Home/Field/Tools/Terrain surfaces. Greyscale; LiDAR ≠ metal.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Android share/FileProvider.

---

## Phase 0

- [x] Call-site audit (W1–W8 present in codebase)
- [ ] Write `docs/WIRING_AUDIT_FIELD_CLOSURE.md` Pass table
- [ ] Fix only if compile or dead UI found

## Wave 1 (features 1–5)

- [ ] Dig photo timeline UI
- [ ] Dig voice note UI  
- [ ] Boundary vertex edit
- [ ] Home offline basemap status
- [ ] Session end debrief share card

## Wave 2 (6–10)

- [ ] GeoPackage writer + export button
- [ ] Annotated image bundle zip
- [ ] QR share helper + Tools UI
- [ ] Import project/site archive
- [ ] Share site package ACTION_SEND

## Wave 3 (11–15)

- [ ] Scorecard on Home + Terrain banner
- [ ] COPC open soft-fail/progress polish
- [ ] Dual-surface blink toggle
- [ ] Rectangle focus clip refine
- [ ] Candidate penalty badges in UI

## Wave 4 (16–20)

- [ ] AR-lite compass ring HUD
- [ ] NAV_TARGET playlist UI
- [ ] This-trip find filter
- [ ] Ethics sticky on mark + dig
- [ ] Home last-opened projects strip

## Docs

- [ ] `docs/FEATURES_FIELD_CLOSURE_PACK.md`
- [ ] ROADMAP section update
