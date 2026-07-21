package com.example.ui.components

import com.example.data.NormalizedRasterBounds
import com.example.geospatial.GeoSpatialLibrary.GeographicBounds
import kotlin.math.max

internal fun hasUsableMapsApiKey(apiKey: String): Boolean {
    val value = apiKey.trim()
    return value.isNotEmpty() &&
        !value.equals("DEFAULT_API_KEY", ignoreCase = true) &&
        !value.equals("YOUR_API_KEY", ignoreCase = true) &&
        !value.contains("MAPS_API_KEY", ignoreCase = true)
}

internal fun shouldUseGeographicMap(
    bounds: GeographicBounds?,
    apiKey: String,
): Boolean = bounds != null &&
    bounds.minLat in -90.0..90.0 &&
    bounds.maxLat in -90.0..90.0 &&
    bounds.minLon in -180.0..180.0 &&
    bounds.maxLon in -180.0..180.0 &&
    bounds.minLat < bounds.maxLat &&
    bounds.minLon < bounds.maxLon &&
    hasUsableMapsApiKey(apiKey)

internal fun normalizedViewportFor(
    layer: GeographicBounds,
    visible: GeographicBounds,
): Pair<NormalizedRasterBounds, Float> {
    val latitudeRange = layer.maxLat - layer.minLat
    val longitudeRange = layer.maxLon - layer.minLon
    if (latitudeRange <= 0.0 || longitudeRange <= 0.0) {
        return NormalizedRasterBounds.Full to 1f
    }

    val viewport = NormalizedRasterBounds(
        left = (visible.minLon - layer.minLon) / longitudeRange,
        top = (layer.maxLat - visible.maxLat) / latitudeRange,
        right = (visible.maxLon - layer.minLon) / longitudeRange,
        bottom = (layer.maxLat - visible.minLat) / latitudeRange,
    ).sanitized()
    val visibleFraction = max(
        viewport.right - viewport.left,
        viewport.bottom - viewport.top,
    ).coerceAtLeast(0.0001)
    return viewport to (1.0 / visibleFraction).toFloat().coerceAtLeast(1f)
}
