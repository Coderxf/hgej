"""
hz_api.py - 杭工e家接口封装（Web 版后端）

从 Login.py / AutoTicket.py 参数化移植：
- 无全局会话变量，凭据（login_name/ses_id/user_id）与设备信息按调用传入
- 每次调用独立构造 payload（随机 3DES 密钥 → RSA 封装 → 敏感字段加密 → SHA256withRSA 签名）
- 设备字段按调用注入：手机网页端上报真实机型，桌面端回退抓包默认值
- IPv4 优先解析：目标域名 AAAA 记录在前而阿里云 ECS 无 IPv6 出口时避免连接黑洞
"""

import base64
import hashlib
import json
import logging
import random
import re
import socket
import string
import time

import requests
# 统一使用 pycryptodomex（Cryptodome 命名空间），服务器无需安装 pycryptodome
from Cryptodome.Cipher import DES3, PKCS1_v1_5
from Cryptodome.Hash import SHA256
from Cryptodome.PublicKey import RSA
from Cryptodome.Signature import pkcs1_15
from Cryptodome.Util.Padding import pad, unpad

# ---------------- 基础常量 ----------------
BASE_URL = "https://app.hzgh.org.cn"
QR_BASE_URL = "https://hzcode.96225.com"

EP = {
    "captcha": "/unionApp/interf/front/U/U067",
    "login": "/unionApp/interf/front/U/U004",
    "smsSend": "/unionApp/interf/front/SMS/SMS1",
    "smsLogin": "/unionApp/interf/front/U/U065",
    "dailyLogin": "/unionApp/interf/front/U/U042",
    "signin": "/unionApp/interf/front/U/U042",
    "comment": "/unionApp/interf/front/AC/AC08",
    "query": "/unionApp/interf/front/U/U005",
    "exchange": "/unionApp/interf/front/OL/OL41",
    "qrToken": "/unionApp/interf/front/OL/OL82",
    "qrVisit": "/unionApp/interf/front/OP/OP80",
    "subwayTickets": "/unionApp/interf/front/OL/OL83",
    "qrApply": "/hzcitizencodeengine/codeEngine/apply",
}

CHANNEL = "02"
APP_VER_NO = "3.1.7"
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

NO_SIGN_KEYS = {
    "answerContent", "surveyId", "content", "preContent", "img", "img1",
    "img2", "package", "codeUrl", "belong", "verCode",
}

# 设备字段：网页端上报真实机型，缺失/非法时回退到真实抓包值（思路.md:534/549）
DEFAULT_MODEL = "23127PN0CC"
DEFAULT_TERM_ID = "42e85afdd7e346e5"
_MODEL_RE = re.compile(r"^[\w\-+/ .]{1,40}$")
_TERM_ID_RE = re.compile(r"^[0-9a-f]{16}$")

CONNECT_TIMEOUT = 8   # 首个地址不通尽快切换下一个
READ_TIMEOUT = 20

log = logging.getLogger("hz_api")


# ---------------- IPv4 优先解析 ----------------
_orig_getaddrinfo = socket.getaddrinfo


def _ipv4_first_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    res = _orig_getaddrinfo(host, port, family, type, proto, flags)
    return sorted(res, key=lambda ai: 1 if ai[0] == socket.AF_INET6 else 0)


socket.getaddrinfo = _ipv4_first_getaddrinfo

_local_sessions = None  # 线程本地 Session，保持连接复用且规避 Session 线程安全问题


def _session():
    global _local_sessions
    import threading

    if _local_sessions is None:
        _local_sessions = threading.local()
    s = getattr(_local_sessions, "session", None)
    if s is None:
        s = requests.Session()
        _local_sessions.session = s
    return s


# ---------------- 异常 ----------------
class HzApiError(Exception):
    """上游接口失败（网络/解密/无 data2）"""


