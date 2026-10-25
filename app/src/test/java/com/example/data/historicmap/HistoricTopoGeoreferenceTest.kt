package com.example.data.historicmap

import com.example.gps.crs.HistoricDatum
import com.example.gps.crs.HistoricDatumShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Expected neatline pixels were computed with GDAL/PROJ from the real USGS GeoTIFF headers of
 * each sheet (Schunemunk 1902, Cornwall 1947, Cornwall-on-Hudson 1994, Scranton 1953).
 */
class HistoricTopoGeoreferenceTest {

    @Test
    fun polyconicNad27Sheet1902() = check(
        coordTrans = 22, nad27 = true, lon0 = -74.125, lat0 = 41.25,
        scale = 5.291666666666666, tieX = -12120.4252049819, tieY = 29705.61913649727,
        box = doubleArrayOf(41.25, 41.5, -74.25, -74.0),
        expected = listOf(318.071 to 365.33, 4262.878 to 365.33, 4270.444 to 5612.236, 310.504 to 5612.236),
    )

    @Test
    fun transverseMercatorNad27Sheet1947() = check(
        coordTrans = 1, nad27 = true, lon0 = -74.0625, lat0 = 41.375,
        scale = 2.032, tieX = -6636.135644822707, tieY = 14310.874368181143,
        box = doubleArrayOf(41.375, 41.5, -74.125, -74.0),
        expected = listOf(697.58 to 209.842, 5834.049 to 209.842, 5838.982 to 7041.825, 692.648 to 7041.825),
    )

    @Test
    fun transverseMercatorWgs84Sheet1994() = check(
        coordTrans = 1, nad27 = false, lon0 = -74.0625, lat0 = 41.375,
        scale = 2.032, tieX = -6563.893126083964, tieY = 14625.289139979708,
        box = doubleArrayOf(41.375, 41.5, -74.125, -74.0),
        expected = listOf(662.098 to 364.472, 5798.427 to 364.472, 5803.359 to 7196.557, 657.165 to 7196.557),
    )

    @Test
    fun transverseMercatorNad27Sheet250k() = check(
        coordTrans = 1, nad27 = true, lon0 = -75.0, lat0 = 41.0,
        scale = 21.166666666666664, tieX = -106975.6213369055, tieY = 120929.93821405855,
        box = doubleArrayOf(41.0, 42.0, -76.0, -74.0),
        expected = listOf(1139.628 to 443.347, 8968.305 to 443.347, 9028.992 to 5690.466, 1078.94 to 5690.466),
    )

    @Test
    fun nad27ShiftMatchesProjHelmert() {
        val cases = listOf(
            doubleArrayOf(41.5, -74.25, 41.4999933, -74.2495721),
            doubleArrayOf(41.25, -74.0, 41.2499947, -73.9995656),
            doubleArrayOf(35.0, -95.0, 35.0001071, -95.0002401),
            doubleArrayOf(45.0, -120.0, 44.9998352, -120.0011025),
        )
        for (c in cases) {
            val (lat, lon) = HistoricDatumShift.toWgs84(c[0], c[1], HistoricDatum.NAD27)
            assertEquals(c[2], lat, 2e-7)
            assertEquals(c[3], lon, 2e-7)
        }
    }

    private fun check(
        coordTrans: Int,
        nad27: Boolean,
        lon0: Double,
        lat0: Double,
        scale: Double,
        tieX: Double,
        tieY: Double,
        box: DoubleArray,
        expected: List<Pair<Double, Double>>,
    ) {
        // Same GeoKey layout as the USGS files: user-defined projection, params in GeoDoubleParams.
        val doubles = if (nad27) {
            doubleArrayOf(6_378_206.4, 294.9786982139006, lon0, lat0, 0.0, 0.0, 1.0)
        } else {
            doubleArrayOf(6_378_137.0, 298.2572235604902, lon0, lat0, 0.0, 0.0, 1.0)
        }
        val keys = intArrayOf(
            1, 1, 0, 12,
            1024, 0, 1, 1,
            1025, 0, 1, 1,
            2048, 0, 1, if (nad27) 4267 else 4326,
            2054, 0, 1, 9102,
            2057, 34736, 1, 0,
            2059, 34736, 1, 1,
            3072, 0, 1, 32767,
            3075, 0, 1, coordTrans,
            3076, 0, 1, 9001,
            3080, 34736, 1, 2,
            3081, 34736, 1, 3,
            3092, 34736, 1, 6,
        )
        val dir = TiffDirectory(
            width = 1, height = 1, compression = 7, photometric = 6, subfileType = 0,
            tileWidth = 512, tileHeight = 512, rowsPerStrip = 0,
            offsets = LongArray(0), byteCounts = LongArray(0), jpegTables = null,
            pixelScale = doubleArrayOf(scale, scale, 0.0),
            tiepoint = doubleArrayOf(0.0, 0.0, 0.0, tieX, tieY, 0.0),
            geoKeys = keys,
            geoDoubles = doubles,
        )
        val geo = HistoricTopoGeoreference.from(dir)
        assertNotNull(geo)
        assertEquals(if (nad27) HistoricDatum.NAD27 else HistoricDatum.WGS84, geo!!.datum)
        val pixels = geo.neatlinePixels(box[0], box[1], box[2], box[3])
        pixels.zip(expected).forEach { (actual, want) ->
            assertEquals(want.first, actual.first, 0.05)
            assertEquals(want.second, actual.second, 0.05)
        }
    }
}
