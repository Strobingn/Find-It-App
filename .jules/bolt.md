## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2025-05-18 - QuickSelect for raster percentile normalization
**Learning:** Calculating percentile scale factors across large terrain rasters (250,000+ floats) using `.copyOf()` + `.sort()` allocates MBs of temporary float array copies and incurs O(N log N) sorting overhead (~4.5M operations per layer). Using an in-place O(N) QuickSelect directly on the populated finite float buffer eliminates sorting overhead and extra array copies.
**Action:** Favor QuickSelect order statistics over full sorting when computing percentiles or rank thresholds over large primitive arrays.