# ---------------- 设备信息 ----------------
def sanitize_device(device) -> tuple:
    """校验前端上报的设备信息，非法时回退抓包默认值。返回 (model, term_id)"""
    model, term_id = DEFAULT_MODEL, DEFAULT_TERM_ID
    if isinstance(device, dict):
        m = str(device.get("model") or "").strip()
        t = str(device.get("term_id") or "").strip().lower()
        if _MODEL_RE.match(m):
            model = m
        if _TERM_ID_RE.match(t):
            term_id = t
    return model, term_id


def _user_agent(model: str, wv: bool = False) -> str:
    mid = f"; {model}; wv" if wv else f"; {model}"
    return (
        f"Mozilla/5.0 (Linux; Android 14{mid}) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Version/4.0 Chrome/110.0.5481.154 Mobile Safari/537.36;unionApp;HZGH"
    )


# ---------------- 核心加解密（移植自 Login.py） ----------------
def _rand_key24() -> str:
    return "".join(random.choice(string.ascii_letters + string.digits) for _ in range(24)).upper()


def _rsa_encrypt(key24: str) -> str:
    rsakey = RSA.import_key(ENCRYPTION_PUBLIC_KEY_PEM)
    cipher = PKCS1_v1_5.new(rsakey)
    return base64.b64encode(cipher.encrypt(key24.encode("utf-8"))).decode("utf-8")


def _des3_ecb_encrypt(key24: str, plaintext: str) -> str:
    cipher = DES3.new(key24.encode("utf-8"), DES3.MODE_ECB)
    padded = pad(plaintext.encode("utf-8"), DES3.block_size, style="pkcs7")
    return base64.b64encode(cipher.encrypt(padded)).decode("utf-8")


def _rsa_sha256_sign(data_string: str) -> str:
    key = RSA.import_key(SIGNING_PRIVATE_KEY_PEM)
    h = SHA256.new(data_string.encode("utf-8"))
    return base64.b64encode(pkcs1_15.new(key).sign(h)).decode("utf-8")


def _decrypt_data2(data2: str):
    """data2 = RSA密文(172字符) + 3DES-CBC密文；解密失败返回 None"""
    try:
        if len(data2) <= 172:
            return None
        rsa_enc_bytes = base64.b64decode(data2[:172])
        des_enc_bytes = base64.b64decode(data2[172:])
        rsakey = RSA.import_key(PRIVATE_KEY_PEM)
        a_bytes = PKCS1_v1_5.new(rsakey).decrypt(rsa_enc_bytes, None)
        a = a_bytes.decode()
        key = ("HTt0Hzsu" + a).encode()
        iv = a[:8].encode()
        decrypted = DES3.new(key, DES3.MODE_CBC, iv).decrypt(des_enc_bytes)
        return json.loads(unpad(decrypted, DES3.block_size, style="pkcs7").decode())
    except Exception as e:
        log.warning("data2 解密失败: %s", e)
        return None


# ---------------- 通用签名请求 ----------------
def _signed_post(endpoint, payload, device=None, encrypt_keys=("login_name", "user_id"),
                 no_sign_keys=NO_SIGN_KEYS):
    """按协议构造加密签名请求并解密 data2。失败抛 HzApiError。"""
    model, term_id = sanitize_device(device)
    p = dict(payload)
    p["channel"] = CHANNEL
    p["app_ver_no"] = APP_VER_NO
    p["timestamp"] = str(int(time.time() * 1000))
    p.setdefault("term_sys_ver", "12")
    p.setdefault("root", "0")
    p.setdefault("term_sys", "2")
    if "model" in p:
        p["model"] = model
    if "term_id" in p:
        p["term_id"] = term_id

    m = _rand_key24()
    p["dec_key"] = _rsa_encrypt(m)
    for k in encrypt_keys:
        if k in p:
            p[k] = _des3_ecb_encrypt(m, str(p[k]))

    sign_keys = [k for k in p if k not in no_sign_keys]
    string_to_sign = "".join(str(p[k]) for k in sign_keys) + SIGN_KEY_NEW
    p["key"] = ",".join(sign_keys)
    p["sign"] = _rsa_sha256_sign(string_to_sign)

    headers = {
        "Host": "app.hzgh.org.cn",
        "Accept": "application/json, text/plain, */*",
        "User-Agent": _user_agent(model),
        "Content-Type": "application/json;charset=UTF-8",
        "Origin": "https://app.hzgh.org.cn:8123",
        "X-Requested-With": "com.zjte.hanggongefamily",
        "Referer": "https://app.hzgh.org.cn:8123/",
    }

    try:
        resp = _session().post(BASE_URL + endpoint, json=p, headers=headers,
                               timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), verify=True)
    except requests.RequestException as e:
        raise HzApiError(f"上游请求失败: {e.__class__.__name__}")

    try:
        body = resp.json()
    except ValueError:
        raise HzApiError(f"上游响应异常 (HTTP {resp.status_code})")
    data = _decrypt_data2(body.get("data2")) if "data2" in body else None
    if data is None:
        # 失败响应通常无 data2，把原始 msg 透出（不含敏感字段）
        raise HzApiError(body.get("msg") or f"上游无有效数据 (HTTP {resp.status_code})")
    return data


