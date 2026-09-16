package com.dt.hgej.repository

import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.model.QrCodeResponse
import com.dt.hgej.data.model.SubwayTicketResponse

class QrRepository(private val apiService: ApiService) {

    suspend fun getGreenTravelCode(loginName: String, sesId: String): Result<QrCodeResponse> {
        return try {
            val tokenResponse = apiService.getQrToken(loginName, sesId)
            if (tokenResponse == null) {
                return Result.failure(Exception("QR Token接口无响应"))
            }
            if (tokenResponse.result != "0") {
                return Result.failure(Exception("QR Token接口返回错误: ${tokenResponse.msg ?: "未知"}"))
            }
            val token = tokenResponse.data?.token
            if (token == null) {
                return Result.failure(Exception("QR Token接口缺少token字段"))
            }
            apiService.recordQrVisit(loginName)
            val qrResponse = apiService.getQrCode(token)
            if (qrResponse != null) {
                Result.success(qrResponse)
            } else {
                Result.failure(Exception("获取乘车码失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSubwayTickets(loginName: String, sesId: String, awardType: String = "1"): Result<SubwayTicketResponse> {
        return try {
            val response = apiService.getSubwayTickets(loginName, sesId, awardType)
            if (response != null) {
                Result.success(response)
            } else {
                Result.failure(Exception("查询地铁券记录失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
