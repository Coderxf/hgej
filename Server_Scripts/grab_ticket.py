"""
grab_ticket.py - 定时抢票：等下一个 7:00 / 11:30 / 17:00 卡点并发兑换
依赖同目录 hz_common.py。

抢票优化（2026-11-18）：
1. 连接预热：距目标 5 秒时预付 TCP/TLS 握手，整点首飞不再付握手延迟
2. 发射提前量：按时间同步测得的单程延迟提前发射，请求到达服务端恰好卡点
3. 全量计时日志：同步 RTT/偏移、预热耗时、每发相对目标的偏差与耗时、
   补试记录、抢后余额——留作每场复盘

环境变量（可选）：
  HANGGONG_EXCHANGE_ID    券种：9=2元 10=4元 11=6元（默认 11）
  HANGGONG_COUNT          抢购次数 1~20，每账户各 N 次（默认 3）
  HANGGONG_SLEEP          提交间隔秒 ≥0.15（默认 0.2）
  HANGGONG_FIRE_LEAD_MS   发射提前量毫秒（默认 80，按链路实测调整）

用法：
  python3 grab_ticket.py          定时模式：锁定下一档位（7:00/11:30/17:00），
                                  卡点等待后发射（青龙定时任务用）
  python3 grab_ticket.py now      立即模式：跳过等待直接发射（手动补抢/
                                  链路验证用）
"""

import os
import sys
import time
from datetime import datetime, timedelta

from hz_common import (HzApiError, exchange_once, log_print,
                       query_user_info, resolve_ses_id, session)

LOGIN_NAME, ACC = None, os.environ.get("HANGGONG_ACC", "")
SES_FALLBACK = os.environ.get(f"HANGGONG_SES_ID_{ACC}", "")
EXCHANGE_ID = os.environ.get("HANGGONG_EXCHANGE_ID", "11")
RUN_COUNT = int(os.environ.get("HANGGONG_COUNT", "3"))
TIME_SLEEP = float(os.environ.get("HANGGONG_SLEEP", "0.2"))
FIRE_LEAD_MS = int(os.environ.get("HANGGONG_FIRE_LEAD_MS", "80"))

RUN_COUNT = max(1, min(RUN_COUNT, 20))
TIME_SLEEP = max(TIME_SLEEP, 0.15)


def extract_anchor_offsets(results):
    """从扫描样本提取秒界锚点偏移（纯函数，可单测）。

    results: [(本地 epoch 毫秒中点, Date 秒值), ...] 已按时间排序。
    返回 (offsets 列表, 日志行列表)。跨秒异常锚点（相邻跳变间隔偏离
    1000ms 超过 ±100）剔除。
    """
    jumps = []   # [(S_new_epoch_ms, 边界对应本地 epoch ms)]
    for i in range(1, len(results)):
        s_prev, s_curr = results[i - 1][1], results[i][1]
        if s_curr != s_prev:
            boundary_local = (results[i - 1][0] + results[i][0]) / 2
            jumps.append((s_curr * 1000, boundary_local))

    log_lines = [f"扫描样本 {len(results)} 个，捕获跳变 {len(jumps)} 次"]
    if not jumps:
        return [], log_lines

    offsets = [jumps[0][0] - jumps[0][1]]
    for i in range(1, len(jumps)):
        span = jumps[i][0] - jumps[i - 1][0]
        if not (900 <= span <= 1100):
            log_lines.append(f"  跳变间隔 {span:.0f}ms 异常，剔除该锚点")
            continue
        offsets.append(jumps[i][0] - jumps[i][1])
    return offsets, log_lines


