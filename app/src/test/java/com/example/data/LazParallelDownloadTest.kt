package com.example.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live throughput check against a real public LAZ. Opt-in (FINDIT_NET_TEST=1) so CI never depends
 * on the network. Compares the single-connection path with the parallel ranged path and requires
 * byte-identical output.
 */
class LazParallelDownloadTest {
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun parallelMatchesSequentialAndIsFaster() {
        assumeTrue(System.getenv("FINDIT_NET_TEST") == "1")
        val urls = (System.getenv("FINDIT_DL_URLS")
            ?: "https://github.com/PDAL/data/raw/main/autzen/autzen-classified.laz").split(",")
        for (url in urls) {
            val results = HashMap<Int, Pair<String, Double>>()
            val counts = System.getenv("FINDIT_DL_CONNS")?.split(",")?.map { it.trim().toInt() }
                ?: listOf(1, LazDownloadManager.DEFAULT_PARALLEL_CONNECTIONS)
            for (connections in counts) {
                val dir = Files.createTempDirectory("dl$connections").toFile()
                try {
                    val t0 = System.nanoTime()
                    val file = LazDownloadManager(parallelConnections = connections).download(url, dir)
                    val seconds = (System.nanoTime() - t0) / 1e9
                    val mbps = file.length() / 1e6 / seconds
                    println("DL $url connections=$connections bytes=${file.length()} seconds=${"%.1f".format(seconds)} MB/s=${"%.2f".format(mbps)}")
                    results[connections] = sha(file) to mbps
                } finally {
                    dir.deleteRecursively()
                }
            }
            assertEquals("all connection counts must produce identical bytes", 1, results.values.map { it.first }.distinct().size)
        }
    }
}
