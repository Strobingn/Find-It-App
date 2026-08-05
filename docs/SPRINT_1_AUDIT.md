# Sprint 1 Core Workflow Audit

**Audit date:** 2026-07-26
**Roadmap:** [ROADMAP.md](../ROADMAP.md)

This audit checks the production UI and persisted state paths against the Phase 1 definition of done. A feature is not marked complete solely because code exists.

| Workflow | Current evidence | Status | Next acceptance work |
|---|---|---|---|
| Manual terrain refinement | Terrain, AI, and Compare call `HillshadeViewModel.refineTerrain` directly. Manual controls are enabled whenever a reopenable source exists, independent of zoom. Automatic refinement retains zoom thresholds to avoid needless work. | Implemented; debug verified | Add an instrumented test at 1x and complete release-build validation. |
| Exact-cell inspection | Terrain Explore mode now maps a tap through the active zoom/pan transform to one source raster cell. The panel reports validity, elevation, bare earth, canopy height, slope, aspect, curvature, local relief, ruggedness, depression depth, openness, linearity, resolution, neighborhood support, and coordinates when georeferenced. | Implemented; emulator and tablet verified | Add instrumented tap/pan/zoom coverage and release-build validation. |
| AI dig-location markers | AI candidates are labeled on the terrain, can be written as saved finds, and carry both dataset and terrain keys. Saved markers are filtered to the active terrain source. | Implemented; debug verified | Add process-restart and migration instrumented tests. |
| Synchronized layer comparison | Compare renders two terrain layers from one grid with a shared zoom and pan state and supports manual visible-area refinement. | Implemented; debug verified | Add screenshot and gesture synchronization regression tests. |
| Multi-dataset candidate comparison | Analyzed dataset snapshots persist and can be compared by geographic proximity. | Implemented; unit coverage present | Validate with two independently imported, overlapping georeferenced datasets. |
| NYS/USGS tile discovery | The Import tab provides coordinate lookup plus reusable rectangle, radius, and polygon selection. Each shape resolves its safe enclosing query envelope, filters returned tile footprints against the exact shape, shows the result geometry, estimates storage, queues downloads, retries failed files, and stores the selection with the mosaic. | Partial; unit verified | Make the selector directly reachable from every import path and add instrumented area-selection coverage. |
| GPX/KML survey workflow | The Import tab securely parses GPX waypoints, routes, and tracks plus KML points, lines, and polygons. Layers persist per terrain source, render on georeferenced terrain and Google Maps, and can be framed or deleted. | Implemented; emulator and tablet build verified | Add instrumented document-picker, Room migration, and round-trip export coverage. |
| Offline basemap regions | The Import tab estimates and downloads named USGS Topo regions for the active georeferenced terrain. Region state and progress persist per terrain source; missing tiles can be retried, active work canceled, stored size inspected, and completed regions reopened without a network. | Implemented; airplane-mode emulator and tablet UI verified | Add multi-region queue instrumentation and larger-area cancellation fixtures. |
| Image and report export | Finds exports the complete source terrain footprint as an annotated PNG and a schema-versioned PDF field report. Compare exports both selected derived layers in aspect-correct side-by-side panes. | Implemented; emulator and external PDF render verified | Add instrumented DocumentsUI coverage and larger imported-LAZ export fixtures. |
| Multi-tile projects | A logical project is persisted before the first transfer. Every source keeps URL, local filename, and bounds; existing source-URL matches are reused; completed members update the manifest; paused or failed projects show ready-count and resume controls; complete projects reopen as one georeferenced mosaic. | Implemented; unit verified | Add cancellation/restart instrumentation and release-build device validation with a real multi-tile area. |
| Release validation | Debug unit tests and APK builds pass, and current workflows run on the emulator and Samsung tablet. | Partial | Add release build, instrumented suite, migration fixtures, and external GIS export checks to the release gate. |

## First completed increment

The first Sprint 1 increment implements exact-cell inspection from the production Terrain workspace.

Data-integrity behavior:

- Measurements come from the source `ElevationGrid`, not the rendered bitmap.
- The selected cell remains the same at any visual zoom.
- No-data cells are labeled and do not display terrain measurements.
- Geographic coordinates are shown only when the dataset has real geographic bounds.
- Local-grid data remains explicitly local.
- Inspection does not rerender terrain or reset the viewport.

