# Android terrain performance

Separate follow-up to the continuous-hillshade work. Full DEM detail is never thinned.

## Decisions

| Suggestion | Decision |
| --- | --- |
| Profile decode / grid / mesh separately | **Done.** `TerrainStageProfiler` logs `FindItTerrainPerf` and Tools → Show stage timings. |
| No per-point objects; grid while decoding | **Done on the hot path.** laszip4j low-level + parallel chunks already avoid `LASPoint`. New packed 16-byte sink is what laz-rs writes into. |
| Parallel per-chunk LAZ | **Already on main** (`readParallelChunks`). Now timed as `decode_parallel`. |
| FileChannel / mmap | **Done for uncompressed LAS** (`DemGenerator.parseLasFileMapped`). LAZ still needs a seekable decoder (chunk table). |
| Native laz-rs via JNI | **Optional, behind a flag.** Crate in `native/lazrs-jni` (las + laz-rs). Default remains laszip4j until Tools → Decoder benchmark shows native winning on the owner's phone. |
| OpenGL ES 3.0 min | **Done.** Manifest `glEsVersion=0x00030000`, `GLSurfaceView` context 3. 32-bit indices unlock tiles &gt; 254 cells. ES 3.1 compute is used when the GL version string has 3.1+; otherwise CPU Horn. |
| Heightmap + CDLOD | **Done on the CPU side.** Full-res R32F pack + CDLOD selection (level 0 = one vertex per cell near the camera). First paint still draws the full-detail mesh; the heightmap is attached for VTF / future VS displacement. |
| GPU hillshade / SVF / LRM | Horn compute GLSL 3.10 shipped; CPU fallback is the default and is tested. SVF/LRM stay on the existing CPU analysis path until compute is proven on-device. |
| Disk DEM cache | **Already on main** (memory + async disk). Benchmark now times write vs reopen. |
| Native / GPU memory | `TerrainMemoryMonitor` (Java heap, `Debug` native heap, estimated GPU bytes). |

## Host benchmark (this agent, x86_64 JVM)

Synthetic **4 000-point LAS** (format 0, 200 m extent), raster 128, AUTO_LOWEST, from
`TerrainPerformanceTest.decoderBenchmarkTimesLaszipAndCacheReopen`.
Host JVM numbers vary with class-load and machine load; two runs:

| Stage | Warm JVM | After full recompile |
| --- | --- | --- |
| laszip4j decode + grid | **6.95 ms** | **67.60 ms** |
| laz-rs native | **not loaded** (no `liblazrs_jni.so` here) | same |
| GPU mesh + heightmap | **29.53 ms** | **15.18 ms** |
| CDLOD select | **0.89 ms** | **0.58 ms** |
| Disk cache write | **14.26 ms** | **24.45 ms** |
| Disk cache reopen | **4.96 ms** | **6.75 ms** |
| Estimated GPU buffers | **181 KiB** (128², 16 384 cells) | same |

Hot path from `TerrainPerformanceTest.rasterizerHotPathAndFullResMeshHaveHostNumbers`
(200k `addPoint` into a 256² raster, then full-res mesh + heightmap):

| Stage | Warm JVM | After full recompile |
| --- | --- | --- |
| bin 200k points | **8.19 ms** (24 413 kpts/s) | **27.66 ms** (7 232 kpts/s) |
| mesh + heightmap 256² | **36.90 ms** | **37.61 ms** |

These are host-JVM numbers, not a phone. Re-run Tools → Decoder benchmark on the device.

Re-run on a device:

1. Tools → **Benchmark a LAS/LAZ file** (same file twice: first decode, then cache reopen).
2. Tools → **Toggle preferred decoder** after cargo-ndk installs `jniLibs`.
3. Compare `laszip4j` vs `laz-rs native` before changing the default.

## Building laz-rs

See `native/lazrs-jni/README.md`. Until the `.so` is present, `LazDecoderSettings.resolve()` always returns laszip4j even if the preference is native.
