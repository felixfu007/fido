/**
 * 購物網站 FIDO 串接參考範例 — 前端示範腳本。
 *
 * 只依賴瀏覽器原生 navigator.credentials API（不引入任何 WebAuthn 函式庫），
 * 目的是讓串接邏輯（哪個欄位要轉 ArrayBuffer、哪個要轉回 base64url）完全攤在陽光下，
 * 方便照抄。所有呼叫都打向本站後端的 /shop/api/fido/* 端點（見 controller），
 * 由後端代理呼叫 fido-server（本頁完全不知道、也不應該知道 fido-server 的網址或 API Key）。
 */

// ---------------------------------------------------------------------------
// base64url <-> ArrayBuffer 轉換工具
// WebAuthn 瀏覽器 API 一律使用 ArrayBuffer/Uint8Array；docs/api-contract.md 1.5 規定
// 所有二進位欄位在 HTTP JSON 傳輸時一律 base64url（無 padding）字串。這組工具函式就是
// 兩邊互轉的地方，串接時最容易出錯（padding、URL-safe 字元）就在這裡。
// ---------------------------------------------------------------------------
function base64urlToBuffer(base64url) {
  const padded = base64url.replace(/-/g, '+').replace(/_/g, '/')
    + '=='.slice(0, (4 - (base64url.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function bufferToBase64url(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function log(elementId, label, data) {
  const el = document.getElementById(elementId);
  const line = `[${new Date().toISOString()}] ${label}\n${JSON.stringify(data, null, 2)}\n\n`;
  el.textContent += line;
  el.scrollTop = el.scrollHeight;
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.body = json;
    throw err;
  }
  return json;
}

// ---------------------------------------------------------------------------
// 1. 註冊 FIDO 裝置：/shop/api/fido/registration/options -> navigator.credentials.create()
//    -> /shop/api/fido/registration/result
// ---------------------------------------------------------------------------
document.getElementById('btnRegister').addEventListener('click', async () => {
  const externalUserId = document.getElementById('externalUserId').value;
  const displayName = document.getElementById('displayName').value;
  const deviceLabel = document.getElementById('deviceLabel').value;

  try {
    // 步驟 1：跟購物網站後端要 WebAuthn creation options（後端會代呼叫 fido-server）
    const optionsResponse = await postJson('/shop/api/fido/registration/options', {
      externalUserId, displayName, deviceLabel,
    });
    log('registrationLog', 'registration/options 回應', optionsResponse);

    const publicKey = optionsResponse.publicKey;
    const createOptions = {
      publicKey: {
        rp: publicKey.rp,
        user: {
          id: base64urlToBuffer(publicKey.user.id),
          name: publicKey.user.name,
          displayName: publicKey.user.displayName,
        },
        challenge: base64urlToBuffer(publicKey.challenge),
        pubKeyCredParams: publicKey.pubKeyCredParams,
        timeout: publicKey.timeout,
        attestation: publicKey.attestation,
        authenticatorSelection: publicKey.authenticatorSelection,
        excludeCredentials: (publicKey.excludeCredentials || []).map(c => ({
          type: c.type,
          id: base64urlToBuffer(c.id),
          transports: c.transports,
        })),
      },
    };

    // 步驟 2：觸發瀏覽器原生 WebAuthn ceremony（會跳出系統的 Passkey / Credential Manager UI）
    const credential = await navigator.credentials.create(createOptions);

    // 步驟 3：把瀏覽器回傳的 ArrayBuffer 欄位轉回 base64url，送回購物網站後端驗證
    const resultBody = {
      ceremonyId: optionsResponse.ceremonyId,
      externalUserId,
      credential: {
        id: credential.id, // 瀏覽器已經回傳 base64url 字串，不需再轉
        rawId: bufferToBase64url(credential.rawId),
        type: credential.type,
        response: {
          clientDataJSON: bufferToBase64url(credential.response.clientDataJSON),
          attestationObject: bufferToBase64url(credential.response.attestationObject),
          transports: credential.response.getTransports ? credential.response.getTransports() : undefined,
        },
      },
      deviceLabel,
    };
    log('registrationLog', 'navigator.credentials.create() 完成，送出 registration/result', resultBody);

    const resultResponse = await postJson('/shop/api/fido/registration/result', resultBody);
    log('registrationLog', 'registration/result 回應（註冊成功）', resultResponse);
  } catch (err) {
    log('registrationLog', '註冊失敗', err.body || { message: err.message });
  }
});

// ---------------------------------------------------------------------------
// 2. 用 FIDO 登入：/shop/api/fido/authentication/options -> navigator.credentials.get()
//    -> /shop/api/fido/authentication/result
// ---------------------------------------------------------------------------
document.getElementById('btnLogin').addEventListener('click', async () => {
  const externalUserId = document.getElementById('externalUserId').value;

  try {
    const optionsResponse = await postJson('/shop/api/fido/authentication/options', { externalUserId });
    log('loginLog', 'authentication/options 回應', optionsResponse);

    const publicKey = optionsResponse.publicKey;
    const getOptions = {
      publicKey: {
        challenge: base64urlToBuffer(publicKey.challenge),
        timeout: publicKey.timeout,
        rpId: publicKey.rpId,
        userVerification: publicKey.userVerification,
        allowCredentials: (publicKey.allowCredentials || []).map(c => ({
          type: c.type,
          id: base64urlToBuffer(c.id),
          transports: c.transports,
        })),
      },
    };

    const assertion = await navigator.credentials.get(getOptions);

    const resultBody = {
      ceremonyId: optionsResponse.ceremonyId,
      credential: {
        id: assertion.id,
        rawId: bufferToBase64url(assertion.rawId),
        type: assertion.type,
        response: {
          clientDataJSON: bufferToBase64url(assertion.response.clientDataJSON),
          authenticatorData: bufferToBase64url(assertion.response.authenticatorData),
          signature: bufferToBase64url(assertion.response.signature),
          userHandle: assertion.response.userHandle ? bufferToBase64url(assertion.response.userHandle) : undefined,
        },
      },
    };
    log('loginLog', 'navigator.credentials.get() 完成，送出 authentication/result', resultBody);

    // 注意：這裡回應的 body 是購物網站後端自己包裝的 ShopLoginResponse，
    // 不是 fido-server 原始的 AuthenticationResultResponse —— fido-server 回應裡的
    // session JWT 已經在後端被驗證並消費掉，不會、也不應該回傳給瀏覽器前端。
    const loginResponse = await postJson('/shop/api/fido/authentication/result', resultBody);
    log('loginLog', 'authentication/result 回應（購物網站已建立 SHOP_SESSION cookie）', loginResponse);
  } catch (err) {
    log('loginLog', '登入失敗', err.body || { message: err.message });
  }
});

// ---------------------------------------------------------------------------
// 3. 裝置管理：GET /shop/api/fido/devices、DELETE /shop/api/fido/devices/{deviceId}
// ---------------------------------------------------------------------------
async function listDevices() {
  const externalUserId = document.getElementById('externalUserId').value;
  const res = await fetch(`/shop/api/fido/devices?externalUserId=${encodeURIComponent(externalUserId)}&status=ALL`,
    { credentials: 'same-origin' });
  const json = await res.json();
  log('deviceLog', 'GET /shop/api/fido/devices 回應', json);

  const tbody = document.querySelector('#deviceTable tbody');
  tbody.innerHTML = '';
  for (const d of json.devices || []) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${d.deviceId}</td>
      <td>${d.deviceName || ''}</td>
      <td>${d.securityLevel || ''}</td>
      <td>${d.status}</td>
      <td>${d.lastUsedAt || ''}</td>
      <td><button data-device-id="${d.deviceId}" class="btnRevoke">撤銷</button></td>
    `;
    tbody.appendChild(tr);
  }
  document.querySelectorAll('.btnRevoke').forEach(btn => {
    btn.addEventListener('click', async () => {
      const deviceId = btn.getAttribute('data-device-id');
      const revokeRes = await fetch(
        `/shop/api/fido/devices/${encodeURIComponent(deviceId)}?externalUserId=${encodeURIComponent(externalUserId)}`,
        { method: 'DELETE', credentials: 'same-origin' });
      const revokeJson = await revokeRes.json();
      log('deviceLog', `DELETE /shop/api/fido/devices/${deviceId} 回應`, revokeJson);
      listDevices();
    });
  });
}

document.getElementById('btnListDevices').addEventListener('click', listDevices);
