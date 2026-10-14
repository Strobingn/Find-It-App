# LAZ engine accuracy and Android memory audit

The changes are isolated on `fix/laz-precision-performance-20261014`.

## Correctness changes

- Withheld points and ASPRS noise classes 7/18 do not contribute to elevation,
  canopy, coverage, or density. Flags survive sequential, parallel, COPC and
  optional packed native decode paths.
- Class 8 is ground only for pre-1.4 legacy LAS. A key-point flag does not turn a
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
- TIN vertices come exclusively from measured cells. Filled cells from earlier
  tiles never become TIN/IDW source measurements for later tiles. Triangles and
  inverse-distance weights use physical X/Y distances. IDW commits a whole pass
  after sampling a fixed mask; it does not propagate along traversal order.
- Persistent caches store both axis spacings and the measured/interpolated mask.
  Old algorithm entries are invalidated. All permitted 2048x2048 refine grids fit
  the cache reader's dimension limit.
- Projected coordinate bounds are checked before converting to display percentages,
  preventing positions just beyond the source extent from landing in the last pixel.
  LAS header inspection keeps file lengths as `Long` for files larger than 2 GB.

## Memory/performance changes

- Ordered chunk binner uses a ring of `2 * workers` futures rather than a future
  for every source chunk. With 50,000-point chunks, packed Java worker payload is
  approximately `22 * 50,000 * 2 * workers` bytes (17.6 MB at eight workers), plus
  readers, raster and interpolation memory. Ordering preserves sequential results.
- TIN vertex collection stops after the triangulation cap is exceeded, avoiding
  thousands of unused vertex objects per dense tile. IDW updates use growable
  primitive arrays rather than boxed collections.
- Header extent queries do not initialize a compressed reader. Duplicate background
  spatial-index requests are coalesced. Incomplete or unsupported >32-bit point-index
  builds are rejected rather than publishing a misleading `.lax`.
- Optional native whole-cloud packed allocation is limited to 64 MiB or half the
  reserved headroom, whichever is smaller. Larger files use the streaming Java
  path. Packed buffer arithmetic is checked for integer overflow. Native cancellation
  callbacks are serialized so concurrent callers cannot overwrite one another.

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
affine plane reproduction across TIN tile seams, physical-distance IDW weights,
and spacing through LOD. `ElevationGridTest` checks matching hillshade pixels for
an identical physical slope sampled with different Y spacing.

The owned LAZ fixture contains 450,123 points, nine complete 50,000-point chunks
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
LAS coordinate quantization. Interpolated cells are explicitly marked. No vertical
or horizontal datum transformation is introduced by converting linear units.

Missing linear-unit metadata retains the prior metre assumption; unknown vertical
units cannot be determined from horizontal units alone. Unsupported CRS projections
still cannot be placed accurately on a geographic map. The optional native ABI
uses origin-relative float coordinates and is not proven bit-identical to the
Java double-XY path. Native-library packaging is unchanged.

No phone timing or device-specific peak-memory improvement is asserted from unit
checks. On a target Android device, compare full import, reopen and focused refine
on the same file/options using the existing decoder benchmark and
`FindItTerrainPerf` logs. Test a large real survey with gaps, a small cellar/pit,
non-metre metadata, and a deep focused crop; record device, source point count,
resolution, decode/grid/render durations and memory. Unit goldens establish the
covered invariants, not the accuracy of every vendor dataset.
