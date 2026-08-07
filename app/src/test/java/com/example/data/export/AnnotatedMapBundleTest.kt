package com.example.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotatedMapBundleTest {

    @Test
    fun writeProducesZipWithReadmeAndMap() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val zip = AnnotatedMapBundle.write(
            projectName = "Ridge survey",
            annotatedPng = png,
            readmeExtra = "Generated for field handoff.",
        )
        val entries = readZipArchive(zip)
        assertTrue(entries.containsKey(AnnotatedMapBundle.README_PATH))
        assertTrue(entries.containsKey(AnnotatedMapBundle.MAP_PATH))
        assertTrue(entries[AnnotatedMapBundle.MAP_PATH]!!.contentEquals(png))

        val readme = entries[AnnotatedMapBundle.README_PATH]!!.toString(Charsets.UTF_8)
        assertTrue(readme.contains("Ridge survey"))
        assertTrue(readme.contains("map.png"))
        assertTrue(readme.contains("LiDAR honesty") || readme.contains("not identify buried metal"))
        assertTrue(readme.contains(DEFAULT_ETHICS_FOOTER.take(20)))
        assertTrue(readme.contains("Generated for field handoff."))
    }

    @Test
    fun writeRejectsEmptyPng() {
        try {
            AnnotatedMapBundle.write("x", ByteArray(0))
            org.junit.Assert.fail("empty PNG must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("empty"))
        }
    }

    @Test
    fun writeUsesUnnamedPlaceholderWhenProjectBlank() {
        val zip = AnnotatedMapBundle.write("  ", byteArrayOf(1, 2, 3))
        val readme = readZipArchive(zip)[AnnotatedMapBundle.README_PATH]!!.toString(Charsets.UTF_8)
        assertTrue(readme.contains("(unnamed)"))
        assertEquals(2, readZipArchive(zip).size)
    }
}
