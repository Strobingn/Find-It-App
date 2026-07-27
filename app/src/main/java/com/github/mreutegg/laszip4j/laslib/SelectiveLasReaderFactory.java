package com.github.mreutegg.laszip4j.laslib;

/**
 * Package bridge for laszip4j's package-private selective-decompression configuration.
 *
 * The terrain pipeline only needs XY, Z, and optionally classification. Avoiding GPS time,
 * RGB/NIR, waveform, intensity, scan-angle, source-id, and extra-byte decompression reduces
 * CPU work without dropping any terrain return or changing elevation precision.
 */
public final class SelectiveLasReaderFactory {
    private SelectiveLasReaderFactory() {
    }

    public static LASreader open(String fileName, int selectiveMask) {
        LASreadOpener opener = new LASreadOpener();
        opener.set_decompress_selective(selectiveMask);
        return opener.open(fileName);
    }
}
