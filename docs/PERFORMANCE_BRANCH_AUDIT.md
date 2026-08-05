# Performance branch audit (main @ aa14acd)

**Date:** 2026-08-04

## Branch inventory

| Branch | Relation to main | Notes |
| ------ | ---------------- | ----- |
| `GROKV5` | Fully merged into main | Feature/UI work only (Phase 5/6, LiDAR library, Gemini key) — **no unique perf commits** |
| `KIMIV5` (local/remote) | Behind or equal main | Same Phase 9 tip as older GROKV4.8; **nothing to cherry-pick for speed** |
| `origin/agent/faster-laz-decode` | **22 commits ahead** | Best source of LAZ decode/raster hot-path ideas |
| `origin/fix/performance-license-compliance` | **12 commits ahead** | Selective decode, cache off critical path, license packaging |
| `origin/fix/seekable-laz-runtime-decode` | **2 commits ahead** | Seekable laszip bridge (mostly already on main) |

## Already on main (from prior merges)

- Selective LAZ field decompression + `LidarPointWork` skip/coverage/elevation gate
- Rolling sample/coverage countdowns (no per-return modulo)
- Parallel hillshade with generation cancellation
- Memory-first + async disk terrain cache
- Spatial index for refine seeks
- Full-detail first paint (1,024+) quality floor — **no 256/512 progressive stubs**

## Deliberately **not** cherry-picked as-is

- **Chunk-seek preview at max 512 px** (`readPreview` / `PREVIEW_MAX_RESOLUTION = 512`)  
  Violates product rule: GPU/analysis products stay ≥ 1,024 on every path.

## Applied on `feature/performance-hotpath`

Adapted from `agent/faster-laz-decode` + main-safe UX:

1. **Rasterizer hot path** — precomputed `xToGrid`/`yToGrid`; skip classification histogram when ground mode is not `SOURCE_CLASSIFIED`; insertion-sort median in low-noise pass  
2. **Earlier first paint** — `onPreview` hands elevation grid (and a fast-tile GPU mesh) to Import/library openers before the final full-tile GPU mesh finishes  
3. **Tighter hillshade debounce** — 48 ms light / 120 ms heavy (was 80 / 180) while generation still drops superseded frames  

## Follow-ups completed on GROKV5

- **Full-res sparse chunk-seek preview** (`LazTerrainReader.readSparsePreview`): keeps requested
  raster resolution (≥ 1,024), samples ~1.5M points across compressed chunks for large tiles
  (≥ 3M points), then background exact `read` upgrades the product via `exactOutcome`.
- **No double GPU on cache hits**: one `buildGpuScene` only; intermediate fast-tile mesh only on
  cold decode / sparse-preview paths with `onPreview`.
- Callers await `exactOutcome` to swap hillshade/GPU when exact finishes.

## Remaining optional follow-ups

- `putMemory` / `putDisk` explicit split if disk write ever blocks the open path  
- Integration tests with multi-million-point LAZ fixtures for chunk distribution  