def phase_scan_offset(max_seconds=2.6):
    """秒跳变扫描校准（毫秒级）。

    原理：HTTP Date 头只有秒级精度（生成时刻向下取整），单次采样残余
    误差 ±0.5s 无法通过加中点消除。改为密集观测 Date **跳秒瞬间**：
    每个响应的 Date 值从 S 变为 S+1 时，其生成时刻恰好跨入服务器
    第 S+1 秒边界——该边界对应的本地时刻即服务器时钟与本地时钟的
    相位锚点（误差 ≈ 响应间隔/2 + RTT 对称性偏差）。
    4 线程并发密集采样，响应超时收紧至 3s（防慢响应拖垮扫描节奏）。

    返回 (offset_ms, spread_ms) 或 None（样本不足/多锚点极差>150ms
    判定为锚点污染——污染偏移会让卡点偏移数百秒，必须弃用回退）。
    """
    import threading

    results = []            # [(本地epoch毫秒中点, date_second)]
    lock = threading.Lock()
    stop = threading.Event()
    BASE_URL_SCAN = "https://app.hzgh.org.cn"

    def sampler():
        from email.utils import parsedate_to_datetime
        while not stop.is_set():
            try:
                e0 = time.time() * 1000
                r = session.get(BASE_URL_SCAN, timeout=(2, 3), verify=True)
                e1 = time.time() * 1000
                dh = r.headers.get("Date")
                if dh:
                    sec = int(parsedate_to_datetime(dh).timestamp())
                    with lock:
                        results.append(((e0 + e1) / 2, sec))   # epoch 毫秒
            except Exception:
                pass

    threads = [threading.Thread(target=sampler, daemon=True) for _ in range(4)]
    for t in threads:
        t.start()
    time.sleep(max_seconds)
    stop.set()
    for t in threads:
        t.join(timeout=4)

    results.sort()
    if len(results) < 30:
        log_lines = [f"跳变扫描样本不足（{len(results)}）"]
        for line in log_lines:
            log_print(line)
        return None

    offsets, log_lines = extract_anchor_offsets(results)
    for line in log_lines:
        log_print(line)
    if not offsets:
        return None

    offset_ms = sum(offsets) / len(offsets)
    spread = (max(offsets) - min(offsets)) if len(offsets) > 1 else 0
    log_print(f"有效锚点 {len(offsets)} 个，偏移 {offset_ms:+.0f}ms（多锚点极差 {spread:.0f}ms）")
    if spread > 150:
        log_print("  ⚠️ 多锚点极差超 150ms，判定锚点污染，弃用扫描结果回退快速校准")
        return None
    return offset_ms, spread


def fast_sync_offset():
    """快速 Date 采样校准（秒级精度 ±0.5s，含截断中点补偿）。
    用于距档位窗口较近、无法承担完整扫描耗时的场景。"""
    best = None
    from email.utils import parsedate_to_datetime
    for i in range(3):
        try:
            start = time.monotonic()
            session.get("https://app.hzgh.org.cn", timeout=(8, 20), verify=True)
            rtt_ms = (time.monotonic() - start) * 1000
            resp = session.get("https://app.hzgh.org.cn", timeout=(8, 20), verify=True)
            date_header = resp.headers.get("Date")
            if not date_header:
                continue
            server_ms = parsedate_to_datetime(date_header).timestamp() * 1000
            server_ms += rtt_ms / 2 + 500
            offset_s = (server_ms - time.time() * 1000) / 1000.0
            log_print(f"  快速采样{i + 1}: RTT {rtt_ms:.0f}ms 偏移 {offset_s * 1000:+.0f}ms")
            if best is None or rtt_ms < best[0]:
                best = (rtt_ms, offset_s)
        except Exception as e:
            log_print(f"  采样{i + 1}失败: {e.__class__.__name__}")
    if best is None:
        log_print("服务器时间同步失败，使用本地时间")
        return 0.0, 80.0, 0
    rtt_ms, offset_s = best
    log_print(f"快速校准采用最优样本: RTT {rtt_ms:.0f}ms，本地偏移 {offset_s * 1000:+.0f}ms")
    return offset_s, rtt_ms / 2, rtt_ms


def get_next_run_time(now=None):
    """下一个抢券档位：7:00 / 11:30 / 17:00，超过 17:00 取明天 7:00。
    now 可注入（单测用），缺省取当前时间。"""
    now = now or datetime.now()
    today = now.date()
    for h, m in [(7, 0), (11, 30), (17, 0)]:
        t = datetime.combine(today, datetime.strptime(f"{h:02d}:{m:02d}", "%H:%M").time())
        if now < t:
            return t
    return datetime.combine(today + timedelta(days=1), datetime.strptime("07:00", "%H:%M").time())


