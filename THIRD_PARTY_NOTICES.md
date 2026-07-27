# Third-party notices

This file identifies third-party libraries distributed with Find It. The complete license texts are also packaged inside the Android application under `assets/open_source_licenses/`.

## laszip4j 0.21

Find It uses the unmodified `com.github.mreutegg:laszip4j:0.21` library to decode losslessly compressed LAS/LAZ point clouds on-device.

- Project and corresponding source: https://github.com/mreutegg/laszip4j
- Exact source tag used by the dependency: https://github.com/mreutegg/laszip4j/tree/laszip4j-0.21
- Artifact license metadata: GNU Lesser General Public License, version 2.1
- Bundled license text: `app/src/main/assets/open_source_licenses/LGPL-2.1.txt`

The library source has not been modified by Find It. Android build tooling compiles the dependency bytecode together with the application into DEX output; the notice does not characterize it as a separately installed shared library.

Find It's source and Gradle build files are available at https://github.com/Strobingn/Find-It-App. A recipient may replace the declared laszip4j dependency with a compatible modified build and rebuild the application. Nothing in Find It's distribution terms is intended to prohibit modification for the recipient's own use or reverse engineering required to debug such modifications.

## NGA TIFF 3.0.0

Find It uses the unmodified `mil.nga:tiff:3.0.0` library to decode TIFF and GeoTIFF elevation rasters on-device.

- Project and corresponding source: https://github.com/ngageoint/tiff-java
- License: MIT License
- Bundled copyright and license text: `app/src/main/assets/open_source_licenses/NGA_TIFF_MIT.txt`

Android build tooling packages the dependency bytecode into the application output. The required MIT copyright and permission notice is retained in the repository and in the application assets.

## Rebuilding with a modified laszip4j

1. Clone the Find It source repository and check out the source revision corresponding to the distributed APK.
2. Clone `mreutegg/laszip4j` at tag `laszip4j-0.21`, make the desired changes, and build a Maven artifact.
3. Publish the modified artifact to `mavenLocal()` or another local Maven repository.
4. Change the laszip4j version or dependency coordinate in `gradle/libs.versions.toml` to the modified artifact.
5. Build Find It with the included Gradle wrapper, for example `./gradlew assembleDebug`.

The Android SDK, Java runtime, Kotlin compiler, and Gradle dependencies are standard build-system components and can be obtained through Android Studio or the repositories configured by the project.
