## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2025-05-18 - TerrainViewshedAnalyzer line-of-sight ray math
**Learning:** In grid ray-marching algorithms, calling `kotlin.math.hypot` inside inner loops introduces scale/branching overhead. Replacing `hypot(dx, dy)` with `Math.sqrt(dx * dx + dySq)`, pre-computing column `dx` offsets, using `invSteps` reciprocal multiplication, and cancelling out uniform scalar factors (`cellSize`) from relative slope comparisons eliminates millions of division and method calls per viewshed scan.
**Action:** In spatial ray-marching loops, pre-compute grid axis offsets and compare unscaled relative ratios directly to avoid redundant scalar divisions in tight inner loops.
