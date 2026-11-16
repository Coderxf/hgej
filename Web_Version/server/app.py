"""
app.py - 杭工助手网页版后端

- 多静态口令授权：passcodes.json（热加载，增删口令无需重启），请求头 X-Site-Key
- 所有 /api 需口令；日志只记口令标签，不含 ses_id/密码
- 同进程托管 web/ 静态前端，单端口部署
"""

import hmac
import json
import logging
import os
import time

from flask import Flask, g, jsonify, request, send_from_directory
from werkzeug.exceptions import RequestEntityTooLarge

import hz_api

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PASSCODES_FILE = os.environ.get("PASSCODES_FILE", os.path.join(BASE_DIR, "passcodes.json"))
CREDENTIALS_FILE = os.environ.get("CREDENTIALS_FILE", os.path.join(BASE_DIR, "credentials.json"))
PORT = int(os.environ.get("PORT", "8090"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
app = Flask(__name__, static_folder=os.path.join(BASE_DIR, "..", "web"), static_url_path="")
app.config["MAX_CONTENT_LENGTH"] = 64 * 1024  # 请求体都很小，防滥用


# ---------------- 口令管理（热加载） ----------------
def _load_passcodes() -> dict:
    """读取口令文件 → {code: label}；文件缺失/损坏返回空表（全部拒绝）"""
    try:
        with open(PASSCODES_FILE, encoding="utf-8") as f:
            entries = json.load(f)
        out = {}
        for e in entries:
            if isinstance(e, dict) and e.get("code"):
                out[str(e["code"])] = str(e.get("label") or "")
        return out
    except FileNotFoundError:
        app.logger.warning("口令文件不存在: %s", PASSCODES_FILE)
    except Exception as e:
        app.logger.error("口令文件读取失败: %s", e)
    return {}


def _match_passcode(code: str):
    """恒时比较遍历，命中返回 label"""
    for k, label in _load_passcodes().items():
        if hmac.compare_digest(code, k):
            return label or "未命名用户"
    return None


def require_passcode(fn):
    from functools import wraps

    @wraps(fn)
    def wrapper(*args, **kwargs):
        label = _match_passcode(request.headers.get("X-Site-Key", ""))
        if label is None:
            return jsonify({"error": "口令无效或已被吊销"}), 401
        g.label = label
        return fn(*args, **kwargs)

    return wrapper


def _body() -> dict:
    b = request.get_json(silent=True)
    return b if isinstance(b, dict) else {}


def _err(e: Exception, status=502):
    return jsonify({"error": str(e)}), status


# ---------------- 授权 ----------------
@app.post("/api/unlock")
def api_unlock():
    """校验口令，返回标签（前端据此记住授权）"""
    code = str(_body().get("code") or request.headers.get("X-Site-Key") or "")
    label = _match_passcode(code)
    if label is None:
        app.logger.warning("口令校验失败 (来源 %s)", request.remote_addr)
        return jsonify({"error": "口令无效或已被吊销"}), 401
    app.logger.info("[%s] 授权通过", label)
    return jsonify({"ok": True, "label": label})


# ---------------- 登录类 ----------------
@app.post("/api/captcha")
@require_passcode
def api_captcha():
    try:
        data = hz_api.get_captcha(_body().get("device"))
        return jsonify({"img": data.get("img"), "imgUniCode": data.get("imgUniCode")})
    except hz_api.HzApiError as e:
        return _err(e)


@app.post("/api/login/password")
@require_passcode
def api_login_password():
    b = _body()
    for k in ("login_name", "password", "imgAuthCode", "imgUniCode"):
        if not str(b.get(k) or "").strip():
            return jsonify({"error": "参数不完整"}), 400
    try:
        data = hz_api.password_login(
            str(b["login_name"]).strip(), str(b["password"]),
            str(b["imgAuthCode"]), str(b["imgUniCode"]), b.get("device"),
        )
    except hz_api.HzApiError as e:
        return _err(e)
    if data.get("result") != "0":
        return jsonify({"error": data.get("msg") or "登录失败", "result": data.get("result")}), 400
    app.logger.info("[%s] 密码登录成功", g.label)
    return jsonify({
        "login_name": data.get("login_name"),
        "user_id": data.get("user_id"),
        "ses_id": data.get("ses_id"),
    })


@app.post("/api/login/sms-send")
@require_passcode
def api_login_sms_send():
    b = _body()
    for k in ("login_name", "imgAuthCode", "imgUniCode"):
        if not str(b.get(k) or "").strip():
            return jsonify({"error": "参数不完整"}), 400
    try:
        data = hz_api.send_sms(
            str(b["login_name"]).strip(), str(b["imgAuthCode"]), str(b["imgUniCode"]),
            b.get("device"),
        )
    except hz_api.HzApiError as e:
        return _err(e)
    if data.get("result") != "0":
        return jsonify({"error": data.get("msg") or "发送失败"}), 400
    app.logger.info("[%s] 短信验证码已发送", g.label)
    return jsonify({"ok": True})


@app.post("/api/login/sms")
@require_passcode
def api_login_sms():
    b = _body()
    for k in ("login_name", "authCode"):
        if not str(b.get(k) or "").strip():
            return jsonify({"error": "参数不完整"}), 400
    try:
        data = hz_api.sms_login(str(b["login_name"]).strip(), str(b["authCode"]), b.get("device"))
    except hz_api.HzApiError as e:
        return _err(e)
    if data.get("result") != "0":
        return jsonify({"error": data.get("msg") or "登录失败"}), 400
    app.logger.info("[%s] 短信登录成功", g.label)
    return jsonify({
        "login_name": data.get("login_name"),
        "user_id": data.get("user_id"),
        "ses_id": data.get("ses_id"),
    })


# ---------------- 主功能 ----------------
@app.post("/api/user-info")
@require_passcode
def api_user_info():
    b = _body()
    if not str(b.get("login_name") or "") or not str(b.get("ses_id") or ""):
        return jsonify({"error": "参数不完整"}), 400
    try:
        data = hz_api.query_user_info(str(b["login_name"]), str(b["ses_id"]))
    except hz_api.HzApiError as e:
        return _err(e)
    if data.get("result") != "0":
        return jsonify({"error": data.get("msg") or "会话已失效", "sessionExpired": True}), 401
    return jsonify({
        "name": data.get("name") or data.get("sensitive_name") or "",
        "remain_integral": data.get("remain_integral"),
        "total_integral": data.get("total_integral"),
    })


@app.post("/api/daily-task")
@require_passcode
def api_daily_task():
    b = _body()
    if not str(b.get("login_name") or "") or not str(b.get("ses_id") or ""):
        return jsonify({"error": "参数不完整"}), 400
    app.logger.info("[%s] 执行每日任务", g.label)
    logs = hz_api.daily_task_workflow(str(b["login_name"]), str(b["ses_id"]))
    return jsonify({"logs": logs})


@app.post("/api/exchange")
@require_passcode
def api_exchange():
    b = _body()
    for k in ("login_name", "ses_id", "exchange_id"):
        if not str(b.get(k) or ""):
            return jsonify({"error": "参数不完整"}), 400
    try:
        data = hz_api.exchange_once(
            str(b["login_name"]), str(b.get("user_id") or b["login_name"]),
            str(b["ses_id"]), str(b["exchange_id"]),
        )
    except hz_api.HzApiError as e:
        return _err(e)
    return jsonify({"msg": data.get("msg"), "result": data.get("result"),
                    "data": data.get("data")})


@app.post("/api/qrcode")
@require_passcode
def api_qrcode():
    b = _body()
    if not str(b.get("login_name") or "") or not str(b.get("ses_id") or ""):
        return jsonify({"error": "参数不完整"}), 400
    try:
        data = hz_api.get_green_code(str(b["login_name"]), str(b["ses_id"]),
                                     str(b.get("user_id") or ""), b.get("device"))
    except hz_api.HzApiError as e:
        return _err(e)
    return jsonify({
        "qrcode": data.get("qrcode"),
        "qrcodeImage": data.get("qrcodeImage"),
        "money": data.get("money"),
        "trafficCardNo": data.get("trafficCardNo"),
        "deadTime": data.get("deadTime") or data.get("deadline"),
    })


@app.post("/api/subway-tickets")
@require_passcode
def api_subway_tickets():
    b = _body()
    if not str(b.get("login_name") or "") or not str(b.get("ses_id") or ""):
        return jsonify({"error": "参数不完整"}), 400
    try:
        data = hz_api.subway_tickets(str(b["login_name"]), str(b["ses_id"]),
                                     str(b.get("award_type") or "1"))
    except hz_api.HzApiError as e:
        return _err(e)
    if data.get("result") != "0":
        return jsonify({"error": data.get("msg") or "查询失败"}), 400
    records = data.get("list") or []
    return jsonify({
        "use_num": data.get("use_num"),
        "expire_num": data.get("expire_num"),
        "num_2": data.get("num_2"),
        "num_4": data.get("num_4"),
        "num_6": data.get("num_6"),
        "totalObtained": sum(int(data.get(k) or 0) for k in
                             ("use_num", "expire_num", "num_2", "num_4", "num_6")),
        "records": records,
    })


@app.post("/api/server-time")
@require_passcode
def api_server_time():
    server_ms, offset_ms = hz_api.server_time_offset_ms()
    if server_ms is None:
        return jsonify({"error": "服务器时间同步失败"}), 502
    return jsonify({"server_ms": round(server_ms), "offset_ms": offset_ms})


# ---------------- 凭据同步中心（青龙定时任务动态拉取） ----------------
def _load_credentials() -> dict:
    """读取凭据库 → {"accounts":[...]}；文件缺失返回空库"""
    try:
        with open(CREDENTIALS_FILE, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict) and isinstance(data.get("accounts"), list):
            return data
    except FileNotFoundError:
        pass
    except Exception as e:
        app.logger.error("凭据库读取失败: %s", e)
    return {"accounts": []}


def _save_credentials(store: dict):
    with open(CREDENTIALS_FILE, "w", encoding="utf-8") as f:
        json.dump(store, f, ensure_ascii=False, indent=2)
    try:
        os.chmod(CREDENTIALS_FILE, 0o600)   # 凭据文件仅属主可读写
    except OSError:
        pass


@app.post("/api/credentials/sync")
@require_passcode
def api_cred_sync():
    """上报账户凭据（按 login_name 合并 upsert）。安卓/网页版登录成功后调用。"""
    accounts = _body().get("accounts")
    if not isinstance(accounts, list) or not accounts:
        return jsonify({"error": "accounts 列表为空"}), 400
    store = _load_credentials()
    now = int(time.time())
    added = updated = 0
    for a in accounts:
        if not isinstance(a, dict):
            continue
        login_name = str(a.get("login_name") or "").strip()
        ses_id = str(a.get("ses_id") or "").strip()
        if not login_name or not ses_id:
            continue
        existing = next((x for x in store["accounts"] if x.get("login_name") == login_name), None)
        if existing:
            existing.update({
                "ses_id": ses_id,
                "user_id": str(a.get("user_id") or existing.get("user_id") or login_name),
                "name": str(a.get("name") or existing.get("name") or ""),
                "label": g.label,
                "updated_at": now,
            })
            updated += 1
        else:
            store["accounts"].append({
                "login_name": login_name,
                "user_id": str(a.get("user_id") or login_name),
                "ses_id": ses_id,
                "name": str(a.get("name") or ""),
                "label": g.label,
                "updated_at": now,
            })
            added += 1
    _save_credentials(store)
    app.logger.info("[%s] 凭据同步: 新增 %d 更新 %d，库中共 %d 个账户", g.label, added, updated, len(store["accounts"]))
    return jsonify({"ok": True, "added": added, "updated": updated, "total": len(store["accounts"])})


@app.post("/api/credentials/get")
@require_passcode
def api_cred_get():
    """按 login_name 精确拉取单账户凭据（青龙脚本动态获取 ses_id）。

    用 POST+JSON 体而非 GET 查询串：login_name 是 Base64 串含 +/=，
    走查询串会被 URL 解码破坏（+ 变空格）。
    """
    login_name = str(_body().get("login_name") or "").strip()
    if not login_name:
        return jsonify({"error": "缺少 login_name"}), 400
    store = _load_credentials()
    acc = next((x for x in store["accounts"] if x.get("login_name") == login_name), None)
    if not acc:
        return jsonify({"error": "该账户不在凭据库中（请先在 App/网页版登录）"}), 404
    return jsonify(acc)


@app.post("/api/credentials/remove")
@require_passcode
def api_cred_remove():
    """从凭据库移除账户（安卓端移除账户时调用，防止残留失效凭据）"""
    login_name = str(_body().get("login_name") or "").strip()
    if not login_name:
        return jsonify({"error": "缺少 login_name"}), 400
    store = _load_credentials()
    before = len(store["accounts"])
    store["accounts"] = [x for x in store["accounts"] if x.get("login_name") != login_name]
    removed = before != len(store["accounts"])
    if removed:
        _save_credentials(store)
        app.logger.info("[%s] 凭据移除: %s（剩余 %d 个账户）", g.label, login_name[:8], len(store["accounts"]))
    return jsonify({"ok": True, "removed": removed})


# ---------------- 静态前端 ----------------
@app.route("/")
def index():
    return send_from_directory(app.static_folder, "index.html")


@app.errorhandler(RequestEntityTooLarge)
def too_large(e):
    return jsonify({"error": "请求体过大"}), 413


if __name__ == "__main__":
    # 生产环境由 systemd + Nginx 反代，仅监听本机
    app.run(host="127.0.0.1", port=PORT, threaded=True)
