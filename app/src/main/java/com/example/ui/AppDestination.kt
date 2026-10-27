package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * App destinations.
 *
 * [primary] entries get a slot in the bottom tab bar, which holds at most [TAB_BAR_LIMIT] so the
 * labels stay readable on a phone: the bar divides the width evenly, so eight tabs left 45dp per
 * slot on a 360dp screen. Every other destination opens from its Home card — Home lists all of
 * them, so nothing is reachable only through the tab bar.
 *
 * [fullBleed] marks screens that fill the window with their own chrome (a map's own controls, or
 * Home's large title) and so don't want the inline nav bar on top. They only get to drop it while
 * they're primary; opened from a Home card they still need a way back.
 */
enum class AppDestination(
    val label: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val primary: Boolean,
    val fullBleed: Boolean = false,
) {
    HOME(
        label = "Home",
        title = "Find It",
        subtitle = "Project hub and shortcuts",
        icon = Icons.Default.Home,
        primary = true,
        fullBleed = true,
    ),
    TERRAIN(
        label = "Terrain",
        title = "Terrain workspace",
        subtitle = "Hillshade, refine, analyze",
        icon = Icons.Default.Landscape,
        primary = true,
        fullBleed = true,
    ),
    FIELD(
        label = "Field",
        title = "Field finds",
        subtitle = "Targets, digs, GPS, boundaries",
        icon = Icons.Default.Flag,
        primary = true,
    ),
    THERMALS(
        label = "Thermals",
        title = "Thermal wind",
        subtitle = "Hour-by-hour scent drift and AI hunt plan",
        icon = Icons.Default.Air,
        primary = false,
    ),
    WIND_MAP(
        label = "Wind",
        title = "Thermal wind map",
        subtitle = "Live 3D map of thermal and scent flow",
        icon = Icons.Default.Explore,
        primary = false,
        fullBleed = true,
    ),
    STANDS(
        label = "Stands",
        title = "AI stand finder",
        subtitle = "Best stand sites and hunt areas on a map",
        icon = Icons.Default.Forest,
        primary = true,
        fullBleed = true,
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
        primary = false,
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
        subtitle = "Satellite, 3D lidar terrain, old topo maps",
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

    /** True when this screen should hide the inline nav bar; see [fullBleed]. */
    val hidesNavBar: Boolean get() = fullBleed && primary

    companion object {
        /** Tab bars stop being readable past five items on a phone (Material and iOS both cap there). */
        const val TAB_BAR_LIMIT = 5

        val primaryDestinations: List<AppDestination> = entries.filter { it.primary }
        val secondaryDestinations: List<AppDestination> = entries.filter { !it.primary }

        /** Everything Home offers as a workspace row — every destination except Home itself. */
        val workspaceDestinations: List<AppDestination> = entries.filter { it != HOME }
    }
}