def next_wait_ms(diff_ms):
    """卡点等待梯度（纯函数，可单测）。

    diff_ms = 距目标的服务器时间毫秒数，返回本轮应休眠的毫秒数。
    梯度与 diff 同单位（毫秒）——11-21 事故教训：毫秒判定配秒级休眠
    导致 sleep(300) 卡死整场（25s 被当成 25h）。
    """
    if diff_ms > 3_600_000:
        return 300_000
    if diff_ms > 600_000:
        return 60_000
    if diff_ms > 60_000:
        return 30_000
    if diff_ms > 2_000:
        return 500
    return 10


def wait_until_target(target, offset_s, fire_lead_ms):
    """按服务器时间卡点等待。

    - 5 秒整时做一次连接预热（session 上发一个轻量请求，把可能断开的
      TCP/TLS 连接重新握好，整点首飞不再付握手成本）
    - 剩余 fire_lead_ms 时直接返回：由调用方立刻发射，
      请求经单程延迟恰好到达服务端于目标时刻
    返回 (是否等到, 实际提前量ms)。
    """
    warmed = False
    while True:
        now_ms = (time.time() + offset_s) * 1000
        diff_ms = target.timestamp() * 1000 - now_ms
        if diff_ms <= fire_lead_ms:
            return True, max(0, -diff_ms)   # 实际提前发射了多少
        if diff_ms > 5000 and not warmed:
            try:
                t0 = time.monotonic()
                session.get("https://app.hzgh.org.cn", timeout=(8, 20), verify=True)
                log_print(f"连接预热完成（{time.monotonic() - t0:.0f}ms），整点首飞免握手")
            except Exception as e:
                log_print(f"连接预热失败: {e.__class__.__name__}")
            warmed = True
        time.sleep(next_wait_ms(diff_ms) / 1000)


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
    """查询当前券种可用余额。返回数量或 None。"""
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


def is_not_started(text):
    """跟随判定（纯函数，可单测）：响应是否为「活动还未开始」类"""
    t = (text or "").lower()
    return "还未开始" in t or "未开始" in t


def job(target, offset_s):
    """并发发射：submit 按间隔节流，但每发独立线程执行——
    上一发 ReadTimeout(20s) 不再阻塞后续发射把窗口外的机会挤掉。

    target=None（now 立即模式）时不做相对目标计时，只记录发射序与耗时。
    跟随模式：收到「活动还未开始」的发射线程在 T0 后每 500ms 自动重发，
    咬住真实放券时刻（部分档位放券晚于整点，11-22 07:00 场实测
    T0+15s 仍未开始），上限 T0+60s。"""
    from concurrent.futures import ThreadPoolExecutor
    import itertools

    shots = []   # [(序, 相对目标ms, 耗时ms, 结果文本)]
    target_ms = target.timestamp() * 1000 if target else None
    lock = __import__("threading").Lock()
    seq = itertools.count(start=1)

    def worker():
        n = next(seq)
        deadline_ms = (target_ms + 60_000) if target_ms else None
        attempt = 0
        while True:
            send_local = time.time() * 1000
            rel_ms = ((send_local + offset_s * 1000) - target_ms) if target_ms else 0
            data, err = exchange_with_retry()
            cost_ms = time.time() * 1000 - send_local
            msg = (data.get("msg") or "无响应") if err is None else f"失败: {err}"
            text = msg if err is None else err
            attempt += 1
            # 跟随判定：只有「活动还未开始」需要咬住放券时刻重发
            done = not is_not_started(text)
            past_deadline = deadline_ms is not None and time.time() * 1000 > deadline_ms
            with lock:
                shots.append((n, rel_ms, cost_ms, msg))
                rel_txt = f"发送于目标{rel_ms:+.0f}ms " if target_ms else ""
                extra = f"（第{attempt}次尝试）" if attempt > 1 else ""
                log_print(f"[{n}/{RUN_COUNT}] {rel_txt}耗时{cost_ms:.0f}ms → {msg}{extra}")
            if done or past_deadline or not target_ms:
                return err is None
            log_print("活动未开始，500ms 后跟随重试...")
            time.sleep(0.5)

    log_print(f"开抢：{RUN_COUNT} 发（提交间隔 {TIME_SLEEP}s，各发独立线程含补试+跟随）")
    with ThreadPoolExecutor(max_workers=RUN_COUNT * 2) as ex:
        futures = []
        for i in range(RUN_COUNT):
            futures.append(ex.submit(worker))
            if i < RUN_COUNT - 1:
                time.sleep(TIME_SLEEP)
        success = sum(f.result() for f in futures)

    log_print("—— 本场统计（按发射序） ——")
    for (n, rel, cost, msg) in sorted(shots):
        rel_txt = f" 目标{rel:+.0f}ms" if target_ms else ""
        log_print(f"第{n}发:{rel_txt} 耗时{cost:.0f}ms → {msg}")
    balance = query_award_balance()
    if balance is not None:
        log_print(f"抢后余额确认：当前券种可用 {balance} 张")
    log_print(f"抢票任务完成（成功响应 {success}/{RUN_COUNT}）")


