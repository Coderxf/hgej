"""
test_grab_ticket.py - 抢票核心逻辑单元测试（unittest，零外部依赖）

覆盖 11-21 事故（毫秒/秒梯度混用导致 sleep(300) 卡死整场）及各关键
纯函数的边界行为。部署前必跑：
  python3 -m unittest test_grab_ticket -v
"""

import sys
import unittest
from datetime import datetime
from unittest.mock import patch

import grab_ticket
import hz_common


class NextWaitMsTest(unittest.TestCase):
    """卡点等待梯度：毫秒判定必须配毫秒休眠（11-21 事故回归）"""

    def test_far_future_sleeps_5min(self):
        self.assertEqual(grab_ticket.next_wait_ms(40 * 3600_000), 300_000)

    def test_over_1hour(self):
        self.assertEqual(grab_ticket.next_wait_ms(3_600_001), 300_000)

    def test_exactly_1hour_boundary(self):
        # 3600_000 恰在下一档（60s）
        self.assertEqual(grab_ticket.next_wait_ms(3_600_000), 60_000)

    def test_10_minutes(self):
        self.assertEqual(grab_ticket.next_wait_ms(600_001), 60_000)
        self.assertEqual(grab_ticket.next_wait_ms(600_000), 30_000)

    def test_1_minute(self):
        self.assertEqual(grab_ticket.next_wait_ms(60_001), 30_000)
        self.assertEqual(grab_ticket.next_wait_ms(60_000), 500)

    def test_regression_25_seconds_must_not_sleep_300(self):
        # 11-21 事故回归：启动提前 15s → diff=25000ms，绝不允许睡 300s
        self.assertEqual(grab_ticket.next_wait_ms(25_000), 500)

    def test_2s_boundary(self):
        self.assertEqual(grab_ticket.next_wait_ms(2_001), 500)
        self.assertEqual(grab_ticket.next_wait_ms(2_000), 10)

    def test_final_millisecond_scan(self):
        self.assertEqual(grab_ticket.next_wait_ms(1), 10)
        self.assertEqual(grab_ticket.next_wait_ms(0), 10)
        self.assertEqual(grab_ticket.next_wait_ms(-5_000), 10)   # 已过点立即返

    def test_monotonic_decrease_never_increases_sleep(self):
        # 从远到近，休眠值必须单调不增
        prev = 10**9
        for diff in [40 * 3600_000, 3_600_001, 3_600_000, 600_001, 600_000,
                     60_001, 60_000, 2_001, 2_000, 1_000, 100, 0, -1_000]:
            cur = grab_ticket.next_wait_ms(diff)
            self.assertLessEqual(cur, prev, f"梯度回涨: diff={diff}")
            prev = cur


class GetNextRunTimeTest(unittest.TestCase):
    """档位选择：7:00 / 11:30 / 17:00 / 跨天"""

    def test_before_7am(self):
        now = datetime(2026, 11, 21, 6, 0, 0)
        self.assertEqual(grab_ticket.get_next_run_time(now), datetime(2026, 11, 21, 7, 0, 0))

    def test_between_7_and_1130(self):
        now = datetime(2026, 11, 21, 7, 30, 0)
        self.assertEqual(grab_ticket.get_next_run_time(now), datetime(2026, 11, 21, 11, 30, 0))

    def test_between_1130_and_17(self):
        now = datetime(2026, 11, 21, 12, 0, 0)
        self.assertEqual(grab_ticket.get_next_run_time(now), datetime(2026, 11, 21, 17, 0, 0))

    def test_after_17_rolls_to_tomorrow(self):
        now = datetime(2026, 11, 21, 17, 30, 0)
        self.assertEqual(grab_ticket.get_next_run_time(now), datetime(2026, 11, 22, 7, 0, 0))

    def test_exactly_on_boundary_skips_to_next(self):
        # 恰好 07:00:00 整 → 已到点，跳下一档
        now = datetime(2026, 11, 21, 7, 0, 0)
        self.assertEqual(grab_ticket.get_next_run_time(now), datetime(2026, 11, 21, 11, 30, 0))


