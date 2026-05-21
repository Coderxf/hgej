package com.dt.hgej.repository

import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.model.ExchangeResponse
import kotlinx.coroutines.delay

class TaskRepository(private val apiService: ApiService) {

    suspend fun executeDailyWorkflow(
        loginName: String,
        sesId: String,
        onLog: (String) -> Unit
    ): Result<String> {
        return try {
            val login = apiService.dailyLogin(loginName, sesId)
            onLog("登录结果: ${login?.msg ?: "无响应"}")

            for (i in 1..3) {
                val sign = apiService.dailySignin(loginName, sesId)
                onLog("第${i}次签到结果: ${sign?.msg ?: "无响应"}")
                delay(1000)
            }

            val comment = apiService.dailyComment(loginName, sesId)
            onLog("评论结果: ${comment?.msg ?: "无响应"}")

            val query = apiService.queryPoints(loginName, sesId)
            onLog("积分查询结果: ${query?.msg ?: "无响应"}")

            Result.success("每日任务执行完成")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exchangeCoupon(
        loginName: String,
        userId: String,
        sesId: String,
        exchangeId: String
    ): Result<ExchangeResponse> {
        return try {
            val response = apiService.exchangeCoupon(loginName, userId, sesId, exchangeId)
            if (response != null) {
                Result.success(response)
            } else {
                Result.failure(Exception("兑换请求失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
