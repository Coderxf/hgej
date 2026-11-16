"""
daily_task.py - 每日任务：登录 → 签到×3 → 评论 → 积分查询
依赖同目录 hz_common.py。

账户解析（二选一）：
  1. 凭据同步中心（推荐）：env 配 HANGGONG_CRED_API + HANGGONG_SITE_KEY，
     用 HANGGONG_USER_ID 或 HANGGONG_ACC(HANGGONG_USER_ID_{ACC}) 定位账户，
     脚本启动时自动拉取该账户最新 ses_id——ses_id 过期后 App 重登即自动刷新
  2. 传统方式：直接配 HANGGONG_SES_ID（或 HANGGONG_SES_ID_{ACC}）

青龙 config.sh 示例（沿用现有后缀命名，仅删掉 HANGGONG_SES_ID_*）：
  export HANGGONG_CRED_API="https://777713.xyz"
  export HANGGONG_SITE_KEY="你的站点口令"
  export HANGGONG_USER_ID_178="3ehvhZLT..."   # 保持不变
"""

import os

from hz_common import (HzApiError, log_print, query_user_info,
                       resolve_ses_id, daily_task_workflow)


def resolve_login_name():
    """按 青龙修复版 的 ACC 后缀模型解析 login_name。"""
    acc = os.environ.get("HANGGONG_ACC", "")
    if acc:
        name = os.environ.get(f"HANGGONG_USER_ID_{acc}", "")
        if name:
            return name, acc
    name = os.environ.get("HANGGONG_USER_ID") or os.environ.get("HANGGONG_USER_ID_kong") or ""
    return name, "默认"


def main():
    login_name, acc = resolve_login_name()
    if not login_name:
        raise SystemExit("缺少账户标识：请配置 HANGGONG_ACC + HANGGONG_USER_ID_{ACC} 或 HANGGONG_USER_ID")

    ses_id, source = resolve_ses_id(login_name, os.environ.get(f"HANGGONG_SES_ID_{acc}", ""), acc)

    # 先校验会话有效性，避免带着失效 ses_id 白跑
    log_print(f"校验会话（来源: {source}）...")
    try:
        info = query_user_info(login_name, ses_id)
    except HzApiError as e:
        raise SystemExit(
            f"会话校验失败: {e}\n"
            "该账户 ses_id 可能已过期：请在安卓 App 或网页版重新登录该手机号，"
            "凭据会自动同步，无需修改任何配置"
        )
    if info.get("result") != "0":
        raise SystemExit(
            f"会话已失效: {info.get('msg')}\n"
            "请在安卓 App 或网页版重新登录该手机号，凭据会自动同步"
        )
    log_print(f"会话有效，用户: {info.get('name') or '未知'}")

    logs = daily_task_workflow(login_name, ses_id)
    for line in logs:
        log_print(line)
    log_print("每日任务执行完成")


if __name__ == "__main__":
    main()
