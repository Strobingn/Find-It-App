package com.example.analysis

/**
 * Fast order-statistic selection (QuickSelect) and percentile normalization helpers.
 *
 * Replaces full O(N log N) sorting and intermediate array allocations with an in-place O(N)
 * expected-time QuickSelect algorithm over the valid finite elements buffer.
 */
internal object PercentileNormalizer {

    /**
     * Selects the k-th smallest element in the given range of arr in-place (O(N) expected time).
     *
     * After execution, arr[k] contains the k-th order statistic.
     */
    fun quickSelect(arr: FloatArray, left: Int, right: Int, k: Int): Float {
        if (left >= right) {
            val clampedIndex = left.coerceIn(0, arr.size - 1)
            return arr[clampedIndex]
        }
        var l = left
        var r = right
        while (l < r) {
            val mid = l + (r - l) / 2
            val pivot = arr[mid]
            var i = l
            var j = r
            while (i <= j) {
                while (arr[i] < pivot) i++
                while (arr[j] > pivot) j--
                if (i <= j) {
                    val tmp = arr[i]
                    arr[i] = arr[j]
                    arr[j] = tmp
                    i++
                    j--
                }
            }
            if (k <= j) {
                r = j
            } else if (k >= i) {
                l = i
            } else {
                break
            }
        }
        return arr[k]
    }

    /**
     * Finds the value at index among the first count elements of values in O(N) expected time,
     * without allocating a copy array or performing a full O(N log N) sort.
     */
    fun computeScaleAtIndex(values: FloatArray, count: Int, index: Int): Float {
        if (count <= 0) return 1f
        val k = index.coerceIn(0, count - 1)
        return quickSelect(values, 0, count - 1, k).coerceAtLeast(1e-6f)
    }
}
