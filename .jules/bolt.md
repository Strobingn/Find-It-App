## 2026-03-30 - TerrainViewshedAnalyzer line-of-sight ray casting optimization
**Learning:** `kotlin.math.hypot` invokes double-precision scale overflow checks that add heavy overhead when executed millions of times per viewshed scan. Replacing `hypot` with `sqrt(dx * dx + dy * dy)`, short-circuiting rays immediately upon encountering blocking terrain (`slope >= targetSlope`), and skipping duplicate step cells reduces viewshed computation time significantly without affecting accuracy.
**Action:** In ray casting and grid traversal loops, avoid `hypot`, exit ray steps on first obstruction, and cancel out constant grid scalar factors before the inner loop.

## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.
