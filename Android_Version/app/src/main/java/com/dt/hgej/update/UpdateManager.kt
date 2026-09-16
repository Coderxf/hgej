package com.dt.hgej.update

import android.content.Context
import android.os.Build
import com.dt.hgej.data.api.ApiConstants
import com.dt.hgej.data.api.HttpClient
import com.dt.hgej.data.model.UpdateInfo
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object UpdateManager {

    private val gson = Gson()
    private val client = HttpClient.client

    /**
     * 检查更新：服务器 versionCode 大于本地版本时返回 UpdateInfo，否则返回 null。
     * 网络异常、地址未配置等情况静默返回 null，不影响正常使用。
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            if (ApiConstants.UPDATE_CHECK_URL.contains("your-aliyun-server.com")) return@withContext null
            val request = Request.Builder()
                .url(ApiConstants.UPDATE_CHECK_URL)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val info = try {
                gson.fromJson(body, UpdateInfo::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext null
            if (info.versionCode <= 0 || info.downloadUrl.isNullOrBlank()) return@withContext null
            if (info.versionCode > currentVersionCode(context)) info else null
        } catch (e: Exception) {
            null
        }
    }

    fun currentVersionCode(context: Context): Long = try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    } catch (e: Exception) {
        0L
    }
}
