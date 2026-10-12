package com.example.data

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Shared HTTP stack for LiDAR transfers.
 *
 * The previous [java.net.HttpURLConnection] path used one HTTP/1.1 stream, no connection pool,
 * and a fresh TCP/TLS handshake after every redirect. USGS and TNM hosts rate-limit a single
 * connection well below a phone's available bandwidth; HTTP/2 reuse plus several Range workers
 * on this client is what fills the pipe.
 */
object LazHttp {
    const val USER_AGENT = "Find-It-Android/2.0 (LiDAR tile download)"
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 60L

    val shared: OkHttpClient by lazy { createClient() }

    fun createClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
