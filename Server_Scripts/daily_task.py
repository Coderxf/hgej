"""
daily_task.py - 每日任务：登录 → 签到×3 → 评论 → 积分查询
依赖同目录 hz_common.py。凭据通过环境变量注入（与服务器现有命名保持一致）。

用法：
  export HANGGONG_USER_ID_kong='xxx'   # login_name / user_id
  export HANGGONG_SES_ID_kong='xxx'    # 会话 id
  python3 daily_task.py
"""

import os

from hz_common import daily_task_workflow, log_print, query_user_info, HzApiError

LOGIN_NAME = os.environ.get("HANGGONG_USER_ID_kong", "")
SES_ID = os.environ.get("HANGGONG_SES_ID_kong", "")


def main():
    if not LOGIN_NAME or not SES_ID:
        raise SystemExit("缺少环境变量 HANGGONG_USER_ID_kong / HANGGONG_SES_ID_kong")

    # 先校验会话有效性，避免带着失效 ses_id 白跑
    log_print("校验会话...")
    try:
        info = query_user_info(LOGIN_NAME, SES_ID)
    except HzApiError as e:
        raise SystemExit(f"会话校验失败: {e}（ses_id 可能已过期，请重新登录获取）")
    if info.get("result") != "0":
        raise SystemExit(f"会话已失效: {info.get('msg')}（请重新登录获取新 ses_id）")
    log_print(f"会话有效，用户: {info.get('name') or '未知'}")

    logs = daily_task_workflow(LOGIN_NAME, SES_ID)
    for line in logs:
        log_print(line)
    log_print("每日任务执行完成")


if __name__ == "__main__":
    main()
