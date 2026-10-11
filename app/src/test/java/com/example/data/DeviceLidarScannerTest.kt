package com.example.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLidarScannerTest {
    @Test
    fun extensionFilterAcceptsLazLasCopc() {
        assertTrue(DeviceLidarScanner.isLidarFileName("tile.laz"))
        assertTrue(DeviceLidarScanner.isLidarFileName("TILE.LAS"))
        assertTrue(DeviceLidarScanner.isLidarFileName("cloud.copc.laz"))
        assertFalse(DeviceLidarScanner.isLidarFileName("notes.txt"))
        assertFalse(DeviceLidarScanner.isLidarFileName("tile.laz.part"))
        assertFalse(DeviceLidarScanner.isLidarFileName(".tile.laz"))
        assertFalse(DeviceLidarScanner.isLidarFileName("lazy.zip"))
    }

    @Test
    fun skipsAndroidDataAndHiddenDirs() {
        val root = File("/storage/emulated/0")
        assertTrue(DeviceLidarScanner.shouldSkipDirectory(File(root, "Android/data"), root))
        assertTrue(DeviceLidarScanner.shouldSkipDirectory(File(root, ".thumbnails"), root))
        assertFalse(DeviceLidarScanner.shouldSkipDirectory(File(root, "Download"), root))
    }

    @Test
    fun dedupeDropsLibraryFilesAndDuplicates() {
        fun f(path: String, size: Long, mod: Long) =
            DeviceLidarFile(path, File(path).name, size, mod, File(path).parent!!)
        val found = listOf(
            f("/sd/Download/a.laz", 100, 1),
            f("/sd/Download/a.laz", 100, 1),
            f("/sd/Other/A.LAZ", 100, 2),
            f("/sd/Download/b.las", 50, 3),
            f("/sd/Download/c.copc.laz", 70, 4),
        )
        val library = listOf(LazDataset(File("/app/lidar/c.copc.laz"), "c.copc.laz", 70, 0))
        val result = DeviceLidarScanner.dedupe(found, library)
        assertEquals(listOf("b.las", "a.laz"), result.map { it.name })
    }
}
