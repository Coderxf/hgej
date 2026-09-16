package com.dt.hgej.util

import android.util.Base64
import java.text.SimpleDateFormat
import java.util.*

object Utils {
    fun currentTimeString(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }

    fun decodeBase64Image(base64: String?): ByteArray? {
        if (base64.isNullOrEmpty()) return null
        val data = if (base64.contains(",")) {
            base64.substringAfter(",")
        } else base64
        return try {
            Base64.decode(data, Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    fun formatTimestamp(timestamp: String?): String {
        if (timestamp.isNullOrEmpty()) return "未知"
        return try {
            val ts = timestamp.toLong()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.format(Date(ts * 1000))
        } catch (e: Exception) {
            timestamp
        }
    }

    fun getNextRunTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = Calendar.getInstance()

        val time07_00 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val time11_30 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 11)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val time17_00 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 17)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val time07_00_tomorrow = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }

        val nextTime = when {
            now.before(time07_00) -> time07_00
            now.before(time11_30) -> time11_30
            now.before(time17_00) -> time17_00
            else -> time07_00_tomorrow
        }

        return sdf.format(nextTime.time)
    }
}
