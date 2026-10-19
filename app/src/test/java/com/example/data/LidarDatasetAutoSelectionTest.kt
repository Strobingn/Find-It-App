package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ranking finishes after network header reads; it must not overwrite a selection the user changed. */
class LidarDatasetAutoSelectionTest {
    private fun tile(project: String, name: String) = NysHistoricLazTileCatalog.Tile(
        objectId = name.hashCode().toLong(),
        name = name,
        downloadUrl = "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/$project/$project/LAZ/$name",
        minLongitude = -74.78, minLatitude = 42.0, maxLongitude = -74.76, maxLatitude = 42.0135,
        project = project,
    )

    private val a = listOf(tile("NY_A_2022", "a1.laz"), tile("NY_A_2022", "a2.laz"))
    private val b = listOf(tile("NY_B_2016", "b1.laz"))
    private val all = (a + b).map { it.downloadUrl }.toSet()
    private val ranked = listOf(
        LidarDatasetRanking.Dataset("A", "A", 2022, 8.0, LidarDatasetRanking.DensitySource.LAS_HEADER, a),
        LidarDatasetRanking.Dataset("B", "B", 2016, 2.0, LidarDatasetRanking.DensitySource.LAS_HEADER, b),
    )

    @Test
    fun untouchedDefaultGetsBestSurvey() =
        assertEquals(a.map { it.downloadUrl }.toSet(), LidarDatasetRanking.autoSelection(all, all, ranked))

    @Test
    fun manualChangeIsKept() {
        assertNull(LidarDatasetRanking.autoSelection(setOf(b[0].downloadUrl), all, ranked))
        assertNull(LidarDatasetRanking.autoSelection(emptySet(), all, ranked))
    }

    @Test
    fun singleSurveyNeedsNoChange() = assertNull(LidarDatasetRanking.autoSelection(all, all, ranked.take(1)))
}
