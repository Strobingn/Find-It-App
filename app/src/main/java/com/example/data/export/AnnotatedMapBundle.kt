package com.example.data.export

/**
 * Zip handoff of an annotated terrain PNG plus a plain-text README.
 * Builds on [ProjectExportRenderer] PNG output without embedding LiDAR metal claims.
 */
object AnnotatedMapBundle {
    const val README_PATH = "README.txt"
    const val MAP_PATH = "map.png"

    fun write(
        projectName: String,
        annotatedPng: ByteArray,
        readmeExtra: String = "",
    ): ByteArray {
        require(annotatedPng.isNotEmpty()) { "annotated PNG must not be empty" }
        val readme = buildString {
            appendLine("Find It annotated map bundle")
            appendLine("Project: ${projectName.ifBlank { "(unnamed)" }}")
            appendLine()
            appendLine("Contents")
            appendLine("- $MAP_PATH — annotated terrain hillshade with logged targets")
            appendLine("- $README_PATH — this file")
            appendLine()
            appendLine("LiDAR honesty")
            appendLine(
                "Terrain morphology and relative surface context only. " +
                    "Does not identify buried metal, ownership, or absolute dig depth.",
            )
            appendLine()
            appendLine("Ethics")
            appendLine(DEFAULT_ETHICS_FOOTER)
            if (readmeExtra.isNotBlank()) {
                appendLine()
                appendLine(readmeExtra.trimEnd())
            }
        }
        return createZipArchive(
            linkedMapOf(
                README_PATH to readme.toByteArray(Charsets.UTF_8),
                MAP_PATH to annotatedPng,
            ),
        )
    }
}
