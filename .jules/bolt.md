## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2025-05-20 - Early-exit line-of-sight occlusion in viewshed sampling
**Learning:** Full ray-traversal in viewshed calculation evaluated all intermediate steps along a line-of-sight ray to find `maxBlockingAngle`, even when early terrain obstacles already occluded the target cell. Returning `false` on the first occluding cell (`slope >= targetSlope`) skips remaining ray steps for occluded target cells and eliminates constant division operations (`/ cellSize`).
**Action:** In line-of-sight and viewshed ray sampling, return early as soon as an occluding obstacle is encountered and normalize scale metrics to avoid inner-loop constant factors.
