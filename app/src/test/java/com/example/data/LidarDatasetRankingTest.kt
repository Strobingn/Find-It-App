package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LidarDatasetRankingTest {
    private fun tile(project: String, sub: String, name: String, lon: Double = -74.78, lat: Double = 42.0) =
        NysHistoricLazTileCatalog.Tile(
            objectId = name.hashCode().toLong(),
            name = name,
            downloadUrl = "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/$project/$sub/LAZ/$name",
            minLongitude = lon, minLatitude = lat, maxLongitude = lon + 0.018, maxLatitude = lat + 0.0135,
            project = "USGS Lidar Point Cloud $project $name",
        )

    private fun header(points: Long, minor: Int = 4): ByteArray {
        val b = ByteBuffer.allocate(375).order(ByteOrder.LITTLE_ENDIAN)
        b.put("LASF".toByteArray()); b.put(24, 1); b.put(25, minor.toByte())
        if (minor >= 4) b.putLong(247, points) else b.putInt(107, points.toInt())
        return b.array()
    }

    @Test fun parsesLegacyAndExtendedPointCounts() {
        assertEquals(31_485_431L, LidarDatasetRanking.lasHeaderPointCount(header(31_485_431L)))
        assertEquals(1_000L, LidarDatasetRanking.lasHeaderPointCount(header(1_000L, minor = 2)))
        assertNull(LidarDatasetRanking.lasHeaderPointCount(ByteArray(375)))
    }

    @Test fun yearsFromUsgsNames() {
        assertEquals(2022, LidarDatasetRanking.yearOf("NY_SouthEast4County_A22"))
        assertEquals(2018, LidarDatasetRanking.yearOf("NY_FEMAR2_Central_2018_D19"))
        assertEquals(2019, LidarDatasetRanking.yearOf("NY_3County_D19"))
    }

    @Test fun densestDatasetWinsThenNewest() {
        val old = tile("NY_FEMA_2016", "NY_FEMA_B1_2016", "old.laz")
        val dense = tile("NY_SouthEast4County_A22", "NY_SE4County_1_A22", "dense.laz")
        val ranked = LidarDatasetRanking.rank(listOf(old, dense)) { t ->
            if (t == dense) header(31_000_000L) else header(6_000_000L)
        }
        assertEquals("NY_SouthEast4County_A22/NY_SE4County_1_A22", ranked.first().key)
        assertEquals(LidarDatasetRanking.DensitySource.LAS_HEADER, ranked.first().densitySource)
        assertEquals(true, ranked.first().pointsPerSquareMeter!! > ranked[1].pointsPerSquareMeter!!)
    }

    @Test fun nearEqualDensityPrefersNewerSurvey() {
        val a = tile("NY_Survey_2016", "S_2016", "a.laz")
        val b = tile("NY_Survey_2021", "S_2021", "b.laz")
        val ranked = LidarDatasetRanking.rank(listOf(a, b)) { t -> if (t == a) header(10_200_000L) else header(10_000_000L) }
        assertEquals(2021, ranked.first().year)
    }

    @Test fun qualityLevelUsedWhenHeaderUnavailableAndUnknownLast() {
        val ql1 = tile("NY_Region_QL1_2015", "R_QL1", "q1.laz")
        val ql2 = tile("NY_Region_QL2_2020", "R_QL2", "q2.laz")
        val unknown = tile("NY_Mystery_2023", "M", "m.laz")
        val ranked = LidarDatasetRanking.rank(listOf(unknown, ql2, ql1))
        assertEquals(listOf(8.0, 2.0, null), ranked.map { it.pointsPerSquareMeter })
        assertEquals(LidarDatasetRanking.DensitySource.UNKNOWN, ranked.last().densitySource)
    }

    @Test fun allTilesOfADatasetStayTogether() {
        val t1 = tile("P_2022", "S_2022", "t1.laz"); val t2 = tile("P_2022", "S_2022", "t2.laz", lon = -74.76)
        val ranked = LidarDatasetRanking.rank(listOf(t1, t2))
        assertEquals(1, ranked.size)
        assertEquals(2, ranked.first().tiles.size)
    }
}
