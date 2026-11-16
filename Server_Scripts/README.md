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

## 配置（环境变量）

### 凭据动态化（推荐，替代手工维护 ses_id）

```bash
export HANGGONG_CRED_API="https://777713.xyz"
```

配好后脚本启动时按 `HANGGONG_USER_ID*`（唯一键）到凭据中心拉取该账户的
最新 ses_id。凭据的写入由安卓 App / 网页版登录时自动上报——**ses_id 过期
后任意一端重登即可，无需改服务器任何配置**。

传统方式仍然兼容：不配 `HANGGONG_CRED_API`，直接配 `HANGGONG_SES_ID`
（或后缀形式 `HANGGONG_SES_ID_{ACC}`），失效需手工更新。

### 账户标识

| 变量 | 说明 |
|---|---|
| `HANGGONG_ACC` | 账户后缀（如 `178`/`132`/`kong`），脚本读 `HANGGONG_USER_ID_{ACC}` |
| `HANGGONG_USER_ID` | 或直接用通用名（无后缀），二选一 |
| `HANGGONG_USER_ID_{ACC}` | 账户标识（config.sh 现有的加密串），**凭据库唯一匹配键** |

### 抢票参数

| 变量 | 默认 | 说明 |
|---|---|---|
| `HANGGONG_EXCHANGE_ID` | 11 | 券种：9=2元 10=4元 11=6元 |
| `HANGGONG_COUNT` | 10 | 抢购次数 1~20 |
| `HANGGONG_SLEEP` | 0.2 | 提交间隔秒 ≥0.15 |

## 部署到服务器

```bash
# 本地：上传三个文件
scp Server_Scripts/hz_common.py Server_Scripts/daily_task.py Server_Scripts/grab_ticket.py \
  root@777713.xyz:/path/to/scripts/

# 服务器：依赖
pip3 install requests pycryptodome pycryptodomex -i https://mirrors.aliyun.com/pypi/simple/

# 手动运行（凭据中心模式）
export HANGGONG_CRED_API="https://777713.xyz"
export HANGGONG_ACC=178                     # 或直接 export HANGGONG_USER_ID=<加密串>
python3 daily_task.py
python3 grab_ticket.py     # 常驻，等下一个档位自动抢
```

> ses_id 过期时脚本退出并提示「去 App 重登该手机号」——App 端重登后凭据
> 自动同步到凭据中心，下次运行自动生效，无需改任何配置。

> 抢票脚本 `grab_ticket.py` 自身带档位等待，可直接常驻或由 cron 在档位前几分钟拉起。
> ses_id 过期时脚本会直接退出并提示重新登录，不会白跑。