class ExtractAnchorOffsetsTest(unittest.TestCase):
    """秒跳变扫描：锚点提取 + 跨秒异常剔除"""

    def _mk_results(self, jump_offsets):
        """按锚点偏移序列合成扫描样本（每秒界推进 1000ms）"""
        results = []
        t = 1_000_000   # 本地 epoch 毫秒（合成基准，任意）
        sec = 1_800_000_000   # 对应合成 Date 秒
        results.append((t, sec))
        for _ in jump_offsets:
            t += 1000
            sec += 1
            results.append((t, sec))
            results.append((t + 40, sec))   # 稳定段采样
        return results

    def test_three_clean_anchors(self):
        results = self._mk_results([0, 0, 0])   # 三锚点同相位
        offsets, lines = grab_ticket.extract_anchor_offsets(results)
        self.assertEqual(len(offsets), 3)
        # 中点估计存在 ±量化差（稳定段采样偏移），极差应远小于秒界
        self.assertLessEqual(max(offsets) - min(offsets), 100)
        self.assertTrue(any("捕获跳变 3 次" in l for l in lines))

    def test_outlier_span_removed(self):
        # 第二个跳变间隔 2000ms（跨了两个秒界）→ 剔除
        results = []
        t, sec = 1_000_000, 1_800_000_000
        results.append((t, sec))
        t += 1000; sec += 1
        results.append((t, sec)); results.append((t + 40, sec))
        t += 2000; sec += 2                              # 异常：跨 2 秒
        results.append((t, sec)); results.append((t + 40, sec))
        t += 1000; sec += 1
        results.append((t, sec)); results.append((t + 40, sec))
        offsets, lines = grab_ticket.extract_anchor_offsets(results)
        self.assertEqual(len(offsets), 2)                # 首锚 + 尾锚
        self.assertTrue(any("异常" in l for l in lines))

    def test_no_jumps_returns_empty(self):
        results = [(1_000_000 + i * 50, 1_800_000_000) for i in range(10)]  # 同一秒
        offsets, _ = grab_ticket.extract_anchor_offsets(results)
        self.assertEqual(offsets, [])


class JobDepsTest(unittest.TestCase):
    """回归：job() 运行时依赖必须模块级可用。

    2026-11-25 事故：job() 使用 itertools/ThreadPoolExecutor 但模块级
    import 缺失，py_compile/unittest 均不报错，直到真实场次运行时
    NameError 崩掉整场（07:00/11:30 三场全废）。
    """

    def test_job_runtime_deps_importable(self):
        self.assertTrue(hasattr(grab_ticket, "itertools"), "缺少模块级 import itertools")
        self.assertTrue(hasattr(grab_ticket, "ThreadPoolExecutor"), "缺少 ThreadPoolExecutor 导入")

    def test_job_smoke_with_mocks(self):
        """job() 冒烟：mock 掉网络与等待，完整跑一遍 3 发不抛 NameError"""
        with patch.object(grab_ticket, "exchange_with_retry",
                          side_effect=[({"msg": "兑换成功"}, None)] * 3), \
             patch.object(grab_ticket, "query_award_balance", return_value=2), \
             patch.object(grab_ticket.time, "sleep"):
            grab_ticket.job(None, 0.0)   # NameError（itertools 等）会在此暴露


class IsTransientTest(unittest.TestCase):
    """瞬时失败判定：超时/5xx 补试，业务失败不补试"""

    def test_timeouts(self):
        for msg in ("上游请求失败: ReadTimeout", "ConnectTimeout", "SocketTimeout"):
            self.assertTrue(grab_ticket._is_transient(msg))

    def test_gateway_5xx(self):
        self.assertTrue(grab_ticket._is_transient("上游响应异常 (HTTP 502)"))
        self.assertTrue(grab_ticket._is_transient("上游响应异常 (HTTP 503)"))

    def test_business_failure_not_transient(self):
        self.assertFalse(grab_ticket._is_transient("手慢啦，优惠券被抢光了"))
        self.assertFalse(grab_ticket._is_transient("请先登录"))


