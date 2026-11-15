package com.dt.hgej.service

import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.model.UserConfig
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class ExchangeScheduler(
    private val apiService: ApiService,
    private val onLog: (String) -> Unit,
    private val onRunningChange: ((Boolean) -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start(config: UserConfig) {
        stop()
        val newJob = scope.launch {
            // 与上游 Python 版一致的限制，保护接口
            val requestedCount = config.runCount.toIntOrNull() ?: 100
            val count = requestedCount.coerceIn(1, 20)
            val sleepMs = ((config.timeSleep.toDoubleOrNull() ?: 0.2) * 1000)
                .toLong()
                .coerceIn(150, 60_000)

            onRunningChange?.invoke(true)

            val targetTime = parseTargetTime(config.runTime)
            var offsetMs = 0L
            if (targetTime != null) {
                onLog("等待目标时间: ${config.runTime}")
                onLog("正在同步服务器时间...")
                val offset = apiService.syncServerTimeOffsetMs(config.loginName, config.sesId)
                if (offset != null) {
                    offsetMs = offset
                    onLog("服务器时间同步完成，本地偏移: ${if (offset >= 0) "+" else ""}${offset}ms")
                } else {
                    onLog("服务器时间同步失败，使用本地时间")
                }
                waitUntilTarget(targetTime, offsetMs)
                if (!isActive) return@launch
            } else {
                onLog("未设置目标时间，立即执行")
            }

            if (count != requestedCount) {
                onLog("次数已限制为 $count（范围 1~20）")
            }
            onLog("开始并发兑换，次数: $count，间隔: ${sleepMs}ms")

            val pending = mutableListOf<Job>()
            for (i in 1..count) {
                if (!isActive) break
                pending += launch {
                    val result = apiService.exchangeCoupon(
                        config.loginName,
                        config.userId,
                        config.sesId,
                        config.exchangeId
                    )
                    val msg = result?.msg ?: "无响应"
                    onLog("[$i] $msg")
                }
                delay(sleepMs)
            }
            // 等收尾请求全部返回后再打完成日志
            pending.joinAll()
            onLog("兑换任务完成")
        }
        job = newJob
        newJob.invokeOnCompletion {
            // job 为 null 说明是主动 stop；job 已换成新任务说明已被 restart 取代，
            // 两种之外的正常结束都要把运行状态置回 false
            if (job == null || job === newJob) {
                onRunningChange?.invoke(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun parseTargetTime(timeStr: String): Date? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.parse(timeStr)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun waitUntilTarget(target: Date, offsetMs: Long = 0L) {
        // 取消任务时 delay 会抛出 CancellationException 退出，无需显式检查 job 状态；
        // offsetMs 为服务器时间与本地时钟的偏移，用于按服务器时间卡点
        while (true) {
            val nowMs = System.currentTimeMillis() + offsetMs
            if (nowMs >= target.time) break
            val diff = target.time - nowMs

            when {
                diff > 3600_000 -> delay(300_000)
                diff > 600_000 -> delay(60_000)
                diff > 60_000 -> delay(30_000)
                diff > 1_000 -> delay(500)
                else -> delay(50)
            }
        }
    }
}
