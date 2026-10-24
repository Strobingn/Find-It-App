## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2025-05-20 - RectSumTable ringMean precomputed RingBounds
**Learning:** In spatial neighborhood filters over 2D grids (like `RectSumTable.ringMean`), computing annulus row offsets (`minDx`, `maxDx`) per cell causes millions of redundant `Math.sqrt` and `integerSqrtFloor` calls. Precomputing and caching `RingBounds` by `(innerRadius, outerRadius)` eliminates all sqrt calculations in the hot loop.
**Action:** Precompute geometric shape bounds and row offset intervals outside cell-iteration loops when performing spatial neighborhood queries.
