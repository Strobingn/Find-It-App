# laz-rs Android JNI decoder

Optional native LAS/LAZ decoder. **laszip4j stays the default** until this `.so`
is present and Tools → Decoder benchmark shows laz-rs winning on the same file.

Native and parallel chunk decompression come from one library: `las` with
`laz-parallel` (laz-rs + rayon). The JNI entry writes a **direct** 16-byte
packed record buffer (`f32 x/y/z + u8 class/flags`) so Kotlin never allocates
a per-return object.

## Build for Android (cargo-ndk)

Requires rustc 1.83 (this crate pins `las 0.8.8` / `laz 0.9.2` / `uuid 1.11`
so it builds without edition2024).

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=/path/to/ndk
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../../app/src/main/jniLibs build --release
```

`System.loadLibrary("lazrs_jni")` then makes `LazRsNativeDecoder.isAvailable == true`.

## Decode path

1. If the file is LAZ and has a laszip VLR + chunk table, `ParLasZipDecompressor`
   inflates several chunks at once (more points than one chunk, which is what
   `decompress_many` requires before it will use extra threads) and packs xyz/class.
2. Otherwise (uncompressed LAS, or no chunk table) `las::Reader` walks every return.

The owner compares both backends on-device (Tools → Benchmark a LAS/LAZ file)
before toggling **Preferred decoder**.
