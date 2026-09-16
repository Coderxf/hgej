package com.dt.hgej.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.Window
import androidx.core.graphics.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

object ScreenCapture {

    private const val SAVE_DIR = "杭工助手"

    /** Android 9 及以下写公共相册需要存储权限 */
    fun needsStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * 截取当前 Activity 窗口内容。优先 PixelCopy（与屏幕显示一致），
     * 失败时回退到 decorView 绘制。返回 null 表示截图失败。
     */
    suspend fun captureWindow(window: Window): Bitmap? = withContext(Dispatchers.Main) {
        val decorView = window.decorView
        if (decorView.width <= 0 || decorView.height <= 0) return@withContext null

        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        val pixelCopyOk = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    window,
                    bitmap,
                    { result -> cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                cont.resume(false)
            }
        }
        if (pixelCopyOk) {
            bitmap
        } else {
            try {
                decorView.drawToBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 保存到系统相册 Pictures/杭工助手/ 目录。
     * 返回给用户展示的路径信息，失败返回 null。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + SAVE_DIR)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Pictures/$SAVE_DIR/$fileName.png"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    SAVE_DIR
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                val file = File(dir, "$fileName.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("image/png"),
                    null
                )
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
