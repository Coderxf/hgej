package com.dt.hgej.data.model

import com.google.gson.annotations.SerializedName

data class CaptchaResponse(
    val result: String? = null,
    val msg: String? = null,
    val img: String? = null,
    val imgUniCode: String? = null
)

data class LoginResponse(
    val result: String? = null,
    val msg: String? = null,
    val login_name: String? = null,
    val user_id: String? = null,
    val ses_id: String? = null
)

data class SmsResponse(
    val result: String? = null,
    val msg: String? = null
)

data class TaskResponse(
    val result: String? = null,
    val msg: String? = null,
    val data: TaskData? = null
)

data class TaskData(
    val login_num: String? = null,
    val sign_flag: String? = null,
    val sign_num: String? = null,
    val score: String? = null,
    val integral: String? = null,
    val totalIntegral: String? = null
)

data class ExchangeResponse(
    val result: String? = null,
    val msg: String? = null,
    val data: ExchangeData? = null
)

data class ExchangeData(
    val exchange_id: String? = null,
    val exchange_name: String? = null,
    val coupon_id: String? = null
)

data class QrTokenResponse(
    val result: String? = null,
    val msg: String? = null,
    val data: QrTokenData? = null
)

data class QrTokenData(
    val token: String? = null
)

data class QrCodeWrapper(
    val respCode: String? = null,
    val respDesc: String? = null,
    val data: QrCodeResponse? = null
)

data class QrCodeResponse(
    val qrcode: String? = null,
    val deadTime: String? = null,
    val money: String? = null,
    val trafficCardNo: String? = null,
    val deadline: String? = null,
    val qrcodeImage: String? = null
)

// 字段名对照 OL83 真实响应（见 思路.md 抓包样本）：
// use_num=已使用数，expire_num=已过期数，num_2/4/6=各面额当前可用数，无单独"总获得"字段
data class SubwayTicketResponse(
    val result: String? = null,
    val msg: String? = null,
    val use_num: String? = null,
    val expire_num: String? = null,
    val num_2: String? = null,
    val num_4: String? = null,
    val num_6: String? = null,
    val total_count: Int? = null,
    val rec_num: String? = null,
    @SerializedName("list") val recordList: List<TicketRecord>? = null
) {
    /** 总获得 = 已使用 + 已过期 + 当前可用三类之和 */
    val totalObtained: Int
        get() = listOf(use_num, expire_num, num_2, num_4, num_6).sumOf { it?.toIntOrNull() ?: 0 }
}

data class TicketRecord(
    val award_name: String? = null,
    val use_state: String? = null,
    val use_state_name: String? = null,
    val create_time: String? = null,
    val expire_time: String? = null,
    val use_time: String? = null,
    val exchange_name: String? = null,
    val award_type: String? = null
)

data class UserInfoResponse(
    val result: String? = null,
    val msg: String? = null,
    val name: String? = null,
    val sensitive_name: String? = null,
    val remain_integral: String? = null,
    val total_integral: String? = null,
    val integral: String? = null
)

data class ApiResponse(
    val result: String? = null,
    val msg: String? = null,
    val data2: String? = null
)

data class UserConfig(
    val loginName: String = "",
    val sesId: String = "",
    val userId: String = "",
    val exchangeId: String = "10",
    val runTime: String = "",
    val runCount: String = "100",
    val timeSleep: String = "0.2"
)

/** 已保存的账户（多账户支持：凭据按账户隔离，排程配置全局共享） */
data class StoredAccount(
    val loginName: String = "",
    val userId: String = "",
    val sesId: String = "",
    val name: String = "",
    val savedAt: Long = 0,
    /** 会话有效性：U005 校验 result != "0" 时置 true，重新登录后清除 */
    val invalid: Boolean = false
)

data class UpdateInfo(
    val versionCode: Long = 0,
    val versionName: String? = null,
    val downloadUrl: String? = null,
    val changelog: String? = null,
    val forceUpdate: Boolean = false
)
