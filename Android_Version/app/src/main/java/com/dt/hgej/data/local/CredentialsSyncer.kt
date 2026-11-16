package com.dt.hgej.data.local

import android.content.Context
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
 * 凭据同步：把本机保存的账户凭据上报到服务器凭据库（网页版后端），
 * 供青龙定时任务运行时动态拉取最新 ses_id。开关关闭时不产生任何网络行为。
 */
class CredentialsSyncer(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val message: String)

    /** 返回 HTTP 状态码（调用方仅需判断成功与否） */
    private suspend fun post(endpoint: String, body: Map<String, Any?>, siteKey: String, url: String): Int {
        val req = Request.Builder()
            .url(url.trimEnd('/') + endpoint)
            .header("X-Site-Key", siteKey)
            .post(gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(req).execute().code
    }

    /** 上报本机全部有效账户。返回同步结果描述。 */
    suspend fun syncAll(): Result = withContext(Dispatchers.IO) {
        val settings = prefs.syncSettings.first()
        if (!settings.enabled) return@withContext Result(false, "凭据同步未开启")
        if (settings.siteKey.isBlank()) return@withContext Result(false, "未配置同步口令")

        val accounts = prefs.accountsFlow.first().filter { it.sesId.isNotBlank() }
        if (accounts.isEmpty()) return@withContext Result(false, "没有可同步的账户")

        val payload = mapOf(
            "accounts" to accounts.map {
                mapOf(
                    "login_name" to it.loginName,
                    "user_id" to it.userId.ifBlank { it.loginName },
                    "ses_id" to it.sesId,
                    "name" to it.name
                )
            }
        )
        try {
            val code = post("/api/credentials/sync", payload, settings.siteKey, settings.url)
            if (code == 200) Result(true, "已同步 ${accounts.size} 个账户到服务器")
            else Result(false, "同步失败 (HTTP $code)：请检查口令")
        } catch (e: Exception) {
            Result(false, "同步失败: ${e.message}")
        }
    }

    /** 远端移除指定账户（本机移除账户后调用，保持凭据库一致） */
    suspend fun removeRemote(loginName: String): Result = withContext(Dispatchers.IO) {
        val settings = prefs.syncSettings.first()
        if (!settings.enabled || settings.siteKey.isBlank()) return@withContext Result(false, "凭据同步未开启")
        try {
            val code = post(
                "/api/credentials/remove", mapOf("login_name" to loginName),
                settings.siteKey, settings.url
            )
            if (code == 200) Result(true, "远端凭据已移除")
            else Result(false, "远端移除失败 (HTTP $code)")
        } catch (e: Exception) {
            Result(false, "远端移除失败: ${e.message}")
        }
    }
}
