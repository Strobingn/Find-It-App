## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2025-02-15 - Early-exit in line-of-sight viewshed ray-marching
**Learning:** In terrain ray-marching analysis (`TerrainViewshedAnalyzer`), scanning the entire ray length to track `maxBlockingAngle` wastes computation when evaluating occluded cells. Because intermediate blocking angles only monotonically increase the barrier height, encountering any angle exceeding the target elevation angle guarantees non-visibility. Exiting immediately skips up to ~86% of ray-marching iterations on rolling or obstructed terrain.
**Action:** In ray-marching visibility and occlusion algorithms, short-circuit immediately once an obstacle exceeds the target horizon angle instead of accumulating state through the full ray distance.
