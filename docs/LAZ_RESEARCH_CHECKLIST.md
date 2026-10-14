# Android LAZ research implementation

This maps the seven implementation areas in the 2026-10-14 Exa research to the
active engine. Changes remain on `fix/laz-precision-performance-20261014`.

| Research area | Active implementation | Validation |
| --- | --- | --- |
| Equivalent benchmarks and stage traces | Reference/index preparation; alternating Java/native and 2/4/6-worker repetitions; strict DEM/count/mask/unit parity; median/range; first application read separated from warm reads; native ATrace and named workers | Owned LAZ parity fixtures and Android benchmark instrumentation. OS caches are uncontrolled. |
| Geographic terrain and derived caches | Anchored 256/512-node terrain tiles with 256-node context; bounded namespaces; candidate-size cold/revisit comparison; checksummed scalar fields keyed by actual DEM/canopy/masks/physical axes/analysis inputs | Cross-tile measured pit, reference interpolation comparison, cold reopen, CRC/identity/cancellation, common memory budget |
| GPU 2D hillshade and physical analysis | Active Android 13+ raw-data shader for hillshade, multidirectional hillshade, slope, aspect, elevation, openness and SVF; cached inputs and uniform-only light/sensitivity changes; settled CPU export snapshots and compatibility fallback | CPU/GPU pixel comparisons on API 35, rectangular physical cells and nodata; separate display encoding error budget |
| Bounded packaged native decoder | ABI 2 source integer XYZ/flags; seekable batches; all three Android ABIs; actual selected ranges; existing indexes reused; worker calibration constrained by cores, spare heap and thermal pressure | Rust tests, host/Android Java parity, native load/JNI export checks and APK archive verification |
| Height-texture 3D and reusable topology | R32F nearest/exact access; balanced screen-space adaptive quadtree; hierarchical geomorphing; coarse-edge stitching; physical X/Y/Z positions; conservative frustum culling; context-loss reupload | Shader compile/link/draw, nonempty pixel output, balanced non-overlapping levels and exact boundary vertex matches across transition scales |
| Spatial access where available | Local/remote COPC hierarchy selection, retaining intersecting ancestors/descendants for final analysis; chunk bounds and selected seeking for ordinary LAZ | COPC hierarchy/footprint tests and coherent/interleaved LAZ fixtures. Ordinary LAZ conversion is an optional source-preparation step using the research's PDAL COPC writer, outside the Android renderer. |
| Useful relief scales, openness and SVF | Independent X/Y metric local-relief/roughness windows, physical curvature, cached eight-direction openness/SVF, grayscale controls; measured returns and provenance retained | Physical quadratic-window test, flat sky factor, pit visibility, source immutability and derived-cache tests |

Tools → Decoder benchmark compares settings using the chosen file. **Apply measured
settings** persists the recommended backend, actual workers and tile size. Native
does not become the default merely because it is packaged. Tile-size scoring uses
one uncached build plus four revisits; the raw measurements are also shown.

Tools → Reset frame capture, perform a representative interaction, then Show frame
timings. The report gives rolling window p50/p95, available GPU timing, dropped
measurement callbacks, native heap and thermal status. GL CPU submission is logged
separately from frame duration. Severe heat caps later decodes at two workers;
critical heat caps them at one. Source point selection and terrain policy are unchanged.

The acceptance cases still requiring the owner's physical devices are sustained
S24 Ultra/Tab A9+ timing, power/heat and real-survey comparison. Emulator and fixture
results establish covered code invariants; they do not establish a universal speedup,
identical interpolation for every possible source footprint, or survey accuracy.

See [precision and validation details](LAZ_PRECISION_VALIDATION.md).
