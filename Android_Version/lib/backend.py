import requests
import time
import random
import string
import base64
import json
import hashlib
import urllib3
from Crypto.PublicKey import RSA
from Crypto.Signature import pkcs1_15
from Cryptodome.Hash import SHA256
from Crypto.Cipher import DES3, PKCS1_v1_5
from Crypto.Util.Padding import pad

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

CHANNEL = "02"
APP_VER_NO = "3.1.7"
BASE_URL = 'https://app.hzgh.org.cn'

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

SIGN_KEY_NEW = "zSw3MLRV7VuwT!*G"
ENCRYPT_KEYS = [
    "login_name", "login_auth_code", "auth_code", "pwd", "password", "newpwd",
    "amt", "tr_amt", "sms_code", "total_amount", "account_no", "mob_data",
    "order_amt", "before_amt", "txn_amt", "tel", "mobile", "new_mobile",
    "code", "cert_no", "card_no", "reserve_mobile", "reply_tel", "card_bal",
    "bank_card_no", "car_no", "user_id", "invite_code", "imgAuthCode", "imgUniCode"
]
NO_SIGN_KEYS = [
    "answerContent", "surveyId", "content", "preContent", "img", "img1",
    "img2", "package", "codeUrl", "belong", "verCode"
]

ENDPOINTS = {
    'captcha': '/unionApp/interf/front/U/U067',
    'login': '/unionApp/interf/front/U/U004',
    'smsSend': '/unionApp/interf/front/SMS/SMS1',
    'smsLogin': '/unionApp/interf/front/U/U065',
    'signin': '/unionApp/interf/front/U/U042',
    'comment': '/unionApp/interf/front/AC/AC08',
    'query': '/unionApp/interf/front/U/U005',
    'exchange': '/unionApp/interf/front/OL/OL41',
    'qrToken': '/unionApp/interf/front/OL/OL82',
    'qrVisit': '/unionApp/interf/front/OP/OP80',
}

session = requests.Session()
session.headers.update({
    "Host": "app.hzgh.org.cn",
    "Connection": "keep-alive",
    "Accept": "application/json, text/plain, */*",
    "User-Agent": "okhttp/3.4.2",
    "Content-Type": "application/json;charset=UTF-8",
    "Accept-Encoding": "gzip, deflate"
})

def rand_str(n):
    return ''.join(random.choice(string.ascii_letters + string.digits) for _ in range(n))

def rsa_encrypt(pub_pem, s):
    key = RSA.importKey(pub_pem)
    cipher = PKCS1_v1_5.new(key)
    return base64.b64encode(cipher.encrypt(s.encode('utf-8'))).decode('utf-8')

def des3_ecb_encrypt(key24, plaintext):
    key_bytes = key24.encode('utf-8')
    cipher = DES3.new(key_bytes, DES3.MODE_ECB)
    padded = pad(plaintext.encode('utf-8'), DES3.block_size, style='pkcs7')
    return base64.b64encode(cipher.encrypt(padded)).decode('utf-8')

def rsa_sha256_sign(private_key_pem, data_string):
    key = RSA.import_key(private_key_pem)
    h = SHA256.new(data_string.encode('utf-8'))
    return base64.b64encode(pkcs1_15.new(key).sign(h)).decode('utf-8')

def pkcs7_unpad(data):
    return data[:-data[-1]]

def decrypt_data2(data2):
    rsa_enc = data2[:172]
    des_enc = data2[172:]
    rsa_bytes = base64.b64decode(rsa_enc)
    des_bytes = base64.b64decode(des_enc)
    rsakey = RSA.importKey(PRIVATE_KEY_PEM)
    a = PKCS1_v1_5.new(rsakey).decrypt(rsa_bytes, None).decode()
    key = ("HTt0Hzsu" + a).encode()
    iv = a[:8].encode()
    decrypted = DES3.new(key, DES3.MODE_CBC, iv).decrypt(des_bytes)
    return pkcs7_unpad(decrypted).decode()

def build_payload(raw):
    payload = {}
    for k, v in raw.items():
        if v is not None and v != '':
            payload[k] = v
    m = rand_str(24).upper()
    payload['dec_key'] = rsa_encrypt(ENCRYPTION_PUBLIC_KEY_PEM, m)
    for key in ENCRYPT_KEYS:
        if key in payload:
            payload[key] = des3_ecb_encrypt(m, str(payload[key]))
    keys_for_sign = [k for k in payload if k not in NO_SIGN_KEYS]
    values_concat = ''.join(str(payload[k]) for k in keys_for_sign)
    sign = rsa_sha256_sign(SIGNING_PRIVATE_KEY_PEM, values_concat + SIGN_KEY_NEW)
    payload['key'] = ','.join(keys_for_sign)
    payload['sign'] = sign
    return payload

def post_and_decrypt(path, payload):
    url = BASE_URL + path
    resp = session.post(url, json=payload, verify=False)
    data = resp.json()
    if 'data2' not in data:
        raise Exception('响应缺少 data2: ' + resp.text[:200])
    decrypted = decrypt_data2(data['data2'])
    return json.loads(decrypted)

def get_captcha():
    payload = build_payload({
        'channel': CHANNEL,
        'app_ver_no': APP_VER_NO,
        'timestamp': str(int(time.time() * 1000)),
        'term_sys_ver': '12',
        'root': '0',
        'term_sys': '2',
        'model': '24031PN0DC',
        'term_id': '42e85afdd7e346e5',
        'trcode': 'U/U067'
    })
    return post_and_decrypt(ENDPOINTS['captcha'], payload)

