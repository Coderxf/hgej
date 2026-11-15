# 杭工助手网页版（Web_Version）

杭工e家助手的网页形态：响应式单页前端（零构建）+ Flask 薄后端（杭工接口代理与加解密）。
抢票循环在**浏览器**执行——网页开着才跑，关掉即停。

```
浏览器 ──同源 /api（X-Site-Key 口令头）──▶ Flask(127.0.0.1:8090) ──▶ app.hzgh.org.cn / hzcode.96225.com
```

## 功能

- 站点口令授权（多人：每人一个口令，`passcodes.json` 管理，热加载）
- 杭工账号登录：图形验证码 + 密码 / 短信双模式
- 用户信息（姓名/积分）、会话失效自动登出
- 每日任务（登录签到×3 + 评论 + 积分查询）
- 抢票：券种选择、目标时间（自动填充 7:00/11:30/17:00 下一档）、次数 1~20、间隔 ≥0.15s、
  服务器时间偏移校准（读响应头 Date + 半 RTT 补偿）、实时日志
- 乘车码（绿色出行码）+ 地铁券统计（总获得/已使用/已过期，字段 use_num/expire_num/num_*）

## 本地运行

```bash
cd Web_Version/server
python -m venv venv
venv/bin/pip install -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/   # Windows: venv\Scripts\pip
cp passcodes.json.example passcodes.json   # 改成自己的口令
venv/bin/python app.py                      # 监听 127.0.0.1:8090
# 浏览器打开 http://127.0.0.1:8090
```

## 服务器部署（阿里云 777713.xyz）

```bash
# 1. 上传（本地执行；Windows 用 scp/WinSCP 均可）
scp -r Web_Version root@777713.xyz:/opt/hgej-web
ssh root@777713.xyz

# 2. 服务器上：安装依赖
cd /opt/hgej-web/server
python3 -m venv venv
venv/bin/pip install -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/

# 3. 配置口令
cp passcodes.json.example passcodes.json
vi passcodes.json    # 改成真实口令，每行 {"code":"...","label":"张三"}
chmod 600 passcodes.json

# 4. systemd 常驻
cp /opt/hgej-web/deploy/hgej-web.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now hgej-web
systemctl status hgej-web    # 确认 active (running)

# 5. Nginx 反代（按需合并到现有 443 配置，保留 /update/）
cp /opt/hgej-web/deploy/nginx-web.conf /etc/nginx/conf.d/hgej-web.conf
vi /etc/nginx/conf.d/hgej-web.conf    # 修改证书路径
nginx -t && systemctl reload nginx
```

完成后访问 `https://777713.xyz/`。

## 日常运维

| 操作 | 命令 |
|---|---|
| 增/删口令（吊销） | 编辑 `passcodes.json` 保存即生效（热加载，无需重启） |
| 生成口令 | `python3 -c "import secrets;print(secrets.token_urlsafe(9))"` |
| 更新代码 | 替换文件后 `systemctl restart hgej-web` |
| 看日志 | `journalctl -u hgej-web -f`（只含口令标签与操作，无 ses_id/密码） |

## 安全与风险边界（重要）

- **仅限熟人小范围使用**。工具自动化操作杭工e家账号，属于违反平台用户协议的灰产行为，
  现实后果可能是积分清零、兑换限制、账号封禁；使用者的账号风险自担。
- 口令勿外传；发现滥用直接删 `passcodes.json` 里对应行吊销。
- 本服务**没有**做 IP 伪装/代理轮换/设备指纹随机化等对抗平台风控的设施，也不建议做。
- 手机浏览器访问时上报的是浏览器真实机型与每浏览器一个的稳定设备 ID（等同正常 App 行为）；
  桌面浏览器从 30 款已核实的真实机型池随机分配一台并长期固定（型号码均真实存在，与 UA 内部自洽）。
- 服务器侧日志不含任何凭据；杭工会话（login_name/ses_id）仅存于各用户自己的浏览器 localStorage。

## 目录结构

```
Web_Version/
├── server/
│   ├── hz_api.py              # 加解密 + 杭工接口封装（Login.py 参数化移植，IPv4 优先解析）
│   ├── app.py                 # Flask 路由 + 口令鉴权 + 静态托管
│   ├── requirements.txt
│   └── passcodes.json.example # 口令模板
├── web/
│   ├── index.html             # 四视图：口令/登录/主页/乘车码
│   ├── app.js                 # 主逻辑（抢票循环在浏览器）
│   ├── api.js                 # fetch 封装 + 设备信息
│   └── style.css
└── deploy/
    ├── hgej-web.service       # systemd
    └── nginx-web.conf         # Nginx 反代示例
```
