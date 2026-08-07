# Phase 0 Wiring Audit — Field Closure Pack

**Date:** 2026-08-05  
**Branch:** GROKV6  

| ID | Feature | Result | Evidence |
|----|---------|--------|----------|
| W1 | Dual surface + class filter | **Pass** | `LidarControlPanel` chips → `setGroundSurfaceMode` / `setPointClassPreset`; cache key includes `allowedClasses` |
| W2 | Refine to survey boundary | **Pass** | `refineToSurveyBoundary` + Terrain/Tools buttons `refine_to_boundary_button` |
| W3 | Surface Z under find | **Pass** | `surfaceZForSignal` → `find_surface_z_card` in TargetLoggerPanel |
| W4 | Mosaic open UX | **Pass** | `MosaicOpenUx.cardFor` in NysLazTilePicker |
| W5 | Site package / LAS / PDF | **Pass** | `buildSitePackageBytes` + Tools CreateDocument |
| W6 | Boundary GPS banner | **Pass** | `boundaryProximityAlert` + `boundary_proximity_banner` |
| W7 | AI confirm-write | **Pass** | `ai_confirm_write_card` + `applyAiFindSuggestions` |
| W8 | Hub navigation | **Pass** | `AppDestination` + `HomeHubScreen` + 5-tab shell |

**Exit:** Phase 0 complete — Wave 1+ may ship.
