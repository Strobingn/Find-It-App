# Feature detection evaluation

Automatic finds are scored against **public historic inventories**, never against old maps.

## Ground truth

Allowed sources:

- National Register of Historic Places (NRHP) coordinate entries
- USGS GNIS historical features
- OpenStreetMap `historic=*` / ruins

Do **not** use USGS historical topo sheets, Sanborns, or other old maps as scoring truth. Those maps only raise detector confidence after a find exists.

## Hold-out rule

Hold out **whole named areas**, not random raster patches. The bundled harness uses:

- `holdout-adirondack-west`
- `holdout-hudson-valley`

## Auto-show threshold

`HistoricFeatureClassifier.DEFAULT_AUTO_SHOW_THRESHOLD` (0.86) is the lowest cutoff that still meets about **95% precision** on those hold-outs. Only `FeatureDisplayBucket.AUTO` finds are drawn automatically. The rest go to the **maybe** list. Mark / Reject / Add find remain available so AI is never the only path.

## How to run

From the repo root:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.analysis.eval.FeatureDetectionEvalTest
./gradlew :app:testDebugUnitTest --tests com.example.analysis.HistoricFeatureClassifierTest
./gradlew :app:testDebugUnitTest --tests com.example.data.LidarRasterizerTest
./gradlew :app:testDebugUnitTest --tests com.example.data.historicmap.*
```

The unit test calls `FeatureDetectionEval.runHoldOut()` with offline detections for the bundled areas and asserts:

- hold-out IDs are whole areas
- old-map sources are absent from the truth set
- chosen auto-show threshold keeps precision ≥ 0.95

To add a new hold-out area, append an `EvalArea` in `FeatureDetectionEval.bundledAreas()` with NRHP / GNIS / OSM historic coordinates and a matching detection list in the test.
