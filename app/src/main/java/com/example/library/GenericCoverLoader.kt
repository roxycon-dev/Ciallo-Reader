package com.example.library

import android.content.Context
import coil.ImageLoader
import com.example.source.js.JsCookieJar
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * 通用封面加载器：使用浏览器 UA 和已登录源的同域 Cookie。
 * 站点专用 Referer 由封面请求自身提供。
 */
object GenericCoverLoader {
    @Volatile
    private var cachedLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return cachedLoader ?: synchronized(this) {
            cachedLoader ?: buildLoader(context.applicationContext).also { cachedLoader = it }
        }
    }

    @Volatile
    private var cachedClient: OkHttpClient? = null

    internal fun client(context: Context): OkHttpClient = cachedClient ?: synchronized(this) {
        cachedClient ?: buildClient(context.applicationContext).also { cachedClient = it }
    }

    private fun buildClient(context: Context): OkHttpClient {
        val clientBuilder = com.example.source.SharedHttpTransport.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val req = chain.request()
                val cookie = JsCookieJar.cookieHeader(context, req.url.toString())
                val builder = req.newBuilder()
                if (cookie.isNotBlank() && req.header("Cookie") == null) {
                    builder.header("Cookie", cookie)
                }
                if (req.header("User-Agent").isNullOrBlank()) {
                    builder.header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36"
                    )
                }
                chain.proceed(builder.build())
            }
        return com.example.source.js.JsSourceProxy.failoverClient(context, clientBuilder.build())
    }

    private fun buildLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .okHttpClient(client(context))
            .crossfade(true)
            .build()
}
