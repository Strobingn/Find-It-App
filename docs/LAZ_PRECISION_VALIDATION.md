# LAZ engine accuracy and Android memory audit

The changes are isolated on `fix/laz-precision-performance-20261014`.

## Correctness changes

- Withheld points and ASPRS noise classes 7/18 do not contribute to elevation,
  canopy, coverage, or density. Flags survive sequential, parallel, COPC and
  optional packed native decode paths.
- Class 8 remains a legacy model key-point in formats 0–5, including LAS 1.4
  legacy payloads (ASPRS LAS 1.4 R15, point-classification tables). In formats 6–10
  it is reserved. A key-point flag does not turn a
  building or tree into ground. Unsmoothed vendor-classified ground is retained;
  the automatic low-return noise heuristic no longer modifies classified pits.
- Raw LAS reads exactly its declared number of records. Trailing EVLR/waveform
  bytes cannot become points, and a short payload is rejected. Cancellation does
  not return a prefix as a completed terrain. Stream spooling checks cancellation
  and deletes partial temporary files.
- XY and Z units are independent. Supported WKT/GeoKey linear units are converted
  before gridding. Horizontal units can be extracted even when the map projection
  is unsupported. A horizontal foot unit does not imply a vertical foot unit.
- Horn derivatives divide X and Y differences by their respective physical
  spacing. Curvature uses separate squared axis spacings. Aspect is the **downslope**
  bearing clockwise from north, with raster rows increasing south.
- The current main-branch ground-mean, nearest-node and harmonic-void-fill changes
  are retained. Classified-cell sums use doubles before producing float raster values.
  Harmonic relaxation weights each axis by inverse physical spacing squared; measured
  cells remain fixed. Interpolated cells retain their provenance.
- Round hull closing uses an exact weighted Euclidean distance transform. It respects
  physical axis spacing and takes linear time in grid size, replacing radius-dependent
  repeated whole-grid dilation passes. It does not add tile seams or promote interpolated
  cells into source measurements.
- Metadata-bearing raw LAS streams are spooled once so WKT/GeoKey VLRs and trailing
  EVLRs supply the same independent unit factors as file-backed LAS. Plain streams
  without metadata retain the metre assumption. Cancellation cleans up the spool.
- Persistent caches store both axis spacings and the measured/interpolated mask.
  Old algorithm entries are invalidated. All permitted 2048x2048 refine grids fit
  the cache reader's dimension limit.
- Focused decode retains exact maximum-boundary returns: laszip4j's exclusive
  rectangle bounds are expanded by one representable double, while the rasterizer
  applies the original inclusive crop. Full focused crops equal full-footprint grids.
- Projected coordinate bounds are checked before converting to display percentages,
  preventing positions just beyond the source extent from landing in the last pixel.
  LAS header inspection keeps file lengths as `Long` for files larger than 2 GB.

## Memory/performance changes

- Ordered chunk binner uses a ring of `2 * workers` futures rather than a future
  for every source chunk. With 50,000-point chunks, packed Java worker payload is
  approximately `22 * 50,000 * 2 * workers` bytes (17.6 MB at eight workers), plus
  readers, raster and interpolation memory. Ordering preserves sequential results.
- Header extent queries do not initialize a compressed reader. Full imports record
  conservative per-chunk XY bounds and eligible-return density in the same ordered
  binner, persisting a checksummed `.fichunks` sidecar. Focused reads seek only to
  overlapping chunks, in file order, with inclusive edges and no point thinning.
- Focus uses the persisted density histogram (or session density / requested size)
  to choose resolution once. It no longer decodes again to correct the resolution.
  Explicit class filters still use chunk pruning; their density uses the existing
  session estimate or requested size. Native whole-cloud decoding is bypassed for crops.
- Sidecars validate source size/mtime, declared chunk geometry, exact length and CRC,
  with bounded allocations and four in-memory entries. Cancelled builds do not publish
  partial indexes. Read-only source folders use an app cache fallback. Legacy cached
  imports need one lightweight XY/classification pass; duplicate builds are coalesced.
  Existing vendor `.lax` remains a compatibility fallback, without a blocking LAX build.
- One-worker devices still seek to selected chunks. Worker count also respects chunk
  buffer size and device headroom.
- Native ABI 2 uses bounded seekable sessions: integer XYZ and classification flags
  stream through a direct buffer in batches up to 200,000 records and 16 MiB of
  scratch data. Adjacent selected chunks share a batch; cancellation is checked
  between batches. The Actions build packages arm64-v8a, armeabi-v7a and x86_64.
