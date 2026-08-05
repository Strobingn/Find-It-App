# GROKV5 product pack (Next 10) — Done

Product/UX features that close the “Next 10” pipeline after AI packs 1 and 3.
Complements cloud copilots with LAZ quality reporting, structured-tag apply actions,
offline drafts, trail-density coverage targets, and field ethics friction.

**Branch:** `GROKV5` (not merge to `main` until green)  
**Status:** Done (shipping on GROKV5)

| # | Feature | Track | What it does |
|---|---------|--------|--------------|
| 1 | **Ground quality scorecard on open LAZ** | LAZ | On open, surfaces structured ground-surface quality (bucket, cell coverage, samples/cell, spikes rejected) so bare-earth trust is visible before analysis. |
| 2 | **CRS / units / density banner** | LAZ | One-liner banner with coordinate reference, units, and point density so georeference risk is obvious on every load. |
| 3 | **Share last AI reply** | AI UX | Share-sheet export of the latest AI field-pack answer for teammate handoff without leaving the app. |
| 4 | **Apply `VIZ_MODE=` from AI** | AI UX | Parses `VIZ_MODE=` tags from AI replies and applies the matching terrain visualization mode. |
| 5 | **Apply `NAV_TARGET id=` to navigate** | AI → Finds | Parses ordered `NAV_TARGET id=<long>` tags and hands signal ids to navigation / Finds flow (user-driven apply). |
| 6 | **Confirm/dismiss metal & outcome suggestions** | AI UX | Dismissable suggestion cards for `METAL_TYPE` / `OUTCOME` / status; never auto-writes finds without user confirm (confirm-write remains a later polish). |
| 7 | **AI field pack filter: All / Pack 1 / Pack 3** | AI UX | Chips filter the 20 field-pack features so operators can focus one pack at a time. |
| 8 | **Offline local draft for return-trip** | AI offline | When cloud keys are missing, produces a local ordered return-trip draft from starred finds, open digs, trails, and GPS (no network required). |
| 9 | **Coverage gap map targets from trail density** | Field | Turns trail-density / sweep-coverage gaps into map targets so unswept high-value ground is actionable offline. |
| 10 | **Ethics disclaimer sticky on dig actions** | Field | Sticky caution on dig / mark actions (private land, cemeteries, modern disturbance); never invents ownership law. |

## Design rules

- **LiDAR ≠ metal** — no age/depth/metal claims as fact from terrain alone.
- Structured tags apply only with explicit user action: `VIZ_MODE`, `NAV_TARGET`; metal/outcome suggestions are dismissable and never silent writes.
- Offline-first for return-trip order and coverage-gap targets when cloud AI is unavailable.
- Ground scorecard and CRS banner report measured metadata and uncertainty honestly.

## Related packs

- Field UX pack 1: [FEATURES_GROKV5.md](FEATURES_GROKV5.md)
- Field finds pack 2: [FEATURES_PACK2.md](FEATURES_PACK2.md)
- AI pack 1: [FEATURES_AI_PACK.md](FEATURES_AI_PACK.md)
- AI pack 3: [FEATURES_AI_PACK3.md](FEATURES_AI_PACK3.md)
- Pipeline tracker: [../ROADMAP.md](../ROADMAP.md) § GROKV5 feature pipeline (2026-08)
- Active checklist: [../TODO.md](../TODO.md)
