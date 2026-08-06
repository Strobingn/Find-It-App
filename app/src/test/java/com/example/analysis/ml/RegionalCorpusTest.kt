package com.example.analysis.ml

import com.example.analysis.ReviewedCandidateExample
import com.example.analysis.ReviewedExampleStore
import com.example.analysis.ReviewedVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RegionalCorpusTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun example(
        id: String,
        lat: Double?,
        lon: Double?,
        verdict: ReviewedVerdict,
    ) = ReviewedCandidateExample(
        id = id,
        datasetKey = "ds",
        xPercent = 50f,
        yPercent = 50f,
        latitude = lat,
        longitude = lon,
        verdict = verdict,
        reviewedAtMillis = 1L,
    )

    @Test
    fun filter_hudsonValleyKeepsInBounds() {
        val list = listOf(
            example("in", 41.7, -74.0, ReviewedVerdict.PRODUCTIVE),
            example("out", 35.0, -80.0, ReviewedVerdict.REJECTED),
        )
        val slice = RegionalCorpus.filter(list, RegionalCorpusCatalog.HUDSON_VALLEY)
        assertEquals(1, slice.size)
        assertEquals("in", slice[0].id)
    }

    @Test
    fun exportImport_roundTrip() {
        val storeFile = tmp.newFile("reviewed.tsv")
        val store = ReviewedExampleStore(storeFile)
        store.append(example("a", 41.5, -74.1, ReviewedVerdict.PRODUCTIVE))
        store.append(example("b", 41.6, -74.2, ReviewedVerdict.REJECTED))
        store.append(example("c", 30.0, -90.0, ReviewedVerdict.PRODUCTIVE)) // outside

        val out = tmp.newFile("corpus-hv.tsv")
        val n = RegionalCorpus.exportToFile(store, RegionalCorpusCatalog.HUDSON_VALLEY, out)
        assertEquals(2, n)
        assertTrue(out.readText().contains("FINDIT_REGIONAL_CORPUS_V1"))

        val store2 = ReviewedExampleStore(tmp.newFile("reviewed2.tsv"))
        val imported = RegionalCorpus.importIntoStore(store2, out)
        assertEquals(2, imported)
        assertEquals(2, store2.readAll().size)
    }

    @Test
    fun detectRegion_prefersHudsonWhenInside() {
        val r = RegionalCorpus.detectRegion(41.5, -74.0)
        assertEquals(RegionalCorpusCatalog.HUDSON_VALLEY.id, r?.id)
    }

    @Test
    fun stats_countsVerdicts() {
        val list = listOf(
            example("p", 41.5, -74.0, ReviewedVerdict.PRODUCTIVE),
            example("r", 41.6, -74.1, ReviewedVerdict.REJECTED),
            example("a", 41.55, -74.05, ReviewedVerdict.AMBIGUOUS),
        )
        val s = RegionalCorpus.stats(list, RegionalCorpusCatalog.HUDSON_VALLEY)
        assertEquals(3, s.total)
        assertEquals(1, s.productive)
        assertEquals(1, s.rejected)
        assertEquals(1, s.other)
    }
}