- Refinement reuses canonical 512-node elevation tiles on an anchored source lattice,
  with 256-node interpolation context. Memory and disk budgets are 24/256 MiB.
  A bounded Java decoded-chunk cache reuses overlapping compressed ranges.
  Tile context makes revisiting the same lattice stable. Harmonic interpolation
  remains an estimate and may differ from a full-footprint solve near large gaps.
- Android 13+ hardware Canvas shades supported 2D views from a cached raw height
  bitmap. Lighting and contrast update shader uniforms; CPU snapshots are deferred
  until controls settle. Older Android and unsupported overlays retain CPU rendering.
  Display heights use relative 24-bit encoding; authoritative float elevations remain
  unchanged. Positive/negative openness use eight physical-direction profiles.
- The active GLES 3 renderer uses an R32F height texture and one reusable 65x65
  topology, with conservative frustum culling and a shared power-of-two mesh spacing.
  This avoids per-cell CPU mesh construction; it is not adaptive CDLOD geomorphing.
- Local COPC refinement traverses hierarchy metadata and seeks intersecting node
  ranges using the LAS header footprint. Ordinary LAZ is not converted to COPC.

## Reproducible checks

```bash
./gradlew testDebugUnitTest --no-configuration-cache
./gradlew assembleRelease --no-configuration-cache
```

`LazPrecisionRegressionTest` verifies filtering in all surface modes, legacy and
extended raw LAS flags, classified depression preservation, trailing metadata,
short records, packed-buffer bounds, 2048x2048 cache provenance/axis round trips,
and bit-identical sequential/parallel output across ring wraps.

`TerrainMathPrecisionTest` checks compass aspect, mixed XY/Z units, vertical WKT,
affine-plane measured-sample preservation, physical-axis harmonic weights, an exact
Euclidean dilation comparison against brute force, and spacing through LOD. `ElevationGridTest` checks matching hillshade pixels for
an identical physical slope sampled with different Y spacing.

`LazViewportRefinementTest` verifies a coherent-strip crop decodes exactly 50,000
of 250,123 source records (one of six chunks), once, with grids and masks identical
to a full scan at the same resolution across surface modes and explicit class filters.
It also checks cold sidecar reuse, corruption/staleness rejection, cancellation,
inclusive boundaries and the short final chunk. The interleaved fixture below covers
the entire footprint in every chunk; it intentionally cannot be spatially pruned.

The owned interleaved LAZ fixture contains 450,123 points, nine complete 50,000-point chunks
and one short chunk. XY has large projected origins, with withheld/noise returns
at chunk boundaries. It is independently compressed with lazrs, read back with
laspy, and decoded with laszip4j in tests. Regenerate it with:

```bash
python -m pip install laspy==2.7.0 lazrs==0.8.2 numpy
python scripts/generate_laz_precision_fixture.py
```

The branch's push workflow runs the full test suite before producing a signed
release APK artifact and SHA256SUMS. Signing follows the same optional release
keystore/fallback policy as main. Main alone publishes stable GitHub releases.

## Precision limits and field validation

A DEM is a rasterized estimate, not the original point cloud or a survey-grade
measurement. Cell minima are associated with their bins; feature localization
remains limited by source point spacing, grid spacing, classification quality and
LAS coordinate quantization. Classified bins use the current main-branch mean;
individual returns sharing a bin cannot be localized independently. Interpolated cells are explicitly marked. No vertical
or horizontal datum transformation is introduced by converting linear units.

Missing linear-unit metadata retains the prior metre assumption; unknown vertical
units cannot be determined from horizontal units alone. Unsupported CRS projections
still cannot be placed accurately on a geographic map. Native ABI 2 preserves raw integer XYZ; the common Java binner applies the same
scale, offset and unit algebra. Regression fixtures require identical Java/native
DEM values, coverage and interpolation provenance. GPU pixels have a separate
small rounding tolerance and are checked on the Android emulator.

No phone timing or device-specific peak-memory improvement is asserted from unit
checks. On a target Android device, compare full import, reopen and focused refine
on the same file/options using the existing decoder benchmark and
`FindItTerrainPerf` logs. Test a large real survey with gaps, a small cellar/pit,
non-metre metadata, and a deep focused crop; record device, source point count,
resolution, decode/grid/render durations and memory. Unit goldens establish the
covered invariants, not the accuracy of every vendor dataset.

## Android pipeline validation

`TerrainPipelineRegressionTest` checks Java/native parity, tile reuse across memory
and disk reopen, cancelled/corrupt source handling, display encoding bounds, shared
heightfield preparation, and physical-direction openness on planes and pits.
`TerrainGpuInstrumentedTest` validates packaged native decode, AGSL/CPU pixel parity
and R32F GLES shader compilation/drawing on API 35. Actions gates the branch APK on
these checks. Emulator checks do not establish real-phone frame times or peak memory.
