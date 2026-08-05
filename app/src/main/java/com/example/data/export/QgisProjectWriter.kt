package com.example.data.export

enum class QgisLayerType(val qgisName: String, val provider: String) {
    RASTER("raster", "gdal"),
    VECTOR("vector", "ogr"),
}

data class QgisLayer(
    val name: String,
    val type: QgisLayerType,
    /** Path relative to the .qgs file, so a whole export folder stays portable. */
    val datasourceRelativePath: String,
    val crsAuthId: String = "EPSG:4326",
)

/**
 * Minimal QGIS project (.qgs) writer: enough structure for QGIS to open the exported layers
 * with names, datasources, and CRS already set — no manual layer hunting after an export.
 */
object QgisProjectWriter {
    fun write(projectName: String, layers: List<QgisLayer>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<qgis projectname="${escape(projectName)}">""")
        appendLine("  <projectlayers>")
        for (layer in layers) {
            appendLine("    <maplayer>")
            appendLine("      <type>${layer.type.qgisName}</type>")
            appendLine("      <layername>${escape(layer.name)}</layername>")
            appendLine("      <datasource>${escape(layer.datasourceRelativePath)}</datasource>")
            appendLine("      <provider>${layer.type.provider}</provider>")
            appendLine("      <srs><spatialrefsys><authid>${escape(layer.crsAuthId)}</authid></spatialrefsys></srs>")
            appendLine("    </maplayer>")
        }
        appendLine("  </projectlayers>")
        appendLine("</qgis>")
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}
