package com.example.geospatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Standard "slippy map" XYZ tile-index math shared by OSM, Google, and Bing tile schemes. */
object SlippyTileMath {
    private const val MAX_LATITUDE = 85.05112878

    data class TileRange(val zoom: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val tileCount: Int get() = (maxX - minX + 1) * (maxY - minY + 1)
    }

    fun lonToTileX(lon: Double, zoom: Int): Int {
        val tilesAcross = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * tilesAcross).toInt().coerceIn(0, tilesAcross - 1)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val tilesAcross = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * tilesAcross)
            .toInt()
            .coerceIn(0, tilesAcross - 1)
    }

    fun boundsToTileRange(bounds: GeoSpatialLibrary.GeographicBounds, zoom: Int): TileRange {
        val minX = lonToTileX(bounds.minLon, zoom)
        val maxX = lonToTileX(bounds.maxLon, zoom)
        // Tile Y grows southward, so the northern (max) latitude maps to the smaller tile Y.
        val minY = latToTileY(bounds.maxLat, zoom)
        val maxY = latToTileY(bounds.minLat, zoom)
        return TileRange(
            zoom = zoom,
            minX = minOf(minX, maxX),
            maxX = maxOf(minX, maxX),
            minY = minOf(minY, maxY),
            maxY = maxOf(minY, maxY),
        )
    }

    /** Picks the highest zoom whose tile coverage for [bounds] stays within [maxTiles]. */
    fun chooseZoomForBounds(bounds: GeoSpatialLibrary.GeographicBounds, maxTiles: Int, maxZoom: Int): Int {
        for (zoom in maxZoom downTo 1) {
            if (boundsToTileRange(bounds, zoom).tileCount <= maxTiles) return zoom
        }
        return 1
    }
}
