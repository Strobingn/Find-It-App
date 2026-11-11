## 2026-11-01 - Directional kernel loops in raster analysis
**Learning:** Iterating over `arrayOf(dx to dy, ...)` in full-raster analysis kernels (such as openness or sky-view factor) destructures `Pair`s and re-evaluates branch checks (e.g. diagonal unit distance calculation) millions of times across grid cells. Precomputing `dirX`, `dirY`, and `dirUnit` in primitive `IntArray`/`FloatArray` and iterating by index avoids millions of allocations and branching cycles.
**Action:** In inner loops across entire elevation grids, avoid `Pair` directional arrays and lambda wrappers like `.let { it * it }`; use primitive index loops with precomputed distances and direct squaring.

## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.
