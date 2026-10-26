## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2025-05-18 - Early exit and squared-distance filtering in line-of-sight analysis
**Learning:** Evaluating all ray steps during viewshed analysis wastes compute cycles on blocked targets. Returning early as soon as an intermediate cell's elevation angle meets or exceeds the target angle (`angle >= targetAngle`) avoids stepping remaining cells. Pre-filtering with squared distance (`distanceSq > maxRadiusSq`) and using `kotlin.math.sqrt` avoids unnecessary `Math.hypot` calls across the grid.
**Action:** In spatial ray-walking algorithms, short-circuit immediately on obstacle hit and filter out-of-range cells with squared distances before computing square roots.
