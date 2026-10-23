## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2026-10-23 - Replaced Math.hypot in line-of-sight raycasts with parametric distances
**Learning:** `Math.hypot` in Java/Kotlin handles subnormal numbers, overflow scaling checks, and double conversions, making it expensive when called millions of times inside tight raycast loops during 2D grid viewshed analysis. Since raycasts progress linearly along a line segment, intermediate cell distance is strictly proportional to step fraction ($fraction \times targetDistance$), avoiding trigonometric or hypot calls entirely.
**Action:** In spatial grid traversals and line-of-sight raycasts, compute line length once and derive intermediate distances linearly or check squared distance thresholds ($dx^2 + dy^2$) before computing square roots.
