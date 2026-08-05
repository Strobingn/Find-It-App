package com.example.data.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Shared deflated-zip helper for the archive-style exporters. */
internal fun createZipArchive(entries: Map<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

internal fun readZipArchive(bytes: ByteArray): Map<String, ByteArray> {
    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return entries
}

/** KMZ is a zip holding a KML document plus any referenced images or overlays. */
object KmzExporter {
    const val MAIN_DOCUMENT = "doc.kml"

    fun createKmz(
        kmlDocument: String,
        supportingFiles: Map<String, ByteArray> = emptyMap(),
    ): ByteArray = createZipArchive(
        linkedMapOf(MAIN_DOCUMENT to kmlDocument.toByteArray(Charsets.UTF_8)) + supportingFiles,
    )

    fun readMainDocument(kmzBytes: ByteArray): String? =
        readZipArchive(kmzBytes)[MAIN_DOCUMENT]?.toString(Charsets.UTF_8)
}
