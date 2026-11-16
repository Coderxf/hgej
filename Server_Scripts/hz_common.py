"""
hz_common.py - 杭工e家接口公共层（服务器脚本版）

签到/抢票两个脚本共用：加解密、签名、通用请求、设备 UA 池。
环境变量：
  HANGGONG_V           App 版本号（默认 3.1.7）
  HANGGONG_UA_INDEX    设备 UA 池序号 0~9（默认 0），同一账号的两个脚本必须配同一个值
"""

import base64
import hashlib
import json
import os
import random
import re
import socket
import string
import time

import requests
# 统一使用 pycryptodomex（Cryptodome 命名空间）——严禁混用 Crypto 与 Cryptodome 的
# 密钥对象与加解密器（跨库密钥对象在部分版本组合下会解密出垃圾数据）
from Cryptodome.Cipher import DES3, PKCS1_v1_5
from Cryptodome.Hash import SHA256
from Cryptodome.PublicKey import RSA
from Cryptodome.Signature import pkcs1_15
from Cryptodome.Util.Padding import pad, unpad

# ================= 基础配置 =================
BASE_URL = 'https://app.hzgh.org.cn'
ENDPOINTS = {
    'login': '/unionApp/interf/front/U/U042',
    'signin': '/unionApp/interf/front/U/U042',
    'comment': '/unionApp/interf/front/AC/AC08',
    'query': '/unionApp/interf/front/U/U005',
    'exchange': '/unionApp/interf/front/OL/OL41',
}

CHANNEL = "02"
APP_VER_NO = os.environ.get("HANGGONG_V", "3.1.7")   # 全脚本统一，杜绝同一"设备"自称多个版本
SIGN_KEY_NEW = "zSw3MLRV7VuwT!*G"

ENCRYPTION_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC7yWoQaojBBqKI2H0j4e8ZeX/n1yip6hxrxSVth5F5n1JJ/B3liPMdz6K1chNLFTAcbI7hTL9KkphP9yQ+bPYD68Ajrt/DFrW679Zi1CoeetHVrM4sF68lYarGXwnSlKloaPWnI4Ch9cSqIvIOInlpeJqYPlJ8ZJvGCmbQoM6bewIDAQAB
-----END PUBLIC KEY-----"""

SIGNING_PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIICeAIBADANBgkqhkiG9w0BAQEFAASCAmIwggJeAgEAAoGBAJ+C8Z9awsGU8DeB
pq47p+pVBgIxWr9epYE5lTrVwoTvOv7dOBTsNgYPgDqFLbU8eZsV26DOvgd4TC5t
ZUWF7WbAleOcxvwA143XTBpZEeDx6who8KiW1WBKUwkeEfXZvOWhN2d+8GlCjvJu
2J4yNGEXScQEIWb+ofE4Pd4yPkkzAgMBAAECgYB0Tzu18a0vEFX0c1JBm3g98w81
jB1aiz3tMzqwMuvqmLIQ4uegwfhGhQkAItoIW/dj8RU7dWS096+87sG4ZwaKCv/S
mT1CibqmSATrX6YNIFU4uXsZzMREJxmZi+V5AllT9DWBG5YjKgrGfWjL0Rq10Zvx
YMTdjO+SbqDIjVoc+QJBAOrMXRO6G349NpLvo1QPevxIykKNKhr5Qkjv4oVydoVo
HW6iMU30PhrBqBYla+K8W+xyeqrjd9ucDQFW/Z2+hD8CQQCt6jz4o7qadQM0giko
BsgWwp7teyZI/8ZH5htrKZwDJzUe6LuM9xjDeXAqqjNjQrDL7M+6T7ZwMmK3UN3b
oe4NAkEA6ioGabYh1TSXSNNVwG/v58twbA78/wm34aXb89rD+Shssflv0p7TkTuxt
uR7RBU2WAmT7PoOfyaSkdN/++IVYQJBAJ/klCvQc/YfkFPNO0N2gK0UP4N8zmUc
6tIdh6XNeocXm+oP9KaUYusMkghXtKkUnnDOBul28fdTC5kYOvD7fl0CQQDLIYfo
8MSMgcFkBH1wRUbhjVv31bk8+4G9a+h7UkLdLtch5qPsS7bsFCyszqEYjhYtQ278
Q20lSzaIsom0Q3ai
-----END PRIVATE KEY-----"""

