"""
grab_ticket.py - 定时抢票：等下一个 7:00 / 11:30 / 17:00 卡点并发兑换
依赖同目录 hz_common.py。

账户解析与 daily_task.py 相同（凭据中心拉取最新 ses_id，或传统 env ses_id）。
目标时间按服务器时间校准（读响应头 Date + 半 RTT 补偿），
本地时钟不准不影响卡点。

环境变量（可选）：
  HANGGONG_EXCHANGE_ID   券种：9=2元 10=4元 11=6元（默认 11）
  HANGGONG_COUNT         抢购次数 1~20，每账户各 N 次（默认 3）
  HANGGONG_SLEEP         提交间隔秒 ≥0.15（默认 0.2）
"""

import os
import time
from datetime import datetime, timedelta

from hz_common import (HzApiError, exchange_once, log_print,
                       query_user_info, resolve_ses_id, session)

LOGIN_NAME, ACC = None, os.environ.get("HANGGONG_ACC", "")
SES_FALLBACK = os.environ.get(f"HANGGONG_SES_ID_{ACC}", "")
EXCHANGE_ID = os.environ.get("HANGGONG_EXCHANGE_ID", "11")
RUN_COUNT = int(os.environ.get("HANGGONG_COUNT", "3"))
TIME_SLEEP = float(os.environ.get("HANGGONG_SLEEP", "0.2"))

RUN_COUNT = max(1, min(RUN_COUNT, 20))
TIME_SLEEP = max(TIME_SLEEP, 0.15)


def sync_time_offset_seconds():
    """返回 服务器时间-本地时间 偏移（秒）；失败回退 0。"""
    try:
        start = time.monotonic()
        session.get("https://app.hzgh.org.cn", timeout=(8, 20), verify=True)
        rtt = (time.monotonic() - start) * 1000
        from email.utils import parsedate_to_datetime
        resp = session.get("https://app.hzgh.org.cn", timeout=(8, 20), verify=True)
        server_ms = parsedate_to_datetime(resp.headers["Date"]).timestamp() * 1000
        server_ms += rtt / 2
        offset = (server_ms - time.time() * 1000) / 1000.0
        log_print(f"服务器时间同步完成，本地偏移: {offset * 1000:+.0f}ms")
        return offset
    except Exception as e:
        log_print(f"服务器时间同步失败，使用本地时间: {e.__class__.__name__}")
        return 0.0


def get_next_run_time():
    """下一个抢券档位：7:00 / 11:30 / 17:00，超过 17:00 取明天 7:00。"""
    now = datetime.now()
    today = now.date()
    for h, m in [(7, 0), (11, 30), (17, 0)]:
        t = datetime.combine(today, datetime.strptime(f"{h:02d}:{m:02d}", "%H:%M").time())
        if now < t:
            return t
    return datetime.combine(today + timedelta(days=1), datetime.strptime("07:00", "%H:%M").time())


def wait_until_target(target, offset_s=0.0):
    """按服务器时间卡点等待（梯度休眠）。"""
    while True:
        now_ms = (time.time() + offset_s) * 1000
        target_ms = target.timestamp() * 1000
        diff_s = (target_ms - now_ms) / 1000.0
        if diff_s <= 0:
            return
        if diff_s > 3600:
            time.sleep(300)
        elif diff_s > 600:
            time.sleep(60)
        elif diff_s > 60:
            time.sleep(30)
        elif diff_s > 1:
            time.sleep(0.5)
        else:
            time.sleep(0.05)


def _is_transient(msg):
    """瞬时失败（超时/网关 5xx）：值得立即补试一次"""
    m = msg.lower()
    return ("readtimeout" in m or "connecttimeout" in m or "sockettimeout" in m
            or "http 50" in m)


