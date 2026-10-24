package com.example.data

/**
 * Where the Public LiDAR picker fetches downloadable LAZ/COPC from.
 *
 * USGS The National Map still lists the official LPC files, but those URLs land on
 * rockyweb (~0.3 MB/s). The same 3DEP points are published as COPC on Azure and typically
 * transfer an order of magnitude faster. NOAA Digital Coast is a third option for shoreline
 * surveys that are often denser than inland 3DEP. OpenTopography hosts additional high-res
 * clouds and will crop them to the search box instead of shipping whole tiles.
 */
enum class LidarDownloadSource(
    val chipLabel: String,
    val shortHost: String,
    val description: String,
) {
    FAST_AZURE(
        chipLabel = "Fast Azure",
        shortHost = "Azure 3DEP (fast)",
        description = "USGS 3DEP COPC on Azure. Same points as TNM, much faster downloads.",
    ),
    OPEN_TOPOGRAPHY(
        chipLabel = "OpenTopo",
        shortHost = "OpenTopography",
        description = "High-res hosted clouds cropped to your box. Needs a free myOpenTopo API key.",
    ),
    USGS_TNM(
        chipLabel = "USGS TNM",
        shortHost = "USGS TNM",
        description = "Official National Map LPC on rockyweb. Complete, but slow.",
    ),
    NOAA_COAST(
        chipLabel = "NOAA Coast",
        shortHost = "NOAA Digital Coast",
        description = "Coastal COPC on AWS S3. Often denser than 3DEP; empty inland.",
    ),
}
