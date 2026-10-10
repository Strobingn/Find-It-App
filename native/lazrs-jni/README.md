# laz-rs Android JNI decoder

Optional native LAZ decoder. The app stays on **laszip4j** until this `.so` is present
and Tools → Decoder benchmark shows it winning on the same file.

## Build for Android (cargo-ndk)

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=/path/to/ndk
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../../app/src/main/jniLibs build --release
```

`System.loadLibrary("lazrs_jni")` then makes `LazRsNativeDecoder.isAvailable == true`.

The crate uses the `las` crate with the `laz` feature (laz-rs, including parallel
chunk decompression when the file has a chunk table). Output is a direct 16-byte
packed record buffer so Kotlin never allocates a per-return object.
