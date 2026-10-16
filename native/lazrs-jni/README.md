# laz-rs Android JNI decoder (streaming)

Default LAZ terrain decoder. Prebuilt `liblazrs_jni.so` for **arm64-v8a** and **x86_64** is
committed in `app/src/main/jniLibs`, so builds need no Rust toolchain. Other ABIs (or any native
error) fall back to laszip4j automatically; Tools → "Toggle preferred decoder" switches back.

## How it decodes

* Kotlin (`LazTerrainReader.readParallelChunks`) picks the chunks: all of them for a full pass, or
  the `LazChunkIndex` selection for a viewport refine.
* `nativeStart` spawns one rayon worker per core. Each worker owns a `LasZipDecompressor`
  (selective: xyz, classification, flags), seeks chunk by chunk through the chunk table,
  decompresses into a reusable scratch buffer and applies the laszip4j filter
  (half-open rect `min <= v < max`, then global point stride).
* Kept returns are packed (24 B: `f64 x, f64 y, f32 z, u8 class, u8 flags, u16 pad`) into pooled
  buffers. Kotlin pulls them in file order as direct ByteBuffers (`nativeNext`), bins them with
  `PackedPointRasterizer.addAll`, and releases the slot. At most `2 × workers` chunks are in
  flight, so memory is independent of file size.
* Cancellation: Kotlin polls `shouldContinue` while waiting and calls `nativeCancel`; workers
  stop at the next chunk. Every JNI entry catches panics.

Output is bit-identical to laszip4j (`LazRsNativeParityTest`, autzen + NY 1.4 format 6/7).
Variable-size-chunk files (COPC) are rejected by `nativeOpen` and use laszip4j.

## Rebuild

Pinned for rustc 1.85 (`las 0.8.8` / `laz 0.9.2` / `uuid 1.11`).

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 3.5.4 --locked
export ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018
cargo ndk -t arm64-v8a -t x86_64 -p 24 -o ../../app/src/main/jniLibs build --release
# host build for the JVM parity test / benchmark
cargo build --release
```

`.cargo/config.toml` links with 16 KB page alignment.

Benchmark: `FINDIT_DECODER_BENCH=NATIVE_LAZ_RS ./gradlew :app:testDebugUnitTest --rerun --tests '*LazDecoderBenchTest*' -i`
(and `LASZIP4J`).