def exchange_with_retry():
    """单次抢票目标：超时/5xx 自动补试一次（1 秒后），业务失败（如被抢光）不重试。
    返回 (结果dict或None, 最终错误或None)。"""
    last_err = None
    for attempt in range(2):
        try:
            return exchange_once(LOGIN_NAME, USER_ID, SES_ID, EXCHANGE_ID), None
        except HzApiError as e:
            last_err = str(e)
            if attempt == 0 and _is_transient(last_err):
                log_print("瞬时失败（超时/网关过载），1 秒后补试...")
                time.sleep(1)
                continue
            return None, last_err
    return None, last_err


def query_award_balance():
    """查询当前券种可用余额，用于确认超时请求是否实际成功。返回数量或 None。"""
    award_type = str(max(1, min(3, int(EXCHANGE_ID) - 8)))   # 9→1(2元) 10→2(4元) 11→3(6元)
    try:
        from hz_common import signed_post
        d = signed_post(
            "/unionApp/interf/front/OL/OL83",
            {
                "login_name": LOGIN_NAME, "user_id": USER_ID, "ses_id": SES_ID,
                "use_state": "1", "award_type": award_type,
                "page_size": 10, "page_num": 1,
            },
        )
        key = f"num_{award_type}"
        return int(d.get(key) or 0)
    except Exception:
        return None


def job():
    log_print(f"开始抢票：{RUN_COUNT} 次，间隔 {TIME_SLEEP}s，券种 {EXCHANGE_ID}")
    success = 0
    for i in range(RUN_COUNT):
        data, err = exchange_with_retry()
        if err is None:
            success += 1
            log_print(f"[{i + 1}] {data.get('msg') or '无响应'}")
        else:
            log_print(f"[{i + 1}] 失败: {err}")
        if i < RUN_COUNT - 1:
            time.sleep(TIME_SLEEP)

    log_print(f"抢票请求全部完成（成功响应 {success}/{RUN_COUNT}，超时的请求可能已在服务端排队）")
    balance = query_award_balance()
    if balance is not None:
        log_print(f"抢后余额确认：当前券种可用 {balance} 张（含本次抢到的）")
    log_print("抢票任务完成")


def main():
    from datetime import datetime
    global LOGIN_NAME, USER_ID, SES_ID

    login_name, acc = None, os.environ.get("HANGGONG_ACC", "")
    if acc:
        login_name = os.environ.get(f"HANGGONG_USER_ID_{acc}", "")
    login_name = login_name or os.environ.get("HANGGONG_USER_ID") or os.environ.get("HANGGONG_USER_ID_kong") or ""
    if not login_name:
        raise SystemExit("缺少账户标识：请配置 HANGGONG_ACC + HANGGONG_USER_ID_{ACC} 或 HANGGONG_USER_ID")
    LOGIN_NAME = login_name
    USER_ID = login_name

    ses_id, source, ua = resolve_ses_id(login_name, SES_FALLBACK, acc)
    SES_ID = ses_id

    # 采用账户上报的真实设备 UA（与登录设备一致），替代脚本内置 UA 池
    if ua:
        session.headers.update({"User-Agent": ua})
        log_print("已采用账户上报的真实设备 UA")

    log_print("校验会话...")
    try:
        info = query_user_info(LOGIN_NAME, SES_ID)
    except HzApiError as e:
        raise SystemExit(
            f"会话校验失败: {e}\n"
            "请在安卓 App 或网页版重新登录该手机号，凭据会自动同步"
        )
    if info.get("result") != "0":
        raise SystemExit("会话已失效，请在安卓 App 或网页版重新登录该手机号")
    log_print(f"会话有效（来源: {source}），用户: {info.get('name') or '未知'}")

    offset = sync_time_offset_seconds()
    target = get_next_run_time()
    log_print(f"程序已启动，将在 {target} 执行兑换，共 {RUN_COUNT} 次，间隔 {TIME_SLEEP}s")
    wait_until_target(target, offset)
    job()


if __name__ == "__main__":
    main()