def _qr_apply(token: str, model: str) -> dict:
    """hzcode 乘车码申请：普通 JSON + token 请求头，无 hzgh 加密签名流程（照抓包）"""
    global_seq = "2500" + "".join(random.choice("0123456789abcdef") for _ in range(14))
    payload = {
        "latitude": None,
        "longitude": None,
        "version": "1.0.0",
        "isImage": "1",
        "timestamp": str(int(time.time() * 1000)),
        "globalSeq": global_seq,
    }
    headers = {
        "Host": "hzcode.96225.com",
        "Accept": "application/json, text/plain, */*",
        "User-Agent": _user_agent(model, wv=True),
        "Content-Type": "application/json;charset=UTF-8",
        "Origin": QR_BASE_URL,
        "X-Requested-With": "com.zjte.hanggongefamily",
        "Referer": QR_BASE_URL + "/hzcitizencodeh5/",
        "token": token,
    }
    try:
        resp = _session().post(QR_BASE_URL + EP["qrApply"], json=payload, headers=headers,
                               timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), verify=True)
    except requests.RequestException as e:
        raise HzApiError(f"乘车码请求失败: {e.__class__.__name__}")
    try:
        wrapper = resp.json()
    except ValueError:
        raise HzApiError(f"乘车码响应异常 (HTTP {resp.status_code})")
    data = wrapper.get("data") if isinstance(wrapper, dict) else None
    if not data:
        desc = wrapper.get("respDesc") if isinstance(wrapper, dict) else None
        raise HzApiError(f"乘车码响应缺少 data: {desc or resp.status_code}")
    return data


# ---------------- 登录类 ----------------
def get_captcha(device=None) -> dict:
    """U067 图形验证码：返回含 img(base64)/imgUniCode 的字典"""
    return _signed_post(
        EP["captcha"],
        {"model": "", "term_id": "", "trcode": "U/U067"},
        device=device, encrypt_keys=(),
    )


def password_login(login_name, password, img_auth_code, img_uni_code, device=None) -> dict:
    """U004 密码登录（密码 MD5 后传输）"""
    return _signed_post(
        EP["login"],
        {
            "model": "", "term_id": "",
            "login_name": login_name,
            "pwd": hashlib.md5(password.encode()).hexdigest(),
            "imgUniCode": img_uni_code,
            "imgAuthCode": img_auth_code.strip(),
        },
        device=device,
        encrypt_keys=("login_name", "pwd", "imgUniCode", "imgAuthCode"),
    )


def send_sms(login_name, img_auth_code, img_uni_code, device=None) -> dict:
    """SMS1 发送登录短信（结构照抓包：无 term_id）"""
    return _signed_post(
        EP["smsSend"],
        {
            "model": "",
            "login_name": login_name,
            "mobile": login_name,
            "imgUniCode": img_uni_code,
            "imgAuthCode": img_auth_code.strip(),
            "sms_type": "10",
        },
        device=device,
        encrypt_keys=("login_name", "mobile", "imgUniCode", "imgAuthCode"),
    )