class FollowModeTest(unittest.TestCase):
    """跟随判定：只有「活动还未开始」需要咬住放券时刻重发（11-22 事故回归）"""

    def test_not_started_variants(self):
        self.assertTrue(grab_ticket.is_not_started("活动还未开始！"))
        self.assertTrue(grab_ticket.is_not_started("活动未开始"))
        self.assertTrue(grab_ticket.is_not_started("尚未开始"))

    def test_other_results(self):
        # 这些结果直接完成，禁止误入跟随循环
        self.assertFalse(grab_ticket.is_not_started("手慢啦，优惠券被抢光了"))
        self.assertFalse(grab_ticket.is_not_started("兑换成功"))
        self.assertFalse(grab_ticket.is_not_started("请勿重复提交"))
        self.assertFalse(grab_ticket.is_not_started("请先登录"))
        self.assertFalse(grab_ticket.is_not_started(""))


class ExchangeWithRetryTest(unittest.TestCase):
    """瞬时失败补试一次；业务失败立即放弃"""

    def setUp(self):
        grab_ticket.LOGIN_NAME = "test"
        grab_ticket.USER_ID = "test"
        grab_ticket.SES_ID = "ses"
        grab_ticket.EXCHANGE_ID = "11"

    def test_transient_then_success(self):
        resp_ok = {"msg": "兑换成功"}
        with patch.object(grab_ticket, "exchange_once",
                          side_effect=[grab_ticket.HzApiError("上游请求失败: ReadTimeout"), resp_ok]), \
             patch.object(grab_ticket.time, "sleep"):
            data, err = grab_ticket.exchange_with_retry()
        self.assertIsNone(err)
        self.assertEqual(data["msg"], "兑换成功")

    def test_transient_twice_gives_up(self):
        with patch.object(grab_ticket, "exchange_once",
                          side_effect=grab_ticket.HzApiError("上游请求失败: ReadTimeout")), \
             patch.object(grab_ticket.time, "sleep"):
            data, err = grab_ticket.exchange_with_retry()
        self.assertIsNone(data)
        self.assertIn("ReadTimeout", err)

    def test_business_failure_no_retry(self):
        calls = []
        def _once(*a):
            calls.append(1)
            raise grab_ticket.HzApiError("手慢啦，优惠券被抢光了")
        with patch.object(grab_ticket, "exchange_once", side_effect=_once), \
             patch.object(grab_ticket.time, "sleep"):
            data, err = grab_ticket.exchange_with_retry()
        self.assertEqual(len(calls), 1)   # 业务失败只打一发
        self.assertIn("被抢光", err)


class ResolveSesIdTest(unittest.TestCase):
    """会话解析：凭据中心优先 → env 回退 → 全无报错"""

    def test_center_first(self):
        with patch.object(hz_common, "fetch_credential",
                          return_value=("ses_from_center", "张三", "")):
            ses, source, _ = hz_common.resolve_ses_id("L", "fallback", "137")
        self.assertEqual(ses, "ses_from_center")
        self.assertIn("凭据中心", source)

    def test_fallback_to_env(self):
        with patch.object(hz_common, "fetch_credential", return_value=None):
            ses, source, _ = hz_common.resolve_ses_id("L", "fallback_ses", "137")
        self.assertEqual(ses, "fallback_ses")
        self.assertIn("环境变量", source)

    def test_no_source_raises(self):
        with patch.object(hz_common, "fetch_credential", return_value=None):
            with self.assertRaises(hz_common.HzApiError):
                hz_common.resolve_ses_id("L", "", "137")


class SanitizeDeviceTest(unittest.TestCase):
    """设备字段校验逻辑存在于 Web 版 hz_api.py（参数化入口）；
    Server_Scripts 的 UA 直接来自凭据库，无本地构造，无需测试。"""
    pass


if __name__ == "__main__":
    unittest.main(verbosity=2)
