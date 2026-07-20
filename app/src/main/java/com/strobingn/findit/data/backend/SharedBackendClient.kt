package com.strobingn.findit.data.backend

import com.strobingn.findit.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for the shared Viewshade/Find It Oracle Cloud backend.
 * Base: http://129.80.174.236:8000
 */
class SharedBackendClient(
  baseUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .build(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  private val root = normalizeUrl(baseUrl)

  @Serializable data class LatLon(val lat: Double, val lon: Double)

  @Serializable data class ElevationSampleRequest(val points: List<LatLon>)

  @Serializable
  data class ElevationSampleResponse(
    val elevations_m: List<Double?>,
    val source: String = "demo",
  )

  @Serializable
  data class TerrainAnalyzeRequest(
    val center_lat: Double,
    val center_lon: Double,
    val half_size_m: Double = 500.0,
    val cell_size_m: Double = 20.0,
    val mode: String = "all",
  )

  @Serializable
  data class TerrainAnalyzeResponse(
    val width: Int,
    val height: Int,
    val west: Double = 0.0,
    val south: Double = 0.0,
    val east: Double = 0.0,
    val north: Double = 0.0,
    val cell_size_m: Double = 0.0,
    val hillshade: List<List<Double>>? = null,
    val svf: List<List<Double>>? = null,
    val disturbance: List<List<Double>>? = null,
    val source: String = "demo",
  )

  suspend fun health(): Boolean =
    withContext(Dispatchers.IO) {
      val req = Request.Builder().url("$root/health").get().build()
      client.newCall(req).execute().use { it.isSuccessful }
    }

  suspend fun sampleElevations(points: List<Pair<Double, Double>>): ElevationSampleResponse =
    withContext(Dispatchers.IO) {
      val body =
        ElevationSampleRequest(points = points.map { LatLon(it.first, it.second) })
      post("/elevation/sample", json.encodeToString(ElevationSampleRequest.serializer(), body))
        .let { json.decodeFromString(ElevationSampleResponse.serializer(), it) }
    }

  suspend fun analyzeTerrain(
    centerLat: Double,
    centerLon: Double,
    halfSizeM: Double = 500.0,
    cellSizeM: Double = 20.0,
    mode: String = "all",
  ): TerrainAnalyzeResponse =
    withContext(Dispatchers.IO) {
      val body =
        TerrainAnalyzeRequest(
          center_lat = centerLat,
          center_lon = centerLon,
          half_size_m = halfSizeM,
          cell_size_m = cellSizeM,
          mode = mode,
        )
      post("/terrain/analyze", json.encodeToString(TerrainAnalyzeRequest.serializer(), body))
        .let { json.decodeFromString(TerrainAnalyzeResponse.serializer(), it) }
    }

  private fun post(path: String, jsonBody: String): String {
    val req =
      Request.Builder()
        .url("$root$path")
        .post(jsonBody.toRequestBody("application/json".toMediaType()))
        .build()
    client.newCall(req).execute().use { resp ->
      val text = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) error("Backend HTTP ${resp.code}: ${text.take(200)}")
      return text
    }
  }

  companion object {
    fun normalizeUrl(raw: String): String {
      var u = raw.trim()
      if (u.isEmpty()) return BuildConfig.DEFAULT_BACKEND_URL
      if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
      return u.trimEnd('/')
    }

    fun default(): SharedBackendClient = SharedBackendClient(BuildConfig.DEFAULT_BACKEND_URL)
  }
}

/** Flatten nested 0..1 grid from backend into FloatArray row-major. */
fun List<List<Double>>.toFloatRaster(): FloatArray {
  if (isEmpty()) return floatArrayOf()
  val h = size
  val w = first().size
  val out = FloatArray(h * w)
  for (r in 0 until h) {
    val row = this[r]
    for (c in 0 until w) {
      out[r * w + c] = row.getOrElse(c) { 0.0 }.toFloat()
    }
  }
  return out
}