PRIVATE_KEY_PEM = """-----BEGIN RSA PRIVATE KEY-----
MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAIOBMtf2AIYQlrNy
/lVPHx4R/LKI+Vtk3bKmzID8vdVnh/4WA3lczqfejM10Xfy3sNe4l5EeQTvnDgUH
bIFK8FyJRpvypAmS9oyW6uwGTjZEu5Y6hsSxiGAOG5ZOlH8vOSfuaAkZ+iUlqifP
E3ZOmHkqGzmukg4wCRaPLx5ioq8zAgMBAAECgYAgLOVmx677HmXxBCrMbq57agU9
HZx9SyGfS4Zv7Ob5pvo0Jei1sgpyMlabEmTIp50iOu0CubdWU8MvYdCfldlXQLW7
cjk8N1NyGQLFd2fJ03a7gGWnwwEdPoNTpSHnB+mDL9l7MVjion5fLojzq9Pz1gMK
L01I2TfZBDL4m6EbgQJBAMfgrMKtj7f40GA3qp/y/9/eBCAu8PbtFmtATLMQRf4t
Ghjvn349x1b6FZj8RiaRBSrq0Owjrdo5TUxgfS7dz3MCQQCobdWk2SQhRlqEHfFE
ro/8ab6gn3GhBDzzKvNjhKr2MO6JWqs+Vr+/P9uYpA+G+rv74uVIGWhjuNtI5+/6
9DFBAkAJOQS/tuJ6yrBSwD7PQpcr7UKjeYcE3cu7ByyC1q1kHRCnNedWG+Omz8NP
W9Sg0vA6GrupKbxL5Xj7nTgpgXKhAkBIVlvioAvfaqrngUClAd//RZ9EtxYDVKGk
wnaj8E/Iyr04KsPPU0ypJBD5XsT4cOmZxho5PAhUhAlSJ6MvAf/BAkA64ieVhtQA
1KV0pSSEJMnbPlZe+yBYGTWLMaG2zL0kKEhIs2fIHbVhLFQ8TkO5oH+mhxuuXI5+
nVU2G0dqUl6D
-----END RSA PRIVATE KEY-----"""

ENCRYPT_KEYS = ["login_name", "user_id"]
NO_SIGN_KEYS = [
    "answerContent", "surveyId", "content", "preContent", "img", "img1",
    "img2", "package", "codeUrl", "belong", "verCode"
]

# ============ 设备 UA 池（10 款真实在售机型，型号码已核实） ============
# 同一账号固定用一个序号（写入 env 长期不变），不要每次随机轮换；
# 签到与抢票脚本必须使用同一序号，保持"同一台手机"的自洽性
DEVICE_UA_POOL = [
    "Mozilla/5.0 (Linux; Android 14; 23127PN0CC Build/UKQ1.231003.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.86 Mobile Safari/537.36;unionApp;HZGH",   # 小米14
    "Mozilla/5.0 (Linux; Android 14; 23013RK75C Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.81 Mobile Safari/537.36;unionApp;HZGH",   # Redmi K60
    "Mozilla/5.0 (Linux; Android 14; BVL-AN00 Build/UKQ1.231125.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.104 Mobile Safari/537.36;unionApp;HZGH",     # 荣耀 Magic6
    "Mozilla/5.0 (Linux; Android 14; PHZ110 Build/UKQ1.230917.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.121 Mobile Safari/537.36;unionApp;HZGH",       # OPPO Find X7
    "Mozilla/5.0 (Linux; Android 14; V2310A Build/UKQ1.231003.003; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.90 Mobile Safari/537.36;unionApp;HZGH",        # vivo X100
    "Mozilla/5.0 (Linux; Android 14; SM-S9210 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.71 Mobile Safari/537.36;unionApp;HZGH",      # 三星 S24
    "Mozilla/5.0 (Linux; Android 14; PJZ110 Build/UKQ1.231105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.104 Mobile Safari/537.36;unionApp;HZGH",       # 一加 12
    "Mozilla/5.0 (Linux; Android 13; 2211133C Build/TKQ1.220905.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.134 Mobile Safari/537.36;unionApp;HZGH",     # 小米13
    "Mozilla/5.0 (Linux; Android 13; 2312DRA50C Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/127.0.6533.122 Mobile Safari/537.36;unionApp;HZGH",   # Redmi Note 13 Pro
    "Mozilla/5.0 (Linux; Android 14; 24053RN09D Build/UKQ1.230917.006; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36;unionApp;HZGH",    # Redmi Turbo 3
]
_UA_INDEX = int(os.environ.get("HANGGONG_UA_INDEX", "0"))
USER_AGENT = DEVICE_UA_POOL[_UA_INDEX % len(DEVICE_UA_POOL)]

# ============ IPv4 优先解析 ============
# 域名 AAAA 记录在前而服务器无 IPv6 出口时，避免连接黑洞到超时
_orig_getaddrinfo = socket.getaddrinfo


