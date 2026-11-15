package com.dt.hgej.data.api

import com.dt.hgej.BuildConfig
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object HttpClient {
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // IPv4 优先：目标域名同时有 AAAA(IPv6)/A(IPv4) 记录，部分运营商网络 IPv6 出口不通，
    // 按 DNS 原始顺序先试 IPv6 会黑洞到连接超时（表现为启动卡 30 秒才出用户信息）
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { it is Inet6Address }
    }

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            // 连接超时从 30s 缩到 8s：首个地址不通时尽快重试下一个
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dns(ipv4FirstDns)

        // 请求/响应体含 ses_id 等敏感信息，仅在 debug 包输出
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(logging)
        }

        builder.build()
    }
}
