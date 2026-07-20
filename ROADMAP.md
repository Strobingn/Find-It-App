# Find It — Metal detecting roadmap

Native Android companion for detectorists: map, log, grid, terrain relief, and share.

| Priority | Feature | Why it helps | Impact | Effort | Status |
|---:|---|---|---|---|---|
| 1 | Historical map overlays (old topo, Sanborn, historical imagery) | See where old houses, roads, privies, foundations used to be | Very High | Medium | **Wired** — catalog + toggles; real tiles next |
| 2 | Advanced terrain visualization (SVF, Openness, multi hillshade) | Subtle depressions / mounds vs plain hillshade | Very High | Medium | **Live** — offline DEM analyzer + raster UI |
| 3 | Structured Find Logging (photo, depth, metal type, notes) | Proper hunt log vs paper notes | High | Low–Med | **Live** — GPS fill, camera photo, session stamp, offline JSON |
| 4 | Search Grid Planner | Overlay grids on fields / beaches | High | Low | **Live** — create grid, mark cells, map canvas |
| 5 | AR Camera Overlay with terrain highlights | Live ground features while swinging | High | Medium | **Shell** — UI + power-aware FPS |
| 6 | Multi-point viewshed + team visibility | What partners can / cannot see | Med–High | Low | **Live** — pairwise LOS + map; terrain LOS next |
| 7 | Battery + thermal optimization | All-day LiDAR / hillshade on phone | High | Low | **Live** — low-power GPS/DEM/AR knobs |
| 8 | Quick ground disturbance detector | Auto-highlight lows/highs from LiDAR | High | Medium | **Live** — residual DEM detector |
| 9 | Export GPX / KML / CSV (+ photos) | Share with hunters / records | Medium | Low | **Live** — share sheet via FileProvider |
| 10 | Offline-first + fast local processing | Faster caching | High | Low | **Live** — OfflineCache + photo store |

## Product identity

| | |
|---|---|
| **App** | Find It |
| **applicationId** | `com.strobingn.findit` |
| **Repo** | https://github.com/Strobingn/https-github.com-Strobingn-Find-It-App |
| **UI** | Jetpack Compose + Material 3 |
| **Branch** | `grok` for agent work |
| **version** | `0.2.0-metal` |

## v0.2 shipped

- Live GPS on home map (follow-me / overview) and Log Find auto-fill  
- Camera capture → find photo URI (FileProvider under `offline/photos`)  
- Hunt session start/end stamps finds  
- “You” team marker tracks GPS  

## Next slices

1. USGS / offline MBTiles for historical layers  
2. CameraX AR + project disturbance hotspots  
3. Terrain-aware team LOS (Viewshade ray math)  
4. LiDAR / GeoTIFF DEM import for real ground disturbance  
