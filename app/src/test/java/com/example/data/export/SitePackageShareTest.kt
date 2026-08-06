package com.example.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
