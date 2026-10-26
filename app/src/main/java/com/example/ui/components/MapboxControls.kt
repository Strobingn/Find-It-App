package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.relief.LidarTerrainServer
import com.example.data.relief.ReliefMode
import com.example.data.relief.ReliefSettings
import com.mapbox.maps.Style
import java.util.Locale
import kotlin.math.roundToInt

/** Base map styles offered on the Mapbox tab. */
internal enum class MapboxBaseStyle(val label: String, val uri: String) {
    SATELLITE("Satellite", Style.SATELLITE_STREETS),
    SATELLITE_CLEAN("No labels", Style.SATELLITE),
    OUTDOORS("Outdoors", Style.OUTDOORS),
}

/** Control panels on the Mapbox tab. */
internal enum class MapboxPanel(val label: String) {
    MAP("Map"),
    TERRAIN("3D"),
    LIDAR("LiDAR"),
    TOPO("Old topo"),
}

/** Everything the user sets on the Mapbox tab. Survives rotation and the app being reclaimed. */
@Stable
internal class MapboxControls {
    var panel by mutableStateOf(MapboxPanel.MAP)

    /** Starts folded to one row of tabs so the map gets the screen. */
    var expanded by mutableStateOf(false)

    var baseStyle by mutableStateOf(MapboxBaseStyle.SATELLITE)
    var hillshade by mutableStateOf(false)
    var usgsTopo by mutableStateOf(false)
    var showLidar by mutableStateOf(true)
    var lidarOpacity by mutableFloatStateOf(0.72f)

    var terrain3d by mutableStateOf(true)

    /** USGS lidar elevation by default: sharper than Mapbox's global DEM wherever 3DEP has flown. */
    var lidarTerrain by mutableStateOf(true)
    var exaggeration by mutableFloatStateOf(1.5f)

    /** Why 3D fell back to Mapbox elevation, if it did. Not saved. */
    var lidarNote by mutableStateOf<String?>(null)

    var reliefOn by mutableStateOf(false)
    var reliefOpacity by mutableFloatStateOf(0.85f)
    var reliefMode by mutableStateOf(ReliefMode.HILLSHADE)
    var sunAzimuth by mutableFloatStateOf(315f)
    var sunAltitude by mutableFloatStateOf(35f)
    var zFactor by mutableFloatStateOf(2f)
    var multiDirection by mutableStateOf(true)
    var multiColour by mutableStateOf(false)
    var radiusMeters by mutableFloatStateOf(10f)
    var brightness by mutableFloatStateOf(0f)
    var contrast by mutableFloatStateOf(1f)
    var reliefMore by mutableStateOf(false)

    var topoOn by mutableStateOf(false)
    var topoOpacity by mutableFloatStateOf(0.85f)
    var topoDecade by mutableStateOf<Int?>(null)

    val reliefSettings: ReliefSettings
        get() = ReliefSettings(
            mode = reliefMode,
            azimuthDeg = sunAzimuth,
            altitudeDeg = sunAltitude,
            zFactor = zFactor,
            multiDirection = multiDirection,
            multiColour = multiColour,
            radiusMeters = radiusMeters,
            brightness = brightness,
            contrast = contrast,
        )

    fun resetLook() {
        val d = ReliefSettings()
        sunAzimuth = d.azimuthDeg
        sunAltitude = d.altitudeDeg
        zFactor = d.zFactor
        multiDirection = d.multiDirection
        multiColour = d.multiColour
        radiusMeters = d.radiusMeters
        brightness = d.brightness
        contrast = d.contrast
    }