def sms_login(login_name, auth_code, device=None) -> dict:
    """U065 短信验证码登录"""
    return _signed_post(
        EP["smsLogin"],
        {"model": "", "term_id": "", "login_name": login_name, "auth_code": auth_code.strip()},
        device=device,
        encrypt_keys=("login_name", "auth_code"),
    )


# ---------------- 会话与任务 ----------------
def query_user_info(login_name, ses_id) -> dict:
    """U005 用户信息/会话校验：result != "0" 即会话失效"""
    return _signed_post(
        EP["query"],
        {"login_name": login_name, "ses_id": ses_id},
    )


def daily_task_workflow(login_name, ses_id) -> list:
    """每日任务链：登录 → 签到×3 → 评论 → 查积分。返回日志列表。"""
    logs = []

    def step(name, endpoint, extra):
        try:
            data = _signed_post(endpoint, {"login_name": login_name, "ses_id": ses_id, **extra})
            logs.append(f"{name}: {data.get('msg') or '无响应'}")
            return data
        except HzApiError as e:
            logs.append(f"{name}: 失败 {e}")
            return None

    step("登录", EP["dailyLogin"], {"type": "1"})
    time.sleep(1)
    for i in range(1, 4):
        step(f"第{i}次签到", EP["signin"], {"type": "5"})
        time.sleep(1)
    step("评论", EP["comment"], {
        "related_id": "1232", "content_type": "1", "oper_type": "0",
        "suffix": "png", "content": "好",
    })
    step("积分查询", EP["query"], {})
    return logs


def exchange_once(login_name, user_id, ses_id, exchange_id) -> dict:
    """OL41 单次兑换"""
    return _signed_post(
        EP["exchange"],
        {
            "login_name": login_name,
            "user_id": user_id,
            "ses_id": ses_id,
            "exchange_id": str(exchange_id),
        },
    )


# ---------------- 乘车码 / 地铁券 ----------------
def get_green_code(login_name, ses_id, user_id=None, device=None) -> dict:
    """OL82 取 token → OP80 记录访问 → hzcode 取码。返回乘车码数据。"""
    uid = user_id or login_name
    token_resp = _signed_post(EP["qrToken"], {"user_id": uid, "ses_id": ses_id},
                              encrypt_keys=("user_id",))
    if token_resp.get("result") != "0":
        raise HzApiError(f"获取乘车码token失败: {token_resp.get('msg') or '未知'}")
    token = (token_resp.get("data") or {}).get("token")
    if not token:
        raise HzApiError("乘车码token响应缺少 token 字段")

    try:
        _signed_post(EP["qrVisit"], {"user_id": uid, "icon_id": "92", "type": "2"},
                     encrypt_keys=("user_id",))
    except HzApiError:
        pass  # 访问记录失败不影响取码

    return _qr_apply(token, sanitize_device(device)[0])


def subway_tickets(login_name, ses_id, award_type="1") -> dict:
    """OL83 地铁券统计与记录。字段：use_num/expire_num/num_2/4/6/list"""
    return _signed_post(
        EP["subwayTickets"],
        {
            "login_name": login_name,
            "user_id": login_name,
            "ses_id": ses_id,
            "use_state": "1",
            "award_type": str(award_type),
            "page_size": 10,
            "page_num": 1,
        },
    )


# ---------------- 服务器时间 ----------------
def server_time_offset_ms():
    """读响应头 Date + 半 RTT 补偿，返回 (服务器毫秒时间, 本地偏移毫秒)。失败返回 (None, None)"""
    try:
        start = time.monotonic()
        resp = requests.get(BASE_URL, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), verify=True)
        rtt = (time.monotonic() - start) * 1000
        date_header = resp.headers.get("Date")
        if not date_header:
            return None, None
        from email.utils import parsedate_to_datetime
        server_ms = parsedate_to_datetime(date_header).timestamp() * 1000
        server_ms += rtt / 2
        return server_ms, round(server_ms - time.time() * 1000)
    except Exception as e:
        log.warning("服务器时间同步失败: %s", e)
        return None, None
