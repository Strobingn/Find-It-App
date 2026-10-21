package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class PercentileNormalizerTest {

    @Test
    fun `quickSelect matches full sort on random float array`() {
        val rand = Random(42)
        val size = 500
        val arr = FloatArray(size) { rand.nextFloat() * 100f }
        val sorted = arr.copyOf()
        sorted.sort()

        for (k in listOf(0, 10, 50, 250, 480, 499)) {
            val copy = arr.copyOf()
            val selected = PercentileNormalizer.quickSelect(copy, 0, size - 1, k)
            assertEquals("Mismatch at order statistic k=$k", sorted[k], selected, 1e-6f)
        }
    }

    @Test
    fun `quickSelect handles duplicates correctly`() {
        val arr = floatArrayOf(5f, 2f, 5f, 1f, 5f, 2f, 8f, 1f, 5f)
        val sorted = arr.copyOf()
        sorted.sort()

        for (k in arr.indices) {
            val copy = arr.copyOf()
            val selected = PercentileNormalizer.quickSelect(copy, 0, arr.size - 1, k)
            assertEquals("Mismatch at index k=$k", sorted[k], selected, 1e-6f)
        }
    }

    @Test
    fun `quickSelect handles sorted and reverse sorted arrays`() {
        val sortedAsc = FloatArray(100) { it * 1.5f }
        val sortedDesc = FloatArray(100) { (100 - it) * 1.5f }

        for (k in listOf(0, 25, 50, 75, 99)) {
            val copyAsc = sortedAsc.copyOf()
            val valAsc = PercentileNormalizer.quickSelect(copyAsc, 0, 99, k)
            assertEquals(k * 1.5f, valAsc, 1e-6f)

            val copyDesc = sortedDesc.copyOf()
            val sortedCopyDesc = sortedDesc.copyOf()
            sortedCopyDesc.sort()
            val valDesc = PercentileNormalizer.quickSelect(copyDesc, 0, 99, k)
            assertEquals(sortedCopyDesc[k], valDesc, 1e-6f)
        }
    }

    @Test
    fun `computeScaleAtIndex returns expected percentile scale`() {
        val arr = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f)
        val count = arr.size
        val index = (count * 0.90).toInt().coerceIn(0, count - 1) // index 9 => 100f

        val copy = arr.copyOf()
        val scale = PercentileNormalizer.computeScaleAtIndex(copy, count, index)
        assertEquals(100f, scale, 1e-6f)
    }

    @Test
    fun `computeScaleAtIndex handles empty or zero count gracefully`() {
        val arr = FloatArray(10)
        assertEquals(1f, PercentileNormalizer.computeScaleAtIndex(arr, 0, 0), 1e-6f)
    }
}
