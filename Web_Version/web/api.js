/* api.js - 后端请求封装与设备信息 */
(function () {
  'use strict';

  const KEY_STORAGE = 'hgej_site_key';
  const TERM_STORAGE = 'hgej_term_id';
  const MODEL_STORAGE = 'hgej_model';

  // 桌面端回退机型池：30 款 2023-2024 真实在售机型（型号码已逐款核实），均为
  // Android 13/14 世代，与后端拼接的 Android 14 UA 自洽。
  // 每浏览器随机分配一次后固定，避免所有桌面用户共用同一机型
  const MODEL_POOL = [
    // 小米 / 红米
    '23127PN0CC', // 小米14
    '23116PN5BC', // 小米14 Pro
    '24030PN60G', // 小米14 Ultra
    '2211133C',   // 小米13
    '2210132C',   // 小米13 Pro
    '23013RK75C', // Redmi K60
    '23117RK66C', // Redmi K70 Pro
    '2312DRA50C', // Redmi Note 13 Pro
    '23090RA98C', // Redmi Note 13 Pro+
    '23049RAD8C', // Redmi Note 12 Turbo
    '24053RN09D', // Redmi Turbo 3
    // OPPO / 一加
    'PHZ110',     // OPPO Find X7
    'PHW110',     // OPPO Reno10
    'PJZ110',     // 一加 12
    'PJE110',     // 一加 Ace 3
    // vivo / iQOO
    'V2310A',     // vivo X100
    'V2324A',     // vivo X100 Pro
    'V2371A',     // iQOO Z9 Turbo
    // 三星
    'SM-S9210',   // Galaxy S24
    'SM-S9260',   // Galaxy S24+
    'SM-S9280',   // Galaxy S24 Ultra
    'SM-S9110',   // Galaxy S23
    'SM-S9180',   // Galaxy S23 Ultra
    'SM-A5560',   // Galaxy A55
    // 荣耀
    'BVL-AN00',   // 荣耀 Magic6
    'BVL-AN16',   // 荣耀 Magic6 Pro
    'MAA-AN00',   // 荣耀 100
    'MAA-AN10',   // 荣耀 100 Pro
    'PGT-AN10'    // 荣耀 Magic5
  ];

  function pickModel() {
    let m = localStorage.getItem(MODEL_STORAGE);
    if (!m || !MODEL_POOL.includes(m)) {
      const idx = crypto.getRandomValues(new Uint32Array(1))[0] % MODEL_POOL.length;
      m = MODEL_POOL[idx];
      localStorage.setItem(MODEL_STORAGE, m);
    }
    return m;
  }

  function siteKey() { return localStorage.getItem(KEY_STORAGE) || ''; }
  function setSiteKey(k) { localStorage.setItem(KEY_STORAGE, k); }
  function clearSiteKey() { localStorage.removeItem(KEY_STORAGE); }

  /**
   * 设备信息：手机浏览器优先上报 UA 中的真实机型；
   * 桌面/提取失败时从机型池随机分配一个并长期固定。
   * term_id 为每浏览器一个的稳定随机 ID（首次访问生成，永久使用）
   */
  function device() {
    let model = null;
    const m = navigator.userAgent.match(/Android[^;]*;\s*([^;)]+?)(?:\s+Build\/|\))/);
    if (m && m[1]) model = m[1].trim();
    if (!model) model = pickModel();
    let termId = localStorage.getItem(TERM_STORAGE);
    if (!termId || !/^[0-9a-f]{16}$/.test(termId)) {
      const bytes = new Uint8Array(8);
      crypto.getRandomValues(bytes);
      termId = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
      localStorage.setItem(TERM_STORAGE, termId);
    }
    return { model: model, term_id: termId };
  }

  async function post(path, body) {
    let res;
    try {
      res = await fetch('/api/' + path, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Site-Key': siteKey()
        },
        body: JSON.stringify(body || {})
      });
    } catch (e) {
      throw new Error('网络请求失败，请检查连接');
    }
    let data = {};
    try { data = await res.json(); } catch (e) { /* 空响应体 */ }
    if (res.status === 401) {
      const err = new Error(data.error || '口令无效或会话已失效');
      err.status = 401;
      err.sessionExpired = !!data.sessionExpired;
      throw err;
    }
    if (!res.ok) throw new Error(data.error || ('请求失败 (' + res.status + ')'));
    return data;
  }

  window.HgejApi = {
    siteKey: siteKey,
    setSiteKey: setSiteKey,
    clearSiteKey: clearSiteKey,
    device: device,
    post: post
  };
})();
