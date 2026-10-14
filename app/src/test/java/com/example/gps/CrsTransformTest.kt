package com.example.gps

import com.example.gps.crs.DatumShift
import com.example.gps.crs.GeodeticFrame
import com.example.gps.crs.GridGeoreference
import com.example.gps.crs.LasCrsReader
import com.example.gps.crs.ProjectedCrs
import com.example.gps.crs.WktCrsParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference values from PROJ 9.8.1 (pyproj 3.8.0). */
class CrsTransformTest {
    private val nyWkt = """COMPD_CS["NAD83(2011) / UTM zone 18N + NAVD88 height - Geoid18 (metres)",PROJCS["NAD83(2011) / UTM zone 18N",GEOGCS["NAD83(2011)",DATUM["NAD83_National_Spatial_Reference_System_2011",SPHEROID["GRS 1980",6378137,298.257222101,AUTHORITY["EPSG","7019"]],AUTHORITY["EPSG","1116"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","6318"]],PROJECTION["Transverse_Mercator"],PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",-75],PARAMETER["scale_factor",0.9996],PARAMETER["false_easting",500000],PARAMETER["false_northing",0],UNIT["metre",1,AUTHORITY["EPSG","9001"]],AXIS["Easting",EAST],AXIS["Northing",NORTH],AUTHORITY["EPSG","6347"]],VERT_CS["NAVD88 height - Geoid18 (metres)",VERT_DATUM["North American Vertical Datum 1988",2005,AUTHORITY["EPSG","5103"]],HEIGHT_MODEL["US Geoid Model 2018"],UNIT["metre",1,AUTHORITY["EPSG","9001"]],AXIS["Up",UP],AUTHORITY["EPSG","5703"]]]"""
    private val autzenWkt = """COMPD_CS["NAD83 / Oregon GIC Lambert (ft) + NAVD88 height (ftUS)",PROJCS["NAD83 / Oregon GIC Lambert (ft)",GEOGCS["NAD83",DATUM["North_American_Datum_1983",SPHEROID["GRS 1980",6378137,298.257222101,AUTHORITY["EPSG","7019"]],AUTHORITY["EPSG","6269"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4269"]],PROJECTION["Lambert_Conformal_Conic_2SP"],PARAMETER["latitude_of_origin",41.75],PARAMETER["central_meridian",-120.5],PARAMETER["standard_parallel_1",43],PARAMETER["standard_parallel_2",45.5],PARAMETER["false_easting",1312335.958],PARAMETER["false_northing",0],UNIT["foot",0.3048,AUTHORITY["EPSG","9002"]],AXIS["Easting",EAST],AXIS["Northing",NORTH],AUTHORITY["EPSG","2992"]],VERT_CS["NAVD88 height (ftUS)",VERT_DATUM["North American Vertical Datum 1988",2005,AUTHORITY["EPSG","5103"]],UNIT["US survey foot",0.304800609601219,AUTHORITY["EPSG","9003"]],AXIS["Gravity-related height",UP],AUTHORITY["EPSG","6360"]]]"""

    private fun assertXY(e: Double, n: Double, actual: Pair<Double, Double>, tol: Double = 0.001) {
        assertEquals("easting", e, actual.first, tol)
        assertEquals("northing", n, actual.second, tol)
    }

    @Test fun utm18nNad83_2011FromRealLasWkt() {
        val crs = WktCrsParser.parseProjected(nyWkt)!!
        assertEquals(GeodeticFrame.NAD83_2011, crs.frame)
        assertEquals(1.0, crs.unitToMeters, 0.0)
        assertXY(524845.2985, 4649819.7484, crs.project(42.0, -74.7))
        assertXY(519048.9828, 4649457.6173, crs.project(41.9969, -74.77))
        val (lat, lon) = crs.unproject(518000.0, 4650750.0)
        assertEquals(42.008564650, lat, 1e-8)
        assertEquals(-74.782625855, lon, 1e-8)
    }

