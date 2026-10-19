package com.example.data.download

import com.example.data.LazDownloadManager
import com.example.data.LazRsNativeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class EptTileBuilderTest {
    private val nyUrl = "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/" +
        "NY_SouthEast4County_A22/NY_SE4County_1_A22/LAZ/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz"

    @Test
    fun mapsTnmProjectFoldersToEptResources() {
        assertEquals(listOf("NY_SE4County_1_A22", "NY_SouthEast4County_A22"), EptTileBuilder.candidateResources(nyUrl))
        assertEquals(
            listOf("NY_Clinton_2019"),
            EptTileBuilder.candidateResources("https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/LPC/Projects/NY_Clinton_2019/LAZ/a.laz"),
        )
        assertTrue(EptTileBuilder.candidateResources("https://example.com/x.laz").isEmpty())
    }

    @Test
    fun parsesUtmFromWktAndRejectsFeet() {
        assertEquals(18, EptTileBuilder.utmFromWkt("PROJCS[\"NAD83(2011) / UTM zone 18N\",UNIT[\"metre\",1]]")?.zone)
        assertNull(EptTileBuilder.utmFromWkt("PROJCS[\"NAD83 / New York Long Island (ftUS)\",UNIT[\"US survey foot\",0.3048]]"))
        assertNull(EptTileBuilder.utmFromWkt("PROJCS[\"WGS 84 / UTM zone 18N\"]"))
    }

    @Test
    fun utmToWebMercatorMatchesPyproj() {
        // pyproj EPSG:6347 -> EPSG:3857 (517500, 4650000) = (-8325438.480377544, 5161252.221366766)
        val (x, y) = EptTileBuilder.utmToMercator(EptTileBuilder.Utm(18), 517500.0, 4650000.0)
        assertEquals(-8325438.480377544, x, 1e-6)
        assertEquals(5161252.221366766, y, 1e-6)
    }

    @Test
    fun selectsOnlyOverlappingOctreeNodes() {
        val b = doubleArrayOf(0.0, 0.0, 0.0, 1024.0, 1024.0, 1024.0)
        val box = doubleArrayOf(10.0, 10.0, 100.0, 100.0)
        assertTrue(EptTileBuilder.nodeIntersects("0-0-0-0", b, box))
        assertTrue(EptTileBuilder.nodeIntersects("3-0-0-5", b, box))
        assertFalse(EptTileBuilder.nodeIntersects("3-1-0-0", b, box))
        assertFalse(EptTileBuilder.nodeIntersects("1-1-1-0", b, box))
    }

    private fun header(count: Long, wkt: String): ByteArray {
        val w = wkt.toByteArray()
        val size = 375 + 54 + w.size
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("LASF".toByteArray()); bb.put(24, 1); bb.put(25, 4)
        bb.putShort(94, 375); bb.putInt(96, size); bb.putInt(100, 1)
        bb.putDouble(179, 519000.0); bb.putDouble(187, 517500.0); bb.putDouble(195, 4651500.0); bb.putDouble(203, 4650000.0)
        bb.putLong(247, count)
        bb.position(375 + 2); bb.put("LASF_Projection".toByteArray())
        bb.putShort(375 + 18, 2112); bb.putShort(375 + 20, w.size.toShort())
        bb.position(375 + 54); bb.put(w)
        return bb.array()
    }

    private val eptJson = """{"srs":{"horizontal":"3857"},"bounds":[-8388608,5033164.8,0,-8126464,5295308.8,262144]}"""

    private fun fakeFetch(hdr: ByteArray, nodeHits: MutableList<String>): (String, LongRange?) -> ByteArray? = { url, range ->
        when {
            url.endsWith("NY_SE4County_1_A22/ept.json") -> eptJson.toByteArray()
            url.endsWith("ept.json") -> null
            // Root count stays inside [header point count, 3x] so the hierarchy gate still downloads.
            url.contains("ept-hierarchy/0-0-0-0") -> """{"0-0-0-0":1000,"1-0-0-0":3,"1-1-1-0":7,"2-1-1-1":-1}""".toByteArray()
            url.contains("ept-hierarchy/2-1-1-1") -> """{"2-1-1-1":4,"3-2-2-2":0}""".toByteArray()
            url.contains("ept-data/") -> { synchronized(nodeHits) { nodeHits += url.substringAfterLast('/') }; ByteArray(10) }
            range != null -> hdr.copyOfRange(range.first.toInt(), minOf(hdr.size, range.last.toInt() + 1))
            else -> null
        }
    }

    @Test
    fun acceptsOnlyExactPointCountAndFallsBackOtherwise() {
        val dir = Files.createTempDirectory("ept").toFile()
        val hdr = header(1000, "PROJCS[\"NAD83(2011) / UTM zone 18N\",UNIT[\"metre\",1]]")
        val hits = mutableListOf<String>()
        var gotTm: DoubleArray? = null
        val ok = EptTileBuilder(4, fakeFetch(hdr, hits), { _, inputs, out, tm ->
            gotTm = tm; out.writeBytes(byteArrayOf(76, 65, 83, 70)); longArrayOf(5000, 1000 + 0L * inputs.size)
        }, { true }).build(nyUrl, dir)
        assertNotNull(ok)
        assertEquals("NY_SE4County_1_A22", ok!!.resource)
        assertEquals(-75.0, gotTm!![0], 0.0)
        // Box sits in the lower-left quadrant of this synthetic cube: 1-1-1-0 is skipped.
        assertEquals(setOf("0-0-0-0.laz", "1-0-0-0.laz"), hits.toSet().minus(setOf("2-1-1-1.laz")))
        assertTrue(File(dir, "USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz").isFile)
        assertTrue(dir.listFiles()!!.none { it.name.startsWith(".ept-") })

        val dir2 = Files.createTempDirectory("ept2").toFile()
        val bad = EptTileBuilder(4, fakeFetch(hdr, mutableListOf()), { _, _, out, _ -> out.writeBytes(ByteArray(4)); longArrayOf(5000, 999) }, { true })
            .build(nyUrl, dir2)
        assertNull(bad)
        assertTrue(dir2.listFiles()!!.isEmpty())

        val feet = header(1000, "PROJCS[\"NAD83 / NY (ftUS)\",UNIT[\"US survey foot\",0.3048]]")
        assertNull(EptTileBuilder(4, fakeFetch(feet, mutableListOf()), { _, _, _, _ -> error("no") }, { true }).build(nyUrl, dir2))
    }

    @Test
    fun downloadManagerFallsBackWhenEptThrows() {
        val dir = Files.createTempDirectory("eptfb").toFile()
        val throwing = EptTileBuilder(2, { _, _ -> throw java.io.IOException("offline") }, { _, _, _, _ -> null }, { true })
        // Falls through to the normal path, which rejects a non-LAZ URL with its usual message.
        val err = runCatching {
            LazDownloadManager(parallelConnections = 1, mirrorResolver = null, eptBuilder = throwing)
                .download("https://example.invalid/not-a-tile.txt", dir)
        }.exceptionOrNull()
        assertNotNull(err)
        assertFalse(err!!.message.orEmpty().contains("offline"))
    }

    private val metreWkt = "PROJCS[\"NAD83(2011) / UTM zone 18N\",UNIT[\"metre\",1]]"

    @Test
    fun skipsWrongSurveyWhenHierarchyTotalCannotBeTheTile() {
        val dir = Files.createTempDirectory("ept-skip").toFile()
        val hdr = header(1_000, metreWkt)
        val dataHits = mutableListOf<String>()
        val hierarchyHits = mutableListOf<String>()
        val fetch = headerAwareFetch(
            hdr,
            mapOf("NY_Hudson_1_D22" to 999L, "NY_Wide_2008" to 3_001L),
            dataHits,
            hierarchyHits,
        )
        val merged = mutableListOf<String>()
        val result = EptTileBuilder(2, fetch, { _, _, out, _ ->
            merged += "merged"
            out.writeBytes(byteArrayOf(76, 65, 83, 70))
            longArrayOf(1, 1_000)
        }, { true }).build(nyUrl, dir, resourcesFor = { listOf("NY_Hudson_1_D22", "NY_Wide_2008") })
        assertNull(result)
        assertTrue(merged.isEmpty())
        assertTrue(dataHits.isEmpty())
        assertTrue(hierarchyHits.any { it.contains("NY_Hudson_1_D22/ept-hierarchy") })
        assertTrue(hierarchyHits.any { it.contains("NY_Wide_2008/ept-hierarchy") })
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".laz") })
    }

    @Test
    fun choosesSurveyWhoseHierarchyTotalFitsAmongSeveral() {
        val dir = Files.createTempDirectory("ept-pick").toFile()
        val hdr = header(1_000, metreWkt)
        val dataHits = mutableListOf<String>()
        val hierarchyHits = mutableListOf<String>()
        val counts = linkedMapOf(
            "NY_Hudson_1_D22" to 999L,
            "NY_Wide_2008" to 3_001L,
            "NY_Columbia_Rensselaer_2016" to 3_000L,
        )
        var merges = 0
        val result = EptTileBuilder(
            2,
            headerAwareFetch(hdr, counts, dataHits, hierarchyHits),
            { _, inputs, out, _ ->
                merges++
                assertEquals(listOf("0-0-0-0.laz"), inputs.map { it.name })
                out.writeBytes(byteArrayOf(76, 65, 83, 70))
                longArrayOf(1, 1_000)
            },
            { true },
        ).build(
            nyUrl,
            dir,
            resourcesFor = { listOf("NY_Hudson_1_D22", "NY_Wide_2008", "NY_Columbia_Rensselaer_2016") },
        )
        assertNotNull(result)
        assertEquals("NY_Columbia_Rensselaer_2016", result!!.resource)
        assertEquals(1, merges)
        assertEquals(1, result.nodes)
        assertTrue(dataHits.all { it.contains("NY_Columbia_Rensselaer_2016/ept-data/") })
        assertEquals(1, dataHits.size)
        assertTrue(hierarchyHits.any { it.contains("NY_Hudson_1_D22/") })
        assertTrue(hierarchyHits.any { it.contains("NY_Wide_2008/") })
        assertTrue(hierarchyHits.any { it.contains("NY_Columbia_Rensselaer_2016/") })
        assertTrue(File(dir, "USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz").isFile)
    }

    @Test
    fun reusesHeaderAndDoesNotFetchAgainWhenOpenTopographyHasNothingNew() {
        val dir = Files.createTempDirectory("ept-cache").toFile()
        val hdr = header(1_000, metreWkt)
        val headerReads = if (hdr.size <= 375) 1 else 2
        val tileUrl = "https://localhost/vdelivery/Datasets/Staged/Elevation/LPC/Projects/" +
            "NY_SouthEast4County_A22/NY_SE4County_1_A22/LAZ/" +
            "USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz"
        val fetches = mutableListOf<String>()
        val fetch: (String, LongRange?) -> ByteArray? = { url, range ->
            fetches += url
            when {
                url.endsWith("NY_SE4County_1_A22/ept.json") -> eptJson.toByteArray()
                url.contains("NY_SE4County_1_A22/ept-hierarchy/") -> """{"0-0-0-0":10}""".toByteArray()
                url.endsWith("/ept.json") -> null
                url.contains("/ept-data/") -> ByteArray(4)
                range != null && url == tileUrl ->
                    hdr.copyOfRange(range.first.toInt(), minOf(hdr.size, range.last.toInt() + 1))
                else -> null
            }
        }
        var catalogCalls = 0
        var catalogUrl: String? = null
        val catalogJson = """
            {"datasets":[
              {"propertyID":"USGS_3DEP_ID","value":"NY_SE4County_1_A22"},
              {"propertyID":"USGS_3DEP_ID","value":"NY_SouthEast4County_A22"}
            ]}
        """.trimIndent()
        val catalog = OpenTopographyCatalog(get = { url ->
            catalogCalls++
            catalogUrl = url
            catalogJson
        })
        val builder = EptTileBuilder(2, fetch, { _, _, out, _ ->
            out.writeBytes(byteArrayOf(76, 65, 83, 70))
            longArrayOf(1, 999)
        }, { true })
        val err = runCatching {
            LazDownloadManager(
                parallelConnections = 1,
                mirrorResolver = null,
                eptBuilder = builder,
                openTopography = catalog,
            ).download(tileUrl, dir)
        }.exceptionOrNull()
        assertNotNull(err)
        assertEquals(1, catalogCalls)
        assertTrue(catalogUrl!!.contains("minx=-74."))
        assertEquals(headerReads, fetches.count { it == tileUrl })
        assertEquals(2, fetches.count { it.endsWith("/ept.json") })
        assertEquals(1, fetches.count { it.contains("/ept-hierarchy/") })
        assertTrue(fetches.none { it.contains("/ept-data/") })
        assertTrue(dir.listFiles().orEmpty().none { it.isFile && !it.name.startsWith(".") })
    }

    private fun headerAwareFetch(
        hdr: ByteArray,
        counts: Map<String, Long>,
        dataHits: MutableList<String>,
        hierarchyHits: MutableList<String>,
    ): (String, LongRange?) -> ByteArray? = { url, range ->
        when {
            url.endsWith("/ept.json") -> {
                val resource = url.removeSuffix("/ept.json").substringAfterLast('/')
                if (resource in counts) eptJson.toByteArray() else null
            }
            url.contains("/ept-hierarchy/") -> {
                val resource = url.substringBefore("/ept-hierarchy/").substringAfterLast('/')
                val count = counts[resource]
                if (count == null) {
                    null
                } else {
                    hierarchyHits += url
                    """{"0-0-0-0":$count}""".toByteArray()
                }
            }
            url.contains("/ept-data/") -> {
                dataHits += url
                ByteArray(8)
            }
            range != null -> hdr.copyOfRange(range.first.toInt(), minOf(hdr.size, range.last.toInt() + 1))
            else -> null
        }
    }

    /** Live end-to-end rebuild of the NY test tile. FINDIT_EPT_E2E=<out dir>, host lib in native/. */
    @Test
    fun liveNyTileFromEpt() {
        val outDir = System.getenv("FINDIT_EPT_E2E") ?: return
        val lib = File("../native/lazrs-jni/target/release/liblazrs_jni.so").absoluteFile
        assumeTrue(lib.isFile && LazRsNativeDecoder.loadForTest(lib.path))
        val t0 = System.nanoTime()
        // rockyweb is sometimes unreachable from CI/box; optionally serve the 38 KB header from a local copy.
        val local = System.getenv("FINDIT_EPT_LOCAL_HEADER")?.let(::File)
        val fetch: (String, LongRange?) -> ByteArray? = { url, range ->
            if (local != null && range != null && url == nyUrl) {
                local.inputStream().use { it.readNBytes((range.last + 1).toInt()) }.copyOfRange(range.first.toInt(), (range.last + 1).toInt())
            } else EptTileBuilder.httpGet(url, range)
        }
        val r = EptTileBuilder(24, fetch).build(nyUrl, File(outDir))
        val s = (System.nanoTime() - t0) / 1e9
        println("EPT_E2E seconds=$s result=$r bytes=${r?.file?.length()}")
        assertNotNull(r)
        assertEquals(31_485_431L, r!!.points)
    }
}
