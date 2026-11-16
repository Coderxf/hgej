"""
query_accounts.py - 管理员查询：查看凭据库中全部账户与设备信息
在青龙面板手动运行即可，不修改任何数据。

环境变量（可选）：
  HANGGONG_CRED_API   凭据中心地址（默认 https://hgej.777713.xyz）
  VERIFY              设为 1 时逐账户调 U005 校验会话有效性（较慢，每账户约1秒）
"""

import os
import re
import time

import requests
from hz_common import query_user_info, HzApiError

CRED_API = os.environ.get("HANGGONG_CRED_API", "https://hgej.777713.xyz").rstrip("/")


def device_desc(ua):
    """从 UA 提取 机型 / Android 版本 摘要"""
    if not ua:
        return "(未上报 - 该账户尚未在 App 1.1.9+ 登录)"
    m = re.search(r"Android ([0-9]+); ([^;)]+)", ua)
    if m:
        return f"{m.group(2).strip()} / Android {m.group(1)}"
    return ua[:50]


def main():
    try:
        resp = requests.post(CRED_API + "/api/credentials/list",
                             json={}, timeout=(8, 20), verify=True)
        data = resp.json()
    except Exception as e:
        raise SystemExit(f"凭据中心访问失败: {e.__class__.__name__}（检查 HANGGONG_CRED_API）")
    if resp.status_code != 200:
        raise SystemExit(f"凭据中心返回异常 (HTTP {resp.status_code})")

    accounts = data.get("accounts") or []
    print(f"{'=' * 62}")
    print(f" 杭工助手凭据库  共 {data.get('total', len(accounts))} 个账户  {time.strftime('%Y-%m-%d %H:%M')}")
    print(f"{'=' * 62}")

    verify = os.environ.get("VERIFY", "") == "1"

    for i, a in enumerate(accounts, 1):
        upd = time.strftime("%m-%d %H:%M", time.localtime(a.get("updated_at") or 0))
        name = a.get("name") or "(未命名)"
        login = a.get("login_name") or ""
        print(f"\n[{i}] {name}")
        print(f"    login_name : {login}")
        print(f"    会话       : {a.get('ses_masked') or '(空)'}   更新于 {upd}")
        print(f"    设备       : {device_desc(a.get('ua'))}")
        if verify:
            try:
                # 校验需要完整 ses_id，按 login_name 精确拉取
                full = requests.post(CRED_API + "/api/credentials/get",
                                     json={"login_name": login},
                                     timeout=(8, 20), verify=True).json()
                info = query_user_info(login, full.get("ses_id") or "")
                ok = info.get("result") == "0"
                print(f"    会话状态   : {'✅ 有效' if ok else '❌ 已失效（请在 App 重登该手机号）'}")
            except HzApiError as e:
                print(f"    会话状态   : ❌ 校验失败 {e}")
            except Exception as e:
                print(f"    会话状态   : ❌ 校验异常 {e.__class__.__name__}")

    print(f"\n{'=' * 62}")
    if not verify:
        print(" 提示：运行时加变量 VERIFY=1 可逐账户校验会话有效性（较慢）")


if __name__ == "__main__":
    main()
