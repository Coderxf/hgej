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

data class SubwayTicketResponse(
    val result: String? = null,
    val msg: String? = null,
    val total: String? = null,
    val used: String? = null,
    val expire: String? = null,
    val twoYuan: String? = null,
    val fourYuan: String? = null,
    val sixYuan: String? = null,
    val num_2: String? = null,
    val num_4: String? = null,
    val num_6: String? = null,
    @SerializedName("list") val recordList: List<TicketRecord>? = null
)

data class TicketRecord(
    val award_name: String? = null,
    val use_state: String? = null,
    val create_time: String? = null,
    val expire_time: String? = null,
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
    val timeSleep: String = "0.08"
)