def _ipv4_first_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    res = _orig_getaddrinfo(host, port, family, type, proto, flags)
    return sorted(res, key=lambda ai: 1 if ai[0] == socket.AF_INET6 else 0)


socket.getaddrinfo = _ipv4_first_getaddrinfo

# ============ 请求 Session ============
session = requests.Session()
session.headers.update({
    "Host": "app.hzgh.org.cn",
    "Connection": "keep-alive",
    "Accept": "application/json, text/plain, */*",
    "User-Agent": USER_AGENT,
    "Content-Type": "application/json;charset=UTF-8",
    "Origin": "https://app.hzgh.org.cn:8123",
    "X-Requested-With": "com.zjte.hanggongefamily",
    "Referer": "https://app.hzgh.org.cn:8123/",
    "Accept-Encoding": "gzip, deflate",
    "Accept-Language": "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
})
REQUEST_TIMEOUT = (8, 20)   # 连接 8s 快速切换，读取 20s


class HzApiError(Exception):
    """上游接口失败"""


# ============ 凭据同步中心（可选） ============
# 配置 HANGGONG_CRED_API 后，脚本启动时按 login_name（HANGGONG_USER_ID，唯一键）
# 到凭据中心拉取该账户的最新 ses_id（凭据由安卓 App / 网页版登录时自动上报），
# 彻底告别手工维护 HANGGONG_SES_ID_*；未配置则回退读环境变量里的 ses_id
CRED_API = os.environ.get("HANGGONG_CRED_API", "").rstrip("/")


def fetch_credential(login_name):
    """按 login_name（唯一键）拉取凭据中心里该账户的最新凭据。
    返回 (ses_id, name, ua)；中心未配置/账户不存在/网络失败返回 None。
    ua 为安卓端登录上报的真实设备 UA（脚本应采用它发起请求，
    使服务器看到的设备与登录设备一致）。"""
    if not CRED_API or not login_name:
        return None
    try:
        # POST+JSON 体而非 GET 查询串：login_name 是 Base64 串含 +/=，
        # 走查询串会被 URL 解码破坏（+ 变空格）
        resp = requests.post(
            CRED_API + "/api/credentials/get",
            json={"login_name": login_name},
            timeout=(8, 20), verify=True,
        )
        if resp.status_code == 404:
            return None
        data = resp.json()
        ses = data.get("ses_id")
        if not ses:
            return None
        return (ses, data.get("name") or "", data.get("ua") or "")
    except Exception as e:
        log_print(f"凭据中心拉取失败: {e.__class__.__name__}")
        return None


def resolve_ses_id(login_name, fallback_ses, acc_label=""):
    """解析账户可用 ses_id：凭据中心优先，回退环境变量。
    返回 (ses_id, 来源描述, ua)。ua 可能为空串（传统 env 模式无 UA）。"""
    remote = fetch_credential(login_name)
    if remote:
        ses, name, ua = remote
        who = name or (f"账户{acc_label}" if acc_label else login_name[:8])
        log_print(f"已从凭据中心拉取 {who} 的最新会话" + ("（含真实设备 UA）" if ua else ""))
        return ses, f"凭据中心({who})", ua
    if fallback_ses:
        log_print("凭据中心不可用，回退使用环境变量中的 ses_id")
        return fallback_ses, "环境变量", ""
    raise HzApiError("无法获取会话：凭据中心不可用且未配置 HANGGONG_SES_ID_*")


# ================= 日志回调 =================
log_callback = None


def set_log_callback(callback):
    global log_callback
    log_callback = callback


def log_print(*args, **kwargs):
    if log_callback:
        log_callback(' '.join(str(arg) for arg in args))
    else:
        print(*args, **kwargs)


# ================= 加解密 =================
def rand_str(n):
    return ''.join(random.choice(string.ascii_letters + string.digits) for _ in range(n))


def rsa_encrypt(pub_pem, s):
    cipher = PKCS1_v1_5.new(RSA.import_key(pub_pem))
    return base64.b64encode(cipher.encrypt(s.encode('utf-8'))).decode('utf-8')


def des3_ecb_pkcs7_encrypt(key24, plaintext):
    cipher = DES3.new(key24.encode('utf-8'), DES3.MODE_ECB)
    return base64.b64encode(
        cipher.encrypt(pad(plaintext.encode('utf-8'), DES3.block_size, style='pkcs7'))
    ).decode('utf-8')


