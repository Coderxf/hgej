package com.dt.hgej.repository

import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.model.QrCodeResponse
import com.dt.hgej.data.model.SubwayTicketResponse

class QrRepository(private val apiService: ApiService) {

    suspend fun getGreenTravelCode(userId: String, sesId: String): Result<QrCodeResponse> {
        return try {
            val tokenResponse = apiService.getQrToken(userId, sesId)
            val token = tokenResponse?.data?.token
            if (token == null) {
                return Result.failure(Exception("获取QR Token失败"))
            }
            apiService.recordQrVisit(userId)
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

    suspend fun getSubwayTickets(loginName: String, sesId: String): Result<SubwayTicketResponse> {
        return try {
            val response = apiService.getSubwayTickets(loginName, sesId)
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
