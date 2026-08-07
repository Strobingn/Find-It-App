# Field Closure Pack 20 Implementation Plan

> **For agentic workers:** Execute wave-by-wave. Prefer parallel agents with non-overlapping files. Phase 0 wiring call sites already present — document Pass and only fix real gaps.

**Goal:** Ship Phase 0 audit doc + 20 Field Closure features fully UI-wired.

**Architecture:** Domain modules + HillshadeViewModel StateFlows + Home/Field/Tools/Terrain surfaces. Greyscale; LiDAR ≠ metal.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Android share/FileProvider.

---

## Phase 0

- [x] Call-site audit (W1–W8 present in codebase)
- [x] Write `docs/WIRING_AUDIT_FIELD_CLOSURE.md` Pass table
- [x] Fix only if compile or dead UI found

## Wave 1 (features 1–5)

- [x] Dig photo timeline UI
- [x] Dig voice note UI  
- [x] Boundary vertex edit
- [x] Home offline basemap status
- [x] Session end debrief share card

## Wave 2 (6–10)

- [x] GeoPackage writer + export button
- [x] Annotated image bundle zip
- [x] QR share helper + Tools UI
- [x] Import project/site archive
- [x] Share site package ACTION_SEND

## Wave 3 (11–15)

- [x] Scorecard on Home + Terrain banner
- [x] COPC open soft-fail/progress polish
- [x] Dual-surface blink toggle
- [x] Rectangle focus clip refine
- [x] Candidate penalty badges in UI

## Wave 4 (16–20)

- [x] AR-lite compass ring HUD
- [x] NAV_TARGET playlist UI
- [x] This-trip find filter
- [x] Ethics sticky on mark + dig
- [x] Home last-opened projects strip

## Docs

- [x] `docs/FEATURES_FIELD_CLOSURE_PACK.md`
- [x] ROADMAP section update

## SDD follow-up (2026-08-06)

- [x] FieldAiCopilot + FieldOfflineAssist restored and compiled
- [x] TargetSignal.starred + Room migration 15→16
- [x] AI field pack UI (AiCloudPanel chips + AiAnalysisWorkspace session pack)
- [x] compileDebugKotlin green
