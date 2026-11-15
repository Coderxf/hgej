package com.dt.hgej.data.api

import com.dt.hgej.HgejApplication
import com.dt.hgej.crypto.CryptoManager
import com.dt.hgej.data.local.PreferencesManager
import com.dt.hgej.data.model.*
import com.google.gson.Gson
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ApiService {

    private val gson = Gson()
    private val client = HttpClient.client
    private val baseUrl = ApiConstants.BASE_URL

    // 设备信息真机化：避免全部用户共用同一硬编码设备指纹
    private val devicePrefs by lazy { PreferencesManager(HgejApplication.instance) }
    private suspend fun deviceModel(): String = android.os.Build.MODEL
    private suspend fun deviceTermId(): String = devicePrefs.getOrCreateTermId()

    private val encryptKeys = listOf("login_name", "user_id")
    private val noSignKeys = listOf(
        "answerContent", "surveyId", "content", "preContent", "img", "img1",
        "img2", "package", "codeUrl", "belong", "verCode"
    )

    private suspend fun postEncrypted(
        url: String,
        payload: Map<String, Any?>,
        encryptFields: List<String> = encryptKeys
    ): ApiResponse? = withContext(Dispatchers.IO) {
        try {
            val encryptedPayload = CryptoManager.buildEncryptedPayload(
                baseParams = payload,
                encryptKeys = encryptFields,
                noSignKeys = noSignKeys
            )
            val response = client.newCall(buildPostRequest(url, gson.toJson(encryptedPayload))).execute()
            val body = response.body?.string()
            if (body != null) {
                try {
                    gson.fromJson(body, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun buildPostRequest(url: String, jsonBody: String): Request = Request.Builder()
        .url(url)
        .header("Host", "app.hzgh.org.cn")
        .header("Accept", "application/json, text/plain, */*")
        .header("User-Agent", "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/110.0.5481.154 Mobile Safari/537.36;unionApp;HZGH")
        .header("Content-Type", "application/json;charset=UTF-8")
        .header("Origin", "https://app.hzgh.org.cn:8123")
        .header("X-Requested-With", "com.zjte.hanggongefamily")
        .header("Referer", "https://app.hzgh.org.cn:8123/")
        .post(jsonBody.toRequestBody(HttpClient.JSON_MEDIA_TYPE))
        .build()

    /**
     * 同步服务器时间（与上游 Python 版逻辑一致）：向查询接口发请求，
     * 从响应头 Date 取服务器时间，以往返时延一半补偿网络延迟，
     * 返回 服务器时间-本地时间 的偏移毫秒数。失败返回 null。
     */
    suspend fun syncServerTimeOffsetMs(loginName: String, sesId: String): Long? = withContext(Dispatchers.IO) {
        try {
            val encryptedPayload = CryptoManager.buildEncryptedPayload(
                baseParams = baseParams() + mapOf(
                    "login_name" to loginName,
                    "ses_id" to sesId
                ),
                encryptKeys = encryptKeys,
                noSignKeys = noSignKeys
            )
            val request = buildPostRequest(baseUrl + ApiConstants.U005_QUERY, gson.toJson(encryptedPayload))
            val start = SystemClock.elapsedRealtime()
            val response = client.newCall(request).execute()
            val rtt = SystemClock.elapsedRealtime() - start
            val dateHeader = response.header("Date") ?: return@withContext null
            val serverMs = ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            serverMs + rtt / 2 - System.currentTimeMillis()
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptData2(apiResponse: ApiResponse?): String? {
        if (apiResponse?.data2 == null) return null
        return try {
            CryptoManager.decryptData2(apiResponse.data2)
        } catch (e: Exception) {
            null
        }
    }

    fun <T> parseDecrypted(data2: String, clazz: Class<T>): T? {
        return try {
            gson.fromJson(data2, clazz)
        } catch (e: Exception) {
            null
        }
    }

    private fun timestamp(): String = (System.currentTimeMillis()).toString()

    private fun baseParams(): Map<String, Any?> = mapOf(
        "channel" to ApiConstants.CHANNEL,
        "app_ver_no" to ApiConstants.APP_VER_NO,
        "timestamp" to timestamp()
    )

    private suspend fun <T> execute(
        url: String,
        extraParams: Map<String, Any?> = emptyMap(),
        encryptFields: List<String> = encryptKeys,
        clazz: Class<T>
    ): T? {
        val payload = baseParams() + extraParams
        val response = postEncrypted(url, payload, encryptFields)
        val decrypted = decryptData2(response) ?: return null
        return parseDecrypted(decrypted, clazz)
    }

    // ========== Auth APIs ==========

    suspend fun getCaptcha(): CaptchaResponse? {
        val params = mapOf<String, Any?>(
            "term_sys_ver" to "12",
            "root" to "0",
            "term_sys" to "2",
            "model" to deviceModel(),
            "term_id" to deviceTermId(),
            "trcode" to "U/U067"
        )
        return execute(
            url = baseUrl + ApiConstants.U067_CAPTCHA,
            extraParams = params,
            encryptFields = emptyList(),
            clazz = CaptchaResponse::class.java
        )
    }

    suspend fun passwordLogin(
        phone: String,
        password: String,
        imgAuthCode: String,
        imgUniCode: String
    ): LoginResponse? {
        val passwordMd5 = CryptoManager.md5(password)
        val params = mapOf<String, Any?>(
            "term_sys_ver" to "12",
            "root" to "0",
            "term_sys" to "2",
            "model" to deviceModel(),
            "term_id" to deviceTermId(),
            "login_name" to phone,
            "pwd" to passwordMd5,
            "imgUniCode" to imgUniCode,
            "imgAuthCode" to imgAuthCode
        )
        val encryptFields = listOf("login_name", "pwd", "imgUniCode", "imgAuthCode")
        return execute(
            url = baseUrl + ApiConstants.U004_LOGIN,
            extraParams = params,
            encryptFields = encryptFields,
            clazz = LoginResponse::class.java
        )
    }

    suspend fun sendSms(
        phone: String,
        captchaData: CaptchaResponse?,
        imgAuthCode: String
    ): SmsResponse? {
        if (captchaData?.imgUniCode == null) return null
        val params = mapOf<String, Any?>(
            "term_sys_ver" to "12",
            "root" to "0",
            "term_sys" to "2",
            "model" to deviceModel(),
            "login_name" to phone,
            "mobile" to phone,
            "imgUniCode" to captchaData.imgUniCode,
            "imgAuthCode" to imgAuthCode,
            "sms_type" to "10"
        )
        val encryptFields = listOf("login_name", "mobile", "imgUniCode", "imgAuthCode")
        return execute(
            url = baseUrl + ApiConstants.SMS1_SEND,
            extraParams = params,
            encryptFields = encryptFields,
            clazz = SmsResponse::class.java
        )
    }

    suspend fun smsLogin(phone: String, authCode: String): LoginResponse? {
        val params = mapOf<String, Any?>(
            "term_sys_ver" to "12",
            "root" to "0",
            "term_sys" to "2",
            "model" to deviceModel(),
            "term_id" to deviceTermId(),
            "login_name" to phone,
            "auth_code" to authCode
        )
        val encryptFields = listOf("login_name", "auth_code")
        return execute(
            url = baseUrl + ApiConstants.U065_SMS_LOGIN,
            extraParams = params,
            encryptFields = encryptFields,
            clazz = LoginResponse::class.java
        )
    }

    // ========== Task APIs ==========

    suspend fun dailyLogin(loginName: String, sesId: String): TaskResponse? {
        val params = mapOf<String, Any?>(
            "login_name" to loginName,
            "ses_id" to sesId,
            "type" to "1"
        )
        return execute(
            url = baseUrl + ApiConstants.U042_LOGIN_SIGN,
            extraParams = params,
            clazz = TaskResponse::class.java
        )
    }

    suspend fun dailySignin(loginName: String, sesId: String): TaskResponse? {
        val params = mapOf<String, Any?>(
            "login_name" to loginName,
            "ses_id" to sesId,
            "type" to "5"
        )
        return execute(
            url = baseUrl + ApiConstants.U042_LOGIN_SIGN,
            extraParams = params,
            clazz = TaskResponse::class.java
        )
    }

    suspend fun dailyComment(loginName: String, sesId: String): TaskResponse? {
        val params = mapOf<String, Any?>(
            "login_name" to loginName,
            "ses_id" to sesId,
            "related_id" to "1232",
            "content_type" to "1",
            "oper_type" to "0",
            "suffix" to "png",
            "content" to "好"
        )
        return execute(
            url = baseUrl + ApiConstants.AC08_COMMENT,
            extraParams = params,
            clazz = TaskResponse::class.java
        )
    }

    suspend fun queryPoints(loginName: String, sesId: String): TaskResponse? {
        val params = mapOf<String, Any?>(
            "login_name" to loginName,
            "ses_id" to sesId
        )
        return execute(
            url = baseUrl + ApiConstants.U005_QUERY,
            extraParams = params,
            clazz = TaskResponse::class.java
        )
    }

    suspend fun exchangeCoupon(
        loginName: String,
        userId: String,
        sesId: String,
        exchangeId: String
    ): ExchangeResponse? {
        val params = mapOf<String, Any?>(
            "login_name" to loginName,
            "user_id" to userId,
            "ses_id" to sesId,
            "exchange_id" to exchangeId
        )
        return execute(
            url = baseUrl + ApiConstants.OL41_EXCHANGE,
            extraParams = params,
            clazz = ExchangeResponse::class.java
        )
    }

    // ========== QR APIs ==========

    suspend fun getQrToken(userId: String, sesId: String): QrTokenResponse? {
        val params = mapOf<String, Any?>(
            "user_id" to userId,
            "ses_id" to sesId
        )
        val encryptFields = listOf("user_id")
        return execute(
            url = baseUrl + ApiConstants.OL82_QR_TOKEN,
            extraParams = params,
            encryptFields = encryptFields,
            clazz = QrTokenResponse::class.java
        )
    }

    suspend fun recordQrVisit(userId: String): Boolean {
        val params = mapOf<String, Any?>(
            "user_id" to userId,
            "icon_id" to "92",
            "type" to "2"
        )
        val encryptFields = listOf("user_id")
        val result = execute(
            url = baseUrl + ApiConstants.OP80_RECORD,
            extraParams = params,
            encryptFields = encryptFields,
            clazz = SmsResponse::class.java
        )
        return result?.result == "0"
    }

    suspend fun getQrCode(token: String): QrCodeResponse? = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis().toString()
            val globalSeq = "2500" + (1..14).map {
                "0123456789abcdef".toList().random()
            }.joinToString("")
            val payload = mapOf(
                "latitude" to null,
                "longitude" to null,
                "version" to "1.0.0",
                "isImage" to "1",
                "timestamp" to ts,
                "globalSeq" to globalSeq
            )
            val jsonBody = gson.toJson(payload)
            val request = Request.Builder()
                .url(ApiConstants.QR_BASE_URL + ApiConstants.QR_APPLY)
                .header("Host", "hzcode.96225.com")
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL} Build/${android.os.Build.ID}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/110.0.5481.154 Mobile Safari/537.36;unionApp;HZGH")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Origin", "https://hzcode.96225.com")
                .header("X-Requested-With", "com.zjte.hanggongefamily")
                .header("Referer", "https://hzcode.96225.com/hzcitizencodeh5/")
                .header("token", token)
                .post(jsonBody.toRequestBody(HttpClient.JSON_MEDIA_TYPE))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (body != null) {
                try {
                    val wrapper = gson.fromJson(body, QrCodeWrapper::class.java)
                    wrapper.data
                } catch (e: Exception) { null }
            } else null
        } catch (e: Exception) { null }
    }

    suspend fun getSubwayTickets(loginName: String, sesId: String, awardType: String = "1"): SubwayTicketResponse? {
        val params = mapOf<String, Any?>(
            "login_name" to loginName,
            "user_id" to loginName,
            "ses_id" to sesId,
            "use_state" to "1",
            "award_type" to awardType,
            "page_size" to 10,
            "page_num" to 1
        )
        return execute(
            url = baseUrl + ApiConstants.OL83_TICKET,
            extraParams = params,
            clazz = SubwayTicketResponse::class.java
        )
    }

    suspend fun getUserInfo(loginName: String, sesId: String): UserInfoResponse? {
        val params = mapOf<String, Any?>(
            "login_name" to loginName,
            "ses_id" to sesId
        )
        return execute(
            url = baseUrl + ApiConstants.U005_QUERY,
            extraParams = params,
            clazz = UserInfoResponse::class.java
        )
    }
}
