package com.example.analysis

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A cache holding fewer layers than the current code renders restores perfectly happily — the
 * detectors only require the layers they read — and then throws the moment someone selects the
 * missing one. The version stamp is what keeps a stale payload from being adopted.
 */
class DerivedLayerCacheVersionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun layers(values: Map<TerrainDerivedLayer, FloatArray>) = TerrainDerivedLayers(
        width = 2,
        height = 2,
        cellSizeMeters = 1f,
        values = values,
    )

    private fun everyLayer(): Map<TerrainDerivedLayer, FloatArray> =
        TerrainDerivedLayer.entries.associateWith { FloatArray(4) { i -> i.toFloat() } }

    @Test
    fun aPayloadWrittenByThisVersionRoundTrips() = runBlocking {
        val directory = folder.newFolder("derived")
        TerrainDerivedLayerCache(directory).put("dataset-a", layers(everyLayer()))

        val restored = TerrainDerivedLayerCache(directory).get("dataset-a")

        assertNotNull("a same-version payload must be readable", restored.layers)
        assertEquals(TerrainDerivedLayer.entries.size, restored.layers!!.values.size)
    }

    /** The invariant that matters: anything restored carries every layer the renderer offers. */
    @Test
    fun aRestoredPayloadCarriesEveryLayerTheRendererCanBeAskedFor() = runBlocking {
        val directory = folder.newFolder("derived")
        TerrainDerivedLayerCache(directory).put("dataset-a", layers(everyLayer()))

        val restored = TerrainDerivedLayerCache(directory).get("dataset-a").layers

        assertNotNull(restored)
        TerrainDerivedLayer.entries.forEach { layer ->
            assertTrue(
                "restored payload is missing ${layer.name}, which the layer picker can select",
                restored!!.values.containsKey(layer),
            )
        }
    }

    /**
     * The point of the version stamp. This writes a structurally valid v1 payload — the format an
     * older build produced, before MULTI_SCALE_RELIEF existed — and requires it to be refused
     * rather than adopted with a layer missing.
     */
    @Test
    fun aPayloadFromAnOlderVersionIsRejectedRatherThanPartiallyAdopted() = runBlocking {
        val directory = folder.newFolder("derived")
        val legacyLayers = TerrainDerivedLayer.entries.filter { it != TerrainDerivedLayer.MULTI_SCALE_RELIEF }

        DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(File(directory, "dataset-a.tic.gz")))))
            .use { output ->
                output.writeInt(0x54494E54) // CACHE_MAGIC
                output.writeInt(1) // the superseded version
                output.writeInt(2)
                output.writeInt(2)
                output.writeFloat(1f)
                output.writeInt(legacyLayers.size)
                legacyLayers.forEach { type ->
                    output.writeInt(type.ordinal)
                    output.writeInt(4)
                    repeat(4) { output.writeFloat(0f) }
                }
            }

        val lookup = TerrainDerivedLayerCache(directory).get("dataset-a")

        assertNull("a v1 payload is missing a layer the picker offers, so it must not be used", lookup.layers)
        assertEquals(TerrainDerivedLayerCache.Hit.MISS, lookup.hit)
    }

    @Test
    fun aCorruptPayloadMissesRatherThanCrashing() = runBlocking {
        val directory = folder.newFolder("derived")
        File(directory, "dataset-a.tic.gz").writeBytes(ByteArray(64))

        assertNull(TerrainDerivedLayerCache(directory).get("dataset-a").layers)
    }

    @Test
    fun anAbsentKeyMisses() = runBlocking {
        val cache = TerrainDerivedLayerCache(folder.newFolder("derived"))

        val lookup = cache.get("never-analyzed")

        assertNull(lookup.layers)
        assertEquals(TerrainDerivedLayerCache.Hit.MISS, lookup.hit)
    }

    @Test
    fun theMultiScaleLayerIsAmongThoseStored() = runBlocking {
        val directory = folder.newFolder("derived")
        TerrainDerivedLayerCache(directory).put("dataset-a", layers(everyLayer()))

        val restored = TerrainDerivedLayerCache(directory).get("dataset-a").layers

        assertTrue(restored!!.values.containsKey(TerrainDerivedLayer.MULTI_SCALE_RELIEF))
    }
}
