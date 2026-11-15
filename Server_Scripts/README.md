# Server_Scripts - 服务器脚本（签到 / 抢票）

部署在阿里云服务器上的两个定时任务，全部修复原脚本的安全与正确性问题：

| 脚本 | 对应原脚本 | 功能 |
|---|---|---|
| `daily_task.py` | 原签到脚本 | 会话校验 → 登录 → 签到×3 → 评论 → 查积分 |
| `grab_ticket.py` | 原抢票脚本 | 服务器时间校准卡点 → 并发兑换 |

共用 `hz_common.py`（加解密 / 签名 / UA 池 / IPv4 优先解析）。

## 本次修复清单（相对服务器旧脚本）

1. **User-Agent 真机化**：旧脚本所有请求自称同一台 2021 年的荣耀 9X（Chrome 91），
   极易被风控识别。现从 10 款已核实真机 UA 池按 `HANGGONG_UA_INDEX` 选择，
   **同一账号的两个脚本必须配同一个序号**（保持"同一台手机"自洽）
2. **app_ver_no 统一**：旧脚本同一"设备"的请求自称 3.1.6 / 3.1.4 / 环境变量三处不一致；
   现全部统一用 `HANGGONG_V`（默认 3.1.7）
3. **恢复 TLS 证书校验**：删除 `verify=False`（证书已实测有效）
4. **修复抢票等待的 elif 漏写**：旧代码 >1 小时分支后会重复补睡 30s，节奏与注释不符
5. **凭据不再进日志**：旧脚本 `job()` 会把 login_name / ses_id 打印到日志
6. 解密失败返回 None 而非崩溃；请求加 8s 连接 / 20s 读取超时

## 配置（环境变量，与旧脚本命名兼容）

| 变量 | 必填 | 说明 |
|---|---|---|
| `HANGGONG_USER_ID_kong` | 是 | login_name / user_id |
| `HANGGONG_SES_ID_kong` | 是 | 会话 id（重新登录后变化） |
| `HANGGONG_V` | 否 | App 版本号，默认 3.1.7 |
| `HANGGONG_UA_INDEX` | 否 | UA 池序号 0~9，默认 0；**同账号两脚本同值** |
| `HANGGONG_EXCHANGE_ID` | 否 | 抢票券种：9=2元 10=4元 11=6元（默认 11） |
| `HANGGONG_COUNT` | 否 | 抢购次数 1~20（默认 10） |
| `HANGGONG_SLEEP` | 否 | 提交间隔秒 ≥0.15（默认 0.2） |

## 部署到服务器

```bash
# 本地：上传三个文件
scp Server_Scripts/hz_common.py Server_Scripts/daily_task.py Server_Scripts/grab_ticket.py \
  root@777713.xyz:/path/to/scripts/

# 服务器：依赖
pip3 install requests pycryptodome pycryptodomex -i https://mirrors.aliyun.com/pypi/simple/

# 手动运行
export HANGGONG_USER_ID_kong='xxx' HANGGONG_SES_ID_kong='xxx' HANGGONG_UA_INDEX=0
python3 daily_task.py
python3 grab_ticket.py     # 常驻，等下一个档位自动抢

# 定时任务示例（crontab，每天 6:55 拉起抢票常驻等待）
# 55 6 * * * cd /path/to/scripts && HANGGONG_UA_INDEX=0 ... python3 grab_ticket.py >> grab.log 2>&1
```

> 抢票脚本 `grab_ticket.py` 自身带档位等待，可直接常驻或由 cron 在档位前几分钟拉起。
> ses_id 过期时脚本会直接退出并提示重新登录，不会白跑。