    companion object {
        val Saver: Saver<MapboxControls, Any> = mapSaver(
            save = { c ->
                mapOf(
                    "panel" to c.panel.name,
                    "expanded" to c.expanded,
                    "baseStyle" to c.baseStyle.name,
                    "hillshade" to c.hillshade,
                    "usgsTopo" to c.usgsTopo,
                    "showLidar" to c.showLidar,
                    "lidarOpacity" to c.lidarOpacity,
                    "terrain3d" to c.terrain3d,
                    "lidarTerrain" to c.lidarTerrain,
                    "exaggeration" to c.exaggeration,
                    "reliefOn" to c.reliefOn,
                    "reliefOpacity" to c.reliefOpacity,
                    "reliefMode" to c.reliefMode.name,
                    "sunAzimuth" to c.sunAzimuth,
                    "sunAltitude" to c.sunAltitude,
                    "zFactor" to c.zFactor,
                    "multiDirection" to c.multiDirection,
                    "multiColour" to c.multiColour,
                    "radiusMeters" to c.radiusMeters,
                    "brightness" to c.brightness,
                    "contrast" to c.contrast,
                    "reliefMore" to c.reliefMore,
                    "topoOn" to c.topoOn,
                    "topoOpacity" to c.topoOpacity,
                    "topoDecade" to c.topoDecade,
                )
            },
            restore = { m ->
                fun <T> get(key: String, default: T): T {
                    @Suppress("UNCHECKED_CAST")
                    return (m[key] as? T) ?: default
                }
                MapboxControls().apply {
                    panel = runCatching { MapboxPanel.valueOf(get("panel", "")) }.getOrDefault(panel)
                    expanded = get("expanded", expanded)
                    baseStyle = runCatching { MapboxBaseStyle.valueOf(get("baseStyle", "")) }.getOrDefault(baseStyle)
                    hillshade = get("hillshade", hillshade)
                    usgsTopo = get("usgsTopo", usgsTopo)
                    showLidar = get("showLidar", showLidar)
                    lidarOpacity = get("lidarOpacity", lidarOpacity)
                    terrain3d = get("terrain3d", terrain3d)
                    lidarTerrain = get("lidarTerrain", lidarTerrain)
                    exaggeration = get("exaggeration", exaggeration)
                    reliefOn = get("reliefOn", reliefOn)
                    reliefOpacity = get("reliefOpacity", reliefOpacity)
                    reliefMode = runCatching { ReliefMode.valueOf(get("reliefMode", "")) }.getOrDefault(reliefMode)
                    sunAzimuth = get("sunAzimuth", sunAzimuth)
                    sunAltitude = get("sunAltitude", sunAltitude)
                    zFactor = get("zFactor", zFactor)
                    multiDirection = get("multiDirection", multiDirection)
                    multiColour = get("multiColour", multiColour)
                    radiusMeters = get("radiusMeters", radiusMeters)
                    brightness = get("brightness", brightness)
                    contrast = get("contrast", contrast)
                    reliefMore = get("reliefMore", reliefMore)
                    topoOn = get("topoOn", topoOn)
                    topoOpacity = get("topoOpacity", topoOpacity)
                    topoDecade = m["topoDecade"] as? Int
                }
            },
        )
    }
}

/** Read-only facts the panels show. Fast-changing values are lambdas so only the panel recomposes. */
internal class MapboxControlStatus(
    val zoom: () -> Double,
    val lidarStats: () -> LidarTerrainServer.Stats,
    val lidarStarting: Boolean,
    val hasMyLidar: Boolean,
    val myLidarUnplaced: Boolean,
    val reliefLoading: Int,
    val reliefResolution: Double?,
    val reliefMinZoom: Int,
    val topoDecades: List<Int>,
    val topoSheetLines: List<String>,
    val topoStatus: String?,
)

/**
 * The Mapbox tab's floating controls: one slim row of tabs that opens into a short panel.
 * Touch padding is trimmed so chips and sliders hug their content instead of 48 dp boxes.
 */
