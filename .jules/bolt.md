## 2025-02-14 - HuntZoneClusterer group aggregation allocations
**Learning:** Using functional Kotlin idioms (`members.map { ... }.average()`, `groupBy`, `maxWith`) during cluster group metric calculation allocates numerous temporary objects (`ArrayList`, `Map.Entry`, `Double`, `Pair`) for each cluster. Replacing these with single-pass primitive arrays (`DoubleArray`, `BooleanArray`) eliminates thousands of allocations in dense target sets.
**Action:** When computing per-group metrics over collections, favor single-pass loops with reused primitive arrays and explicit tracking over chained collection transformations.

## 2025-02-15 - Disjoint-Set Forest grouping without groupBy
**Learning:** Calling `targets.indices.groupBy { find(it) }` after union-find constructs a `LinkedHashMap<Int, MutableList<Int>>`, boxing every primitive index into `java.lang.Integer` and allocating `Map.Entry` objects along with nested `ArrayList`s. In dense clustering paths, replacing `groupBy` with flat integer linked lists (`head` and `next` arrays of size N) preserves insertion order while reducing intermediate heap allocations to 0 objects beyond the primitive index buffers.
**Action:** When grouping elements by disjoint-set representative roots or integer keys in hot paths, use primitive `head`/`next` linked-list arrays instead of `groupBy`.
