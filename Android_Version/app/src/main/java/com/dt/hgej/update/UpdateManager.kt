package com.dt.hgej.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dt.hgej.data.api.ApiConstants
import com.dt.hgej.data.api.HttpClient
import com.dt.hgej.data.model.UpdateInfo
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

object UpdateManager {

    private val gson = Gson()
    private val client = HttpClient.client

    /**
     * 检查更新：服务器 versionCode 大于本地版本时返回 UpdateInfo，否则返回 null。
     * force=true 时忽略版本号比较（开发者五连击触发，用于重复安装同一版本测试）。
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
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
            if (force || info.versionCode > currentVersionCode(context)) info else null
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

    /**
     * 下载 APK 到缓存目录，返回 File。
     * @param onProgress 进度回调 (0..100)
     */
    suspend fun downloadApk(
        url: String,
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "updates")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "update.apk")
            if (file.exists()) file.delete()

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body ?: return@withContext null
            val contentLength = body.contentLength()
            var downloaded = 0L

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (contentLength > 0) {
                            onProgress((downloaded * 100 / contentLength).toInt())
                        }
                    }
                }
            }
            onProgress(100)
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通过系统 PackageInstaller 安装 APK。
     */
    fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 检查是否允许安装未知来源应用。
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * 跳转到系统设置页面，允许安装未知来源应用。
     */
    fun startInstallPermissionSetting(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
