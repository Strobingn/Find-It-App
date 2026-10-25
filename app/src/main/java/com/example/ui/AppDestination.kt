package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * App destinations. Primary entries appear in the bottom bar; secondary ones open from Home
 * cards (or Library) as full screens so every workflow has its own surface.
 */
enum class AppDestination(
    val label: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val primary: Boolean,
) {
    HOME(
        label = "Home",
        title = "Find It",
        subtitle = "Project hub and shortcuts",
        icon = Icons.Default.Home,
        primary = true,
    ),
    TERRAIN(
        label = "Terrain",
        title = "Terrain workspace",
        subtitle = "Hillshade, refine, analyze",
        icon = Icons.Default.Landscape,
        primary = true,
    ),
    FIELD(
        label = "Field",
        title = "Field finds",
        subtitle = "Targets, digs, GPS, boundaries",
        icon = Icons.Default.Flag,
        primary = true,
    ),
    AI(
        label = "AI",
        title = "AI field assistant",
        subtitle = "Cloud copilots and prompts",
        icon = Icons.Default.AutoAwesome,
        primary = true,
    ),
    LIBRARY(
        label = "Library",
        title = "Terrain library",
        subtitle = "Import LAZ, downloads, basemaps",
        icon = Icons.Default.UploadFile,
        primary = true,
    ),
    MAP(
        label = "Map",
        title = "Site map",
        subtitle = "Google Maps + historic overlays",
        icon = Icons.Default.Layers,
        primary = false,
    ),
    MAPBOX(
        label = "3D Map",
        title = "Mapbox terrain map",
        subtitle = "Satellite, 3D terrain, USGS topo",
        icon = Icons.Default.Terrain,
        primary = false,
    ),
    LIDAR(
        label = "LiDAR",
        title = "LiDAR area picker",
        subtitle = "Select and download tiles",
        icon = Icons.Default.CenterFocusStrong,
        primary = false,
    ),
    COMPARE(
        label = "Compare",
        title = "Layer compare",
        subtitle = "Side-by-side terrain analysis",
        icon = Icons.Default.Compare,
        primary = false,
    ),
    TOOLS(
        label = "Tools",
        title = "Field tools",
        subtitle = "Export, routes, stats, ethics",
        icon = Icons.Default.Build,
        primary = false,
    ),
    ;

    companion object {
        val primaryDestinations: List<AppDestination> = entries.filter { it.primary }
        val secondaryDestinations: List<AppDestination> = entries.filter { !it.primary }
    }
}
