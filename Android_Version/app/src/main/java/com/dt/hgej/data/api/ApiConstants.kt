package com.dt.hgej.data.api

object ApiConstants {
    const val BASE_URL = "https://app.hzgh.org.cn"
    const val QR_BASE_URL = "https://hzcode.96225.com"

    const val CHANNEL = "02"
    const val APP_VER_NO = "3.1.7"

    // API Endpoints
    const val U067_CAPTCHA = "/unionApp/interf/front/U/U067"
    const val U004_LOGIN = "/unionApp/interf/front/U/U004"
    const val SMS1_SEND = "/unionApp/interf/front/SMS/SMS1"
    const val U065_SMS_LOGIN = "/unionApp/interf/front/U/U065"
    const val U042_LOGIN_SIGN = "/unionApp/interf/front/U/U042"
    const val AC08_COMMENT = "/unionApp/interf/front/AC/AC08"
    const val U005_QUERY = "/unionApp/interf/front/U/U005"
    const val OL41_EXCHANGE = "/unionApp/interf/front/OL/OL41"
    const val OL82_QR_TOKEN = "/unionApp/interf/front/OL/OL82"
    const val OP80_RECORD = "/unionApp/interf/front/OP/OP80"
    const val OL83_TICKET = "/unionApp/interf/front/OL/OL83"
    const val QR_APPLY = "/hzcitizencodeengine/codeEngine/apply"

    // ========== 应用更新检查 ==========
    // 阿里云服务器上 update.json 的完整地址，返回格式见 docs/更新检查配置.md
    // TODO: 部署后替换为你自己的服务器地址
    const val UPDATE_CHECK_URL = "http://your-aliyun-server.com/update/app_update.json"
}
