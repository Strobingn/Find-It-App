# Dead-code wiring fix (2026-08-06)

Response to audit: domain existed with unit tests but no production callers; two claims overstated.

## Wired this pass

| Gap | Fix |
|-----|-----|
| ReviewedExampleStore never written | `HillshadeViewModel.updateLoggedSignal` appends `ReviewedCandidateExample.fromSignal` when outcome is set |
| ML train ignored ReviewedExamples / folds / hard negatives | `trainRankerFromFeedback` loads store, uses `SpatialFoldSplitter` + `HardNegativeMiner` |
| MapTerrainAgreement.rankingAdjustment unused | Applied in target refine / rank path with published map agreement score |
| HistoricMapFeature no create UI | Manual add from control points on Map tab |
| TerrainViewshedAnalyzer.horizon unreachable | Horizon button + result card on Terrain |
| SyncConflictResolver orphan | Called from sync coalescing / Tools status when applicable |
| Directional photos claim | Photos store optional `|bearing=` from compass when available |
| Voice → structured find claim | Device speech recognition fills prompt for VOICE_STRUCTURED_FIND |

## Still deferred (honest)

| Item | Status |
|------|--------|
| Cloud multi-device sync | Not started; local queue + conflict resolver ready |
| Google ARCore world-mesh anchors | Deferred (poor canopy fit); **camera AR guidance shipped** on GROKV2.5.0 |
| Auto historic-map extraction | Deferred (manual feature entry only) |
