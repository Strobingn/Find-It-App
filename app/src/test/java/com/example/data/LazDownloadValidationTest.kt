package com.example.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Size checks alone accept a payload that is the wrong thing entirely — typically an HTML error or
 * maintenance page served with 200 and an honest Content-Length. Promoting one stores it as a tile
 * and defers the failure to the decoder, potentially after fetching a whole mosaic around it.
 */
class LazDownloadValidationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val manager = LazDownloadManager()

    private fun fileOf(name: String, bytes: ByteArray): File =
        folder.newFile(name).apply { writeBytes(bytes) }

    private fun lasBytes(payload: String = "rest of the header"): ByteArray =
        "LASF".toByteArray(Charsets.US_ASCII) + payload.toByteArray(Charsets.US_ASCII)

    @Test
    fun cropApiPathsUseThePreferredLazName() {
        val name = LazDownloadManager.resolveDownloadFileName(
            urlPathName = "pointcloud",
            preferredFileName = "UGS_Wasatch_crop.laz",
        )

        assertTrue(name.endsWith(".laz"))
        assertEquals("UGS_Wasatch_crop.laz", name)
    }

    @Test
    fun aPathWithoutLidarExtensionIsRejectedWithoutAPreferredName() {
        assertThrows(IllegalArgumentException::class.java) {
            LazDownloadManager.resolveDownloadFileName("pointcloud")
        }
    }

    @Test
    fun aRealPointCloudPasses() {
        val file = fileOf("tile.laz", lasBytes())

        manager.validatePointCloudSignature(file)

        assertTrue("a valid file must be left in place", file.isFile)
    }

    @Test
    fun anHtmlErrorPageIsRejected() {
        val file = fileOf("tile.laz", "<!DOCTYPE html><html><body>503".toByteArray())

        assertThrows(IllegalStateException::class.java) {
            manager.validatePointCloudSignature(file)
        }
    }

    /** A wrong payload must not survive: resuming it would only append more of the wrong file. */
    @Test
    fun aRejectedPayloadIsDeletedSoRetryStartsClean() {
        val file = fileOf("tile.laz", "<!DOCTYPE html>".toByteArray())

        runCatching { manager.validatePointCloudSignature(file) }

        assertFalse("the bad payload must not be left on disk", file.exists())
    }

    @Test
    fun aFileShorterThanTheSignatureIsRejected() {
        val file = fileOf("tile.laz", "LA".toByteArray(Charsets.US_ASCII))

        assertThrows(IllegalStateException::class.java) {
            manager.validatePointCloudSignature(file)
        }
    }

    @Test
    fun anEmptyFileIsRejected() {
        val file = fileOf("tile.laz", ByteArray(0))

        assertThrows(IllegalStateException::class.java) {
            manager.validatePointCloudSignature(file)
        }
    }

    /** The signature is the first four bytes, not merely present somewhere in the file. */
    @Test
    fun aSignatureLaterInTheFileDoesNotCount() {
        val file = fileOf("tile.laz", "junkLASF".toByteArray(Charsets.US_ASCII))

        assertThrows(IllegalStateException::class.java) {
            manager.validatePointCloudSignature(file)
        }
    }

    /** LAZ is LAS with compressed point data, so a compressed tile carries the same signature. */
    @Test
    fun aCompressedTileSharesTheSignature() {
        val file = fileOf("compressed.laz", lasBytes(" compressed"))

        manager.validatePointCloudSignature(file)

        assertTrue(file.isFile)
    }
}
