package com.example.gps.ntrip

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

data class NtripConfig(
    val host: String,
    val port: Int,
    val mountpoint: String,
    val username: String,
    val password: String,
    val useTls: Boolean = false,
    /** Network RTK (VRS/MAC, e.g. NYSNet) needs the rover position as GGA. */
    val sendGga: Boolean = true,
) {
    val isComplete: Boolean get() = host.isNotBlank() && port in 1..65535 && mountpoint.isNotBlank() && username.isNotBlank()
}

data class NtripMountpoint(
    val name: String,
    val identifier: String,
    val format: String,
    val latitude: Double?,
    val longitude: Double?,
    /** STR field 11: 0 = no NMEA needed, 1 = client must send GGA. */
    val requiresGga: Boolean,
)

class NtripAuthException(message: String) : IOException(message)

/**
 * Minimal NTRIP 2.0 client with 1.0 fallback. Streams RTCM bytes from a caster mountpoint to
 * [stream]'s callback, which forwards them to the receiver. Pure JVM: reusable outside Android.
 */
class NtripClient(
    private val config: NtripConfig,
    private val userAgent: String = "NTRIP FindItGps/1.0",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private fun open(): Socket {
        val socket = if (config.useTls) SSLSocketFactory.getDefault().createSocket() else Socket()
        socket.connect(InetSocketAddress(config.host, config.port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs
        socket.tcpNoDelay = true
        return socket
    }

    private fun request(path: String, auth: Boolean): String = buildString {
        append("GET /").append(path).append(" HTTP/1.1\r\n")
        append("Host: ").append(config.host).append(':').append(config.port).append("\r\n")
        append("Ntrip-Version: Ntrip/2.0\r\n")
        append("User-Agent: ").append(userAgent).append("\r\n")
        if (auth) {
            val token = basicAuthBase64("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
            append("Authorization: Basic ").append(token).append("\r\n")
        }
        append("Connection: close\r\n\r\n")
    }

    private class Response(val status: String, val headers: Map<String, String>, val body: InputStream)

    private fun readResponse(input: BufferedInputStream): Response {
        val status = readLine(input) ?: throw IOException("Caster closed the connection")
        val headers = HashMap<String, String>()
        // NTRIP 1.0 "ICY 200 OK" streams data right after the status line (optionally a blank line).
        if (!status.startsWith("ICY")) {
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        return Response(status, headers, if (chunked) ChunkedInputStream(input) else input)
    }

    /** Lists mountpoints (STR records). Many casters allow this without credentials. */
    fun fetchSourceTable(): List<NtripMountpoint> = open().use { socket ->
        socket.getOutputStream().apply { write(request("", auth = config.username.isNotBlank()).toByteArray(Charsets.US_ASCII)); flush() }
        val response = readResponse(BufferedInputStream(socket.getInputStream()))
        if (" 401" in response.status) throw NtripAuthException("Caster rejected the NTRIP login")
        val text = response.body.readBytes().toString(Charsets.ISO_8859_1)
        parseSourceTable(text)
    }

    /**
     * Blocks while streaming. [gga] supplies the current rover GGA (null = none yet); it is sent
     * on connect and every [ggaIntervalMs]. Returns when [shouldContinue] turns false; throws on
     * network/auth errors so the caller can back off and reconnect.
     */
    fun stream(
        gga: () -> String?,
        onRtcm: (bytes: ByteArray, count: Int) -> Unit,
        shouldContinue: () -> Boolean,
        ggaIntervalMs: Long = 10_000L,
    ) {
        open().use { socket ->
            val out: OutputStream = socket.getOutputStream()
            out.write(request(config.mountpoint, auth = true).toByteArray(Charsets.US_ASCII))
            out.flush()
            val response = readResponse(BufferedInputStream(socket.getInputStream(), 16 * 1024))
            when {
                " 401" in response.status -> throw NtripAuthException("NTRIP login rejected — check user name and password")
                "SOURCETABLE" in response.status || response.headers["content-type"]?.contains("gnss/sourcetable") == true ->
                    throw IOException("Mountpoint '${config.mountpoint}' not found on ${config.host}")
                !(response.status.contains(" 200")) -> throw IOException("Caster replied: ${response.status}")
            }
            var lastGga = 0L
            val buffer = ByteArray(4096)
            socket.soTimeout = 1_000
            while (shouldContinue()) {
                val now = System.currentTimeMillis()
                if (config.sendGga && now - lastGga >= ggaIntervalMs) {
                    gga()?.let { out.write("$it\r\n".toByteArray(Charsets.US_ASCII)); out.flush(); lastGga = now }
                }
                val count = try {
                    response.body.read(buffer)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                if (count < 0) throw IOException("Caster closed the correction stream")
                if (count > 0) onRtcm(buffer, count)
            }
        }
    }

    companion object {
        internal fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 8_192) throw IOException("Header line too long")
                sb.append(c.toChar())
            }
        }

        fun parseSourceTable(text: String): List<NtripMountpoint> = text.lineSequence()
            .filter { it.startsWith("STR;") }
            .mapNotNull { line ->
                val f = line.split(';')
                if (f.size < 12) return@mapNotNull null
                NtripMountpoint(
                    name = f[1], identifier = f[2], format = f[3],
                    latitude = f[9].toDoubleOrNull(), longitude = f[10].toDoubleOrNull(),
                    requiresGga = f[11].trim() == "1",
                )
            }.toList()
    }
}

/** HTTP/1.1 chunked transfer decoding (NTRIP 2.0 casters). */
internal class ChunkedInputStream(private val source: InputStream) : InputStream() {
    private var remaining = 0
    private var finished = false

    private fun nextChunk(): Boolean {
        if (finished) return false
        val line = NtripClient.readLine(source) ?: return false.also { finished = true }
        val size = line.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("Bad chunk header: $line")
        if (size == 0) { finished = true; return false }
        remaining = size
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining == 0) {
            if (finished || !nextChunk()) return -1
        }
        val n = source.read(b, off, minOf(len, remaining))
        if (n < 0) return -1
        remaining -= n
        if (remaining == 0) NtripClient.readLine(source) // CRLF after each chunk
        return n
    }
}


/** RFC 4648 Base64 (min SDK 24 lacks java.util.Base64; android.util.Base64 is unavailable on the JVM). */
internal fun basicAuthBase64(bytes: ByteArray): String {
    val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        sb.append(a[b0 ushr 2]).append(a[((b0 and 3) shl 4) or (b1 ushr 4)])
        sb.append(if (i + 1 < bytes.size) a[((b1 and 15) shl 2) or (b2 ushr 6)] else '=')
        sb.append(if (i + 2 < bytes.size) a[b2 and 63] else '=')
        i += 3
    }
    return sb.toString()
}
