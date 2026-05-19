# AutoTicket — AGENTS.md

Four variants of the same automation for 杭工e家 APP: **Python (PyQt5 GUI)**, **Kivy Android App**, **JavaScript (Node CLI)**, **WeChat Mini-Program (uni-app/Vue 3)**.

## Entrypoints

```
python gui.py            # PyQt5 desktop GUI (main)
python Login.py          # CLI login (captcha → password/SMS → auto-fill config.json)
python AutoTicket.py     # Headless timed exchange (runs `main()`)
cd JavaScript_Version && node workflow_sigin.js   # daily tasks
cd JavaScript_Version && node AutoTicket.js        # continuous exchange
cd Android_Version && python main.py                # Kivy Android app (desktop test)
cd Android_Version && buildozer android debug       # Build APK (WSL/Ubuntu)
```

## Commands

```bash
pip install -r requirements.txt      # Python deps (requests, pycryptodome, pycryptodomex)
pyinstaller --onefile --windowed --icon=./icon.ico -n AutoTicket gui.py  # build exe
cd JavaScript_Version && npm install  # JS deps (node-rsa, crypto)
```

## Commands (Android)

```bash
pip install -r Android_Version/requirements.txt      # Kivy deps (本地测试用)
python Android_Version/main.py                        # 桌面测试

# WSL/Ubuntu build environment (first time):
powershell -File install_wsl_buildozer.ps1
wsl -d Ubuntu -u root
cd /mnt/d/study/AutoTicket/Android_Version
buildozer android debug    # produces bin/AutoTicket-1.0.0-*.apk
python main.py             # desktop test with Kivy (no Android build needed)
```

## Key Architecture

- **API base**: `https://app.hzgh.org.cn/unionApp/interf/front/`
- **All requests share** this crypto pipeline (reversed from app JS in `b775` module):
  1. Generate random 24-char → RSA (PKCS1_v1_5) encrypt → `dec_key`
  2. 3DES (ECB/PKCS7) encrypt sensitive fields (`login_name`, `user_id`, `pwd`, `imgAuthCode`, etc.)
  3. SHA256withRSA sign the concatenated sorted values + salt `zSw3MLRV7VuwT!*G`
  4. Response `data2` = RSA block (172 base64 chars) + 3DES (CBC/PKCS7) ciphertext
- **Three public keys** exist (channel 02 uses `ENCRYPTION_PUBLIC_KEY_PEM`, the `-----BEGIN PUBLIC KEY----- MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC7yWoQaojBBqKI2H0j...` one)
- **Three sets of crypto keys** are hardcoded in Python sources (all from reverse engineering, not real secrets)
- **exchange_id**: `9` = 2元, `10` = 4元, `11` = 6元 coupons
- JS version has two sign salts: `qwerqaz.-*` (old) and `zSw3MLRV7VuwT!*G` (new/channel 02)

## Repo Structure Gotchas

- `config.json` is **auto-generated** by GUI and **gitignored** — never commit it
- `AutoTicket.spec` is **gitignored** — local PyInstaller artifacts
- No test framework, no CI, no linters, no type checking exist
- WeChat Mini-Program at `App_Version/app_version/` uses uni-app (Vue 3 + crypto-js + jsencrypt)
- **Android version** at `Android_Version/` is a Kivy app; `main.py` imports from `lib/backend.py` (same crypto/API as desktop). Build APK via `buildozer android debug` in WSL/Ubuntu
- Key Python imports: `pycryptodome` for `Crypto.*`, `pycryptodomex` for `Cryptodome.Hash.SHA256`
- Login flow: U067 (captcha image) → U004 (password) **or** SMS/SMS1 → U065 (SMS code)
- Green commute QR: OL82 (get token) → OP80 (log visit) → 3rd-party QR API at `hzcode.96225.com`
