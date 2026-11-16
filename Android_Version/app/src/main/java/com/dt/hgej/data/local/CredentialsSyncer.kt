package com.dt.hgej.data.local

import android.content.Context
import android.os.Build
import com.dt.hgej.data.model.StoredAccount
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 凭据同步：本机登录的账户自动上报到服务器凭据库（hgej.777713.xyz），
 * 供青龙定时任务拉取最新 ses_id 与真实设备 UA。默认开启，无需任何配置。
 */
class CredentialsSyncer(private val context: Context) {

    companion object {
        /** 凭据中心地址（服务器已反代到凭据 API） */
        const val HOST = "https://hgej.777713.xyz"

        /** 本机真实设备的完整 UA（登录上报用，青龙脚本按账户取用） */
        fun deviceUserAgent(): String =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.86 " +
                "Mobile Safari/537.36;unionApp;HZGH"
    }

    private val prefs = PreferencesManager(context)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val message: String)

    private fun post(endpoint: String, body: Map<String, Any?>): Int {
        val req = Request.Builder()
            .url(HOST + endpoint)
            .post(gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(req).execute().code
    }

    /** 上报本机全部有效账户（含真实设备 UA）。登录成功后自动调用。 */
    suspend fun syncAll(): Result = withContext(Dispatchers.IO) {
        val accounts = prefs.accountsFlow.first().filter { it.sesId.isNotBlank() }
        if (accounts.isEmpty()) return@withContext Result(false, "没有可同步的账户")

        val payload = mapOf(
            "accounts" to accounts.map { acc ->
                mapOf(
                    "login_name" to acc.loginName,
                    "user_id" to acc.userId.ifBlank { acc.loginName },
                    "ses_id" to acc.sesId,
                    "name" to acc.name,
                    "ua" to deviceUserAgent()
                )
            }
        )
        try {
            val code = post("/api/credentials/sync", payload)
            if (code == 200) Result(true, "已同步 ${accounts.size} 个账户到服务器")
            else Result(false, "同步失败 (HTTP $code)")
        } catch (e: Exception) {
            Result(false, "同步失败: ${e.message}")
        }
    }
}
