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

Synthetic **4 000-point LAS** (format 0, 200 m extent), raster 128, AUTO_LOWEST. Numbers come from `TerrainPerformanceTest.decoderBenchmarkTimesLaszipAndCacheReopen` and the raster/mesh micro-benches:

| Stage | Host JVM |
| --- | --- |
| laszip4j decode + grid | see unit-test stdout (`Decoder benchmark`) |
| laz-rs native | **not loaded** (no `liblazrs_jni.so` in this environment) |
| GPU mesh + heightmap/CDLOD | see `mesh_build` / `cdlod_select` |
| Disk cache reopen | see `cache_reopen` — should be ≪ first decode |

Re-run on a device:

1. Tools → **Benchmark a LAS/LAZ file** (same file twice: first decode, then cache reopen).
2. Tools → **Toggle preferred decoder** after cargo-ndk installs `jniLibs`.
3. Compare `laszip4j` vs `laz-rs native` before changing the default.

## Building laz-rs

See `native/lazrs-jni/README.md`. Until the `.so` is present, `LazDecoderSettings.resolve()` always returns laszip4j even if the preference is native.
