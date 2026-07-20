package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LidarControlPanel(
    selectedSiteIndex: Int,
    onSiteSelected: (Int) -> Unit,
    sunAzimuth: Float,
    onSunAzimuthChanged: (Float) -> Unit,
    sunAltitude: Float,
    onSunAltitudeChanged: (Float) -> Unit,
    vegetationFilter: Float,
    onVegetationFilterChanged: (Float) -> Unit,
    paletteType: Int,
    onPaletteTypeChanged: (Int) -> Unit,
    contrast: Float,
    onContrastChanged: (Float) -> Unit,
    visualizationMode: Int,
    onVisualizationModeChanged: (Int) -> Unit,
    overlayType: Int,
    onOverlayTypeChanged: (Int) -> Unit,
    overlayOpacity: Float,
    onOverlayOpacityChanged: (Float) -> Unit,
    gridSpacing: Float,
    onGridSpacingChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141518))
            .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("lidar_control_panel")
    ) {
        // --- SECTION 1: ARCHAEOLOGICAL SITES ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Landscape,
                contentDescription = null,
                tint = Color(0xFF00E676),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "ARCHAEOLOGICAL SITE TEMPLATE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Site Tabs (Homestead, Fort, Roman Villa, Custom Layer)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val sites = listOf("1800s Homestead", "Civil War Fort", "Roman Villa", "Custom Layer")
            sites.forEachIndexed { index, title ->
                val isSelected = selectedSiteIndex == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) {
                                if (index == 3) Color(0xFF29B6F6).copy(alpha = 0.15f) else Color(0xFF00E676).copy(alpha = 0.15f)
                            } else {
                                Color(0xFF1E2026)
                            }
                        )
                        .border(
                            1.dp,
                            if (isSelected) {
                                if (index == 3) Color(0xFF29B6F6) else Color(0xFF00E676)
                            } else {
                                Color(0xFF2E313D)
                            },
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSiteSelected(index) }
                        .padding(vertical = 10.dp, horizontal = 2.dp)
                        .testTag("site_tab_$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 2: GROUND CLASSIFICATION FILTER ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color(0xFF2196F3),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "GROUND CLASSIFICATION (CANOPY REMOVAL)",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (vegetationFilter == 0f) "Surface Model (DSM - Full Trees)" else if (vegetationFilter == 1f) "Bare Ground (DEM - Fully Classified)" else "LiDAR Point Filter Active",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${(vegetationFilter * 100).toInt()}% filtered",
                color = Color(0xFF2196F3),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Slider(
            value = vegetationFilter,
            onValueChange = onVegetationFilterChanged,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF2196F3),
                activeTrackColor = Color(0xFF2196F3),
                inactiveTrackColor = Color(0xFF1E2026)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("vegetation_slider")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- SECTION 3: ADVANCED TERRAIN VISUALIZATION (Priority 2) ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Landscape,
                contentDescription = null,
                tint = Color(0xFFFFD700),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "ADVANCED SHADOW RELIEF STYLE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val visualizerStyles =
                listOf("Standard", "Multi-Vec", "Slope", "Foundations")
            visualizerStyles.forEachIndexed { index, name ->
                val isSelected = visualizationMode == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFFFFD700).copy(alpha = 0.15f) else Color(0xFF1E2026))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFFFFD700) else Color(0xFF2E313D),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onVisualizationModeChanged(index) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 9.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 4: HISTORICAL MAP OVERLAYS (Priority 1) ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                tint = Color(0xFF00E676),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "HISTORICAL GEOSPATIAL MAP OVERLAYS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val overlays = listOf("Disabled", "1880s Homestead Plat", "1940s Vintage Contours")
            overlays.forEachIndexed { index, name ->
                val isSelected = overlayType == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFF1E2026))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF00E676) else Color(0xFF2E313D),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onOverlayTypeChanged(index) }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        if (overlayType > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Overlay Opacity Blend", color = Color.Gray, fontSize = 11.sp)
                Text(text = "${(overlayOpacity * 100).toInt()}%", color = Color(0xFF00E676), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = overlayOpacity,
                onValueChange = onOverlayOpacityChanged,
                valueRange = 0.1f..0.9f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00E676),
                    activeTrackColor = Color(0xFF00E676),
                    inactiveTrackColor = Color(0xFF1E2026)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 5: COIL SEARCH GRID PLANNER (Priority 4) ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                tint = Color(0xFF29B6F6),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "SURVEY SEARCH GRID PLANNER",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val gridOptions = listOf(0f to "Disabled", 20f to "5x5 Cells", 10f to "10x10 Cells", 5f to "20x20 Cells")
            gridOptions.forEach { (spacing, label) ->
                val isSelected = gridSpacing == spacing
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF29B6F6).copy(alpha = 0.15f) else Color(0xFF1E2026))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF29B6F6) else Color(0xFF2E313D),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onGridSpacingChanged(spacing) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SECTION 6: HILLSHADE GEOMETRY CONTROLS ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Brightness5,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "SUN LIGHT HILLSHADE GEOMETRY",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Sun Azimuth Slider
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Light Direction (Azimuth)", color = Color.Gray, fontSize = 11.sp)
            Text(text = "${sunAzimuth.toInt()}°", color = Color(0xFFFF9800), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = sunAzimuth,
            onValueChange = onSunAzimuthChanged,
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF9800),
                activeTrackColor = Color(0xFFFF9800),
                inactiveTrackColor = Color(0xFF1E2026)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sun_azimuth_slider")
        )

        // Sun Altitude Slider
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Light Angle Height (Altitude)", color = Color.Gray, fontSize = 11.sp)
            Text(text = "${sunAltitude.toInt()}°", color = Color(0xFFFF9800), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = sunAltitude,
            onValueChange = onSunAltitudeChanged,
            valueRange = 5f..85f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF9800),
                activeTrackColor = Color(0xFFFF9800),
                inactiveTrackColor = Color(0xFF1E2026)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sun_altitude_slider")
        )

        // Contrast / Relief depth Slider
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Contrast, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Shadow Contrast Depth", color = Color.Gray, fontSize = 11.sp)
            }
            Text(text = "${String.format("%.1f", contrast)}x", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = contrast,
            onValueChange = onContrastChanged,
            valueRange = 1.0f..2.5f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF1E2026)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("contrast_slider")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- SECTION 7: COLOR PALETTE ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                tint = Color(0xFFE040FB),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "HYPSOMETRIC ELEVATION TINT",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val palettes = listOf("Grey Clay", "LiDAR Copper", "Terra Geoid")
            palettes.forEachIndexed { index, name ->
                val isSelected = paletteType == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFFE040FB).copy(alpha = 0.15f) else Color(0xFF1E2026))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFFE040FB) else Color(0xFF2E313D),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onPaletteTypeChanged(index) }
                        .padding(vertical = 10.dp)
                        .testTag("palette_tab_$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
