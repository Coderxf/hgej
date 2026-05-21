package com.dt.hgej.repository

import com.dt.hgej.data.api.ApiService
import com.dt.hgej.data.model.CaptchaResponse
import com.dt.hgej.data.model.LoginResponse

class AuthRepository(private val apiService: ApiService) {

    suspend fun getCaptcha(): Result<CaptchaResponse> {
        return try {
            val response = apiService.getCaptcha()
            if (response != null && response.result == "0") {
                Result.success(response)
            } else {
                Result.failure(Exception(response?.msg ?: "获取验证码失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun passwordLogin(
        phone: String,
        password: String,
        imgAuthCode: String,
        imgUniCode: String
    ): Result<LoginResponse> {
        return try {
            val response = apiService.passwordLogin(phone, password, imgAuthCode, imgUniCode)
            if (response != null && response.result == "0") {
                Result.success(response)
            } else {
                Result.failure(Exception(response?.msg ?: "密码登录失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendSms(phone: String, captchaData: CaptchaResponse?, captchaCode: String): Result<Unit> {
        return try {
            val response = apiService.sendSms(phone, captchaData, captchaCode)
            if (response != null && response.result == "0") {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response?.msg ?: "发送短信失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun smsLogin(phone: String, authCode: String): Result<LoginResponse> {
        return try {
            val response = apiService.smsLogin(phone, authCode)
            if (response != null && response.result == "0") {
                Result.success(response)
            } else {
                Result.failure(Exception(response?.msg ?: "短信登录失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
