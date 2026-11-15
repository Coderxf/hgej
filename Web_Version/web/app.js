/* app.js - 杭工助手网页版主逻辑
 * 视图流：口令 → 登录 → 主页/乘车码
 * 吸收 Android 版修复：登录失败自动刷新验证码、切换模式重置短信状态、发短信前空校验、
 * 会话失效自动登出、服务器时间偏移校准抢票、次数 1~20 / 间隔 ≥0.15s、地铁券正确字段
 */
(function () {
  'use strict';

  const API = window.HgejApi;
  const AUTH_KEY = 'hgej_auth';
  const $ = (id) => document.getElementById(id);

  // ---------------- 全局状态 ----------------
  let auth = null;            // {login_name, user_id, ses_id}
  let smsTimer = null;        // 短信倒计时
  let grabRunning = false;    // 抢票循环开关

  // ---------------- 工具 ----------------
  function loadAuth() {
    try { auth = JSON.parse(localStorage.getItem(AUTH_KEY) || 'null'); } catch (e) { auth = null; }
    return auth;
  }
  function saveAuth(a) { auth = a; localStorage.setItem(AUTH_KEY, JSON.stringify(a)); }
  function clearAuth() { auth = null; localStorage.removeItem(AUTH_KEY); }

  function show(id) {
    ['view-unlock', 'view-login', 'view-main', 'view-qr'].forEach(v => $(v).classList.add('hidden'));
    $(id).classList.remove('hidden');
  }
  function showMsg(el, text) { el.textContent = text || ''; el.classList.toggle('hidden', !text); }
  function nowStr() {
    const d = new Date();
    return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0');
  }
  function appendLog(box, msg) {
    const div = document.createElement('div');
    div.textContent = '[' + nowStr() + '] ' + msg;
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
  }
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  // 会话失效：清会话回登录页（Android 版行为）
  function onSessionExpired() {
    clearAuth();
    loadAuth();
    refreshTopbar();
    show('view-login');
    fetchCaptcha();
  }

  async function call(path, body) {
    try {
      return await API.post(path, body);
    } catch (e) {
      if (e.sessionExpired) {
        appendLog($('grabLogs'), '登录会话已失效，请重新登录');
        onSessionExpired();
      }
      throw e;
    }
  }

  // ---------------- 顶栏 ----------------
  function refreshTopbar() {
    const logged = !!auth;
    $('navHome').classList.toggle('hidden', !logged);
    $('navQr').classList.toggle('hidden', !logged);
    $('btnLogout').classList.toggle('hidden', !logged);
    $('userChip').classList.toggle('hidden', !logged);
  }

  // ---------------- 1. 站点口令 ----------------
  async function tryUnlockStored() {
    if (!API.siteKey()) return false;
    try {
      await API.post('unlock', {});
      return true;
    } catch (e) {
      API.clearSiteKey();
      return false;
    }
  }

  async function initUnlock() {
    if (await tryUnlockStored()) return enterApp();
    show('view-unlock');
    $('unlockInput').focus();
  }

  $('unlockBtn').addEventListener('click', async () => {
    const code = $('unlockInput').value.trim();
    if (!code) return;
    API.setSiteKey(code);
    try {
      await API.post('unlock', {});
      showMsg($('unlockMsg'), '');
      enterApp();
    } catch (e) {
      API.clearSiteKey();
      showMsg($('unlockMsg'), e.message);
    }
  });
  $('unlockInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('unlockBtn').click(); });

  function enterApp() {
    if (loadAuth()) return enterMain();
    show('view-login');
    fetchCaptcha();
  }

  // ---------------- 2. 登录 ----------------
  let smsSent = false;

  let loginMode = 'pwd'; // pwd | sms | cred

  async function fetchCaptcha(clearMsg = true) {
    // 失败重试场景传 false：保留已有错误提示（只换图不清提示）
    if (clearMsg) showMsg($('loginMsg'), '');
    $('captchaCode').value = '';
    try {
      const data = await API.post('captcha', { device: API.device() });
      let img = data.img || '';
      if (img && !img.startsWith('data:')) img = 'data:image/png;base64,' + img;
      $('captchaImg').src = img;
      $('captchaImg').dataset.uniCode = data.imgUniCode || '';
    } catch (e) {
      $('captchaImg').src = '';
      $('captchaImg').dataset.uniCode = '';
      showMsg($('loginMsg'), '验证码获取失败: ' + e.message);
    }
  }
  $('captchaImg').addEventListener('click', fetchCaptcha);

  function setLoginMode(mode) {
    loginMode = mode;
    // 切换模式重置短信状态与倒计时（Android 修复）
    smsSent = false;
    $('smsCode').value = '';
    if (smsTimer) { clearInterval(smsTimer); smsTimer = null; }
    $('smsCountdown').classList.add('hidden');
    // 仅倒计时进行中禁用；切到短信模式即可点击（否则与倒计时互相死锁）
    $('smsResend').disabled = false;
    $('pwdRow').classList.toggle('hidden', mode !== 'pwd');
    $('smsRow').classList.toggle('hidden', mode !== 'sms');
    $('credRow').classList.toggle('hidden', mode !== 'cred');
    // 凭证登录不需要手机号与图形验证码
    $('loginPhone').classList.toggle('hidden', mode === 'cred');
    $('captchaImg').classList.toggle('hidden', mode === 'cred');
    $('captchaCode').classList.toggle('hidden', mode === 'cred');
    $('tabPwd').classList.toggle('active', mode === 'pwd');
    $('tabSms').classList.toggle('active', mode === 'sms');
    $('tabCred').classList.toggle('active', mode === 'cred');
    showMsg($('loginMsg'), '');
  }
  $('tabPwd').addEventListener('click', () => { setLoginMode('pwd'); fetchCaptcha(); });
  $('tabSms').addEventListener('click', () => { setLoginMode('sms'); fetchCaptcha(); });
  $('tabCred').addEventListener('click', () => setLoginMode('cred'));

  function startSmsCountdown() {
    let left = 60;
    $('smsResend').disabled = true;
    $('smsCountdown').classList.remove('hidden');
    const tick = () => {
      $('smsCountdown').textContent = left + 's 后可重新发送';
      if (left-- <= 0) {
        clearInterval(smsTimer); smsTimer = null;
        $('smsCountdown').classList.add('hidden');
        $('smsResend').disabled = false;
      }
    };
    tick();
    smsTimer = setInterval(tick, 1000);
  }

  $('smsResend').addEventListener('click', async () => {
    const phone = $('loginPhone').value.trim();
    const code = $('captchaCode').value.trim();
    if (!phone) return showMsg($('loginMsg'), '请输入手机号');
    if (!code) return showMsg($('loginMsg'), '请先输入图形验证码'); // 发短信前空校验（Android 修复）
    try {
      await API.post('login/sms-send', {
        login_name: phone, imgAuthCode: code,
        imgUniCode: $('captchaImg').dataset.uniCode || '',
        device: API.device()
      });
      smsSent = true;
      startSmsCountdown();
      showMsg($('loginMsg'), '');
    } catch (e) {
      showMsg($('loginMsg'), e.message);
      fetchCaptcha(false); // 失败自动换验证码但保留错误提示（Android 修复）
    }
  });

  function handleLoginOk(data) {
    saveAuth({
      login_name: data.login_name || $('loginPhone').value.trim(),
      user_id: data.user_id || data.login_name || $('loginPhone').value.trim(),
      ses_id: data.ses_id || ''
    });
    refreshTopbar();
    enterMain();
  }

  // 凭证登录：直接粘贴 LOGIN_NAME + SES_ID，用 user-info 校验有效性
  async function credentialLogin() {
    const name = $('credName').value.trim();
    const ses = $('credSes').value.trim();
    if (!name || !ses) return showMsg($('loginMsg'), '请填写 LOGIN_NAME 和 SES_ID');
    const btn = $('loginBtn');
    btn.disabled = true; btn.textContent = '验证中...';
    try {
      await API.post('user-info', { login_name: name, ses_id: ses });
      saveAuth({ login_name: name, user_id: name, ses_id: ses });
      refreshTopbar();
      enterMain();
    } catch (e) {
      showMsg($('loginMsg'), e.sessionExpired ? '凭证无效或已过期，请检查后重试' : e.message);
    } finally {
      btn.disabled = false; btn.textContent = '登录';
    }
  }

  $('loginBtn').addEventListener('click', async () => {
    if (loginMode === 'cred') return credentialLogin();
    const phone = $('loginPhone').value.trim();
    if (!phone) return showMsg($('loginMsg'), '请输入手机号');
    const captcha = $('captchaCode').value.trim();
    if (!captcha) return showMsg($('loginMsg'), '请输入图形验证码');

    const btn = $('loginBtn');
    btn.disabled = true;
    try {
      if (loginMode === 'pwd') {
        const pwd = $('loginPwd').value;
        if (!pwd) { showMsg($('loginMsg'), '请输入密码'); return; }
        const data = await API.post('login/password', {
          login_name: phone, password: pwd, imgAuthCode: captcha,
          imgUniCode: $('captchaImg').dataset.uniCode || '', device: API.device()
        });
        handleLoginOk(data);
      } else {
        const sms = $('smsCode').value.trim();
        if (!sms) { showMsg($('loginMsg'), '请输入短信验证码'); return; }
        if (!smsSent) { showMsg($('loginMsg'), '请先获取短信验证码'); return; }
        const data = await API.post('login/sms', {
          login_name: phone, authCode: sms, device: API.device()
        });
        handleLoginOk(data);
      }
    } catch (e) {
      showMsg($('loginMsg'), e.message);
      if (e.sessionExpired) onSessionExpired();
      else fetchCaptcha(false); // 验证码一次性，失败自动换但保留错误提示（Android 修复）
    } finally {
      btn.disabled = false;
    }
  });

  // ---------------- 3. 主页 ----------------
  async function enterMain() {
    show('view-main');
    refreshTopbar();
    refreshUserInfo();
  }

  async function refreshUserInfo() {
    $('stLogin').textContent = '检查中';
    $('stName').classList.add('hidden');
    $('stPoints').classList.add('hidden');
    try {
      const info = await call('user-info', auth);
      $('stLogin').textContent = '已登录';
      if (info.name) {
        $('stName').textContent = info.name;
        $('stName').classList.remove('hidden');
        $('userChip').textContent = info.name;
      }
      if (info.remain_integral && parseInt(info.remain_integral, 10) > 0) {
        $('stPoints').textContent = '积分 ' + info.remain_integral;
        $('stPoints').classList.remove('hidden');
      }
      $('stAccount').textContent = '账号: ' + (auth.login_name || '');
    } catch (e) {
      if (!e.sessionExpired) {
        $('stLogin').textContent = '获取失败';
        $('stAccount').textContent = e.message;
      }
    }
  }

  // ---- 每日任务 ----
  $('dailyBtn').addEventListener('click', async () => {
    const btn = $('dailyBtn');
    btn.disabled = true; btn.textContent = '执行中...';
    const logs = $('dailyLogs');
    logs.classList.remove('hidden');
    logs.innerHTML = '';
    appendLog(logs, '开始每日任务...');
    try {
      const data = await call('daily-task', auth);
      (data.logs || []).forEach(l => appendLog(logs, l));
      appendLog(logs, '每日任务完成');
    } catch (e) {
      appendLog(logs, '每日任务失败: ' + e.message);
    } finally {
      btn.disabled = false; btn.textContent = '执行每日任务';
    }
  });

  // ---- 抢票 ----
  const WINdowS = [
    { h: 7, m: 0 },
    { h: 11, m: 30 },
    { h: 17, m: 0 },
  ];

  function pad(n) { return String(n).padStart(2, '0'); }
  function toLocalInput(d) {
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
      'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  $('grabAutoTime').addEventListener('click', () => {
    const now = new Date();
    let target = null;
    for (const w of WINdowS) {
      const t = new Date(now.getFullYear(), now.getMonth(), now.getDate(), w.h, w.m, 0);
      if (t > now) { target = t; break; }
    }
    if (!target) target = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 7, 0, 0);
    $('grabTime').value = toLocalInput(target);
  });

  let serverOffsetMs = null;

  async function syncServerTime(force) {
    if (serverOffsetMs !== null && !force) return serverOffsetMs;
    try {
      const data = await call('server-time', {});
      serverOffsetMs = data.offset_ms || 0;
      const sign = serverOffsetMs >= 0 ? '+' : '';
      $('grabServerTime').textContent = '服务器时间已校准，本地偏移 ' + sign + serverOffsetMs + 'ms';
    } catch (e) {
      serverOffsetMs = null;
      $('grabServerTime').textContent = '服务器时间同步失败，将使用本地时间';
    }
    return serverOffsetMs;
  }

  function serverNow() {
    return Date.now() + (serverOffsetMs || 0);
  }

  // 梯度等待：远间隔粗睡，临近精睡（Python/Android 版节奏）
  async function waitUntil(targetMs) {
    while (grabRunning) {
      const diff = targetMs - serverNow();
      if (diff <= 0) return true;
      const wait = diff > 3600000 ? 300000 :
        diff > 600000 ? 60000 :
        diff > 60000 ? 30000 :
        diff > 1000 ? 500 : 50;
      await sleep(Math.min(wait, diff));
    }
    return false;
  }

  $('grabStart').addEventListener('click', async () => {
    if (grabRunning) return;
    const timeVal = $('grabTime').value;
    const count = parseInt($('grabCount').value, 10);
    const sleepS = parseFloat($('grabSleep').value);
    const targetMs = timeVal ? new Date(timeVal).getTime() : NaN;

    if (!timeVal || isNaN(targetMs)) return alert('请设置执行时间');
    const countOk = !isNaN(count) && count >= 1 && count <= 20;
    const sleepOk = !isNaN(sleepS) && sleepS >= 0.15;
    if (!countOk || !sleepOk) return alert('次数需 1~20，间隔需不小于 0.15 秒');

    grabRunning = true;
    $('grabStart').classList.add('hidden');
    $('grabStop').classList.remove('hidden');
    const logs = $('grabLogs');
    logs.innerHTML = '';

    try {
      const offset = await syncServerTime(false);
      if (offset === null) await syncServerTime(true);
      const localStr = new Date(targetMs).toLocaleString('zh-CN');
      appendLog(logs, '目标时间: ' + localStr + '，次数 ' + count + '，间隔 ' + sleepS + 's');
      const diff = targetMs - serverNow();
      if (diff > 0) {
        appendLog(logs, '等待中...');
        const ok = await waitUntil(targetMs);
        if (!ok) { appendLog(logs, '已停止'); return; }
      }
      appendLog(logs, '开始兑换');
      for (let i = 1; i <= count && grabRunning; i++) {
        // 不 await 完成再发下一发：与各版本一致按提交节奏控制
        call('exchange', {
          login_name: auth.login_name, user_id: auth.user_id,
          ses_id: auth.ses_id, exchange_id: $('grabAward').value
        }).then(r => appendLog(logs, '[' + i + '] ' + (r.msg || '无响应')))
          .catch(e => appendLog(logs, '[' + i + '] 失败: ' + e.message));
        if (i < count) await sleep(sleepS * 1000);
      }
      await sleep(1500);
      appendLog(logs, '抢票任务完成');
    } finally {
      grabRunning = false;
      $('grabStart').classList.remove('hidden');
      $('grabStop').classList.add('hidden');
    }
  });

  $('grabStop').addEventListener('click', () => {
    grabRunning = false;
    appendLog($('grabLogs'), '停止请求已发出...');
  });

  // ---------------- 4. 乘车码 ----------------
  async function loadQr() {
    $('qrLoading').classList.remove('hidden');
    $('qrImg').classList.add('hidden');
    $('qrInfo').classList.add('hidden');
    showMsg($('qrMsg'), '');
    try {
      const d = await call('qrcode', auth);
      const imgData = d.qrcodeImage || d.qrcode;
      if (!imgData) throw new Error('无二维码数据');
      if (/^[0-9a-fA-F]+$/.test(imgData) && imgData.length > 64) {
        // hex 编码的图片字节 → Blob
        const bytes = new Uint8Array(imgData.length / 2);
        for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(imgData.substr(i * 2, 2), 16);
        $('qrImg').src = URL.createObjectURL(new Blob([bytes]));
      } else {
        $('qrImg').src = imgData.startsWith('data:') ? imgData : 'data:image/png;base64,' + imgData;
      }
      $('qrImg').classList.remove('hidden');
      $('qrMoney').textContent = d.money || '未知';
      $('qrCard').textContent = d.trafficCardNo || '未知';
      $('qrDead').textContent = d.deadTime ? formatTs(d.deadTime) : '未知';
      $('qrInfo').classList.remove('hidden');
    } catch (e) {
      showMsg($('qrMsg'), e.message);
    } finally {
      $('qrLoading').classList.add('hidden');
    }
  }

  function formatTs(ts) {
    const n = parseInt(ts, 10);
    if (isNaN(n)) return ts;
    return new Date(n * 1000).toLocaleString('zh-CN');
  }

  async function loadTickets(awardType) {
    const stats = $('ticketStats');
    const recBox = $('ticketRecords');
    stats.textContent = '加载中...';
    recBox.innerHTML = '';
    try {
      const d = await call('subway-tickets', { ...auth, award_type: awardType });
      stats.innerHTML =
        '当前可用 — 2元: <b>' + (d.num_2 || 0) + '</b> · 4元: <b>' + (d.num_4 || 0) +
        '</b> · 6元: <b>' + (d.num_6 || 0) + '</b><br>' +
        '总获得: <b>' + (d.totalObtained || 0) + '</b> · 已使用: <b>' + (d.use_num || 0) +
        '</b> · 已过期: <b>' + (d.expire_num || 0) + '</b>';
      stats.classList.remove('muted');
      (d.records || []).forEach(r => {
        const div = document.createElement('div');
        div.className = 'record';
        const name = r.exchange_name || r.award_name || '-';
        const state = r.use_state_name || (r.use_state === '1' ? '未使用' : r.use_state === '2' ? '已使用' : '-');
        div.innerHTML = '<b></b> · ' + state + '<br>领取: ' + (r.create_time || '-') +
          '<br>过期: ' + (r.expire_time || '-');
        div.querySelector('b').textContent = name; // 防 XSS
        recBox.appendChild(div);
      });
      if (!recBox.children.length) {
        recBox.innerHTML = '<div class="record">暂无记录</div>';
      }
    } catch (e) {
      stats.textContent = '查询失败: ' + e.message;
    }
  }

  document.querySelectorAll('.award-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.award-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      loadTickets(tab.dataset.award);
    });
  });

  // ---------------- 导航 ----------------
  $('navHome').addEventListener('click', () => { show('view-main'); refreshUserInfo(); });
  $('navQr').addEventListener('click', () => {
    show('view-qr');
    loadQr();
    loadTickets(document.querySelector('.award-tab.active').dataset.award);
  });
  $('btnLogout').addEventListener('click', () => {
    grabRunning = false;
    clearAuth();
    refreshTopbar();
    show('view-login');
    setLoginMode('pwd');
    fetchCaptcha();
  });

  // ---------------- 启动 ----------------
  setLoginMode('pwd');
  initUnlock();
})();