Validation:

- Unit tests cover planar metrics and no-data handling.
- Full debug unit-test and APK build passes.
- Live emulator and Samsung tablet taps display the measurement panel over a real imported LAZ.

## Second completed increment

The next Sprint 1 increment implements persistent GPX/KML survey layers while leaving the skipped NYS/USGS area-selection work unchanged.

Data-integrity behavior:

- GPX and KML coordinates remain in their source WGS84 latitude/longitude values.
- Layers are keyed to the active terrain source and do not leak into another LAZ project.
- XML document types and entities are rejected before parsing.
- Unsupported or malformed files fail visibly instead of creating placeholder geometry.
- Local-grid terrain does not fabricate an overlay position; the same layer remains visible on Google Maps.
- Android providers that report GPX/KML as generic files remain selectable, with format validation performed by the parser.

Validation:

- Unit tests cover GPX tracks and waypoints, KML points, lines and polygons, coordinate order, and external-entity rejection.
- The full debug unit-test suite and APK build pass.
- A real GPX layer was imported on the emulator, remained after a process restart, and rendered its waypoint and track on Google Maps.
- The same APK installs and launches successfully on the Samsung tablet with the database migration applied.

## Third completed increment

The third Sprint 1 increment implements durable offline basemap regions without changing the skipped NYS/USGS LAZ area-selector scope.

Data-integrity behavior:

- Offline regions are keyed to the active terrain source.
- Download planning requires real geographic bounds; local-grid terrain is rejected rather than placed at fabricated coordinates.
- The preview reports zoom, exact tile count, tiles already present, and estimated new bytes.
- Tiles use the official USGS Topo cached-map service and live in app-private `filesDir`, outside Android's evictable cache.
- Cancellation and process interruption retain completed tiles and expose retry.
- Retry requests only missing tiles.
- Deleting a region removes only tiles not shared by another saved region and requires confirmation.
- Ready regions load with network access disabled.

Validation:

- Unit tests cover entity round-tripping, project scope fields, status/progress persistence, and bounded pre-download planning.
- The full debug unit-test suite and APK build pass.
- A live USGS region downloaded to `Ready offline` on the emulator with exact stored-byte reporting.
- With Wi-Fi and mobile data disabled and network access confirmed unreachable, the app restarted and reopened the saved basemap.
- The version-7 database migration, APK install, cold launch, and new manager UI were verified on the Samsung tablet.

## Fourth completed increment

The fourth Sprint 1 increment implements full-footprint image, comparison, and PDF field-report export.

Data-integrity behavior:

- Terrain export rebuilds the active visualization from the retained source-footprint grid rather than capturing the current zoomed screen.
- Saved targets and survey geometry are taken from the active terrain project only.
- Geographic coordinates are printed only from existing terrain and target georeferencing; local-grid projects remain labeled as local.
- PNG exports include the visualization, raster dimensions, resolution, source metadata, legend, targets, survey layers, and an interpretation notice.
- Comparison export uses the complete selected derived-layer bitmaps and fit-contain panes so neither layer is cropped, stretched, or overlaid on the other.
- PDF report schema 1 includes the annotated map, project metadata, saved-target records, survey provenance, and explicit data-integrity limitations.

Validation:

- Unit tests cover whole-raster annotation, PNG output, comparison export, and aspect-preserving comparison dimensions.
- The full debug unit-test suite and APK build pass.
- Android DocumentsUI successfully saved a terrain PNG, comparison PNG, and four-page PDF on the emulator.
- Both PNGs were visually inspected at original resolution.
- Poppler validated the PDF structure and extracted all four pages; every rendered page was visually inspected for clipping, overlap, and footer placement.

## Next implementation target

Make the public LiDAR area selector reachable from every terrain-import path:

1. Route compatible URL and document-import entry points into the same selector without losing their existing direct-file workflows.
2. Add instrumented area-selection, cancellation, resume, and mosaic-reopening coverage.
3. Validate a real multi-tile area through the release build on device.
4. Add migration, reopen, and interrupted-project fixtures.
