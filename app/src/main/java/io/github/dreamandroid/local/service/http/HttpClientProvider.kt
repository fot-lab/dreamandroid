package io.github.dreamandroid.local.service.http

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    /** Shared OkHttpClient for backend API calls (short timeouts, connection pooling). */
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    /**
     * OkHttpClient variant for large file downloads with longer timeouts
     * and shared connection pool.
     */
    fun createForDownload(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
}