def send_sms(captcha_data, phone, img_auth_code):
    raw = {
        'channel': CHANNEL,
        'app_ver_no': APP_VER_NO,
        'timestamp': str(int(time.time() * 1000)),
        'term_sys_ver': '12',
        'root': '0',
        'term_sys': '2',
        'model': '24031PN0DC',
        'login_name': phone,
        'mobile': phone,
        'imgUniCode': captcha_data['imgUniCode'],
        'imgAuthCode': img_auth_code.strip(),
        'sms_type': '10'
    }
    payload = build_payload(raw)
    return post_and_decrypt(ENDPOINTS['smsSend'], payload)

def login_u065(phone, auth_code):
    raw = {
        'channel': CHANNEL,
        'app_ver_no': APP_VER_NO,
        'timestamp': str(int(time.time() * 1000)),
        'term_sys_ver': '12',
        'root': '0',
        'term_sys': '2',
        'model': '24031PN0DC',
        'term_id': '42e85afdd7e346e5',
        'login_name': phone,
        'auth_code': auth_code.strip()
    }
    payload = build_payload(raw)
    return post_and_decrypt(ENDPOINTS['smsLogin'], payload)

def login_u004_with_code(captcha_data, phone, password, img_auth_code):
    password_md5 = hashlib.md5(password.encode('utf-8')).hexdigest()
    raw = {
        'channel': CHANNEL,
        'app_ver_no': APP_VER_NO,
        'timestamp': str(int(time.time() * 1000)),
        'term_sys_ver': '12',
        'root': '0',
        'term_sys': '2',
        'model': '24031PN0DC',
        'term_id': '42e85afdd7e346e5',
        'login_name': phone,
        'pwd': password_md5,
        'imgUniCode': captcha_data['imgUniCode'],
        'imgAuthCode': img_auth_code.strip()
    }
    payload = build_payload(raw)
    return post_and_decrypt(ENDPOINTS['login'], payload)

def get_qr_token(user_id, ses_id):
    raw = {
        'channel': CHANNEL,
        'app_ver_no': APP_VER_NO,
        'timestamp': str(int(time.time() * 1000)),
        'user_id': user_id,
        'ses_id': ses_id
    }
    payload = build_payload(raw)
    result = post_and_decrypt(ENDPOINTS['qrToken'], payload)
    if result.get('result') == '0' and 'data' in result:
        return result['data'].get('token', '')
    raise Exception(result.get('msg', '获取 token 失败'))

def record_qr_visit(user_id):
    raw = {
        'channel': CHANNEL,
        'app_ver_no': APP_VER_NO,
        'timestamp': str(int(time.time() * 1000)),
        'user_id': user_id,
        'icon_id': '92',
        'type': '2'
    }
    payload = build_payload(raw)
    try:
        post_and_decrypt(ENDPOINTS['qrVisit'], payload)
    except:
        pass

def get_qr_code(token):
    ts = str(int(time.time() * 1000))
    global_seq = '2500' + ''.join(random.choices('0123456789abcdef', k=14))
    payload = {
        'latitude': None,
        'longitude': None,
        'version': '1.0.0',
        'isImage': '0',
        'timestamp': ts,
        'globalSeq': global_seq
    }
    headers = {
        'Content-Type': 'application/json;charset=UTF-8',
        'token': token
    }
    resp = requests.post(
        'https://hzcode.96225.com/hzcitizencodeengine/codeEngine/apply',
        json=payload, headers=headers, verify=False
    )
    data = resp.json()
    if data.get('respCode') != '00':
        raise Exception(data.get('respDesc', '获取乘车码失败'))
    return data.get('data', {})

def run_exchange_once(login_name, user_id, ses_id, exchange_id):
    raw = {
        'channel': CHANNEL,
        'app_ver_no': APP_VER_NO,
        'timestamp': str(int(time.time() * 1000)),
        'login_name': login_name,
        'user_id': user_id,
        'ses_id': ses_id,
        'exchange_id': str(exchange_id)
    }
    payload = build_payload(raw)
    result = post_and_decrypt(ENDPOINTS['exchange'], payload)
    return json.dumps(result, ensure_ascii=False)

def run_daily_task(login_name, ses_id, log_callback):
    def exec(path, extra):
        raw = {
            'channel': CHANNEL,
            'app_ver_no': '3.1.4',
            'timestamp': str(int(time.time() * 1000)),
            'login_name': login_name,
            'ses_id': ses_id,
        }
        raw.update(extra)
        payload = build_payload(raw)
        result = post_and_decrypt(path, payload)
        log_callback(json.dumps(result, ensure_ascii=False))

    log_callback('=== 开始每日任务 ===')
    exec(ENDPOINTS['signin'], {'type': '1'})
    for i in range(1, 4):
        exec(ENDPOINTS['signin'], {'type': '5'})
        log_callback(f'第{i}次签到完成')
    exec(ENDPOINTS['comment'], {
        'related_id': '1232',
        'content_type': '1',
        'oper_type': '0',
        'suffix': 'png',
        'content': '好'
    })
    exec(ENDPOINTS['query'], {})
    log_callback('=== 每日任务完成 ===')
