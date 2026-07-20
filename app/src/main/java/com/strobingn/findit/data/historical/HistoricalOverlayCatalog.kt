package com.strobingn.findit.data.historical

import com.strobingn.findit.data.model.OverlayKind
import com.strobingn.findit.data.model.OverlayLayer

/**
 * Historical map overlays for metal detectorists (roadmap #1):
 * old topo, Sanborn fire insurance, historical imagery.
 *
 * Sources are catalog entries; actual tiles can be wired to USGS/ESRI later.
 * Offline demo still works with synthetic shading from TerrainAnalyzer.
 */
object HistoricalOverlayCatalog {

  val defaultLayers: List<OverlayLayer> =
    listOf(
      OverlayLayer(
        id = "hist_topo_usgs",
        name = "USGS Historical Topo",
        kind = OverlayKind.HISTORICAL_TOPO,
        description = "Old contour maps — spot vanished roads, homes, and field lines.",
        sourceUrl = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer",
      ),
      OverlayLayer(
        id = "sanborn",
        name = "Sanborn / fire insurance",
        kind = OverlayKind.SANBORN,
        description = "Building footprints and privies in historic towns (when available offline).",
      ),
      OverlayLayer(
        id = "hist_imagery",
        name = "Historical imagery",
        kind = OverlayKind.HISTORICAL_IMAGERY,
        description = "Older aerials to see former house pads and farmsteads.",
      ),
      OverlayLayer(
        id = "hillshade",
        name = "Hillshade (multi-style)",
        kind = OverlayKind.HILLSHADE,
        description = "Classic relief shading; change light angle to reveal subtle mounds.",
      ),
      OverlayLayer(
        id = "svf",
        name = "Sky View Factor",
        kind = OverlayKind.SKY_VIEW_FACTOR,
        description = "Highlights depressions and hollows better than plain hillshade.",
      ),
      OverlayLayer(
        id = "openness",
        name = "Openness",
        kind = OverlayKind.OPENNESS,
        description = "Emphasizes ridges and free sky — good for banks and berms.",
      ),
      OverlayLayer(
        id = "disturbance",
        name = "Ground disturbance",
        kind = OverlayKind.GROUND_DISTURBANCE,
        description = "Auto-highlights local highs/lows from LiDAR/DEM (foundations, pits).",
      ),
    )
}
