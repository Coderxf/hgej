"""
grab_ticket.py - 定时抢票：等下一个 7:00 / 11:30 / 17:00 卡点并发兑换
依赖同目录 hz_common.py。凭据通过环境变量注入（与服务器现有命名保持一致）。

环境变量（可选）：
  HANGGONG_EXCHANGE_ID   券种：9=2元 10=4元 11=6元（默认 11）
  HANGGONG_COUNT         抢购次数 1~20（默认 10）
  HANGGONG_SLEEP         提交间隔秒 ≥0.15（默认 0.2）

说明：目标时间按服务器时间校准（读响应头 Date + 半 RTT 补偿），
本地时钟不准不影响卡点。
"""

import concurrent.futures
import os
import time
from datetime import datetime, timedelta

from hz_common import (HzApiError, exchange_once, log_print,
                       query_user_info, session)

LOGIN_NAME = os.environ.get("HANGGONG_USER_ID_kong", "")
SES_ID = os.environ.get("HANGGONG_SES_ID_kong", "")
USER_ID = LOGIN_NAME
EXCHANGE_ID = os.environ.get("HANGGONG_EXCHANGE_ID", "11")
RUN_COUNT = int(os.environ.get("HANGGONG_COUNT", "10"))
TIME_SLEEP = float(os.environ.get("HANGGONG_SLEEP", "0.2"))

# 与安卓/网页版一致的保护性限制
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
    """按服务器时间卡点等待（梯度休眠）。返回 False 表示中途异常退出。"""
    while True:
        now_ms = (time.time() + offset_s) * 1000
        target_ms = target.timestamp() * 1000
        diff_s = (target_ms - now_ms) / 1000.0
        if diff_s <= 0:
            return True
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


def job():
    log_print(f"开始并发兑换：{RUN_COUNT} 次，间隔 {TIME_SLEEP}s，券种 {EXCHANGE_ID}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as executor:
        futures = []
        for i in range(RUN_COUNT):
            futures.append(executor.submit(
                exchange_once, LOGIN_NAME, USER_ID, SES_ID, EXCHANGE_ID))
            log_print(f"已提交第 {i + 1}/{RUN_COUNT} 个请求")
            time.sleep(TIME_SLEEP)
        for i, f in enumerate(futures, 1):
            try:
                data = f.result()
                log_print(f"[{i}] {data.get('msg') or data}")
            except HzApiError as e:
                log_print(f"[{i}] 失败: {e}")
    log_print("抢票任务完成")


def main():
    if not LOGIN_NAME or not SES_ID:
        raise SystemExit("缺少环境变量 HANGGONG_USER_ID_kong / HANGGONG_SES_ID_kong")

    log_print("校验会话...")
    try:
        info = query_user_info(LOGIN_NAME, SES_ID)
    except HzApiError as e:
        raise SystemExit(f"会话校验失败: {e}")
    if info.get("result") != "0":
        raise SystemExit(f"会话已失效，请重新登录获取新 ses_id")
    log_print(f"会话有效，用户: {info.get('name') or '未知'}")

    offset = sync_time_offset_seconds()
    target = get_next_run_time()
    log_print(f"程序已启动，将在 {target} 执行兑换，共 {RUN_COUNT} 次，间隔 {TIME_SLEEP}s")
    wait_until_target(target, offset)
    job()


if __name__ == "__main__":
    main()
