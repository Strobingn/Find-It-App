package com.github.mreutegg.laszip4j.laslib

import java.io.File
import java.io.RandomAccessFile

/**
 * Small package-level bridge to laszip4j's seekable RandomAccessFile decoder.
 *
 * laszip4j exposes the stream opener publicly, but its seekable overload is package-private.
 * LAZ chunk tables and internal spatial indexes may require seeking, so opening a compressed file
 * through a plain InputStream can fail at runtime even though the same file is valid.
 */
object SeekableLaszipReader {
    @JvmStatic
    fun open(file: File, selectiveFields: Int): LASreaderLAS? {
        val randomAccessFile = RandomAccessFile(file, "r")
        val reader = LASreaderLAS()
        return if (reader.open(randomAccessFile, false, selectiveFields)) {
            reader
        } else {
            runCatching { randomAccessFile.close() }
            null
        }
    }
}