    @Test fun oregonLambertInternationalFeetNotUsSurveyFeet() {
        val crs = WktCrsParser.parseProjected(autzenWkt)!!
        // Horizontal unit is the international foot; the compound VERT_CS uses US survey feet.
        assertEquals(0.3048, crs.unitToMeters, 0.0)
        assertXY(634263.8587, 849025.9420, crs.project(44.05, -123.08))
        assertXY(1992137.8011, 101507.7227, crs.project(42.0, -118.0))
        val (lat, lon) = crs.unproject(637000.0, 851000.0)
        assertEquals(44.055649124, lat, 1e-8)
        assertEquals(-123.069831073, lon, 1e-8)
    }

    @Test fun newYorkStatePlaneFromEpsg() {
        assertXY(693882.1100, 1391294.0264, ProjectedCrs.fromEpsg(2260)!!.project(42.65, -73.75))
        assertXY(987020.7287, 212522.0448, ProjectedCrs.fromEpsg(2263)!!.project(40.75, -73.99))
        assertXY(285303.5718, 338815.4775, ProjectedCrs.fromEpsg(32116)!!.project(43.05, -76.15))
    }

    @Test fun itrfToNad83_2011MatchesProjAtTwoEpochs() {
        val a = DatumShift.convert(42.0, -74.7, GeodeticFrame.ITRF, GeodeticFrame.NAD83_2011, 2010.0)
        assertEquals(41.999990834, a.first, 2e-7); assertEquals(-74.699995557, a.second, 2e-7)
        val b = DatumShift.convert(42.0, -74.7, GeodeticFrame.ITRF, GeodeticFrame.NAD83_2011, 2026.8)
        assertEquals(41.999989960, b.first, 2e-7); assertEquals(-74.699992072, b.second, 2e-7)
        val back = DatumShift.convert(b.first, b.second, GeodeticFrame.NAD83_2011, GeodeticFrame.ITRF, 2026.8)
        assertEquals(42.0, back.first, 1e-10); assertEquals(-74.7, back.second, 1e-10)
    }

    @Test fun phoneFixLandsOnTheRightNyCellWithinCentimetres() {
        val crs = WktCrsParser.parseProjected(nyWkt)!!
        val grid = GridGeoreference(crs, 517500.00001, 518999.999, 4650000.0, 4651500.0, 1536, 1536)
        // PROJ ITRF2014@2026.79 → EPSG:6347: 519049.6442, 4649456.5056 (outside this tile → null).
        assertEquals(null, grid.toGridPercent(41.9969, -74.77, GeodeticFrame.ITRF, 2026.79))
        val pct = grid.toGridPercent(42.008564650, -74.782625855, GeodeticFrame.NAD83_2011, 2026.79)!!
        val e = 517500.00001 + pct.first / 100.0 * 1536 / 1535 * (518999.999 - 517500.00001)
        val n = 4651500.0 - pct.second / 100.0 * 1536 / 1535 * 1500.0
        assertEquals(518000.0, e, 0.05); assertEquals(4650750.0, n, 0.05)
        val geo = grid.gridPercentToGeographic(pct.first.toDouble(), pct.second.toDouble(), GeodeticFrame.NAD83_2011, 2026.79)
        assertEquals(42.008564650, geo.first, 1e-6)
    }

    @Test fun ignoringTheDatumWouldMissByMoreThanOneMetre() {
        val shifted = DatumShift.convert(42.0, -74.7, GeodeticFrame.ITRF, GeodeticFrame.NAD83_2011, 2026.8)
        val crs = WktCrsParser.parseProjected(nyWkt)!!
        val a = crs.project(42.0, -74.7)
        val b = crs.project(shifted.first, shifted.second)
        val d = kotlin.math.hypot(a.first - b.first, a.second - b.second)
        assertTrue("datum shift is $d m", d in 1.1..1.5)
    }

    @Test fun readsCrsAndExtentFromRealLaz() {
        val ny = File("/workspace/lazdata/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz")
        org.junit.Assume.assumeTrue(ny.isFile)
        val info = LasCrsReader.read(ny)
        assertNotNull(info?.crs)
        assertEquals(517500.0, info!!.minX, 0.01)
        assertEquals(4651500.0, info.maxY, 0.01)
        val autzen = File("/workspace/lazdata/autzen-classified.laz")
        if (autzen.isFile) assertEquals(0.3048, LasCrsReader.read(autzen)!!.crs!!.unitToMeters, 0.0)
    }
}
