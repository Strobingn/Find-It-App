# Find-It-App Development TODO

This roadmap tracks planned AI-assisted metal-detecting features and advanced LiDAR terrain-analysis tools.

## Priority legend

- **P0** — Core platform work required by several later features
- **P1** — High-value field feature
- **P2** — Advanced analysis or research feature
- **P3** — Experimental or long-term feature

---

## 🤖 AI Features

### AI platform foundation

- [ ] **P0 — Build a reusable terrain-feature extraction pipeline**
  - Produce normalized rasters for elevation, slope, curvature, openness, relief, drainage, and texture.
  - Support tiled processing for large GeoTIFF, LAS, and LAZ datasets.
  - Cache derived layers for offline use.

- [ ] **P0 — Create an AI inference framework**
  - Define a common model interface for on-device and cloud inference.
  - Add model-version tracking, confidence thresholds, and explainable result metadata.
  - Store detections with coordinates, bounding regions, source layer, confidence, and model version.

- [ ] **P0 — Add training-data annotation tools**
  - Allow users to draw points, lines, and polygons over terrain.
  - Label confirmed foundations, walls, roads, pits, mines, camps, and false positives.
  - Export labeled examples for future model training.

### Detection and recommendation features

- [ ] **P1 — AI dig recommendation**
  - Return **Dig / Maybe / Skip** with a confidence score.
  - Combine target signal, terrain context, prior finds, depth, soil conditions, and user feedback.
  - Show the reasons behind each recommendation.

- [ ] **P1 — AI old homesite detector using LiDAR patterns**
  - Identify likely historic occupation areas from foundations, terraces, roads, wells, walls, and terrain flattening.
  - Display probability zones and supporting terrain indicators.

- [ ] **P1 — AI cellar-hole finder**
  - Detect rectangular or square depressions with berms, spoil piles, access paths, and nearby walls.
  - Rank candidates by shape quality, dimensions, and surrounding context.

- [ ] **P1 — AI stone-wall recognition**
  - Detect narrow linear raised features across hillshade, openness, slope, and local-relief layers.
  - Convert detections into editable map polylines.

- [ ] **P1 — AI foundation detection**
  - Detect rectangular platforms, wall outlines, corners, and compact elevation discontinuities.
  - Support confidence overlays and candidate review.

- [ ] **P1 — AI road and trail recognition**
  - Detect sunken roads, cart paths, logging roads, terraces, and historic trails.
  - Distinguish modern roads from abandoned linear features where possible.

- [ ] **P1 — AI artifact hotspot prediction**
  - Generate probability heat maps from detected structures, paths, water access, terrain, and previous finds.
  - Explain why each hotspot was ranked highly.

- [ ] **P2 — AI charcoal-pit recognition**
  - Detect circular or oval leveled platforms on slopes.
  - Identify associated access paths and repeated pit clusters.

- [ ] **P2 — AI military-camp probability**
  - Score terrain for camp suitability using flat ground, drainage, water access, historic roads, defensibility, and known find clusters.
  - Clearly label results as probability estimates, not confirmed sites.

- [ ] **P2 — AI mine and quarry detection**
  - Detect excavation faces, spoil piles, shafts, pits, haul roads, and quarry benches.
  - Separate natural rock features from likely human excavation.

---

## 🛰️ LiDAR Analysis

### Core visualization layers

- [ ] **P1 — Multi-layer hillshade comparison**
  - Display multiple sun azimuths and altitudes side by side.
  - Add swipe comparison, opacity blending, and synchronized zoom/pan.
  - Support composite multidirectional hillshade.

- [ ] **P1 — Sky-View Factor**
  - Generate a sky-view raster with adjustable search radius and direction count.
  - Provide presets for walls, foundations, pits, and broad terrain.

- [ ] **P1 — Local Relief Model (LRM)**
  - Remove broad terrain trends to expose subtle human-made features.
  - Include adjustable smoothing radius and residual scaling.

- [ ] **P1 — Positive and Negative Openness**
  - Generate separate positive- and negative-openness layers.
  - Add combined visualization modes for raised and depressed features.

- [ ] **P1 — Slope**
  - Compute slope in degrees and percent grade.
  - Add threshold highlighting and selectable palettes.

- [ ] **P1 — Curvature**
  - Support profile, plan, mean, and general curvature.
  - Include adjustable smoothing to reduce noisy LiDAR artifacts.

- [ ] **P1 — Aspect**
  - Display terrain orientation using a circular palette.
  - Support aspect filtering for slope-dependent feature searches.

- [ ] **P1 — Ruggedness Index**
  - Compute TRI or equivalent neighborhood roughness.
  - Use it to separate leveled human features from naturally rough terrain.

### Hydrology and terrain reconstruction

- [ ] **P2 — Flow accumulation**
  - Fill or breach sinks as an optional preprocessing step.
  - Calculate flow direction and accumulation.
  - Highlight likely drainage channels.

- [ ] **P2 — Watershed analysis**
  - Allow a user-selected outlet point.
  - Delineate catchments and sub-catchments.
  - Export watershed polygons and drainage lines.

- [ ] **P1 — Depression finder**
  - Detect closed depressions and rank them by depth, area, shape, and edge geometry.
  - Add filters for likely cellar holes, pits, ponds, sinkholes, and modern disturbance.

- [ ] **P2 — Ancient-stream reconstruction**
  - Estimate former drainage routes from elevation, terraces, abandoned channels, and valley morphology.
  - Compare reconstructed channels with current streams and historic maps.

- [ ] **P3 — Erosion simulation**
  - Estimate runoff paths and erosion-prone areas using slope, flow accumulation, soil assumptions, and rainfall scenarios.
  - Support before/after terrain comparison when multiple datasets exist.

---

## Suggested implementation order

### Phase A — Analysis foundation

- [ ] Terrain-feature extraction pipeline
- [ ] Derived-layer cache
- [ ] Slope
- [ ] Aspect
- [ ] Curvature
- [ ] Local Relief Model
- [ ] Multi-layer hillshade comparison

### Phase B — Archaeological feature detection

- [ ] Positive and Negative Openness
- [ ] Sky-View Factor
- [ ] Depression finder
- [ ] Stone-wall recognition
- [ ] Foundation detection
- [ ] Cellar-hole finder
- [ ] Road and trail recognition

### Phase C — Predictive field tools

- [ ] Old homesite detector
- [ ] Artifact hotspot prediction
- [ ] AI dig recommendation
- [ ] Annotation and user-feedback loop

### Phase D — Advanced research tools

- [ ] Ruggedness Index
- [ ] Flow accumulation
- [ ] Watershed analysis
- [ ] Charcoal-pit recognition
- [ ] Mine and quarry detection
- [ ] Military-camp probability
- [ ] Ancient-stream reconstruction
- [ ] Erosion simulation

---

## Completion requirements for every feature

- [ ] Works with real imported terrain data; no mock-only implementation.
- [ ] Handles large files without blocking the Android main thread.
- [ ] Supports offline use where technically possible.
- [ ] Includes loading, progress, empty, error, and cancellation states.
- [ ] Persists settings and generated layers.
- [ ] Includes unit tests for calculations and regression tests for known terrain samples.
- [ ] Documents assumptions, limitations, units, and confidence meanings.
- [ ] Exports results using appropriate formats such as GeoTIFF, GeoJSON, KML, GPX, CSV, or PDF.