def rsa_sha256_sign(private_key_pem, data_string):
    h = SHA256.new(data_string.encode('utf-8'))
    return base64.b64encode(pkcs1_15.new(RSA.import_key(private_key_pem)).sign(h)).decode('utf-8')


def pkcs7_unpad(data):
    return data[:-data[-1]]


def decrypt_data2(data2):
    """解密 data2；格式异常返回 None（不再抛异常崩溃）"""
    try:
        rsa_enc_bytes = base64.b64decode(data2[:172])
        des_enc_bytes = base64.b64decode(data2[172:])
        a = PKCS1_v1_5.new(RSA.import_key(PRIVATE_KEY_PEM)).decrypt(rsa_enc_bytes, None).decode()
        key = ("HTt0Hzsu" + a).encode()
        iv = a[:8].encode()
        decrypted = DES3.new(key, DES3.MODE_CBC, iv).decrypt(des_enc_bytes)
        return pkcs7_unpad(decrypted).decode()
    except Exception:
        return None


# ================= 通用签名请求 =================
def signed_post(endpoint, payload, encrypt_keys=None):
    """构造加密签名请求并解密 data2。失败抛 HzApiError（错误信息不含凭据）。

    注意：payload 字段与原版请求严格一致——不自动补 term_sys_ver/root/term_sys/
    model/term_id（U042/U005/OL41 等任务接口抓包中并无这些字段，多余字段会
    改变服务器响应格式导致解密失败）；登录类接口的设备字段由调用方显式传入。
    """
    encrypt_keys = ENCRYPT_KEYS if encrypt_keys is None else encrypt_keys
    p = {k: v for k, v in payload.items() if v is not None and v != ""}
    p["channel"] = CHANNEL
    p["app_ver_no"] = APP_VER_NO
    p["timestamp"] = int(time.time() * 1000)

    m = rand_str(24).upper()
    p["dec_key"] = rsa_encrypt(ENCRYPTION_PUBLIC_KEY_PEM, m)
    for k in encrypt_keys:
        if k in p:
            p[k] = des3_ecb_pkcs7_encrypt(m, str(p[k]))

    p_for_sign = {k: v for k, v in p.items() if k not in NO_SIGN_KEYS}
    string_to_sign = "".join(str(v) for v in p_for_sign.values()) + SIGN_KEY_NEW
    p["key"] = ",".join(p_for_sign.keys())
    p["sign"] = rsa_sha256_sign(SIGNING_PRIVATE_KEY_PEM, string_to_sign)

    try:
        resp = session.post(BASE_URL + endpoint, json=p, timeout=REQUEST_TIMEOUT, verify=True)
    except requests.RequestException as e:
        raise HzApiError(f"上游请求失败: {e.__class__.__name__}")
    try:
        body = resp.json()
    except ValueError:
        raise HzApiError(f"上游响应异常 (HTTP {resp.status_code})")
    if "data2" not in body:
        raise HzApiError(body.get("msg") or f"上游无 data2 (HTTP {resp.status_code})")
    decrypted = decrypt_data2(body["data2"])
    if decrypted is None:
        raise HzApiError("data2 解密失败")
    return json.loads(decrypted)


# ================= 业务封装 =================
def daily_task_workflow(login_name, ses_id):
    """每日任务链：登录 → 签到×3 → 评论 → 查积分。返回日志列表。"""
    logs = []

    def step(name, endpoint, extra, ek=None):
        try:
            data = signed_post(endpoint, {"login_name": login_name, "ses_id": ses_id, **extra},
                               encrypt_keys=ek)
            logs.append(f"{name}: {data.get('msg') or '无响应'}")
        except HzApiError as e:
            logs.append(f"{name}: 失败 {e}")

    step("登录", ENDPOINTS['login'], {"type": "1"})
    time.sleep(1)
    for i in range(1, 4):
        step(f"第{i}次签到", ENDPOINTS['signin'], {"type": "5"})
        time.sleep(1)
    step("评论", ENDPOINTS['comment'], {
        "related_id": "1232", "content_type": "1", "oper_type": "0",
        "suffix": "png", "content": "好",
    })
    step("积分查询", ENDPOINTS['query'], {})
    return logs


def exchange_once(login_name, user_id, ses_id, exchange_id):
    """OL41 单次兑换，返回结果 dict。"""
    return signed_post(
        ENDPOINTS['exchange'],
        {
            "login_name": login_name,
            "user_id": user_id,
            "ses_id": ses_id,
            "exchange_id": str(exchange_id),
        },
    )


def query_user_info(login_name, ses_id):
    """U005 会话校验：result != "0" 即失效。"""
    return signed_post(ENDPOINTS['query'], {"login_name": login_name, "ses_id": ses_id})
