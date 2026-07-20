# Find It — Metal detecting roadmap

Native Android companion for detectorists: map, log, grid, terrain relief, and share.

| Priority | Feature | Why it helps | Impact | Effort | Status |
|---:|---|---|---|---|---|
| 1 | Historical map overlays (old topo, Sanborn, historical imagery) | See where old houses, roads, privies, foundations used to be | Very High | Medium | **Wired** — layer catalog + toggles; tile sources next |
| 2 | Advanced terrain visualization (SVF, Openness, multi hillshade) | Subtle depressions / mounds vs plain hillshade | Very High | Medium | **Wired** — offline DEM analysis + live raster UI |
| 3 | Structured Find Logging (photo, depth, metal type, notes) | Proper hunt log vs paper notes | High | Low–Med | **Wired** — CRUD + offline JSON persist |
| 4 | Search Grid Planner | Overlay grids on fields / beaches | High | Low | **Wired** — create grid, mark cells, map canvas |
| 5 | AR Camera Overlay with terrain highlights | Live ground features while swinging | High | Medium | **Shell** — UI + power-aware FPS target |
| 6 | Multi-point viewshed + team visibility | What partners can / cannot see | Med–High | Low | **Wired** — pairwise LOS stub + map links |
| 7 | Battery + thermal optimization | All-day LiDAR / hillshade on phone | High | Low | **Wired** — low-power DEM size / refresh / AR fps |
| 8 | Quick ground disturbance detector | Auto-highlight lows/highs from LiDAR | High | Medium | **Wired** — residual DEM detector in Terrain tools |
| 9 | Export GPX / KML / CSV (+ photos) | Share with hunters / records | Medium | Low | **Wired** — share sheet via FileProvider |
| 10 | Offline-first + fast local processing | Faster caching | High | Low | **Wired** — `OfflineCache` under app files |

## Product identity

| | |
|---|---|
| **App** | Find It |
| **applicationId** | `com.strobingn.findit` |
| **Repo** | https://github.com/Strobingn/https-github.com-Strobingn-Find-It-App |
| **UI** | Jetpack Compose + Material 3 |
| **Branch** | `grok` for agent work |

## Next implementation slices

1. Live GPS into Log Find + map centering  
2. Camera capture → photo URI on finds  
3. Real historical tiles (USGS / offline MBTiles)  
4. CameraX AR plane + project disturbance hotspots  
5. Full terrain LOS for team (share Viewshade ray math)  
