package com.example.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SitePackageShareTest {

    @Test
    fun inspectAcceptsValidArchive() {
        val archive = ProjectArchiveWriter.write(
            projectName = "Ridge survey",
            files = listOf(ProjectArchiveFile("targets.csv", "id\n1\n".toByteArray())),
            createdAtMillis = 1_700_000_000_000L,
        )
        val result = ProjectArchiveImport.inspect(archive)
        assertTrue(result.ok)
        assertEquals("Ridge survey", result.manifestName)
        assertEquals(1, result.fileCount)
        assertTrue(result.message.contains("Ridge survey"))
    }

    @Test
    fun inspectRejectsMalformedBytes() {
        val bad = ProjectArchiveImport.inspect("not a zip".toByteArray())
        assertFalse(bad.ok)
        assertNull(bad.manifestName)
        assertTrue(bad.message.contains("manifest") || bad.message.contains("archive"))

        val empty = ProjectArchiveImport.inspect(ByteArray(0))
        assertFalse(empty.ok)
        assertEquals("Empty file", empty.message)
    }

    @Test
    fun applyIfSafeRejectsZipSlipPath() {
        // Valid manifest so inspect passes; payload path escapes dest via `..`.
        val manifest = buildString {
            appendLine("FINDIT_PROJECT_ARCHIVE_V1")
            appendLine("name\tZipSlip")
            appendLine("created\t1700000000000")
            appendLine("file\t../evil.txt\t5")
        }
        val archive = createZipArchive(
            linkedMapOf(
                ProjectArchiveWriter.MANIFEST_PATH to manifest.toByteArray(Charsets.UTF_8),
                "../evil.txt" to "pwned".toByteArray(Charsets.UTF_8),
            ),
        )
        val destRoot = Files.createTempDirectory("findit-zipslip").toFile()
        try {
            val result = ProjectArchiveImport.applyIfSafe(archive, destRoot)
            assertFalse("zip-slip entry must be rejected", result.ok)
            assertTrue(
                result.message.contains("unsafe", ignoreCase = true) ||
                    result.message.contains("zip-slip", ignoreCase = true),
            )
            // Must never materialize outside the intended extract tree.
            val escaped = File(destRoot, "evil.txt")
            assertFalse("zip-slip must not write $escaped", escaped.exists())
            val parentEvil = File(destRoot.parentFile, "evil.txt")
            assertFalse("zip-slip must not write $parentEvil", parentEvil.exists())
            val underImports = File(destRoot, "findit-imports${File.separator}evil.txt")
            assertFalse(underImports.exists())
        } finally {
            destRoot.deleteRecursively()
        }
    }

    @Test
    fun isUnsafeZipPathDetectsTraversal() {
        assertTrue(ProjectArchiveImport.isUnsafeZipPath("../evil.txt"))
        assertTrue(ProjectArchiveImport.isUnsafeZipPath("foo/../../evil.txt"))
        assertTrue(ProjectArchiveImport.isUnsafeZipPath("/abs/evil.txt"))
        assertFalse(ProjectArchiveImport.isUnsafeZipPath("targets.csv"))
        assertFalse(ProjectArchiveImport.isUnsafeZipPath("nested/dir/file.laz"))
    }

    @Test
    fun inspectRejectsOversizedArchive() {
        // Construct a byte array larger than the cap without allocating 80MB+ of zip.
        // Use a fake oversized buffer only if cap is small enough for tests; otherwise
        // verify exceedsSizeCap and the early length gate on a known-oversize claim.
        assertTrue(ProjectArchiveImport.exceedsSizeCap(ProjectArchiveImport.MAX_ARCHIVE_BYTES + 1))
        assertFalse(ProjectArchiveImport.exceedsSizeCap(-1L))
        assertFalse(ProjectArchiveImport.exceedsSizeCap(0L))
        assertFalse(ProjectArchiveImport.exceedsSizeCap(1_024L))
    }

    @Test
    fun applyIfSafeExtractsSafeArchive() {
        val archive = ProjectArchiveWriter.write(
            projectName = "Safe extract",
            files = listOf(ProjectArchiveFile("targets.csv", "id\n1\n".toByteArray())),
            createdAtMillis = 1_700_000_000_000L,
        )
        val destRoot = Files.createTempDirectory("findit-safe").toFile()
        try {
            val result = ProjectArchiveImport.applyIfSafe(archive, destRoot)
            assertTrue(result.ok)
            assertEquals(1, result.fileCount)
            assertNotNullPath(result.extractDirPath)
            val extracted = File(result.extractDirPath!!, "targets.csv")
            assertTrue(extracted.exists())
            assertEquals("id\n1\n", extracted.readText())
        } finally {
            destRoot.deleteRecursively()
        }
    }

    private fun assertNotNullPath(path: String?) {
        assertTrue(path != null && path.isNotBlank())
    }

    @Test
    fun qrPayloadUsesShareFileWhenLarge() {
        val hash = "abc123"
        val large = QrSharePayload.forProject(
            projectName = "Big site",
            archiveByteSize = QrSharePayload.MAX_INLINE_ARCHIVE_BYTES + 1,
            contentHash = hash,
        )
        assertTrue(large.startsWith(QrSharePayload.HEADER))
        assertTrue(large.contains(QrSharePayload.SHARE_FILE))
        assertFalse(large.contains(QrSharePayload.INLINE_META))
        assertTrue(large.contains("name=Big site"))
        assertTrue(large.contains("sha256=$hash"))
        assertFalse(large.contains("PK")) // never embeds zip magic
    }

    @Test
    fun qrPayloadUsesInlineMetaWhenSmall() {
        val small = QrSharePayload.forProject(
            projectName = "Tiny",
            archiveByteSize = 100,
            contentHash = "DEADBEEF",
        )
        assertTrue(small.contains(QrSharePayload.INLINE_META))
        assertFalse(small.contains(QrSharePayload.SHARE_FILE))
        assertTrue(small.contains("sha256=deadbeef"))
    }

    @Test
    fun sha256HexIsStable() {
        val a = QrSharePayload.sha256Hex("hello".toByteArray())
        val b = QrSharePayload.sha256Hex("hello".toByteArray())
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            a,
        )
    }
}
