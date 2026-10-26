package com.example.data.relief

import android.content.Context
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Serves [LidarTerrainTiles] to the Mapbox map over plain HTTP on 127.0.0.1, so a raster-dem
 * source can use USGS lidar elevation everywhere the camera looks. Mapbox terrain takes one
 * elevation source; serving every tile, not just a pre-built patch, keeps the ground seamless.
 */
class LidarTerrainServer internal constructor(
    private val tiles: (z: Int, x: Int, y: Int) -> LidarTerrainTiles.Result,
    threads: Int = 4,
) : Closeable {
    data class Stats(
        val requested: Int = 0,
        val served: Int = 0,
        val noData: Int = 0,
        val failed: Int = 0,
        val inFlight: Int = 0,
    )

    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    val tileUrl: String = "http://127.0.0.1:$port/lidar-terrain/{z}/{x}/{y}.png"

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    // Newest first: while panning, the tiles just asked for matter more than ones scrolled past.
    private val workers = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LifoQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val acceptor = Thread(::acceptLoop, "lidar-terrain-accept").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            runCatching { workers.execute { handle(socket) } }.onFailure { runCatching { socket.close() } }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = READ_TIMEOUT_MS
                val input = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                val requestLine = input.readLine() ?: return
                // Drain headers; nothing in them changes the answer.
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)
                val match = PATH.matchEntire(parts.getOrNull(1)?.substringBefore('?').orEmpty())
                val out = BufferedOutputStream(s.getOutputStream())
                if (match == null || (method != "GET" && method != "HEAD")) {
                    respond(out, 404, "Not Found", null, method == "HEAD")
                    return
                }
                val (z, x, y) = match.destructured
                _stats.update { it.copy(requested = it.requested + 1, inFlight = it.inFlight + 1) }
                val result = try {
                    runCatching { tiles(z.toInt(), x.toInt(), y.toInt()) }.getOrElse { LidarTerrainTiles.Result.Failed }
                } finally {
                    _stats.update { it.copy(inFlight = it.inFlight - 1) }
                }
                when (result) {
                    is LidarTerrainTiles.Result.Png -> {
                        _stats.update { it.copy(served = it.served + 1) }
                        respond(out, 200, "OK", result.bytes, method == "HEAD")
                    }
                    LidarTerrainTiles.Result.NoData -> {
                        _stats.update { it.copy(noData = it.noData + 1) }
                        respond(out, 404, "Not Found", null, method == "HEAD")
                    }
                    LidarTerrainTiles.Result.Failed -> {
                        _stats.update { it.copy(failed = it.failed + 1) }
                        respond(out, 503, "Service Unavailable", null, method == "HEAD")
                    }
                }
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, body: ByteArray?, headOnly: Boolean) {
        val length = body?.size ?: 0
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            if (body != null) append("Content-Type: image/png\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            // The port changes each launch, so Mapbox's URL-keyed disk cache could never hit; tiles are cached here.
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (body != null && !headOnly) out.write(body)
        out.flush()
    }

    override fun close() {
        runCatching { server.close() }
        workers.shutdownNow()
        acceptor.interrupt()
    }

    private class LifoQueue : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    companion object {
        private val PATH = Regex("/lidar-terrain/(\\d{1,2})/(\\d{1,6})/(\\d{1,6})\\.png")
        private const val READ_TIMEOUT_MS = 15_000

        @Volatile
        private var instance: LidarTerrainServer? = null

        /** The app-wide server, started on first use. Binds a socket, so call off the main thread. */
        fun get(context: Context): LidarTerrainServer = instance ?: synchronized(this) {
            instance ?: run {
                // The old "build a 3D model" tiles this server replaces.
                File(context.applicationContext.filesDir, "terrain-models").deleteRecursively()
                val cache = context.applicationContext.cacheDir
                val client = ThreeDepElevationClient(File(cache, "3dep-elevation"))
                val tiles = LidarTerrainTiles(File(cache, "lidar-terrain-v1")) { key ->
                    client.fetchTile(key, LidarTerrainTiles.TILE)
                }
                LidarTerrainServer(tiles::tile).also { instance = it }
            }
        }
    }
}
