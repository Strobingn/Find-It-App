package com.example.gps.ntrip

/**
 * Caster presets. Credentials are never bundled: users register for free and enter their own.
 */
data class NtripPreset(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val defaultMountpoint: String,
    val requiresGga: Boolean,
    val registrationUrl: String,
    val note: String,
    /** Datum of corrected positions: NYSNet broadcasts NAD83(2011) reference coordinates. */
    val correctedFrameIsNad83: Boolean,
)

object NtripPresets {
    val NYSNET = NtripPreset(
        id = "nysnet",
        label = "NYSNet (NYS Spatial Reference Network)",
        host = "rtn.dot.ny.gov",
        port = 8080,
        defaultMountpoint = "",
        requiresGga = true,
        registrationUrl = "https://cors.dot.ny.gov/",
        note = "Free account at cors.dot.ny.gov. Confirm the port and pick a mountpoint (RTCM 3 network " +
            "or nearest-site) from your NYSNet activation email, or tap 'Fetch mountpoints'.",
        correctedFrameIsNad83 = true,
    )

    val CUSTOM = NtripPreset(
        id = "custom",
        label = "Custom caster",
        host = "",
        port = 2101,
        defaultMountpoint = "",
        requiresGga = true,
        registrationUrl = "",
        note = "Enter your caster, port, mountpoint and login.",
        correctedFrameIsNad83 = false,
    )

    val ALL = listOf(NYSNET, CUSTOM)

    fun byId(id: String?): NtripPreset = ALL.firstOrNull { it.id == id } ?: NYSNET
}