def main():
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

    # now 立即模式：跳过档位等待直接发射（手动补抢/链路验证）
    if "now" in (sys.argv[1:] if len(sys.argv) > 1 else []):
        log_print("【立即模式】跳过档位等待，直接发射")
        job(None, 0.0)
        return

    # 快速校准（±0.5s）先粗算档位距离——判断是否在可执行窗口内
    offset_s, one_way_ms, _ = fast_sync_offset()
    target = get_next_run_time()

    # 智能窗口判定：只处理"当前时刻周围"的档位，不挂长等待
    # - 档位前 ≤ MAX_WAIT_S（定时任务提前 15~25s 启动的等待量）→ 等待并执行
    # - 档位后一律跳过（放券数秒内即空，过点抢不到，无宽限）
    # - 其余（如中午手动执行距 17:00 还有数小时）→ 跳过退出
    MAX_WAIT_MS = int(os.environ.get("HANGGONG_MAX_WAIT_S", "60")) * 1000
    delta_ms = target.timestamp() * 1000 - (time.time() * 1000 + offset_s * 1000)

    if delta_ms > MAX_WAIT_MS:
        delta_ms = int(delta_ms)
        remain_desc = f"{delta_ms // 3600000}h{(delta_ms % 3600000) // 60000:02d}m" \
            if delta_ms >= 3600000 else f"{delta_ms / 1000:.0f}s"
        raise SystemExit(
            f"距下一档位 {target} 还有 {remain_desc}，超出可执行窗口"
            f"（档位前 ≤{MAX_WAIT_MS // 1000}s）——跳过，不挂长等待。\n"
            f"手动提前触发请在该档位前 {MAX_WAIT_MS // 1000}s 内运行；"
            f"需要立即发射请用 now 参数"
        )
    if delta_ms < 0:
        raise SystemExit(
            f"本场档位 {target} 已过（{-delta_ms / 1000:.0f}s），跳过。"
            "下一档位将自动尝试，无需干预"
        )

    # 窗口内：距档位 >8s 时做毫秒级跳变扫描精校准（含污染熔断+重扫），
    # 否则沿用快速校准结果
    if delta_ms > 8000:
        scan = phase_scan_offset()
        if scan is None:
            # 首次扫描被污染（如抢票高峰上游拥堵），隔 2 秒重扫一次
            log_print("首次扫描结果被弃用，2 秒后重扫...")
            time.sleep(2)
            scan = phase_scan_offset(max_seconds=4.0)
        if scan is not None:
            offset_s, _ = scan
            start = time.monotonic()
            session.get("https://app.hzgh.org.cn", timeout=(8, 20), verify=True)
            one_way_ms = (time.monotonic() - start) * 1000 / 2
            log_print(f"毫秒级对齐完成（跳变扫描），单程延迟 {one_way_ms:.0f}ms")
    lead = max(FIRE_LEAD_MS, one_way_ms)

    log_print(f"程序已启动，将在 {target} 执行兑换，共 {RUN_COUNT} 次，间隔 {TIME_SLEEP}s")
    log_print(f"发射提前量 {lead:.0f}ms（单程延迟 {one_way_ms:.0f}ms）")
    ok, actual_lead = wait_until_target(target, offset_s, lead)
    if not ok:
        return
    log_print(f"开抢（实际提前 {actual_lead:.0f}ms 发射）")
    job(target, offset_s)


if __name__ == "__main__":
    main()