@Composable
internal fun MapboxControlCard(
    controls: MapboxControls,
    status: MapboxControlStatus,
    onTilt: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Card(
            modifier = modifier.testTag("mapbox_controls"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                // A translucent surface matches no theme colour, so say which text colour goes on it.
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MapboxPanel.entries.forEach { option ->
                            val selected = controls.expanded && controls.panel == option
                            PanelTab(
                                label = option.label,
                                selected = selected,
                                onClick = {
                                    if (selected) {
                                        controls.expanded = false
                                    } else {
                                        controls.panel = option
                                        controls.expanded = true
                                    }
                                },
                                modifier = Modifier.testTag("mapbox_panel_${option.name.lowercase(Locale.US)}"),
                            )
                        }
                    }
                    if (controls.expanded) {
                        IconButton(onClick = { controls.expanded = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Hide map controls", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (controls.expanded) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Column(
                        Modifier
                            .heightIn(max = PANEL_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        when (controls.panel) {
                            MapboxPanel.MAP -> MapPanel(controls, status)
                            MapboxPanel.TERRAIN -> TerrainPanel(controls, status, onTilt)
                            MapboxPanel.LIDAR -> ReliefPanel(controls, status)
                            MapboxPanel.TOPO -> TopoPanel(controls, status)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPanel(c: MapboxControls, status: MapboxControlStatus) {
    ChipRow {
        MapboxBaseStyle.entries.forEach { option ->
            SmallChip(
                option.label,
                c.baseStyle == option,
                { c.baseStyle = option },
                Modifier.testTag("mapbox_style_${option.name.lowercase(Locale.US)}"),
            )
        }
    }
    ChipRow {
        SmallChip("Hillshade", c.hillshade, { c.hillshade = !c.hillshade })
        SmallChip("USGS topo", c.usgsTopo, { c.usgsTopo = !c.usgsTopo })
        if (status.hasMyLidar) SmallChip("My LiDAR", c.showLidar, { c.showLidar = !c.showLidar })
    }
    if (status.hasMyLidar && c.showLidar) {
        SliderRow("My LiDAR", "${(c.lidarOpacity * 100).toInt()}%", c.lidarOpacity, { c.lidarOpacity = it }, 0.1f..1f)
    }
    if (status.myLidarUnplaced) {
        Hint("Your LiDAR isn't georeferenced yet. Place it on the Google map tab first.")
    }
}

@Composable
private fun TerrainPanel(c: MapboxControls, status: MapboxControlStatus, onTilt: (Double) -> Unit) {
    ChipRow {
        SmallChip(
            if (c.terrain3d) "3D on" else "3D off",
            c.terrain3d,
            {
                c.terrain3d = !c.terrain3d
                onTilt(if (c.terrain3d) TILT_DEGREES else 0.0)
            },
            Modifier.testTag("mapbox_terrain_toggle"),
        )
        if (c.terrain3d) {
            SmallChip(
                "USGS lidar 1 m",
                c.lidarTerrain,
                {
                    c.lidarTerrain = true
                    c.lidarNote = null
                },
                Modifier.testTag("mapbox_terrain_lidar"),
            )
            SmallChip("Mapbox", !c.lidarTerrain, { c.lidarTerrain = false }, Modifier.testTag("mapbox_terrain_mapbox"))
        }
    }
    if (!c.terrain3d) {
        Hint("Turn on 3D to stand the ground up. Every layer drapes over it.")
        return
    }
    SliderRow("Height", String.format(Locale.US, "%.1f×", c.exaggeration), c.exaggeration, { c.exaggeration = it }, 1f..6f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallButton("Tilt") { onTilt(TILT_DEGREES) }
        SmallButton("Top-down") { onTilt(0.0) }
        val stats = status.lidarStats()
        val note = when {
            !c.lidarTerrain -> c.lidarNote ?: "Worldwide, smoother"
            status.lidarStarting -> "Starting…"
            stats.inFlight > 0 -> "Loading ${stats.inFlight}…"
            stats.failed > 0 && stats.served == 0 -> "Not loading. Check signal."
            else -> "US only · two fingers tilt"
        }
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
    }
}

@Composable
private fun ReliefPanel(c: MapboxControls, status: MapboxControlStatus) {
    ChipRow {
        SmallChip(
            if (c.reliefOn) "On" else "Off",
            c.reliefOn,
            { c.reliefOn = !c.reliefOn },
            Modifier.testTag("mapbox_relief_toggle"),
        )
        ReliefMode.entries.forEach { mode ->
            SmallChip(mode.label, c.reliefOn && c.reliefMode == mode, {
                c.reliefMode = mode
                c.reliefOn = true
            })
        }
    }
    Hint(c.reliefMode.description, maxLines = 2)
    SliderRow("Opacity", "${(c.reliefOpacity * 100).toInt()}%", c.reliefOpacity, { c.reliefOpacity = it }, 0.1f..1f)

    val zoom = status.zoom()
    val note = when {
        !c.reliefOn -> null
        zoom < status.reliefMinZoom -> "Zoom in further to draw LiDAR."
        status.reliefLoading > 0 -> "Loading LiDAR… (${status.reliefLoading})"
        status.reliefResolution != null -> String.format(Locale.US, "USGS 3DEP, ≈%.1f m per pixel here.", status.reliefResolution)
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            note.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SmallButton(if (c.reliefMore) "Less" else "Adjust look") { c.reliefMore = !c.reliefMore }
    }
    if (!c.reliefMore) return

    val mode = c.reliefMode
    if (mode == ReliefMode.HILLSHADE) {
        ChipRow {
            SmallChip("Grey", !c.multiColour, { c.multiColour = false })
            SmallChip("Colour", c.multiColour, { c.multiColour = true })
            SmallChip("One sun", !c.multiDirection, { c.multiDirection = false })
            SmallChip("Four suns", c.multiDirection, { c.multiDirection = true })
        }
    }
    val usesLight = mode in setOf(ReliefMode.HILLSHADE, ReliefMode.VAT, ReliefMode.MULTI_LIGHT_PCA)
    val usesZ = mode in setOf(
        ReliefMode.HILLSHADE, ReliefMode.SLOPE, ReliefMode.RED_RELIEF, ReliefMode.VAT,
        ReliefMode.MULTI_LIGHT_PCA, ReliefMode.SVF_SLOPE,
    )
    val usesRadius = mode !in setOf(ReliefMode.HILLSHADE, ReliefMode.SLOPE, ReliefMode.MULTI_LIGHT_PCA)
    if (usesLight && mode != ReliefMode.MULTI_LIGHT_PCA) {
        CommitSliderRow("Sun", { "${it.roundToInt()}° ${compass(it)}" }, c.sunAzimuth, 0f..360f) { c.sunAzimuth = it }
    }
    if (usesLight) {
        CommitSliderRow("Sun height", { "${it.roundToInt()}°" }, c.sunAltitude, 5f..80f) { c.sunAltitude = it }
    }
    if (usesZ) {
        CommitSliderRow("Z factor", { String.format(Locale.US, "×%.1f", it) }, c.zFactor, 0.5f..10f) { c.zFactor = it }
    }
    if (usesRadius) {
        CommitSliderRow("Radius", { "${it.roundToInt()} m" }, c.radiusMeters, 2f..30f) { c.radiusMeters = it }
    }
    CommitSliderRow("Brightness", { signedPercent(it * 2) }, c.brightness, -0.5f..0.5f) { c.brightness = it }
    CommitSliderRow("Contrast", { String.format(Locale.US, "×%.1f", it) }, c.contrast, 0.5f..2f) { c.contrast = it }
    SmallButton("Reset look") { c.resetLook() }
}

@Composable
private fun TopoPanel(c: MapboxControls, status: MapboxControlStatus) {
    val decade = c.topoDecade
    val decades = status.topoDecades
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallChip(
            if (c.topoOn) "On" else "Off",
            c.topoOn,
            { c.topoOn = !c.topoOn },
            Modifier.testTag("mapbox_topo_toggle"),
        )
        val title = when {
            c.topoOn && decade != null && decades.isNotEmpty() -> "${decade}s"
            c.topoOn && status.zoom() < 9.0 -> "Zoom in to a town or farm"
            c.topoOn -> "Looking for old maps…"
            else -> "USGS historical topo maps"
        }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (decade != null && c.topoOn) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    if (c.topoOn && decade != null && decades.size > 1) {
        val index = decades.indexOf(decade).coerceAtLeast(0)
        SliderRow(
            "${decades.first()}s",
            "${decades.last()}s",
            index.toFloat(),
            { c.topoDecade = decades[it.roundToInt().coerceIn(0, decades.lastIndex)] },
            0f..decades.lastIndex.toFloat(),
            steps = (decades.size - 2).coerceAtLeast(0),
        )
    }
    if (c.topoOn && decade != null) {
        SliderRow("Opacity", "${(c.topoOpacity * 100).toInt()}%", c.topoOpacity, { c.topoOpacity = it }, 0.1f..1f)
        status.topoSheetLines.forEach { Hint(it) }
    }
    status.topoStatus?.let { Hint(it, maxLines = 2) }
}

// --- compact building blocks -------------------------------------------------------------------

@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        modifier = modifier.height(28.dp),
    )
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun Hint(text: String, maxLines: Int = 2) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Label, slider and value on one line. */
@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(SLIDER_LABEL_WIDTH),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.weight(1f).height(32.dp),
        )
        Text(
            valueLabel,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(SLIDER_VALUE_WIDTH),
        )
    }
}

/** A [SliderRow] that only commits when the finger lifts, so expensive re-renders run once per change. */
@Composable
private fun CommitSliderRow(
    label: String,
    valueLabel: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    SliderRow(label, valueLabel(draft), draft, { draft = it }, range, onValueChangeFinished = { onCommit(draft) })
}

private fun compass(degrees: Float): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[((degrees / 45f).roundToInt() % 8 + 8) % 8]
}

private fun signedPercent(v: Float): String {
    val p = (v * 100).roundToInt()
    return when {
        p == 0 -> "Normal"
        p > 0 -> "+$p%"
        else -> "$p%"
    }
}

/** Camera tilt for 3D views: steep enough to read relief, low enough to keep the horizon out. */
internal const val TILT_DEGREES = 60.0

private val PANEL_MAX_HEIGHT = 240.dp
private val SLIDER_LABEL_WIDTH = 76.dp
private val SLIDER_VALUE_WIDTH = 64.dp
