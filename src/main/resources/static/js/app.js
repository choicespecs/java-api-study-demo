/* ═══════════════════════════════════════════════════════════
   REST API Study Demo — Interactive Frontend
   All pages, state, routing, and API calls in one file.
   ═══════════════════════════════════════════════════════════ */

// ── STATE ────────────────────────────────────────────────────
const State = {
  page: 'home',
  jwt: null,
  username: null,
  apiKey: 'demo-api-key-user-12345',
  paginationPage: 0,
  paginationSize: 6,
  paginationCategory: '',
  paginationSearch: '',
  cbFailures: 0,
  cbSuccesses: 0,
  cbState: 'CLOSED',
  cbLog: [],
  rateBuckets: { standard: 20, strict: 5, tiered: 10 },
};

// ── API HELPERS ───────────────────────────────────────────────
async function apiFetch(url, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (State.jwt && !options.noJwt) headers['Authorization'] = `Bearer ${State.jwt}`;
  try {
    const res = await fetch(url, { ...options, headers });
    let body;
    try { body = await res.json(); } catch { body = await res.text(); }
    return { status: res.status, ok: res.ok, body, headers: res.headers };
  } catch (e) {
    return { status: 0, ok: false, body: { error: 'Network error: ' + e.message }, headers: null };
  }
}

// ── UTILITIES ─────────────────────────────────────────────────
function syntaxHighlight(obj) {
  const json = typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2);
  return json
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g,
      m => {
        let c = 'json-number';
        if (/^"/.test(m)) c = /:$/.test(m) ? 'json-key' : 'json-string';
        else if (/true|false/.test(m)) c = 'json-bool';
        else if (/null/.test(m)) c = 'json-null';
        return `<span class="${c}">${m}</span>`;
      });
}

function statusBadge(status) {
  if (!status) return '';
  let cls = status >= 500 ? 'status-5xx' : status >= 400 ? (status === 429 ? 'status-429' : 'status-4xx') : 'status-2xx';
  return `<span class="status-badge ${cls}">HTTP ${status}</span>`;
}

function responseViewer(res, label = 'Response') {
  const body = res ? syntaxHighlight(res.body) : '<span class="response-placeholder">Hit a button to see the response here</span>';
  return `
    <div class="response-viewer">
      <div class="response-header">
        <span class="response-label">${label}</span>
        ${res ? statusBadge(res.status) : ''}
      </div>
      <div class="response-body">${body}</div>
    </div>`;
}

function escHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function requestViewer(method, url, headers = {}, body = null) {
  if (!method) {
    return `<div class="response-viewer">
      <div class="response-header"><span class="response-label">Request</span></div>
      <div class="response-body"><span class="response-placeholder">Hit a button to see the request here</span></div>
    </div>`;
  }
  const lines = [];
  lines.push(`<span class="method-${method.toLowerCase()}">${method}</span> <span class="json-string">${escHtml(url)}</span>`);
  for (const [k, v] of Object.entries(headers)) {
    const disp = (k === 'Authorization' && String(v).length > 52) ? String(v).slice(0,42)+'…' : v;
    lines.push(`<span class="req-key">${escHtml(k)}:</span> <span class="req-val">${escHtml(disp)}</span>`);
  }
  if (body !== null) {
    lines.push('');
    try {
      const p = typeof body === 'string' ? JSON.parse(body) : body;
      lines.push(syntaxHighlight(p));
    } catch { lines.push(escHtml(String(body))); }
  }
  return `<div class="response-viewer">
    <div class="response-header">
      <span class="response-label">Request</span>
      <span class="method-badge method-${method.toLowerCase()}">${method}</span>
    </div>
    <div class="response-body">${lines.join('\n')}</div>
  </div>`;
}

function b64decode(str) {
  try { return JSON.parse(atob(str.replace(/-/g, '+').replace(/_/g, '/'))); } catch { return null; }
}

function parseJwt(token) {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  return { header: b64decode(parts[0]), payload: b64decode(parts[1]), raw: parts };
}

function jwtVisualizer(token) {
  if (!token) return `<div class="text-muted text-sm">Login to see your JWT here</div>`;
  const parsed = parseJwt(token);
  if (!parsed) return `<div class="text-muted text-sm">Invalid token</div>`;
  const [h, p, s] = parsed.raw;
  return `
    <div class="jwt-parts">
      <span class="jwt-part jwt-header" title="Header">${h.substring(0,20)}…</span>
      <span class="jwt-dot">.</span>
      <span class="jwt-part jwt-payload" title="Payload">${p.substring(0,20)}…</span>
      <span class="jwt-dot">.</span>
      <span class="jwt-part jwt-sig" title="Signature">${s.substring(0,14)}…</span>
    </div>
    <div class="jwt-decoded">
      <div>
        <div class="jwt-section-label jwt-header-label">🔴 Header</div>
        <div class="response-viewer" style="font-size:11px">
          <div class="response-body">${syntaxHighlight(parsed.header)}</div>
        </div>
      </div>
      <div>
        <div class="jwt-section-label jwt-payload-label">🟢 Payload (readable!)</div>
        <div class="response-viewer" style="font-size:11px">
          <div class="response-body">${syntaxHighlight(parsed.payload)}</div>
        </div>
      </div>
      <div>
        <div class="jwt-section-label jwt-sig-label">🔵 Signature</div>
        <div class="response-viewer" style="font-size:11px">
          <div class="response-body"><span class="json-string">"${s.substring(0,24)}…"</span>
<span class="response-placeholder" style="display:block;margin-top:4px">HMAC-SHA256 of header+payload.
Cannot be decoded — only verified.</span></div>
        </div>
      </div>
    </div>`;
}

function tokenMeter(current, max, label) {
  const pct = max > 0 ? (current / max) * 100 : 0;
  const cls = pct > 60 ? 'high' : pct > 20 ? 'medium' : 'low';
  return `
    <div class="token-meter">
      <div class="token-count"><span>${label}</span><span>${current} / ${max} tokens</span></div>
      <div class="token-track"><div class="token-fill token-fill--${cls}" style="width:${pct}%"></div></div>
    </div>`;
}

// ── ROUTER ────────────────────────────────────────────────────
function navigate(page) {
  State.page = page;
  document.querySelectorAll('.nav-item').forEach(el => {
    el.classList.toggle('active', el.dataset.page === page);
  });
  render();
}

window.addEventListener('hashchange', () => {
  const page = location.hash.slice(1) || 'home';
  navigate(page);
});

// ── RENDER ENGINE ─────────────────────────────────────────────
const pages = {
  home, 'auth-jwt': authJwt, 'auth-basic': authBasic, 'auth-apikey': authApiKey,
  oauth2: oauthPage, rbac: rbacPage, 'rate-limit': rateLimitPage,
  'circuit-breaker': circuitBreakerPage, 'hanging-apis': hangingApisPage,
  'third-party': thirdPartyPage, 'partner-api': partnerApiPage,
  pagination: paginationPage, versioning: versioningPage, errors: errorsPage,
  'write-ops': writeOpsPage,
};

function render() {
  const fn = pages[State.page] || home;
  document.getElementById('content').innerHTML = `<div>${fn()}</div>`;
  updateAuthStatus();
  // Auto-load data for pages that need an initial API call
  if (State.page === 'pagination') handlers.paginationLoad();
}

function updateAuthStatus() {
  const dot  = document.getElementById('auth-status').querySelector('.status-dot');
  const text = document.getElementById('auth-status-text');
  if (State.jwt) {
    dot.className  = 'status-dot status-dot--on';
    text.textContent = `Logged in as ${State.username || 'user'}`;
  } else {
    dot.className  = 'status-dot status-dot--off';
    text.textContent = 'Not logged in';
  }
}

// ── EVENT DELEGATION ─────────────────────────────────────────
document.getElementById('main').addEventListener('click', async e => {
  const btn = e.target.closest('[data-action]');
  if (!btn || btn.disabled) return;
  const action = btn.dataset.action;
  if (handlers[action]) await handlers[action](btn);
});

document.getElementById('main').addEventListener('keydown', e => {
  if (e.key === 'Enter' && e.target.matches('[data-enter]')) {
    const action = e.target.dataset.enter;
    if (handlers[action]) handlers[action](e.target);
  }
});

document.getElementById('sidebar').addEventListener('click', e => {
  const item = e.target.closest('[data-page]');
  if (item) {
    e.preventDefault();
    navigate(item.dataset.page);
    location.hash = item.dataset.page;
  }
});

// ── ACTION HANDLERS ───────────────────────────────────────────
const handlers = {

  // ── JWT LOGIN
  async jwtLogin(btn) {
    const user = val('jwt-username');
    const pass = val('jwt-password');
    if (!user || !pass) { flashError('Enter username and password'); return; }
    setLoading(btn, true);
    const res = await apiFetch('/api/auth/login', {
      method: 'POST', noJwt: true,
      body: JSON.stringify({ username: user, password: pass })
    });
    setLoading(btn, false);
    const reqView = requestViewer('POST', '/api/auth/login',
      { 'Content-Type': 'application/json' },
      { username: user, password: '●●●●●●●●' });
    if (res.ok) {
      State.jwt = res.body.accessToken;
      State.username = user;
      // Re-render the whole page so conditional elements (Decode, Clear, Protected buttons)
      // all appear correctly based on the updated State.jwt
      render();
      setHtml('jwt-login-request', reqView);
      setHtml('jwt-login-response', responseViewer(res, 'POST /api/auth/login'));
    } else {
      setHtml('jwt-login-request', reqView);
      setHtml('jwt-login-response', responseViewer(res, 'POST /api/auth/login'));
    }
  },

  async jwtProtected() {
    const headers = {};
    if (State.jwt) headers['Authorization'] = `Bearer ${State.jwt}`;
    const res = await apiFetch('/api/jwt/protected');
    setHtml('jwt-protected-request', requestViewer('GET', '/api/jwt/protected', headers));
    setHtml('jwt-protected-response', responseViewer(res, 'GET /api/jwt/protected'));
  },

  async jwtDecode() {
    if (!State.jwt) return;
    const res = await apiFetch('/api/auth/decode', {
      method: 'POST', noJwt: true,
      body: JSON.stringify({ token: State.jwt })
    });
    setHtml('jwt-decode-result', responseViewer(res, 'Decoded Payload'));
  },

  jwtClear() {
    State.jwt = null;
    State.username = null;
    render();
  },

  // ── BASIC AUTH
  async basicTest(btn) {
    const user = val('basic-user');
    const pass = val('basic-pass');
    const endpoint = val('basic-endpoint') || '/api/basic/protected';
    setLoading(btn, true);
    const creds = btoa(`${user}:${pass}`);
    const res = await apiFetch(endpoint, {
      noJwt: true,
      headers: { Authorization: `Basic ${creds}` }
    });
    setLoading(btn, false);
    setHtml('basic-request', requestViewer('GET', endpoint, { Authorization: `Basic ${creds}` }));
    setHtml('basic-response', responseViewer(res, `GET ${endpoint}`));
  },

  // ── API KEY
  async apiKeyTest(btn) {
    const key = val('apikey-input') || State.apiKey;
    const endpoint = val('apikey-endpoint') || '/api/apikey/data';
    setLoading(btn, true);
    const res = await apiFetch(endpoint, {
      noJwt: true,
      headers: { 'X-API-Key': key }
    });
    setLoading(btn, false);
    setHtml('apikey-request', requestViewer('GET', endpoint, { 'X-API-Key': key }));
    setHtml('apikey-response', responseViewer(res, `GET ${endpoint}`));
  },

  // ── OAUTH2
  async oauthClientCreds(btn) {
    setLoading(btn, true);
    const creds = btoa('machine-client:machine-secret');
    const res = await apiFetch('/oauth2/token', {
      method: 'POST', noJwt: true,
      headers: {
        'Authorization': `Basic ${creds}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: 'grant_type=client_credentials&scope=read'
    });
    setLoading(btn, false);
    setHtml('oauth-token-request', requestViewer('POST', '/oauth2/token', {
      'Authorization': `Basic ${creds}`,
      'Content-Type': 'application/x-www-form-urlencoded'
    }, 'grant_type=client_credentials&scope=read'));
    setHtml('oauth-token-response', responseViewer(res, 'POST /oauth2/token'));
    if (res.ok && res.body.access_token) {
      const token = res.body.access_token;
      setHtml('oauth-token-visual', `
        <div class="alert alert-success">OAuth2 token received! Using it to call the resource server...</div>
        ${jwtVisualizer(token)}
      `);
      // Now call the resource server
      const apiRes = await apiFetch('/api/oauth/data', {
        noJwt: true,
        headers: { Authorization: `Bearer ${token}` }
      });
      setHtml('oauth-api-request', requestViewer('GET', '/api/oauth/data', { Authorization: `Bearer ${token}` }));
      setHtml('oauth-api-response', responseViewer(apiRes, 'GET /api/oauth/data (Resource Server)'));
    }
  },

  oauthStartFlow() {
    const url = '/oauth2/authorize?client_id=web-client&response_type=code' +
      '&redirect_uri=http://localhost:8080/api/oauth/callback&scope=openid+read';
    window.open(url, '_blank', 'width=600,height=700');
  },

  // ── RBAC
  async rbacTest(btn) {
    const role = val('rbac-role') || 'user';
    const endpoint = val('rbac-endpoint') || '/api/rbac/viewer';
    // Login as that role first
    setLoading(btn, true);
    const loginRes = await apiFetch('/api/auth/login', {
      method: 'POST', noJwt: true,
      body: JSON.stringify({ username: role, password: 'password' })
    });
    if (!loginRes.ok) {
      setLoading(btn, false);
      setHtml('rbac-request', requestViewer('POST', '/api/auth/login', {'Content-Type':'application/json'}, {username: role, password: '●●●●●●●●'}));
      setHtml('rbac-response', responseViewer(loginRes, 'Login failed'));
      return;
    }
    const token = loginRes.body.accessToken;
    const res = await apiFetch(endpoint, {
      noJwt: true,
      headers: { Authorization: `Bearer ${token}` }
    });
    setLoading(btn, false);
    setHtml('rbac-request', requestViewer('GET', endpoint, { Authorization: `Bearer ${token}` }));
    setHtml('rbac-response', `
      <div class="mb-8"><strong>Logged in as:</strong> <code>${role}</code>
        <span class="tag tag-purple ml-8">${loginRes.body.roles?.join(', ')}</span>
      </div>
      ${responseViewer(res, `GET ${endpoint}`)}
    `);
  },

  // ── RATE LIMIT
  async rateSend(btn) {
    const endpoint = btn.dataset.endpoint || '/api/rate/standard';
    const key = btn.dataset.key || 'standard';
    setLoading(btn, true);
    const res = await apiFetch(endpoint, { noJwt: true });
    setLoading(btn, false);
    const remaining = res.headers?.get('X-Rate-Limit-Remaining');
    const limit = res.headers?.get('X-Rate-Limit-Limit');
    if (remaining !== null && limit !== null) {
      State.rateBuckets[key] = parseInt(remaining);
    }
    if (!res.ok) State.rateBuckets[key] = 0;
    setHtml(`rate-${key}-meter`, tokenMeter(State.rateBuckets[key], parseInt(limit || 20), 'Token bucket'));
    setHtml(`rate-${key}-request`, requestViewer('GET', endpoint, {}));
    setHtml(`rate-${key}-response`, responseViewer(res, `GET ${endpoint}`));
  },

  async rateRapidFire(btn) {
    const endpoint = btn.dataset.endpoint || '/api/rate/standard';
    const key = btn.dataset.key || 'standard';
    btn.disabled = true;
    for (let i = 0; i < 10; i++) {
      const res = await apiFetch(endpoint, { noJwt: true });
      const remaining = res.headers?.get('X-Rate-Limit-Remaining');
      const limit = res.headers?.get('X-Rate-Limit-Limit');
      if (remaining !== null && limit !== null) State.rateBuckets[key] = parseInt(remaining);
      if (!res.ok) State.rateBuckets[key] = 0;
      setHtml(`rate-${key}-meter`, tokenMeter(State.rateBuckets[key], parseInt(limit || 20), `Token bucket (request ${i+1}/10)`));
      setHtml(`rate-${key}-request`, requestViewer('GET', endpoint, {}));
      setHtml(`rate-${key}-response`, responseViewer(res, `Request ${i+1}/10 → GET ${endpoint}`));
      await sleep(120);
    }
    btn.disabled = false;
  },

  // ── CIRCUIT BREAKER
  async cbSend(btn) {
    const fail = btn.dataset.fail === 'true';
    setLoading(btn, true);
    const url = `/api/timeout/unreliable?fail=${fail}`;
    const res = await apiFetch(url, { noJwt: true });
    setLoading(btn, false);

    const isFallback = typeof res.body?.result === 'string' && res.body.result.includes('FALLBACK');

    if (fail || !res.ok) State.cbFailures++;
    else if (!isFallback) State.cbSuccesses++;

    const logEntry = { time: new Date().toLocaleTimeString(), fail, isFallback, status: res.status };
    State.cbLog.unshift(logEntry);
    if (State.cbLog.length > 8) State.cbLog.pop();

    // Estimate CB state from failures
    const total = State.cbFailures + State.cbSuccesses;
    if (total >= 5 && State.cbFailures / total >= 0.5) State.cbState = 'OPEN';
    else if (State.cbState === 'OPEN' && !fail) State.cbState = 'HALF_OPEN';
    else if (State.cbState === 'HALF_OPEN' && !fail) State.cbState = 'CLOSED';

    refreshCb(res);
    setHtml('cb-request', requestViewer('GET', url, {}));
    setHtml('cb-response', responseViewer(res, `GET ${url}`));
  },

  cbReset() {
    State.cbFailures = 0;
    State.cbSuccesses = 0;
    State.cbState = 'CLOSED';
    State.cbLog = [];
    refreshCb(null);
    setHtml('cb-request', requestViewer(null));
    setHtml('cb-response', responseViewer(null));
  },

  // ── TIMEOUT
  async timeoutSend(btn) {
    const delay = parseInt(val('timeout-delay') || '2');
    setLoading(btn, true);
    const start = Date.now();
    const url = `/api/timeout/slow?delay=${delay}`;
    const res = await apiFetch(url, { noJwt: true });
    const elapsed = ((Date.now() - start) / 1000).toFixed(2);
    setLoading(btn, false);
    const isFallback = typeof res.body?.result === 'string' && res.body.result.includes('FALLBACK');
    setHtml('timeout-request', requestViewer('GET', url, {}));
    setHtml('timeout-result', `
      <div class="alert ${isFallback ? 'alert-warning' : 'alert-success'}">
        ${isFallback ? '⚡ Timeout! Fallback returned after 3s' : `✅ Responded in ${elapsed}s`}
      </div>
      ${responseViewer(res, `GET ${url}`)}
    `);
  },

  // ── PAGINATION
  async paginationLoad() {
    const page = State.paginationPage;
    const size = State.paginationSize;
    const cat  = State.paginationCategory;
    const search = State.paginationSearch;
    let url = `/api/products?page=${page}&size=${size}&sort=name,asc`;
    if (cat) url += `&category=${encodeURIComponent(cat)}`;
    if (search) url += `&search=${encodeURIComponent(search)}`;
    const res = await apiFetch(url, { noJwt: true });
    if (res.ok) {
      const d = res.body;
      setHtml('product-grid', renderProductGrid(d));
      setHtml('pagination-meta', `
        <div class="pagination-info">Page ${d.currentPage + 1} of ${d.totalPages} (${d.totalElements} total products)</div>
      `);
      renderPaginationButtons(d);
    }
  },

  paginationPrev() { if (State.paginationPage > 0) { State.paginationPage--; handlers.paginationLoad(); } },
  paginationNext(btn) { State.paginationPage++; handlers.paginationLoad(); },

  paginationFilter() {
    State.paginationCategory = val('filter-category') || '';
    State.paginationSearch   = val('filter-search') || '';
    State.paginationPage = 0;
    handlers.paginationLoad();
  },

  // ── VERSIONING
  async versionFetch(btn) {
    const strategy = val('version-strategy') || 'uri';
    const version  = btn.dataset.version;
    let url = '/api/v1/items'; // default
    const headers = {};

    if (strategy === 'uri') {
      url = `/api/v${version}/items`;
    } else if (strategy === 'header') {
      url = '/api/items/by-header';
      headers['X-API-Version'] = version;
    } else if (strategy === 'query') {
      url = `/api/items/by-param?version=${version}`;
    } else if (strategy === 'accept') {
      url = '/api/items/by-accept';
      headers['Accept'] = `application/vnd.demo.v${version}+json`;
    }

    const res = await apiFetch(url, { noJwt: true, headers });
    setHtml(`version-v${version}-request`, requestViewer('GET', url, headers));
    setHtml(`version-v${version}-response`, responseViewer(res, `V${version} response`));
  },

  async versionGone(btn) {
    const res = await apiFetch('/api/v0/items', { noJwt: true });
    setHtml('version-gone-request', requestViewer('GET', '/api/v0/items', {}));
    setHtml('version-gone-result', responseViewer(res, 'GET /api/v0/items'));
  },

  // ── THIRD-PARTY APIs
  async tpSendWebhook(btn) {
    const tamper = btn.dataset.tamper === 'true';
    const replay = btn.dataset.replay === 'true';
    const eventType = val('tp-event-type') || 'payment.completed';
    setLoading(btn, true);
    const reqBody = { tamper, replay_attack: replay, type: eventType };
    const res = await apiFetch('/api/third-party/webhook/send-test', {
      noJwt: true,
      method: 'POST',
      body: JSON.stringify(reqBody),
    });
    setLoading(btn, false);
    setHtml('tp-webhook-request', requestViewer('POST', '/api/third-party/webhook/send-test', {'Content-Type':'application/json'}, reqBody));
    setHtml('tp-webhook-result', responseViewer(res, 'POST /api/third-party/webhook/send-test'));
    // Refresh event log
    const log = await apiFetch('/api/third-party/events', { noJwt: true });
    if (log.ok) setHtml('tp-event-log', renderEventLog(log.body.events));
  },

  async tpOutbound(btn) {
    const scenario = btn.dataset.scenario;
    setLoading(btn, true);
    const url = `/api/third-party/outbound?scenario=${scenario}`;
    const res = await apiFetch(url, { noJwt: true });
    setLoading(btn, false);
    setHtml('tp-outbound-request', requestViewer('GET', url, {}));
    setHtml('tp-outbound-result', responseViewer(res, `GET ${url}`));
  },

  // ── PARTNER API ──────────────────────────────────────────────
  //
  // All partner requests send noJwt:true because the partner API uses
  // X-Partner-Key header auth, not the JWT stored in State.jwt.
  // apiFetch's default behaviour would inject the JWT if one exists,
  // which would be wrong here — partner endpoints don't accept JWTs.
  //
  async partnerFetch(btn) {
    const endpoint = btn.dataset.endpoint;
    const key = val('partner-key'); // reads the <select> for the active partner key

    setLoading(btn, true);
    const res = await apiFetch(endpoint, {
      noJwt: true,                         // do NOT inject JWT — not applicable here
      headers: { 'X-Partner-Key': key },   // partner identity is in this header
    });
    setLoading(btn, false);

    // Multiple buttons share this handler; route the response to the correct
    // result element based on which endpoint was called.
    const idMap = {
      '/api/partner/auth-info': 'partner-auth-result',
      '/api/partner/catalog':   'partner-catalog-result',
      '/api/partner/quota':     'partner-quota-result',
      '/api/partner/audit':     'partner-audit-result',
    };
    const reqIdMap = {
      '/api/partner/auth-info': 'partner-auth-request',
      '/api/partner/catalog':   'partner-catalog-request',
      '/api/partner/quota':     'partner-quota-request',
      '/api/partner/audit':     'partner-audit-request',
    };
    const targetId = idMap[endpoint] || 'partner-auth-result';
    const reqTargetId = reqIdMap[endpoint] || 'partner-auth-request';
    setHtml(reqTargetId, requestViewer('GET', endpoint, { 'X-Partner-Key': key }));
    setHtml(targetId, responseViewer(res, `GET ${endpoint}`));
  },

  async partnerVersionFetch(btn) {
    const version = btn.dataset.version; // 'v1' or 'v2' from data-version attribute
    const key = val('partner-key');

    setLoading(btn, true);
    const res = await apiFetch(`/api/partner/${version}/items`, {
      noJwt: true,
      headers: { 'X-Partner-Key': key },
    });
    setLoading(btn, false);

    // Both v1 and v2 share the same result element so they appear side-by-side
    // conceptually (user clicks v1 then v2 to compare the schemas).
    setHtml('partner-version-request', requestViewer('GET', `/api/partner/${version}/items`, { 'X-Partner-Key': key }));
    setHtml('partner-version-result', responseViewer(res, `GET /api/partner/${version}/items`));
  },

  async partnerDispatchWebhook(btn) {
    const key       = val('partner-key');
    const eventType = val('partner-event-type'); // 'order.created' or 'inventory.low'

    setLoading(btn, true);
    const reqBody = { event_type: eventType };
    const res = await apiFetch('/api/partner/webhook/dispatch', {
      noJwt: true,
      method: 'POST',
      headers: { 'X-Partner-Key': key },
      body: JSON.stringify(reqBody),
    });
    setLoading(btn, false);

    setHtml('partner-webhook-request', requestViewer('POST', '/api/partner/webhook/dispatch', {
      'X-Partner-Key': key, 'Content-Type': 'application/json'
    }, reqBody));
    setHtml('partner-webhook-result', responseViewer(res, 'POST /api/partner/webhook/dispatch'));
  },

  // ── HANGING APIS
  async hangingSend(btn) {
    const approach = btn.dataset.approach;
    const delay    = parseInt(val('hanging-delay') || '5');
    const deadline = parseInt(val('hanging-deadline') || '3');
    let url;
    if (approach === 'no-timeout') {
      url = `/api/hanging/no-timeout?delay=${delay}`;
    } else if (approach === 'deadline') {
      url = `/api/hanging/with-deadline?delay=${delay}&deadline=${deadline}`;
    } else {
      url = `/api/hanging/http-client?delay=${delay}&timeout=${deadline}`;
    }
    setHtml('hanging-result', `<div class="alert alert-info text-sm">⏳ Request in flight… (watching for ${deadline}s deadline)</div>`);
    setLoading(btn, true);
    const start = Date.now();
    const res = await apiFetch(url, { noJwt: true });
    const elapsed = ((Date.now() - start) / 1000).toFixed(2);
    setLoading(btn, false);
    const isTimeout = res.status === 504 || res.body?.result?.includes?.('TIMEOUT') || res.body?.result?.includes?.('DEADLINE');
    setHtml('hanging-timer', `<span class="tag ${isTimeout ? 'tag-red' : 'tag-green'}">Responded in ${elapsed}s</span>`);
    setHtml('hanging-request', requestViewer('GET', url, {}));
    setHtml('hanging-result', responseViewer(res, `GET ${url}`));
  },

  // ── ERRORS
  async errorTrigger(btn) {
    const endpoint = btn.dataset.endpoint;
    const method = btn.dataset.method || 'GET';
    const body = btn.dataset.body;
    const basicAuth = btn.dataset.basicAuth;
    const extraHeaders = basicAuth ? { Authorization: `Basic ${btoa(basicAuth)}` } : {};
    const res = await apiFetch(endpoint, {
      noJwt: true,
      method,
      body: body ? body : undefined,
      headers: extraHeaders,
    });
    setHtml('error-request', requestViewer(method, endpoint, extraHeaders, body ? body : null));
    setHtml('error-response', responseViewer(res, `${method} ${endpoint}`));
  },

  // ── Write Operations handlers ─────────────────────────────────────────────

  async crudPost(btn) {
    setLoading(btn, true);
    const name     = val('wo-name');
    const price    = parseFloat(val('wo-price')) || null;
    const category = val('wo-category');
    const stock    = parseInt(val('wo-stock')) || 0;
    const body     = { name, price, category, stock };
    const res = await apiFetch('/api/products', { method: 'POST', body: JSON.stringify(body) });
    setHtml('wo-request',  requestViewer('POST', '/api/products', {'Content-Type': 'application/json'}, body));
    setHtml('wo-response', responseViewer(res, 'POST /api/products'));
    // Auto-populate the ID field if creation succeeded so user can immediately try PUT/PATCH/DELETE
    if (res.ok && res.body && res.body.id) {
      const idEl = document.getElementById('wo-id');
      if (idEl) idEl.value = res.body.id;
    }
    setLoading(btn, false);
  },

  async crudPostInvalid(btn) {
    setLoading(btn, true);
    const body = {};
    const res = await apiFetch('/api/products', { method: 'POST', body: JSON.stringify(body) });
    setHtml('wo-request',  requestViewer('POST', '/api/products', {'Content-Type': 'application/json'}, body));
    setHtml('wo-response', responseViewer(res, 'POST /api/products → 422 Validation Failed'));
    setLoading(btn, false);
  },

  async crudPostDuplicate(btn) {
    setLoading(btn, true);
    // "iPhone 15" is a seeded product — guaranteed to already exist on every restart
    const body = { name: 'iPhone 15', price: 999.99, category: 'Electronics' };
    const res = await apiFetch('/api/products', { method: 'POST', body: JSON.stringify(body) });
    setHtml('wo-request',  requestViewer('POST', '/api/products', {'Content-Type': 'application/json'}, body));
    setHtml('wo-response', responseViewer(res, 'POST /api/products → 409 Conflict'));
    setLoading(btn, false);
  },

  async crudPut(btn) {
    setLoading(btn, true);
    const id       = val('wo-id');
    const name     = val('wo-name');
    const price    = parseFloat(val('wo-price')) || null;
    const category = val('wo-category');
    const stock    = parseInt(val('wo-stock')) || 0;
    const body     = { name, price, category, stock };
    const res = await apiFetch(`/api/products/${id}`, { method: 'PUT', body: JSON.stringify(body) });
    setHtml('wo-request',  requestViewer('PUT', `/api/products/${id}`, {'Content-Type': 'application/json'}, body));
    setHtml('wo-response', responseViewer(res, `PUT /api/products/${id}`));
    setLoading(btn, false);
  },

  async crudPutMissing(btn) {
    setLoading(btn, true);
    const body = { name: 'Ghost Product', price: 1.00, category: 'Test' };
    const res = await apiFetch('/api/products/99999', { method: 'PUT', body: JSON.stringify(body) });
    setHtml('wo-request',  requestViewer('PUT', '/api/products/99999', {'Content-Type': 'application/json'}, body));
    setHtml('wo-response', responseViewer(res, 'PUT /api/products/99999 → 404 Not Found'));
    setLoading(btn, false);
  },

  async crudPatch(btn) {
    setLoading(btn, true);
    const id    = val('wo-id');
    const patch = {};
    const name  = val('wo-name');
    const price = val('wo-price');
    if (name)  patch.name  = name;
    if (price) patch.price = parseFloat(price);
    const res = await apiFetch(`/api/products/${id}`, { method: 'PATCH', body: JSON.stringify(patch) });
    setHtml('wo-request',  requestViewer('PATCH', `/api/products/${id}`, {'Content-Type': 'application/json'}, patch));
    setHtml('wo-response', responseViewer(res, `PATCH /api/products/${id} — only provided fields changed`));
    setLoading(btn, false);
  },

  async crudDelete(btn) {
    setLoading(btn, true);
    const id  = val('wo-id');
    const res = await apiFetch(`/api/products/${id}`, { method: 'DELETE' });
    setHtml('wo-request',  requestViewer('DELETE', `/api/products/${id}`, {}));
    setHtml('wo-response', responseViewer(res, `DELETE /api/products/${id}`));
    if (res.status === 204) {
      const idEl = document.getElementById('wo-id');
      if (idEl) idEl.value = '';
    }
    setLoading(btn, false);
  },

  async crudDeleteMissing(btn) {
    setLoading(btn, true);
    const res = await apiFetch('/api/products/99999', { method: 'DELETE' });
    setHtml('wo-request',  requestViewer('DELETE', '/api/products/99999', {}));
    setHtml('wo-response', responseViewer(res, 'DELETE /api/products/99999 → 404 Not Found'));
    setLoading(btn, false);
  },

  // ── Upsert / Merge strategy handlers ─────────────────────────────────────

  async strategyUpsertExisting(btn) {
    setLoading(btn, true);
    // Send to a seeded product — server will find it and replace (200, not 201)
    const body = { name: 'iPhone 15', price: 799.99, category: 'Electronics', stock: 999 };
    const res = await apiFetch('/api/products/upsert', { method: 'POST', body: JSON.stringify(body) });
    setHtml('strategy-request',  requestViewer('POST', '/api/products/upsert', {'Content-Type': 'application/json'}, body));
    setHtml('strategy-response', responseViewer(res, res.status === 200 ? 'POST /upsert → 200 (existed, replaced)' : 'POST /upsert → 201 (created)'));
    setLoading(btn, false);
  },

  async strategyUpsertNew(btn) {
    setLoading(btn, true);
    const ts = Date.now();
    const body = { name: `New Gadget ${ts}`, price: 49.99, category: 'Electronics', stock: 10 };
    const res = await apiFetch('/api/products/upsert', { method: 'POST', body: JSON.stringify(body) });
    setHtml('strategy-request',  requestViewer('POST', '/api/products/upsert', {'Content-Type': 'application/json'}, body));
    setHtml('strategy-response', responseViewer(res, 'POST /upsert → 201 Created (did not exist)'));
    setLoading(btn, false);
  },

  async strategyMergeExisting(btn) {
    setLoading(btn, true);
    // Only price and stock — name and category are left unchanged on the server
    const body = { name: 'iPhone 15', price: 1099.99, stock: 25 };
    const res = await apiFetch('/api/products/merge', { method: 'POST', body: JSON.stringify(body) });
    setHtml('strategy-request',  requestViewer('POST', '/api/products/merge', {'Content-Type': 'application/json'}, body));
    setHtml('strategy-response', responseViewer(res, 'POST /merge → 200 (existed, only price+stock changed)'));
    setLoading(btn, false);
  },

  async strategyMergeNew(btn) {
    setLoading(btn, true);
    const ts = Date.now();
    // All required fields for create path (price + category)
    const body = { name: `Merged Product ${ts}`, price: 19.99, category: 'Books', stock: 5 };
    const res = await apiFetch('/api/products/merge', { method: 'POST', body: JSON.stringify(body) });
    setHtml('strategy-request',  requestViewer('POST', '/api/products/merge', {'Content-Type': 'application/json'}, body));
    setHtml('strategy-response', responseViewer(res, 'POST /merge → 201 Created (did not exist)'));
    setLoading(btn, false);
  },

  async strategyMergeMissingRequired(btn) {
    setLoading(btn, true);
    // Merge a non-existent product but omit price — triggers 422 on create path
    const body = { name: `Incomplete Product ${Date.now()}` };
    const res = await apiFetch('/api/products/merge', { method: 'POST', body: JSON.stringify(body) });
    setHtml('strategy-request',  requestViewer('POST', '/api/products/merge', {'Content-Type': 'application/json'}, body));
    setHtml('strategy-response', responseViewer(res, 'POST /merge → 422 (new product, price required)'));
    setLoading(btn, false);
  },
};

// ── PAGE: HOME ────────────────────────────────────────────────
function home() {
  return `
    <div class="page-title">REST API Study Demo</div>
    <div class="page-sub">An interactive playground for learning REST API concepts hands-on.</div>

    <div class="concept-grid">
      ${card('🎫', 'JWT Authentication', 'Login, receive tokens, decode the payload, use Bearer auth.', 'auth-jwt')}
      ${card('🔐', 'Basic Auth', 'HTTP Basic — credentials on every request.', 'auth-basic')}
      ${card('🗝️', 'API Keys', 'Opaque keys — instantly revocable, DB-backed.', 'auth-apikey')}
      ${card('🔑', 'OAuth2', 'Authorization Code & Client Credentials flows.', 'oauth2')}
      ${card('🛡️', 'RBAC', 'Role-based access: ADMIN, USER, VIEWER.', 'rbac')}
      ${card('⚡', 'Rate Limiting', 'Token bucket — send rapid requests and see 429.', 'rate-limit')}
      ${card('🔄', 'Circuit Breaker', 'Simulate failures, watch the circuit open and recover.', 'circuit-breaker')}
      ${card('⏳', 'Hanging APIs', 'Thread exhaustion, deadlines, and HTTP client timeouts.', 'hanging-apis')}
      ${card('🔌', 'Third-Party APIs', 'Webhook verification, outbound error handling, credential management.', 'third-party')}
      ${card('🤝', 'Partner Integration', 'B2B API design: partner keys, tenant isolation, tiered limits, versioning contracts.', 'partner-api')}
      ${card('📄', 'Pagination', 'Browse products with filtering, sorting, and pages.', 'pagination')}
      ${card('📦', 'API Versioning', 'URI, header, query param, and Accept header strategies.', 'versioning')}
      ${card('⚠️', 'Error Handling', 'RFC 7807 Problem Details — consistent, safe errors.', 'errors')}
      ${card('✏️', 'Write Operations', 'POST/PUT/PATCH/DELETE design, idempotency, and failure handling.', 'write-ops')}
    </div>

    <div class="card">
      <div class="card-title">🔑 Demo Credentials</div>
      <table class="cred-table">
        <thead><tr><th>Type</th><th>Identifier</th><th>Secret</th><th>Roles / Scopes</th></tr></thead>
        <tbody>
          <tr><td>User</td><td><code>admin</code></td><td><code>password</code></td><td><span class="tag tag-red">ADMIN</span> <span class="tag tag-blue">USER</span></td></tr>
          <tr><td>User</td><td><code>user</code></td><td><code>password</code></td><td><span class="tag tag-blue">USER</span></td></tr>
          <tr><td>User</td><td><code>viewer</code></td><td><code>password</code></td><td><span class="tag tag-green">VIEWER</span></td></tr>
          <tr><td>API Key (Admin)</td><td colspan="2"><code>demo-api-key-admin-12345</code></td><td><span class="tag tag-red">ADMIN</span></td></tr>
          <tr><td>API Key (User)</td><td colspan="2"><code>demo-api-key-user-12345</code></td><td><span class="tag tag-blue">USER</span></td></tr>
          <tr><td>OAuth2 Client</td><td><code>machine-client</code></td><td><code>machine-secret</code></td><td><span class="tag tag-purple">client_credentials</span></td></tr>
          <tr><td>OAuth2 Client</td><td><code>web-client</code></td><td><code>web-secret</code></td><td><span class="tag tag-purple">authorization_code</span></td></tr>
        </tbody>
      </table>
    </div>`;
}

function card(icon, title, desc, page) {
  return `<div class="concept-card" onclick="navigate('${page}'); location.hash='${page}'">
    <span class="concept-card-icon">${icon}</span>
    <div class="concept-card-title">${title}</div>
    <div class="concept-card-desc">${desc}</div>
  </div>`;
}

// ── PAGE: JWT AUTH ────────────────────────────────────────────
function authJwt() {
  return `
    <div class="page-title">🎫 JWT Authentication</div>
    <div class="page-sub">JSON Web Tokens — stateless, self-contained, signed credentials.</div>

    <div class="concept-box">
      A JWT has 3 Base64URL-encoded parts: <strong>HEADER</strong> <code>.</code> <strong>PAYLOAD</strong> <code>.</code> <strong>SIGNATURE</strong><br>
      The payload is readable by anyone — it is <strong>signed, not encrypted</strong>. Never put secrets in a JWT.<br>
      Access tokens are short-lived (15 min). Refresh tokens are long-lived (24 hr) and stored securely.
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Step 1 — Login</div>
        <div class="form-row">
          <label class="form-label">Username</label>
          <input class="form-input" id="jwt-username" value="user" data-enter="jwtLogin" placeholder="admin, user, or viewer">
        </div>
        <div class="form-row">
          <label class="form-label">Password</label>
          <input class="form-input" id="jwt-password" type="password" value="password" data-enter="jwtLogin">
        </div>
        <div class="btn-group">
          <button class="btn btn-primary" data-action="jwtLogin">Login →</button>
          ${State.jwt ? `<button class="btn btn-secondary btn-sm" data-action="jwtClear">Clear Token</button>` : ''}
        </div>
      </div>

      <div class="card">
        <div class="card-title">Step 2 — Your Token</div>
        <div id="jwt-visualizer">${jwtVisualizer(State.jwt)}</div>
        ${State.jwt ? `
          <div class="btn-group mt-12">
            <button class="btn btn-secondary btn-sm" data-action="jwtDecode">Decode Payload</button>
          </div>
          <div id="jwt-decode-result" class="mt-12"></div>
        ` : ''}
      </div>
    </div>

    <div class="http-exchange">
      <div id="jwt-login-request">${requestViewer(null)}</div>
      <div id="jwt-login-response">${responseViewer(null)}</div>
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Step 3 — Call Protected Endpoint</div>
        <div class="alert alert-info text-sm">
          <code>Authorization: Bearer &lt;accessToken&gt;</code> is sent automatically.
        </div>
        <button class="btn btn-primary btn-block" data-action="jwtProtected" ${!State.jwt ? 'disabled' : ''}>
          GET /api/jwt/protected
        </button>
      </div>

      <div class="card">
        <div class="card-title">How JWT Works</div>
        <table class="comparison-table">
          <tr><td>Sign with</td><td>HMAC-SHA256 (symmetric secret)</td></tr>
          <tr><td>Access token TTL</td><td>15 minutes</td></tr>
          <tr><td>Refresh token TTL</td><td>24 hours</td></tr>
          <tr><td>Revocable?</td><td><span class="con">✗ Not without a token blocklist</span></td></tr>
          <tr><td>DB lookup needed?</td><td><span class="pro">✓ No — signature check only</span></td></tr>
          <tr><td>Payload private?</td><td><span class="con">✗ Base64 encoded, not encrypted</span></td></tr>
        </table>
      </div>
    </div>

    <div class="http-exchange">
      <div id="jwt-protected-request">${requestViewer(null)}</div>
      <div id="jwt-protected-response">${responseViewer(null)}</div>
    </div>`;
}

// ── PAGE: BASIC AUTH ──────────────────────────────────────────
function authBasic() {
  return `
    <div class="page-title">🔐 HTTP Basic Authentication</div>
    <div class="page-sub">Credentials sent as Base64(username:password) on every request.</div>

    <div class="concept-box">
      Header sent: <code>Authorization: Basic base64("username:password")</code><br>
      Simple, but credentials travel on every request — always use HTTPS.<br>
      No expiry — "logout" only works by changing the password.
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Test Basic Auth</div>
        <div class="form-row">
          <label class="form-label">Username</label>
          <input class="form-input" id="basic-user" value="user" placeholder="admin, user, or viewer">
        </div>
        <div class="form-row">
          <label class="form-label">Password</label>
          <input class="form-input" id="basic-pass" value="password" type="password">
        </div>
        <div class="form-row">
          <label class="form-label">Endpoint</label>
          <select class="form-select" id="basic-endpoint">
            <option value="/api/basic/protected">GET /api/basic/protected (any role)</option>
            <option value="/api/basic/admin">GET /api/basic/admin (ADMIN only)</option>
            <option value="/api/basic/public">GET /api/basic/public (no auth)</option>
          </select>
        </div>
        <button class="btn btn-primary btn-block" data-action="basicTest">Send Request</button>
        <div class="mt-12 text-sm text-muted">
          Try <code>user</code> on <code>/api/basic/admin</code> → you'll get <strong>403 Forbidden</strong>
        </div>
      </div>

      <div class="card">
        <div class="card-title">Basic Auth vs JWT</div>
        <table class="comparison-table">
          <thead><tr><th></th><th>Basic Auth</th><th>JWT</th></tr></thead>
          <tbody>
            <tr><td>Credentials sent</td><td>Every request</td><td>Only on login</td></tr>
            <tr><td>Expiry</td><td class="con">None</td><td class="pro">15 min access token</td></tr>
            <tr><td>Revoke?</td><td>Change password</td><td>Blocklist or wait</td></tr>
            <tr><td>DB lookup</td><td>Every request</td><td>Signature only</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="http-exchange">
      <div id="basic-request">${requestViewer(null)}</div>
      <div id="basic-response">${responseViewer(null)}</div>
    </div>`;
}

// ── PAGE: API KEY ─────────────────────────────────────────────
function authApiKey() {
  return `
    <div class="page-title">🗝️ API Key Authentication</div>
    <div class="page-sub">Opaque tokens sent in a header — validated via database lookup.</div>

    <div class="concept-box">
      Header sent: <code>X-API-Key: &lt;key&gt;</code><br>
      Unlike JWT, the server looks up the key in the database on <strong>every request</strong>.<br>
      Key advantage: <strong>instantly revocable</strong> — flip <code>active=false</code> in the DB.
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Test API Key</div>
        <div class="form-row">
          <label class="form-label">API Key</label>
          <select class="form-select" id="apikey-input">
            <option value="demo-api-key-user-12345">demo-api-key-user-12345 (USER role)</option>
            <option value="demo-api-key-admin-12345">demo-api-key-admin-12345 (ADMIN role)</option>
            <option value="demo-api-key-expired-12345">demo-api-key-expired-12345 (EXPIRED)</option>
            <option value="invalid-key">invalid-key (should fail)</option>
          </select>
        </div>
        <div class="form-row">
          <label class="form-label">Endpoint</label>
          <select class="form-select" id="apikey-endpoint">
            <option value="/api/apikey/data">GET /api/apikey/data</option>
            <option value="/api/apikey/admin">GET /api/apikey/admin (ADMIN only)</option>
          </select>
        </div>
        <button class="btn btn-primary btn-block" data-action="apiKeyTest">Send Request</button>
      </div>

      <div class="card">
        <div class="card-title">Production Security Notes</div>
        <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
          <li>Store only a <strong>SHA-256 hash</strong> of the key (like password hashing)</li>
          <li>Show the plain key <strong>once</strong> on creation — never again</li>
          <li>Add <strong>per-key rate limiting</strong> independently</li>
          <li>Support key <strong>rotation</strong> with overlap period</li>
          <li>Add <strong>caching</strong> (Redis) to avoid DB hit per request</li>
        </ul>
      </div>
    </div>

    <div class="http-exchange">
      <div id="apikey-request">${requestViewer(null)}</div>
      <div id="apikey-response">${responseViewer(null)}</div>
    </div>`;
}

// ── PAGE: OAUTH2 ──────────────────────────────────────────────
function oauthPage() {
  return `
    <div class="page-title">🔑 OAuth2 & OpenID Connect</div>
    <div class="page-sub">Delegated authorization — clients access resources on behalf of users.</div>

    <div class="concept-box">
      <strong>Problem OAuth2 solves:</strong> How does App B access your data on App A <em>without your password?</em><br>
      The user grants permission → the Authorization Server issues a token → App B uses the token.<br>
      This app is both the <strong>Authorization Server</strong> (issues tokens) and the <strong>Resource Server</strong> (validates them).
    </div>

    <div class="section-heading">Why OAuth2?</div>
    <div class="demo-grid">
      <div class="card">
        <div class="card-title" style="color:var(--green)">Benefits</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>No password sharing</strong> — your credentials never leave the authorization server; third-party apps only see short-lived tokens</li>
          <li><strong>Scoped access</strong> — tokens carry explicit permissions (<code>read</code>, <code>write</code>, <code>admin</code>); an app can only do what it was granted</li>
          <li><strong>Short-lived tokens</strong> — access tokens expire in minutes/hours, limiting the blast radius of a leaked token</li>
          <li><strong>Revocable</strong> — tokens can be invalidated server-side instantly without requiring a password change</li>
          <li><strong>Separation of concerns</strong> — authentication (who are you?) and authorization (what can you do?) are handled by dedicated services (Auth0, Okta, Keycloak)</li>
          <li><strong>Industry standard</strong> — every major platform (Google, GitHub, AWS) speaks OAuth2; your integration pattern is reusable</li>
          <li><strong>Delegated access without impersonation</strong> — a token proves a user <em>authorized</em> an action, not that someone logged in as them</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title" style="color:var(--red)">Drawbacks</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Complexity</strong> — Authorization Code flow has 6+ steps across browser, client, and server; Basic Auth is one round-trip</li>
          <li><strong>Requires an Authorization Server</strong> — you need a separate service (or embed one like Spring Authorization Server); adds infrastructure cost</li>
          <li><strong>Extra latency</strong> — Client Credentials flow adds one token request before every fresh session; Auth Code flow is a browser multi-redirect dance</li>
          <li><strong>Token management overhead</strong> — clients must store, refresh, and rotate tokens; a stale token means a 401 mid-session</li>
          <li><strong>Harder to debug</strong> — opaque tokens offer no visibility; even JWTs require a JWKS endpoint and signature verification to inspect</li>
          <li><strong>Overkill for simple cases</strong> — if your API is internal, single-tenant, or has a handful of trusted clients, API keys or mutual TLS are simpler and equally secure</li>
        </ul>
      </div>
    </div>

    <div class="card" style="margin-bottom:16px">
      <div class="card-title">OAuth2 vs Other Auth Methods</div>
      <table class="comparison-table">
        <thead><tr><th>Method</th><th>Best for</th><th>Password exposed?</th><th>Revocable?</th><th>Scoped?</th><th>Complexity</th></tr></thead>
        <tbody>
          <tr><td><strong>Basic Auth</strong></td><td>Internal tools, server-to-server with TLS</td><td class="con">Every request</td><td class="con">No (change pwd)</td><td class="con">No</td><td class="pro">Minimal</td></tr>
          <tr><td><strong>API Key</strong></td><td>Developer-facing APIs, simple clients</td><td class="con">Every request</td><td class="pro">Yes (key rotation)</td><td class="con">Rarely</td><td class="pro">Low</td></tr>
          <tr><td><strong>JWT (custom)</strong></td><td>Stateless microservices, mobile apps</td><td class="pro">Never</td><td class="con">Hard (blocklist)</td><td class="pro">Yes (claims)</td><td>Medium</td></tr>
          <tr><td><strong>OAuth2</strong></td><td>Third-party delegation, public-facing APIs</td><td class="pro">Never</td><td class="pro">Yes (server-side)</td><td class="pro">Yes (scopes)</td><td class="con">High</td></tr>
          <tr><td><strong>mTLS</strong></td><td>Zero-trust service mesh, partner APIs</td><td class="pro">Never</td><td class="pro">Yes (cert revoke)</td><td class="con">No</td><td class="con">High</td></tr>
        </tbody>
      </table>
      <div class="alert alert-info text-sm" style="margin-top:12px">
        <strong>Rule of thumb:</strong> reach for OAuth2 when a user is granting a third-party app access to their data, or when you need fine-grained scope control across multiple resource servers. For internal M2M with no user, API keys or mTLS are often simpler.
      </div>
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Flow 1 — Client Credentials (M2M)</div>
        <div class="alert alert-info text-sm">Machine-to-machine: no user involved. Server authenticates directly.</div>
        <div class="text-sm text-muted mb-8">Client: <code>machine-client</code> / <code>machine-secret</code></div>
        <button class="btn btn-primary btn-block" data-action="oauthClientCreds">
          Get Token + Call API →
        </button>
        <div class="section-heading mt-12">Step 1 — Token Request</div>
        <div class="http-exchange">
          <div id="oauth-token-request">${requestViewer(null)}</div>
          <div id="oauth-token-response">${responseViewer(null)}</div>
        </div>
        <div id="oauth-token-visual" class="mt-8"></div>
        <div class="section-heading">Step 2 — Resource Server Call</div>
        <div class="http-exchange">
          <div id="oauth-api-request">${requestViewer(null)}</div>
          <div id="oauth-api-response">${responseViewer(null)}</div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">Flow 2 — Authorization Code (User Login)</div>
        <div class="alert alert-warning text-sm">Opens a browser window — login with <code>admin/password</code>.</div>
        <ol class="text-sm" style="padding-left:18px;line-height:2.2">
          <li>Click the button → browser opens the authorization endpoint</li>
          <li>Login with <code>admin / password</code></li>
          <li>Click "Approve" on the consent screen</li>
          <li>Browser redirects to <code>/api/oauth/callback?code=...</code></li>
          <li>Copy the <code>code</code> value to exchange for a token</li>
        </ol>
        <button class="btn btn-secondary btn-block mt-12" data-action="oauthStartFlow">
          🔗 Open Authorization Flow ↗
        </button>
      </div>

      <div class="card full-width">
        <div class="card-title">OAuth2 Roles in this Demo</div>
        <table class="comparison-table">
          <thead><tr><th>Role</th><th>What it does</th><th>In this demo</th></tr></thead>
          <tbody>
            <tr><td>Authorization Server</td><td>Issues tokens after user consent</td><td>This app at <code>/oauth2/**</code></td></tr>
            <tr><td>Resource Server</td><td>Validates tokens, serves protected data</td><td>This app at <code>/api/oauth/**</code></td></tr>
            <tr><td>Client</td><td>The app requesting access</td><td><code>machine-client</code> or <code>web-client</code></td></tr>
            <tr><td>Resource Owner</td><td>The user who owns the data</td><td>admin/user/viewer</td></tr>
          </tbody>
        </table>
        <div class="divider"></div>
        <div class="card-title text-sm">Key Endpoints</div>
        <table class="cred-table">
          <tr><td><code>GET /.well-known/openid-configuration</code></td><td>OIDC discovery document</td></tr>
          <tr><td><code>GET /oauth2/jwks</code></td><td>Public keys for token verification</td></tr>
          <tr><td><code>POST /oauth2/token</code></td><td>Exchange credentials/code for tokens</td></tr>
          <tr><td><code>GET /oauth2/authorize</code></td><td>Start authorization code flow</td></tr>
          <tr><td><code>POST /oauth2/revoke</code></td><td>Revoke a token</td></tr>
        </table>
      </div>
    </div>`;
}

// ── PAGE: RBAC ────────────────────────────────────────────────
function rbacPage() {
  return `
    <div class="page-title">🛡️ Role-Based Access Control</div>
    <div class="page-sub">Permissions assigned to roles, roles assigned to users.</div>

    <div class="concept-box">
      RBAC asks: <strong>"What role does this user have?"</strong> then grants access accordingly.<br>
      Spring Security supports URL-level rules in <code>SecurityConfig</code> and method-level rules with <code>@PreAuthorize</code>.<br>
      Role hierarchy: <strong>ADMIN &gt; USER &gt; VIEWER</strong>
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Try Different Roles</div>
        <div class="form-row">
          <label class="form-label">Login as</label>
          <select class="form-select" id="rbac-role">
            <option value="admin">admin (ROLE_ADMIN + ROLE_USER)</option>
            <option value="user" selected>user (ROLE_USER)</option>
            <option value="viewer">viewer (ROLE_VIEWER)</option>
          </select>
        </div>
        <div class="form-row">
          <label class="form-label">Endpoint to call</label>
          <select class="form-select" id="rbac-endpoint">
            <option value="/api/rbac/viewer">GET /api/rbac/viewer (VIEWER+)</option>
            <option value="/api/rbac/user">GET /api/rbac/user (USER+)</option>
            <option value="/api/rbac/admin">GET /api/rbac/admin (ADMIN only)</option>
            <option value="/api/rbac/method-security/admin-only">GET /api/rbac/method-security/admin-only (@PreAuthorize)</option>
          </select>
        </div>
        <button class="btn btn-primary btn-block" data-action="rbacTest">Test Access →</button>

        <div class="divider"></div>
        <div class="text-sm text-muted">
          Interesting combinations to try:<br>
          <code>viewer</code> → <code>/api/rbac/user</code> → <span class="tag tag-red">403</span><br>
          <code>user</code> → <code>/api/rbac/admin</code> → <span class="tag tag-red">403</span><br>
          <code>admin</code> → any → <span class="tag tag-green">200</span>
        </div>
      </div>

      <div class="card">
        <div class="card-title">Two Ways to Enforce Roles</div>
        <div class="text-sm" style="line-height:1.8">
          <strong>1. URL rules in SecurityConfig</strong> — coarse-grained:<br>
          <code style="font-size:11px">.requestMatchers("/api/rbac/admin/**").hasRole("ADMIN")</code>
          <br><br>
          <strong>2. @PreAuthorize on methods</strong> — fine-grained + ownership:<br>
          <code style="font-size:11px">@PreAuthorize("hasRole('USER') and #userId == authentication.name")</code>
        </div>
      </div>
    </div>

    <div class="http-exchange">
      <div id="rbac-request">${requestViewer(null)}</div>
      <div id="rbac-response">${responseViewer(null)}</div>
    </div>

    <div class="divider"></div>
    <div class="section-heading" style="font-size:18px">Access Control Models Beyond RBAC</div>
    <div class="alert alert-info text-sm">RBAC is the most common starting point, but five other models handle cases where roles alone are not expressive enough.</div>

    <div class="card" style="margin-bottom:16px">
      <div class="card-title">Model Comparison at a Glance</div>
      <table class="comparison-table">
        <thead><tr><th>Model</th><th>Access granted by</th><th>Best fit</th><th>Real-world examples</th><th>Complexity</th></tr></thead>
        <tbody>
          <tr><td><strong>RBAC</strong></td><td>User's assigned role</td><td>Most apps, clear job functions</td><td>Admin panel, CMS, SaaS tiers</td><td>Low</td></tr>
          <tr><td><strong>ABAC</strong></td><td>Attributes of user, resource &amp; environment</td><td>Fine-grained rules, multi-factor access</td><td>Healthcare records, financial compliance</td><td>Medium</td></tr>
          <tr><td><strong>DAC</strong></td><td>Resource owner's discretion</td><td>User-generated content with sharing</td><td>Google Drive, Dropbox, S3</td><td>Low–Medium</td></tr>
          <tr><td><strong>MAC</strong></td><td>System-assigned classification labels</td><td>Government, military, high-security</td><td>SELinux, classified document systems</td><td>High</td></tr>
          <tr><td><strong>ReBAC</strong></td><td>Graph relationship to the resource</td><td>Social/collaborative data with hierarchies</td><td>Google Docs, GitHub teams, Notion</td><td>High</td></tr>
          <tr><td><strong>ACL</strong></td><td>Explicit per-resource permission list</td><td>OS-level or per-object control</td><td>Linux filesystem, AWS S3 bucket policies</td><td>Medium</td></tr>
          <tr><td><strong>PBAC</strong></td><td>Centralized policy engine evaluation</td><td>Multi-service, auditable enterprise policy</td><td>OPA (Open Policy Agent), AWS Cedar</td><td>High</td></tr>
        </tbody>
      </table>
    </div>

    <div class="section-heading">ABAC — Attribute-Based Access Control</div>
    <div class="concept-box">
      ABAC asks: <strong>"Given everything we know about this user, this resource, and this environment — should access be granted?"</strong><br>
      Instead of a flat role, the policy engine evaluates <em>attributes</em>: user department, resource owner, data classification, time of day, IP address.
    </div>
    <div class="demo-grid">
      <div class="card">
        <div class="card-title" style="color:var(--green)">Benefits</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Extremely fine-grained</strong> — "only doctors in cardiology can read cardiology records during business hours"</li>
          <li><strong>No role explosion</strong> — RBAC grows a new role every time a new combination of permissions is needed; ABAC encodes that in policy attributes</li>
          <li><strong>Ownership checks built-in</strong> — <code>resource.ownerId == user.id</code> is a single attribute check</li>
          <li><strong>Context-aware</strong> — time, IP, and device trust level can gate access dynamically</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title" style="color:var(--red)">Drawbacks</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Policy complexity</strong> — attribute combinations multiply fast; a policy with 5 attributes has hundreds of possible states</li>
          <li><strong>Hard to audit</strong> — "who can access resource X?" requires evaluating every user's attributes, not a simple role lookup</li>
          <li><strong>Attribute management overhead</strong> — user and resource attributes must be kept up-to-date or access decisions are stale</li>
          <li><strong>Testing difficulty</strong> — covering all attribute combinations in tests is impractical</li>
        </ul>
      </div>
    </div>
    <div class="card" style="margin-bottom:16px">
      <div class="card-title">Spring Implementation (<code>@PreAuthorize</code> + SpEL)</div>
      <pre class="response-body" style="margin:0">@PreAuthorize("hasRole('USER') and #userId == authentication.name")
public ResponseEntity&lt;?&gt; myData(@PathVariable String userId) { ... }

@PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")
public ResponseEntity&lt;?&gt; adminOrOwner(@PathVariable String userId) { ... }

// Time-based attribute via custom bean
@PreAuthorize("hasRole('USER') and @accessPolicy.isBusinessHours()")
public ResponseEntity&lt;?&gt; businessHoursOnly() { ... }</pre>
    </div>

    <div class="section-heading">DAC — Discretionary Access Control</div>
    <div class="concept-box">
      DAC asks: <strong>"Did the resource owner explicitly grant you access?"</strong><br>
      The owner of each resource decides who else can read, write, or share it. The system enforces what the owner declares — it does not impose rules from above.
    </div>
    <div class="demo-grid">
      <div class="card">
        <div class="card-title" style="color:var(--green)">Benefits</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>User autonomy</strong> — owners share resources without needing an admin to update roles</li>
          <li><strong>Familiar mental model</strong> — "share with…" dialogs in Google Drive and Dropbox are DAC</li>
          <li><strong>Scales with content</strong> — per-resource permissions don't require new roles as content grows</li>
          <li><strong>Flexible delegation</strong> — owners can grant others the ability to re-share (can-share vs read-only)</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title" style="color:var(--red)">Drawbacks</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Accidental over-sharing</strong> — users often default to "anyone with the link"; data leaks are common</li>
          <li><strong>No central audit trail</strong> — IT cannot answer "who has access to all files containing PII?"</li>
          <li><strong>Ownership transfer is messy</strong> — when an employee leaves, orphaned resources may have no owner</li>
          <li><strong>Inconsistent enforcement</strong> — every resource has its own ACL; a misconfigured one is invisible until exploited</li>
        </ul>
      </div>
    </div>
    <div class="card" style="margin-bottom:16px">
      <div class="card-title">Spring ACL Implementation</div>
      <pre class="response-body" style="margin:0">// Requires spring-security-acl dependency
MutableAcl acl = aclService.createAcl(objectIdentity);
acl.insertAce(acl.getEntries().size(), BasePermission.READ,
    new PrincipalSid("bob"), true);
aclService.updateAcl(acl);

@PreAuthorize("hasPermission(#docId, 'com.example.Document', 'read')")
public Document getDocument(Long docId) { ... }</pre>
    </div>

    <div class="section-heading">MAC — Mandatory Access Control</div>
    <div class="concept-box">
      MAC asks: <strong>"Does the subject's clearance level meet or exceed the resource's classification?"</strong><br>
      Access decisions are made by the <em>system</em> — no user, including the resource owner, can override them.<br>
      <strong>Bell-LaPadula rules:</strong> <em>no read up</em> (Secret cannot read Top Secret) · <em>no write down</em> (Top Secret cannot write to Confidential, preventing leakage).
    </div>
    <div class="demo-grid">
      <div class="card">
        <div class="card-title" style="color:var(--green)">Benefits</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Strong guarantees</strong> — classified data cannot leak to lower clearance levels by any user action</li>
          <li><strong>Centrally enforced</strong> — the system sets labels; individuals cannot bypass policy, even accidentally</li>
          <li><strong>Prevents insider threats</strong> — a malicious employee cannot downgrade and exfiltrate data</li>
          <li><strong>Audit-friendly</strong> — every access attempt is logged against the classification hierarchy</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title" style="color:var(--red)">Drawbacks</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Extremely rigid</strong> — legitimate collaboration across classification levels requires explicit policy changes</li>
          <li><strong>High administrative overhead</strong> — every resource and user must be assigned and maintained with correct labels</li>
          <li><strong>Poor fit for most commercial apps</strong> — the complexity is only justified when regulatory or national security requirements demand it</li>
          <li><strong>User friction</strong> — operations that feel natural in consumer apps (copy-paste, email) are locked down</li>
        </ul>
      </div>
    </div>
    <div class="card" style="margin-bottom:16px">
      <div class="card-title">Spring Implementation (Custom <code>AccessDecisionVoter</code>)</div>
      <pre class="response-body" style="margin:0">public class ClearanceVoter implements AccessDecisionVoter&lt;Object&gt; {
    public int vote(Authentication auth, Object object,
                    Collection&lt;ConfigAttribute&gt; attrs) {
        int userLevel = getClearanceLevel(auth);   // UNCLASSIFIED=0, SECRET=2, TOP_SECRET=3
        int requiredLevel = getRequiredLevel(attrs);
        return userLevel &gt;= requiredLevel
            ? ACCESS_GRANTED : ACCESS_DENIED;
    }
}</pre>
    </div>

    <div class="section-heading">ReBAC — Relationship-Based Access Control</div>
    <div class="concept-box">
      ReBAC asks: <strong>"Does a path exist in the relationship graph from this user to this resource?"</strong><br>
      Access is determined by traversing an object relationship graph: <code>user → member-of → team → viewer-of → folder → parent-of → document</code>.<br>
      Google's <strong>Zanzibar</strong> paper (2019) formalized this; <strong>OpenFGA</strong>, <strong>Ory Keto</strong>, and <strong>SpiceDB</strong> are open-source implementations.
    </div>
    <div class="demo-grid">
      <div class="card">
        <div class="card-title" style="color:var(--green)">Benefits</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Natural for hierarchies</strong> — folder → document → comment inheritance is a first-class concept, not a hack on top of roles</li>
          <li><strong>Handles "shared with me"</strong> — access via group membership, direct share, or parent object all resolve the same way</li>
          <li><strong>Consistent API:</strong> <code>check(user, relation, object)</code> answers any access question</li>
          <li><strong>Scales with data</strong> — adding millions of objects adds tuples, not roles</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title" style="color:var(--red)">Drawbacks</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Graph traversal cost</strong> — deep hierarchies require multi-hop lookups; Zanzibar uses aggressive caching (Zookies) to compensate</li>
          <li><strong>Relationship tuple storage</strong> — every (user, relation, object) triple must be stored; large sharing graphs require a dedicated store</li>
          <li><strong>Schema design is hard</strong> — getting the authorization model right upfront is critical; renames break existing tuples</li>
          <li><strong>Operational complexity</strong> — running OpenFGA or SpiceDB is another service to operate</li>
        </ul>
      </div>
    </div>
    <div class="card" style="margin-bottom:16px">
      <div class="card-title">OpenFGA / Zanzibar Tuple Model</div>
      <pre class="response-body" style="margin:0">// OpenFGA authorization model (schema)
type document
  relations
    define owner: [user]
    define viewer: [user, team#member] or owner
    define editor: [user] or owner

// Stored tuples
user:alice  owner   document:report-q3
user:bob    viewer  document:report-q3

// Spring: check via OpenFGA SDK
CheckRequest req = new CheckRequest().tupleKey(
    new TupleKey().user("user:"+name)._object("document:"+id).relation("viewer"));
boolean allowed = fgaClient.check(req).getAllowed();</pre>
    </div>

    <div class="section-heading">ACL — Access Control Lists</div>
    <div class="concept-box">
      An ACL is a list of <strong>(principal, permission)</strong> pairs attached to each resource.<br>
      When access is requested, the system looks up the resource's ACL and checks whether the requesting principal has the required permission listed.
      Linux filesystem permissions (<code>rwxr-xr--</code>) are the most familiar example.
    </div>
    <div class="demo-grid">
      <div class="card">
        <div class="card-title" style="color:var(--green)">Benefits</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Per-resource precision</strong> — each object has its own independently configured permission set</li>
          <li><strong>Simple to reason about</strong> — "who has access to this file?" is answered by reading its ACL directly</li>
          <li><strong>Well-understood</strong> — OS, databases, cloud storage (S3 bucket policies) all implement ACLs; tooling is mature</li>
          <li><strong>Granular without a graph</strong> — no hierarchy to traverse; the answer is in the list</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title" style="color:var(--red)">Drawbacks</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Does not scale</strong> — N resources × M users = enormous ACL tables; querying "what can user X access?" scans every ACL</li>
          <li><strong>No inheritance</strong> — permissions do not propagate from parent to child unless explicitly copied</li>
          <li><strong>Stale entries</strong> — when a user is deleted, their ACL entries across all resources must be found and cleaned up</li>
          <li><strong>Audit complexity</strong> — "what does bob have access to?" requires joining across all resource ACLs</li>
        </ul>
      </div>
    </div>
    <div class="card" style="margin-bottom:16px">
      <div class="card-title">Spring Security ACL</div>
      <pre class="response-body" style="margin:0">// Grant permission
ObjectIdentity oi = new ObjectIdentityImpl(Document.class, resourceId);
MutableAcl acl = (MutableAcl) aclService.readAclById(oi);
acl.insertAce(acl.getEntries().size(), BasePermission.WRITE,
    new PrincipalSid(username), true);
aclService.updateAcl(acl);

// Check
@PreAuthorize("hasPermission(#resourceId, 'com.example.Document', 'write')")
public void updateDocument(Long resourceId, DocumentDto dto) { ... }</pre>
    </div>

    <div class="section-heading">PBAC — Policy-Based Access Control</div>
    <div class="concept-box">
      PBAC externalizes access decisions to a <strong>policy engine</strong> that evaluates declarative rules at runtime.<br>
      Your application asks: <code>allowed = engine.evaluate(input)</code>. The engine (OPA, AWS Cedar, Casbin) consults a policy document — not hardcoded logic — and returns a decision.<br>
      Policies can be updated, versioned, and tested independently of application code.
    </div>
    <div class="demo-grid">
      <div class="card">
        <div class="card-title" style="color:var(--green)">Benefits</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>Decoupled from code</strong> — policy changes ship without a code deploy; security teams update rules independently</li>
          <li><strong>Auditable and version-controlled</strong> — policy files live in Git; every change is a diff, reviewable and traceable</li>
          <li><strong>Testable in isolation</strong> — policy unit tests run without spinning up the application</li>
          <li><strong>Consistent across services</strong> — a single OPA sidecar enforces the same policy across 20 microservices</li>
          <li><strong>Expressive</strong> — OPA's Rego and Cedar can express RBAC, ABAC, and ownership checks in one policy language</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title" style="color:var(--red)">Drawbacks</div>
        <ul class="text-sm" style="padding-left:18px;line-height:2.2">
          <li><strong>New language to learn</strong> — OPA's Rego is non-obvious; Cedar has its own syntax; real onboarding cost</li>
          <li><strong>Latency</strong> — every access check is an external call; hot paths need caching</li>
          <li><strong>Operational overhead</strong> — another service to deploy, monitor, and keep in sync with application data</li>
          <li><strong>Overkill for small teams</strong> — if one team owns all services, hardcoded RBAC with code review is simpler</li>
          <li><strong>Data synchronization</strong> — the policy engine needs up-to-date user/resource data; stale input → wrong decisions</li>
        </ul>
      </div>
    </div>
    <div class="card" style="margin-bottom:16px">
      <div class="card-title">OPA (Rego) Policy + Spring Integration</div>
      <pre class="response-body" style="margin:0">// invoice_approval.rego
package invoice.approval
default allow = false
allow {
    input.user.role == "manager"
    input.user.department == input.invoice.department
    input.invoice.amount &lt;= 50000
}
allow { input.user.department == "finance" }

// Spring Boot — call OPA sidecar
Map&lt;String,Object&gt; body = Map.of("input", Map.of(
    "user",    Map.of("role", role, "department", dept),
    "invoice", Map.of("department", inv.getDept(), "amount", inv.getAmount())));
OpaResponse res = restTemplate.postForObject(
    "http://opa:8181/v1/data/invoice/approval", body, OpaResponse.class);
if (!Boolean.TRUE.equals(res.getResult().get("allow")))
    throw new AccessDeniedException("Policy denied");</pre>
    </div>`;
}

// ── PAGE: RATE LIMITING ───────────────────────────────────────
function rateLimitPage() {
  return `
    <div class="page-title">⚡ Rate Limiting</div>
    <div class="page-sub">Token bucket algorithm — exhaust tokens to trigger 429 Too Many Requests.</div>

    <div class="concept-box">
      Each client gets a <strong>bucket of tokens</strong>. Each request consumes 1 token. Tokens refill at a fixed rate.<br>
      If the bucket is empty → <code>429 Too Many Requests</code>.<br>
      Response headers tell clients how many tokens remain and how long to wait: <code>X-Rate-Limit-Remaining</code>, <code>Retry-After</code>
    </div>

    <div class="triple-grid">
      ${rateLimitCard('standard', 'Standard', '/api/rate/standard', 20, 'Per-IP: 20 requests per minute')}
      ${rateLimitCard('strict', 'Strict', '/api/rate/strict', 5, 'Shared: 5 requests per minute — models login endpoints')}
      ${rateLimitCard('tiered', 'Tiered', '/api/rate/tiered', 10, 'Burst: 10/10s AND Sustained: 100/hr — both must pass')}
    </div>`;
}

function rateLimitCard(key, label, endpoint, max, desc) {
  return `
    <div class="card">
      <div class="card-title">${label} Limit</div>
      <div class="text-sm text-muted mb-8">${desc}</div>
      <div id="rate-${key}-meter">${tokenMeter(State.rateBuckets[key] ?? max, max, 'Token bucket')}</div>
      <div class="btn-group mt-8">
        <button class="btn btn-primary btn-sm" data-action="rateSend" data-endpoint="${endpoint}" data-key="${key}">Send 1</button>
        <button class="btn btn-danger btn-sm" data-action="rateRapidFire" data-endpoint="${endpoint}" data-key="${key}">Rapid Fire ×10</button>
      </div>
      <div id="rate-${key}-request" class="mt-8">${requestViewer(null)}</div>
      <div id="rate-${key}-response" class="mt-8">${responseViewer(null)}</div>
    </div>`;
}

// ── PAGE: CIRCUIT BREAKER ─────────────────────────────────────
function circuitBreakerPage() {
  const state = State.cbState;
  const logHtml = State.cbLog.map(e => `
    <div class="cb-log-entry">
      <span class="cb-log-time">${e.time}</span>
      <span class="${e.isFallback ? 'cb-log-fallback' : e.fail ? 'cb-log-failure' : 'cb-log-success'}">
        ${e.isFallback ? '⚡ FALLBACK' : e.fail ? '✗ FAILURE' : '✓ SUCCESS'}
      </span>
    </div>`).join('');

  return `
    <div class="page-title">🔄 Circuit Breaker</div>
    <div class="page-sub">Fail fast to prevent cascading failures when a downstream service is broken.</div>

    <div class="concept-box">
      Like an electrical circuit breaker: too many failures → circuit <strong>OPENS</strong> → requests fail immediately without trying the service.<br>
      After a cooldown (10s), the circuit goes <strong>HALF-OPEN</strong> to test recovery.<br>
      Config: opens after <strong>50% failure rate</strong> over last 10 calls.
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Circuit State (Live)</div>
        <div class="cb-diagram">
          <div class="cb-state cb-state-closed ${state === 'CLOSED' ? 'active' : ''}">
            <span class="cb-state-icon">✅</span>
            <span class="cb-state-name">Closed</span>
            <span class="cb-state-desc">Normal — requests flow through</span>
          </div>
          <span class="cb-arrow">→</span>
          <div class="cb-state cb-state-open ${state === 'OPEN' ? 'active' : ''}">
            <span class="cb-state-icon">🚫</span>
            <span class="cb-state-name">Open</span>
            <span class="cb-state-desc">Failing — immediate fallback</span>
          </div>
          <span class="cb-arrow">→</span>
          <div class="cb-state cb-state-half ${state === 'HALF_OPEN' ? 'active' : ''}">
            <span class="cb-state-icon">🔶</span>
            <span class="cb-state-name">Half-Open</span>
            <span class="cb-state-desc">Testing recovery</span>
          </div>
        </div>
        <div class="cb-stats">
          <div class="cb-stat">
            <div class="cb-stat-value" style="color:var(--success)">${State.cbSuccesses}</div>
            <div class="cb-stat-label">Successes</div>
          </div>
          <div class="cb-stat">
            <div class="cb-stat-value" style="color:var(--error)">${State.cbFailures}</div>
            <div class="cb-stat-label">Failures</div>
          </div>
          <div class="cb-stat">
            <div class="cb-stat-value" style="color:${state === 'CLOSED' ? 'var(--success)' : state === 'OPEN' ? 'var(--error)' : 'var(--warning)'}">${state}</div>
            <div class="cb-stat-label">Circuit State</div>
          </div>
        </div>
        <div class="card-title text-sm mt-12">Event Log</div>
        <div class="cb-log">${logHtml || '<span style="color:#475569">No events yet</span>'}</div>
      </div>

      <div class="card">
        <div class="card-title">Send Requests</div>
        <div class="btn-group">
          <button class="btn btn-success" data-action="cbSend" data-fail="false">✓ Success Request</button>
          <button class="btn btn-danger" data-action="cbSend" data-fail="true">✗ Fail Request</button>
          <button class="btn btn-secondary" data-action="cbReset">Reset</button>
        </div>
        <div class="text-xs text-muted mt-8">
          Tip: send 6+ fail requests to open the circuit. Then send a success — notice the immediate fallback response.
        </div>
      </div>
    </div>

    <div class="http-exchange">
      <div id="cb-request">${requestViewer(null)}</div>
      <div id="cb-response">${responseViewer(null)}</div>
    </div>

    <div class="card">
      <div class="card-title">⏱️ Timeout Demo</div>
      <div class="text-sm text-muted mb-8">Configured timeout: <strong>3 seconds</strong>. A &lt;3s delay succeeds; ≥3s triggers the fallback.</div>
      <div class="flex gap-8 items-center" style="flex-wrap:wrap">
        <div class="form-row" style="margin:0;flex:1;min-width:120px">
          <label class="form-label">Delay (seconds)</label>
          <input class="form-input" id="timeout-delay" type="number" value="2" min="0" max="8">
        </div>
        <button class="btn btn-primary" data-action="timeoutSend" style="margin-top:18px">Send →</button>
      </div>
      <div id="timeout-request" class="mt-12">${requestViewer(null)}</div>
      <div id="timeout-result" class="mt-8"></div>
    </div>`;
}

function refreshCb(res) {
  // Re-render only the CB-specific parts (avoid full page re-render which resets inputs)
  const state = State.cbState;
  const states = [
    { key: 'CLOSED',    cls: 'cb-state-closed' },
    { key: 'OPEN',      cls: 'cb-state-open' },
    { key: 'HALF_OPEN', cls: 'cb-state-half' },
  ];
  document.querySelectorAll('.cb-state').forEach((el, i) => {
    el.classList.toggle('active', states[i]?.key === state);
  });
  const vals = document.querySelectorAll('.cb-stat-value');
  if (vals[0]) vals[0].textContent = State.cbSuccesses;
  if (vals[1]) vals[1].textContent = State.cbFailures;
  if (vals[2]) {
    vals[2].textContent = state;
    vals[2].style.color = state === 'CLOSED' ? 'var(--success)' : state === 'OPEN' ? 'var(--error)' : 'var(--warning)';
  }
  const logEl = document.querySelector('.cb-log');
  if (logEl) {
    const logHtml = State.cbLog.map(e => `
      <div class="cb-log-entry">
        <span class="cb-log-time">${e.time}</span>
        <span class="${e.isFallback ? 'cb-log-fallback' : e.fail ? 'cb-log-failure' : 'cb-log-success'}">
          ${e.isFallback ? '⚡ FALLBACK' : e.fail ? '✗ FAILURE' : '✓ SUCCESS'}
        </span>
      </div>`).join('');
    logEl.innerHTML = logHtml || '<span style="color:#475569">No events yet</span>';
  }
}

// ── PAGE: THIRD-PARTY APIS ───────────────────────────────────
function thirdPartyPage() {
  return `
    <div class="page-title">🔌 Third-Party API Integration</div>
    <div class="page-sub">Design patterns when your service consumes external providers — not just serves users.</div>

    <div class="concept-box">
      Integrating with providers inverts the usual model. <strong>You are the client</strong>, not the server.<br>
      Authentication, error handling, rate limits, and availability are all controlled by someone else.
      <table class="comparison-table" style="margin-top:12px">
        <thead><tr><th></th><th>User-Facing API</th><th>Third-Party Integration</th></tr></thead>
        <tbody>
          <tr><td>Auth direction</td><td>Client → your API</td><td>You → provider (outbound)<br>Provider → your webhook (inbound)</td></tr>
          <tr><td>Auth mechanism</td><td>JWT / session / API key</td><td>HMAC signature verify (inbound)<br>API key / OAuth2 (outbound)</td></tr>
          <tr><td>Rate limits</td><td>You set them</td><td>Provider sets them — you obey</td></tr>
          <tr><td>Availability SLA</td><td>You control</td><td>Provider controls — design for their outages</td></tr>
          <tr><td>Error format</td><td>You standardize</td><td>Map provider errors → your domain</td></tr>
          <tr><td>Schema changes</td><td>You control</td><td>Provider can change anytime — parse defensively</td></tr>
        </tbody>
      </table>
    </div>

    <div class="demo-grid">

      <div class="card">
        <div class="card-title">Pattern 1 — Webhook Receiver</div>
        <div class="text-sm text-muted mb-8">
          Providers POST events to your URL when something happens (payment succeeded, PR merged, etc.).
          You must <strong>verify the signature</strong> on every request — otherwise anyone can POST fake events to your public URL.
        </div>
        <div class="alert alert-info text-sm">
          Webhook secret for this demo: <code>whsec_demo_secret_12345</code><br>
          Signature format: <code>X-Webhook-Signature: sha256=&lt;HMAC-SHA256(secret, timestamp.body)&gt;</code>
        </div>
        <div class="form-row">
          <label class="form-label">Event type</label>
          <select class="form-select" id="tp-event-type">
            <option value="payment.completed">payment.completed</option>
            <option value="payment.failed">payment.failed</option>
            <option value="subscription.cancelled">subscription.cancelled</option>
            <option value="user.created">user.created</option>
          </select>
        </div>
        <div class="btn-group" style="flex-direction:column;gap:8px;margin-top:8px">
          <button class="btn btn-primary btn-block" data-action="tpSendWebhook" data-tamper="false" data-replay="false">
            ✓ Send valid signed webhook
          </button>
          <button class="btn btn-secondary btn-block" data-action="tpSendWebhook" data-tamper="true" data-replay="false">
            ✗ Send tampered payload (signature fails)
          </button>
          <button class="btn btn-secondary btn-block" data-action="tpSendWebhook" data-tamper="false" data-replay="true">
            ✗ Replay attack — old timestamp (rejected)
          </button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">Received Event Log</div>
        <div class="text-sm text-muted mb-8">
          Events successfully verified and stored. Send webhooks on the left to populate this log.<br>
          <strong>Idempotency:</strong> sending the same event twice is detected and skipped.
        </div>
        <div id="tp-event-log"><div class="text-muted text-sm">No events yet — send a webhook to see them here.</div></div>
        <div class="divider"></div>
        <div class="text-sm text-muted">
          <strong>Production pattern:</strong><br>
          1. Verify signature → reject immediately if invalid<br>
          2. Check event ID → skip if already processed (idempotency)<br>
          3. Store raw event to DB<br>
          4. Return <code>200 OK</code> immediately<br>
          5. Process event async (background job / queue)<br>
          <br>
          Never do heavy processing before the 200 — providers mark delivery
          as failed if you take &gt;5s and will retry, causing duplicates.
        </div>
      </div>

    </div>

    <div class="http-exchange">
      <div id="tp-webhook-request">${requestViewer(null)}</div>
      <div id="tp-webhook-result">${responseViewer(null)}</div>
    </div>

    <div class="card full-width">
      <div class="card-title">Pattern 2 — Outbound API Call Scenarios</div>
      <div class="text-sm text-muted mb-12">
        Click each scenario to see the correct handling pattern. Each maps to a real provider error you will encounter.
      </div>
      <div class="btn-group" style="flex-wrap:wrap;gap:8px">
        <button class="btn btn-secondary" data-action="tpOutbound" data-scenario="success">
          ✓ Success
        </button>
        <button class="btn btn-secondary" data-action="tpOutbound" data-scenario="rate-limited">
          429 Rate Limited
        </button>
        <button class="btn btn-secondary" data-action="tpOutbound" data-scenario="auth-failed">
          401 Auth Failed
        </button>
        <button class="btn btn-secondary" data-action="tpOutbound" data-scenario="server-error">
          503 Server Error
        </button>
        <button class="btn btn-secondary" data-action="tpOutbound" data-scenario="schema-drift">
          Schema Drift
        </button>
      </div>
    </div>

    <div class="http-exchange">
      <div id="tp-outbound-request">${requestViewer(null)}</div>
      <div id="tp-outbound-result">${responseViewer(null)}</div>
    </div>

    <div class="card">
      <div class="card-title">Credential Management</div>
      <table class="comparison-table">
        <thead><tr><th>Practice</th><th>Bad</th><th>Good</th></tr></thead>
        <tbody>
          <tr>
            <td>Storage</td>
            <td class="con">Hardcoded in source code or config files</td>
            <td class="pro">Environment variable or secrets manager (Vault, AWS Secrets Manager)</td>
          </tr>
          <tr>
            <td>Rotation</td>
            <td class="con">Rotate by deleting old key — causes downtime</td>
            <td class="pro">Activate new key → migrate traffic → revoke old (overlap window)</td>
          </tr>
          <tr>
            <td>Scope</td>
            <td class="con">One key with all permissions for all services</td>
            <td class="pro">Per-service keys with minimum required permissions</td>
          </tr>
          <tr>
            <td>Environments</td>
            <td class="con">Same key in dev, staging, prod</td>
            <td class="pro">Separate keys per environment — prod keys never leave prod</td>
          </tr>
          <tr>
            <td>Logging</td>
            <td class="con">Log full request headers (exposes key)</td>
            <td class="pro">Redact Authorization headers and API key fields in logs</td>
          </tr>
          <tr>
            <td>Monitoring</td>
            <td class="con">Find out key was revoked when calls start failing</td>
            <td class="pro">Alert on 401s from provider; set key expiry reminders</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <div class="card-title">Webhook Security Checklist</div>
      <div class="triple-grid" style="gap:12px">
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:6px">Signature Verification</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li>Verify HMAC on <em>every</em> request, no exceptions</li>
            <li>Use constant-time comparison (not <code>===</code>)</li>
            <li>Include timestamp in signed payload</li>
            <li>Reject if timestamp is &gt; 5 min old</li>
          </ul>
        </div>
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:6px">Idempotency</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li>Store event ID before processing</li>
            <li>Check for duplicate event ID on arrival</li>
            <li>Make all event handlers idempotent</li>
            <li>Providers retry — expect duplicates</li>
          </ul>
        </div>
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:6px">Reliability</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li>ACK with 200 in &lt; 5s always</li>
            <li>Process async — never block the ACK</li>
            <li>Handle out-of-order delivery</li>
            <li>Expose a manual replay endpoint for recovery</li>
          </ul>
        </div>
      </div>
    </div>`;
}

function renderEventLog(events) {
  if (!events || events.length === 0) {
    return '<div class="text-muted text-sm">No events received yet.</div>';
  }
  return events.map(e => `
    <div class="cb-log-entry" style="margin-bottom:8px;padding:8px;background:var(--surface);border-radius:6px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px">
        <code style="font-size:11px">${e.type}</code>
        <div>
          ${e.duplicate ? '<span class="tag tag-yellow" style="font-size:10px">DUPLICATE</span>' : '<span class="tag tag-green" style="font-size:10px">NEW</span>'}
          <span class="text-muted" style="font-size:10px;margin-left:6px">${e.receivedAt?.substring(11, 19) || ''}</span>
        </div>
      </div>
      <code style="font-size:10px;color:var(--text-muted)">${e.eventId}</code>
    </div>`).join('');
}

// ── PAGE: PARTNER INTEGRATION ─────────────────────────────────
function partnerApiPage() {
  return `
    <div class="page-title">🤝 Partner Integration (B2B)</div>
    <div class="page-sub">Designing your API to be consumed by third-party companies — not just end users.</div>

    <div class="concept-box">
      Opening your API to external companies introduces a fundamentally different set of concerns.
      <strong>A partner integration is a contractual relationship</strong>, not a user session.
      <table class="comparison-table" style="margin-top:12px">
        <thead><tr><th></th><th>User-Facing API</th><th>B2B Partner API</th></tr></thead>
        <tbody>
          <tr><td>Identity unit</td><td>Individual user (JWT per session)</td><td>Organization (long-lived API key)</td></tr>
          <tr><td>Rate limits</td><td>Per IP or per user</td><td>Per partner, tiered by contract</td></tr>
          <tr><td>Data access</td><td>Own records only</td><td>Tenant-scoped subset</td></tr>
          <tr><td>Breaking changes</td><td>Notify users, soft-launch</td><td>6–12 month deprecation period</td></tr>
          <tr><td>Audit trail</td><td>Session logs</td><td>Immutable, per-partner, compliance-grade</td></tr>
          <tr><td>Event delivery</td><td>Optional webhooks</td><td>You push signed events to partner URLs</td></tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <div class="card-title">Why API Keys Alone Are Not Enough</div>
      <div class="text-sm" style="margin-bottom:10px">An API key in a header proves the caller <em>knows the key</em> — it does not prove the request was not tampered with, is not a replay, or came from an authorized source. Real B2B integrations layer multiple mechanisms on top of the API key.</div>
      <table class="comparison-table">
        <thead><tr><th>Risk</th><th>What Happens</th><th>Mitigation</th></tr></thead>
        <tbody>
          <tr><td><strong>Key leakage</strong></td><td>Partner embeds key in source code or CI/CD logs — compromised without a breach of your system</td><td>HMAC signing: knowing the key alone is not enough without the signing secret</td></tr>
          <tr><td><strong>Replay attack</strong></td><td>Attacker captures a valid request and resends it unchanged — the API key is still valid</td><td>HMAC + timestamp: server rejects requests where <code>|now − timestamp| &gt; 300s</code></td></tr>
          <tr><td><strong>Body tampering</strong></td><td>Body is modified in transit; the key header is preserved — the API key covers identity, not payload integrity</td><td>HMAC signs the full request body — any modification invalidates the signature</td></tr>
          <tr><td><strong>Impersonation</strong></td><td>Any client that learns the key can call your API — the key proves knowledge of a secret, not caller identity</td><td>mTLS: client must present a certificate at the TLS handshake level</td></tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <div class="card-title">Select Partner Key</div>
      <div class="flex gap-16" style="flex-wrap:wrap;align-items:flex-end">
        <div class="form-row" style="margin:0;flex:1;min-width:240px">
          <label class="form-label">X-Partner-Key</label>
          <select class="form-select" id="partner-key">
            <option value="partner-alpha-key-12345">partner-alpha-key-12345 → Alpha Corp (PREMIUM)</option>
            <option value="partner-beta-key-12345">partner-beta-key-12345 → Beta Inc (STANDARD)</option>
            <option value="partner-gamma-key-12345">partner-gamma-key-12345 → Gamma LLC (FREE)</option>
            <option value="invalid-key">invalid-key → Should return 401</option>
          </select>
        </div>
      </div>
      <div class="text-sm text-muted mt-8">
        All requests below use this key. Switch keys to see how tier affects scopes, quota, and data access.<br>
        <strong>Note:</strong> In production each request would also carry <code>X-Timestamp</code> and <code>X-Signature</code> (HMAC-SHA256) headers. The interactive demos here focus on partner key auth, tenant isolation, and scope enforcement — see the HMAC section below for the signing pattern.
      </div>
    </div>

    <div class="demo-grid">

      <div class="card">
        <div class="card-title">1. Partner Auth Info</div>
        <div class="text-sm text-muted mb-8">
          Identifies the partner organization, tier, and granted scopes.<br>
          Notice how <strong>Alpha Corp</strong> gets 5 scopes vs <strong>Gamma LLC</strong>'s 1 scope.
        </div>
        <button class="btn btn-primary btn-block" data-action="partnerFetch" data-endpoint="/api/partner/auth-info">
          GET /api/partner/auth-info →
        </button>
        <div id="partner-auth-request" class="mt-12">${requestViewer(null)}</div>
        <div id="partner-auth-result" class="mt-8">${responseViewer(null)}</div>
      </div>

      <div class="card">
        <div class="card-title">2. Tenant-Isolated Catalog</div>
        <div class="text-sm text-muted mb-8">
          Same endpoint, different data per partner — this is <strong>tenant isolation</strong>.<br>
          Alpha sees 3 items, Beta sees 2, Gamma sees 1. Gamma FREE tier needs <code>catalog:read</code> scope (which it has).
          FREE tier does NOT have <code>orders:read</code> scope — try the quota demo for that.
        </div>
        <button class="btn btn-primary btn-block" data-action="partnerFetch" data-endpoint="/api/partner/catalog">
          GET /api/partner/catalog →
        </button>
        <div id="partner-catalog-request" class="mt-12">${requestViewer(null)}</div>
        <div id="partner-catalog-result" class="mt-8">${responseViewer(null)}</div>
      </div>

      <div class="card">
        <div class="card-title">3. Quota &amp; Tiered Rate Limits</div>
        <div class="text-sm text-muted mb-8">
          Rate limits by partner ID (not by IP — partners may call from multiple servers).<br>
          Tiers: FREE 100/min, STANDARD 1,000/min, PREMIUM 10,000/min.<br>
          Quota info surfaces in <code>X-RateLimit-*</code> response headers.
        </div>
        <button class="btn btn-primary btn-block" data-action="partnerFetch" data-endpoint="/api/partner/quota">
          GET /api/partner/quota →
        </button>
        <div id="partner-quota-request" class="mt-12">${requestViewer(null)}</div>
        <div id="partner-quota-result" class="mt-8">${responseViewer(null)}</div>
      </div>

      <div class="card">
        <div class="card-title">4. Audit Log</div>
        <div class="text-sm text-muted mb-8">
          Immutable per-partner call trail — required for compliance (SOC 2, PCI-DSS).<br>
          Only <strong>PREMIUM</strong> (Alpha) has <code>analytics:read</code> scope to view the audit log.<br>
          Try with Beta or Gamma key — you'll get <code>403 INSUFFICIENT_SCOPE</code>.
        </div>
        <button class="btn btn-primary btn-block" data-action="partnerFetch" data-endpoint="/api/partner/audit">
          GET /api/partner/audit →
        </button>
        <div id="partner-audit-request" class="mt-12">${requestViewer(null)}</div>
        <div id="partner-audit-result" class="mt-8">${responseViewer(null)}</div>
      </div>

      <div class="card">
        <div class="card-title">5. API Versioning Contract</div>
        <div class="text-sm text-muted mb-8">
          With B2B partners, breaking changes require <strong>6–12 months notice</strong>.<br>
          <code>Deprecation</code> header warns the integration is retiring.<br>
          <code>Sunset</code> gives the exact retirement date (RFC 8594).<br>
          <code>Link</code> points to the successor version or migration guide.
        </div>
        <div class="btn-group">
          <button class="btn btn-secondary" data-action="partnerVersionFetch" data-version="v1">
            GET /v1/items (deprecated)
          </button>
          <button class="btn btn-primary" data-action="partnerVersionFetch" data-version="v2">
            GET /v2/items (current)
          </button>
        </div>
        <div id="partner-version-request" class="mt-12">${requestViewer(null)}</div>
        <div id="partner-version-result" class="mt-8">${responseViewer(null)}</div>
      </div>

      <div class="card">
        <div class="card-title">6. Outbound Webhook Dispatch</div>
        <div class="text-sm text-muted mb-8">
          Your system pushes signed events to the partner's registered callback URL.<br>
          This is the <em>inverse</em> of webhook receiving (Third-Party APIs section):<br>
          you generate the signature, the partner verifies it.<br>
          Requires <code>orders:write</code> or <code>catalog:write</code> scope — Alpha and Beta only.
        </div>
        <div class="form-row">
          <label class="form-label">Event type</label>
          <select class="form-select" id="partner-event-type">
            <option value="order.created">order.created</option>
            <option value="inventory.low">inventory.low</option>
          </select>
        </div>
        <button class="btn btn-primary btn-block mt-8" data-action="partnerDispatchWebhook">
          Dispatch webhook to partner callback →
        </button>
        <div id="partner-webhook-request" class="mt-12">${requestViewer(null)}</div>
        <div id="partner-webhook-result" class="mt-8">${responseViewer(null)}</div>
      </div>

    </div>

    <div class="card">
      <div class="card-title">HMAC Request Signing</div>
      <div class="text-sm" style="margin-bottom:10px">
        Partners sign every inbound request with <strong>HMAC-SHA256</strong> using a shared signing secret (separate from the API key). The server recomputes the signature independently and rejects mismatches — without the secret the signature cannot be forged.
      </div>
      <table class="comparison-table">
        <thead><tr><th>Header</th><th>Value</th><th>Purpose</th></tr></thead>
        <tbody>
          <tr><td><code>X-Partner-Key</code></td><td><code>partner-alpha-key-12345</code></td><td>Identifies the partner organization</td></tr>
          <tr><td><code>X-Timestamp</code></td><td><code>1704067200</code> (Unix epoch)</td><td>Binds the signature to this moment; server rejects <code>|now − ts| &gt; 300s</code></td></tr>
          <tr><td><code>X-Signature</code></td><td><code>sha256=a7f3d2b1...</code></td><td>HMAC-SHA256 of <code>METHOD\nPATH\nTIMESTAMP\nSHA256(body)</code></td></tr>
        </tbody>
      </table>
      <div class="text-sm text-muted" style="margin-top:8px">
        The signed canonical string covers the HTTP method, path, timestamp, and a hash of the request body. Any modification to the body changes its SHA-256 hash, which changes the HMAC output — a man-in-the-middle cannot tamper without the secret. Always use <code>MessageDigest.isEqual()</code> for constant-time comparison; <code>String.equals()</code> is vulnerable to timing attacks. See <code>docs/10-partner-integration.md</code> for the full Java server-side implementation.
      </div>
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Mutual TLS (mTLS)</div>
        <table class="comparison-table" style="margin-bottom:8px">
          <thead><tr><th></th><th>Standard TLS</th><th>Mutual TLS</th></tr></thead>
          <tbody>
            <tr><td>Server cert</td><td>✓</td><td>✓</td></tr>
            <tr><td>Client cert</td><td>✗</td><td>✓ — signed by your CA</td></tr>
            <tr><td>Enforced at</td><td>TLS handshake</td><td>TLS handshake (before your code runs)</td></tr>
            <tr><td>Revocation</td><td>N/A</td><td>CRL / OCSP — immediate, no deploy</td></tr>
          </tbody>
        </table>
        <div class="text-sm text-muted">
          Partners present a client certificate on every connection. The API gateway rejects connections without a valid cert before a single HTTP byte reaches your application. Use for high-compliance environments (PCI-DSS, HIPAA).
        </div>
      </div>
      <div class="card">
        <div class="card-title">IP Allowlisting</div>
        <div class="text-sm" style="line-height:1.9">
          Partners register egress IP ranges at onboarding. Requests from unregistered IPs are rejected at the <strong>load balancer</strong> — before authentication is even attempted.<br><br>
          <strong>Rules:</strong>
          <ul style="padding-left:16px;line-height:2;margin-top:4px">
            <li>Require static egress IPs (NAT gateway), not developer workstation IPs</li>
            <li>Treat IP range updates as approved change requests — never self-service</li>
            <li>Combine with API key + HMAC: a stolen key from an unregistered IP still returns 403</li>
          </ul>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Defense in Depth — Layered Security</div>
      <table class="comparison-table">
        <thead><tr><th>Layer</th><th>Mechanism</th><th>What It Prevents</th></tr></thead>
        <tbody>
          <tr><td><strong>Network</strong></td><td>IP allowlisting (load balancer)</td><td>Connections from unknown sources</td></tr>
          <tr><td><strong>Transport</strong></td><td>TLS 1.3 + mTLS client certificate</td><td>Eavesdropping, unauthorized TLS connections</td></tr>
          <tr><td><strong>Request integrity</strong></td><td>HMAC signing + 5-min timestamp window</td><td>Replay attacks, request body tampering</td></tr>
          <tr><td><strong>Identity</strong></td><td>Partner API key (HMAC-SHA256 hashed in DB)</td><td>Unauthorized API access</td></tr>
          <tr><td><strong>Authorization</strong></td><td>Scopes + tenant isolation per partner</td><td>Cross-partner data leakage, privilege escalation</td></tr>
          <tr><td><strong>Audit</strong></td><td>Immutable append-only logs</td><td>Undetected misuse, compliance gaps</td></tr>
        </tbody>
      </table>
      <div class="text-sm text-muted" style="margin-top:8px">
        <strong>Minimum viable B2B security:</strong> TLS + API key + HMAC signing + audit logs.<br>
        <strong>Full enterprise security:</strong> all of the above + mTLS + IP allowlisting.
      </div>
    </div>

    <div class="card">
      <div class="card-title">Partner Onboarding Checklist</div>
      <div class="triple-grid" style="gap:12px">
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:6px">Authentication &amp; Signing</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li>Issue per-partner API keys (not shared)</li>
            <li>Hash keys in DB — never store plaintext</li>
            <li>Support 2 active keys per partner (rotation)</li>
            <li>Issue a separate HMAC signing secret per partner</li>
            <li>Enforce 5-min timestamp window on signed requests</li>
            <li>Use constant-time comparison for signature verification</li>
            <li>Alert ops on sustained 401s from any partner</li>
          </ul>
        </div>
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:6px">Transport &amp; Network</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li>Enforce TLS 1.2+ on all endpoints (prefer 1.3)</li>
            <li>mTLS for high-compliance partners (HIPAA, PCI-DSS)</li>
            <li>Collect partner egress IP ranges at onboarding</li>
            <li>Enforce IP allowlist at the load balancer layer</li>
            <li>Treat IP range updates as approved change requests</li>
          </ul>
        </div>
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:6px">Data, Contracts &amp; Reliability</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li>Enforce tenant isolation on every query</li>
            <li>Assign scopes at provisioning, not per-request</li>
            <li>Provide a sandbox environment with test data</li>
            <li>Separate prod and sandbox keys</li>
            <li>Deprecation header on every old-version response</li>
            <li>Sunset date ≥ 6 months out (12 for enterprise)</li>
            <li>Sign outbound webhooks with per-partner secret</li>
            <li>Retry webhook delivery with exponential backoff</li>
          </ul>
        </div>
      </div>
    </div>`;
}

// ── PAGE: HANGING APIS ───────────────────────────────────────
function hangingApisPage() {
  return `
    <div class="page-title">⏳ Handling Hanging API Calls</div>
    <div class="page-sub">What happens when a downstream service stops responding — and how to protect against it.</div>

    <div class="concept-box">
      A hanging API call holds a server thread for its entire duration. Tomcat's default thread pool is
      <strong>200 threads</strong>. With 200 concurrent hanging calls your server stops responding to
      <em>everything</em> — not just requests to the slow service.<br><br>
      <strong>Two levels of protection you always need:</strong><br>
      &nbsp;1. <strong>Caller-side</strong> — HTTP client timeouts (connect + read)<br>
      &nbsp;2. <strong>Server-side</strong> — deadline on the async work itself (so threads are freed)
    </div>

    <div class="card">
      <div class="card-title">Configure the Experiment</div>
      <div class="flex gap-16" style="flex-wrap:wrap;align-items:flex-end">
        <div class="form-row" style="margin:0;min-width:160px">
          <label class="form-label">Downstream delay (s)</label>
          <input class="form-input" id="hanging-delay" type="number" value="5" min="1" max="15">
        </div>
        <div class="form-row" style="margin:0;min-width:160px">
          <label class="form-label">Deadline / timeout (s)</label>
          <input class="form-input" id="hanging-deadline" type="number" value="3" min="1" max="10">
        </div>
        <div id="hanging-timer" style="min-width:140px"></div>
      </div>
    </div>

    <div class="triple-grid">

      <div class="card">
        <div class="card-title">❌ Approach 1 — No Protection</div>
        <div class="text-sm text-muted mb-8">
          <code>Thread.sleep(delay)</code> blocks the Tomcat request thread for the full duration.
          Set delay=10 and notice: <em>your browser hangs for 10 seconds</em>.
        </div>
        <div class="alert alert-error text-sm">
          200 concurrent hangs = thread pool exhausted = server freeze.
        </div>
        <button class="btn btn-danger btn-block mt-8" data-action="hangingSend" data-approach="no-timeout">
          Send (no timeout) →
        </button>
        <div class="text-sm text-muted mt-8">
          <strong>Endpoint:</strong> <code>/api/hanging/no-timeout?delay=N</code><br>
          <strong>Thread impact:</strong> 1 thread blocked for N seconds<br>
          <strong>Response time:</strong> always N seconds
        </div>
      </div>

      <div class="card">
        <div class="card-title">✅ Approach 2 — CompletableFuture Deadline</div>
        <div class="text-sm text-muted mb-8">
          Java 9+ built-in: <code>CompletableFuture.orTimeout(deadline, SECONDS)</code>.
          Work runs in an executor thread; if the deadline passes, the caller gets a fast 504.
        </div>
        <div class="alert alert-success text-sm">
          Caller freed after deadline seconds regardless of how slow downstream is.
        </div>
        <button class="btn btn-primary btn-block mt-8" data-action="hangingSend" data-approach="deadline">
          Send (with deadline) →
        </button>
        <div class="text-sm text-muted mt-8">
          <strong>Endpoint:</strong> <code>/api/hanging/with-deadline?delay=N&deadline=N</code><br>
          <strong>Thread impact:</strong> request thread freed at deadline<br>
          <strong>Response time:</strong> min(delay, deadline) seconds
        </div>
      </div>

      <div class="card">
        <div class="card-title">✅ Approach 3 — HTTP Client Timeouts</div>
        <div class="text-sm text-muted mb-8">
          Configure <code>connectTimeout</code> and per-request <code>timeout</code> on the HTTP client.
          This endpoint calls <code>/api/hanging/no-timeout</code> using a Java HttpClient.
        </div>
        <div class="alert alert-warning text-sm">
          ⚠ Client timeout cancels <em>the caller's wait</em> — the server-side thread keeps running.
          Read the response to see both effects.
        </div>
        <button class="btn btn-secondary btn-block mt-8" data-action="hangingSend" data-approach="http-client">
          Send (HTTP client) →
        </button>
        <div class="text-sm text-muted mt-8">
          <strong>Endpoint:</strong> <code>/api/hanging/http-client?delay=N&timeout=N</code><br>
          <strong>Connect timeout:</strong> 2s (hardcoded)<br>
          <strong>Request timeout:</strong> deadline input above
        </div>
      </div>

    </div>

    <div class="http-exchange mt-8">
      <div id="hanging-request">${requestViewer(null)}</div>
      <div id="hanging-result">${responseViewer(null)}</div>
    </div>

    <div class="card mt-16">
      <div class="card-title">Strategy Comparison</div>
      <table class="comparison-table">
        <thead>
          <tr><th>Strategy</th><th>Frees request thread?</th><th>Cancels server work?</th><th>Complexity</th><th>Best for</th></tr>
        </thead>
        <tbody>
          <tr>
            <td>No protection</td>
            <td><span class="con">✗ Never</span></td>
            <td><span class="con">✗ N/A</span></td>
            <td>None</td>
            <td>⚠ Don't use</td>
          </tr>
          <tr>
            <td><code>CompletableFuture.orTimeout()</code></td>
            <td><span class="pro">✓ At deadline</span></td>
            <td><span class="con">✗ Task may linger</span></td>
            <td>Low</td>
            <td>Simple service calls</td>
          </tr>
          <tr>
            <td>HTTP client timeouts</td>
            <td><span class="pro">✓ At timeout</span></td>
            <td><span class="con">✗ Server thread runs on</span></td>
            <td>Low</td>
            <td>Calling external services</td>
          </tr>
          <tr>
            <td><code>@TimeLimiter</code> (Resilience4j)</td>
            <td><span class="pro">✓ At timeout</span></td>
            <td><span class="pro">✓ Future cancelled</span></td>
            <td>Medium</td>
            <td>Production services + fallback</td>
          </tr>
          <tr>
            <td><code>@CircuitBreaker</code> (Resilience4j)</td>
            <td><span class="pro">✓ Immediately</span></td>
            <td><span class="pro">✓ No call made</span></td>
            <td>Medium</td>
            <td>Repeatedly failing downstream</td>
          </tr>
        </tbody>
      </table>

      <div class="divider"></div>
      <div class="card-title text-sm">The Two-Timeout Rule</div>
      <div class="text-sm text-muted" style="line-height:1.8">
        <strong>Connect timeout</strong> — guards against unreachable hosts (DNS failure, firewall drop).<br>
        <strong>Read/request timeout</strong> — guards against a host that accepts the connection but then hangs.<br><br>
        Both must be set. A read timeout without a connect timeout leaves you vulnerable to dead hosts.
        A connect timeout without a read timeout leaves you vulnerable to slow responses.
      </div>
    </div>`;
}

// ── PAGE: PAGINATION ──────────────────────────────────────────
function paginationPage() {
  return `
    <div class="page-title">📄 Pagination</div>
    <div class="page-sub">Always paginate — returning all records is a memory and performance anti-pattern.</div>

    <div class="concept-box">
      Spring Data's <code>Pageable</code>: <code>?page=0&size=6&sort=price,asc</code><br>
      Always return pagination metadata: <code>currentPage, totalPages, totalElements, hasNext, hasPrevious</code><br>
      Cap the <code>size</code> parameter server-side (max 100) to prevent abuse.
    </div>

    <div class="card">
      <div class="filter-bar">
        <div class="form-row">
          <label class="form-label">Category</label>
          <select class="form-select" id="filter-category">
            <option value="">All categories</option>
            <option value="Electronics">Electronics</option>
            <option value="Books">Books</option>
            <option value="Clothing">Clothing</option>
            <option value="Sports">Sports</option>
            <option value="Kitchen">Kitchen</option>
            <option value="Toys">Toys</option>
          </select>
        </div>
        <div class="form-row">
          <label class="form-label">Search</label>
          <input class="form-input" id="filter-search" placeholder="Product name..." data-enter="paginationFilter">
        </div>
        <button class="btn btn-primary" data-action="paginationFilter" style="margin-top:18px">Filter</button>
        <button class="btn btn-secondary" data-action="paginationLoad" style="margin-top:18px">Refresh</button>
      </div>

      <div id="product-grid" class="product-grid">
        <div class="text-muted text-sm">Loading products…</div>
      </div>

      <div id="pagination-meta" class="pagination-info text-center"></div>
      <div class="pagination-controls">
        <button class="page-btn" data-action="paginationPrev">‹ Prev</button>
        <div id="pagination-pages" style="display:flex;gap:6px"></div>
        <button class="page-btn" data-action="paginationNext">Next ›</button>
      </div>
    </div>`;
}

function renderProductGrid(data) {
  if (!data.content || data.content.length === 0) {
    return '<div class="text-muted text-sm">No products found.</div>';
  }
  return data.content.map(p => `
    <div class="product-card">
      <span class="product-category">${p.category}</span>
      <div class="product-name">${p.name}</div>
      <div class="product-price">$${p.price.toFixed(2)}</div>
      <div class="product-stock">${p.stock} in stock</div>
    </div>`).join('');
}

function renderPaginationButtons(data) {
  const container = document.getElementById('pagination-pages');
  if (!container) return;
  const pages = Math.min(data.totalPages, 7);
  let html = '';
  for (let i = 0; i < pages; i++) {
    html += `<button class="page-btn${i === data.currentPage ? ' current' : ''}"
      onclick="State.paginationPage=${i};handlers.paginationLoad()">${i + 1}</button>`;
  }
  container.innerHTML = html;
}

// ── PAGE: VERSIONING ──────────────────────────────────────────
function versioningPage() {
  return `
    <div class="page-title">📦 API Versioning</div>
    <div class="page-sub">Strategies, tradeoffs, and hard decisions for evolving APIs without breaking existing clients.</div>

    <div class="concept-box">
      When you change field names, types, or remove endpoints, existing clients break.
      Versioning lets you introduce breaking changes under a new version while old clients keep working.<br>
      Compare V1 <code>{"price": 9.99}</code> vs V2 <code>{"price": {"amount": 9.99, "currency": "USD"}}</code>
    </div>

    <div class="card">
      <div class="card-title">Select Strategy</div>
      <div class="flex gap-8 items-center" style="flex-wrap:wrap">
        <div class="form-row" style="margin:0;flex:1;min-width:180px">
          <label class="form-label">Versioning Strategy</label>
          <select class="form-select" id="version-strategy">
            <option value="uri">URI Path (/api/v1/items)</option>
            <option value="header">Request Header (X-API-Version)</option>
            <option value="query">Query Parameter (?version=1)</option>
            <option value="accept">Accept Header (application/vnd.demo.v1+json)</option>
          </select>
        </div>
        <button class="btn btn-secondary" data-action="versionFetch" data-version="1" style="margin-top:18px">Fetch V1</button>
        <button class="btn btn-primary"   data-action="versionFetch" data-version="2" style="margin-top:18px">Fetch V2</button>
      </div>
    </div>

    <div class="version-compare">
      <div class="card">
        <div class="card-title"><span class="version-badge version-v1">V1</span></div>
        <div id="version-v1-request">${requestViewer(null)}</div>
        <div id="version-v1-response" class="mt-8">${responseViewer(null, 'V1 — click Fetch V1')}</div>
      </div>
      <div class="card">
        <div class="card-title"><span class="version-badge version-v2">V2</span></div>
        <div id="version-v2-request">${requestViewer(null)}</div>
        <div id="version-v2-response" class="mt-8">${responseViewer(null, 'V2 — click Fetch V2')}</div>
        <div class="alert alert-warning text-sm mt-8">
          Breaking change: <code>price</code> changed from a <code>number</code> to an <code>object</code>.
          V1 clients parsing <code>res.price * 1.1</code> would break on V2.
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Try It — 410 Gone (Post-Sunset Endpoint)</div>
      <div class="text-sm text-muted mb-8">
        After a version is fully retired, return <code>410 Gone</code> — not <code>404</code>. 404 implies the URL was always wrong. 410 says: this existed, it was intentionally removed, stop retrying, follow the migration link.
      </div>
      <button class="btn btn-secondary" data-action="versionGone">GET /api/v0/items →</button>
      <div id="version-gone-request" class="mt-12">${requestViewer(null)}</div>
      <div id="version-gone-result" class="mt-8">${responseViewer(null)}</div>
    </div>

    <div class="card">
      <div class="card-title">Strategy Comparison</div>
      <table class="comparison-table">
        <thead><tr><th>Strategy</th><th>Example</th><th>Pros</th><th>Cons</th><th>Used by</th></tr></thead>
        <tbody>
          <tr>
            <td><strong>URI Path</strong></td>
            <td><code>/api/v2/items</code></td>
            <td><span class="pro">✓</span> Visible, cacheable, easy to test and share</td>
            <td><span class="con">✗</span> Version in URI violates REST resource semantics</td>
            <td>Stripe, Twilio, Twitter</td>
          </tr>
          <tr>
            <td><strong>Query Param</strong></td>
            <td><code>?version=2</code></td>
            <td><span class="pro">✓</span> Backward-compatible default</td>
            <td><span class="con">✗</span> Easy to omit; cache-key complications</td>
            <td>Google (some APIs)</td>
          </tr>
          <tr>
            <td><strong>Header</strong></td>
            <td><code>X-API-Version: 2</code></td>
            <td><span class="pro">✓</span> Clean URLs; version is metadata</td>
            <td><span class="con">✗</span> Invisible in browser; must add to Vary header for caching</td>
            <td>GitHub v3, Microsoft</td>
          </tr>
          <tr>
            <td><strong>Accept Header</strong></td>
            <td><code>application/vnd.co.v2+json</code></td>
            <td><span class="pro">✓</span> Most RESTful; proper HTTP content negotiation</td>
            <td><span class="con">✗</span> Complex; hard to test manually in a browser</td>
            <td>GitHub (media type)</td>
          </tr>
        </tbody>
      </table>
      <div class="text-sm text-muted" style="margin-top:8px">
        <strong>In practice:</strong> URI path wins on discoverability and simplicity. Use Accept header versioning only if REST purity is a hard requirement.
      </div>
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Non-Breaking Changes (no new version needed)</div>
        <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
          <li>Adding a new optional response field</li>
          <li>Adding a new endpoint or HTTP method</li>
          <li>Adding a new optional query parameter</li>
          <li>Making a required request field optional</li>
          <li>Adding a new enum value <em>(see caveat below)</em></li>
          <li>Increasing rate limits or quotas</li>
          <li>Improving response time or error messages</li>
        </ul>
      </div>
      <div class="card">
        <div class="card-title">Breaking Changes (require a new version)</div>
        <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
          <li>Renaming or removing a response field</li>
          <li>Changing a field's type (<code>number</code> → <code>object</code>)</li>
          <li>Removing an endpoint or HTTP method</li>
          <li>Making an optional field required</li>
          <li>Changing the authentication scheme</li>
          <li>Changing the error response format</li>
          <li>A bug fix that changes behavior clients rely on</li>
          <li>Narrowing accepted enum or input values</li>
        </ul>
      </div>
    </div>

    <div class="concept-box">
      <strong>Enum caveat:</strong> Adding a new enum value looks additive, but clients using exhaustive <code>switch/match</code> (e.g. <code>default: throw("unknown")</code>) break when they encounter the new value. Flag new enum values in release notes and recommend clients always implement a safe default branch.
    </div>

    <div class="card">
      <div class="card-title">The Hard Question: What If the Change Only Affects Some Users?</div>
      <div class="text-sm" style="margin-bottom:10px">
        A change breaks 20% of callers; 80% still work on the old behavior. Do you version? Force migration? The answer depends entirely on <em>who</em> those users are — not how many.
      </div>
      <table class="comparison-table">
        <thead><tr><th>Who Is Still on Legacy?</th><th>Recommended Approach</th><th>Why</th></tr></thead>
        <tbody>
          <tr>
            <td><strong>B2B / partner clients</strong></td>
            <td>Version it. 6–12 month sunset window. Contact holdouts individually near the deadline.</td>
            <td>Partners have production integrations you cannot touch. You cannot deploy a change on their behalf overnight.</td>
          </tr>
          <tr>
            <td><strong>Mobile app users (old versions)</strong></td>
            <td>Version it, possibly indefinitely. Old app store versions persist for years.</td>
            <td>You cannot force an app update. Breaking old clients causes silent crashes with no recovery path.</td>
          </tr>
          <tr>
            <td><strong>Internal services you own</strong></td>
            <td>Coordinated deploy — no versioning needed. Migrate caller and service together.</td>
            <td>You control both sides. Adding a version number to internal APIs adds overhead with little benefit.</td>
          </tr>
          <tr>
            <td><strong>Public third-party developers</strong></td>
            <td>Version it. Minimum 6 months notice with migration guide and code samples.</td>
            <td>Unknown count of callers. You cannot enumerate or contact them all. Some will be slow to migrate.</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <div class="card-title">When to Force Migration vs. Keep Legacy Alive</div>
      <table class="comparison-table">
        <thead><tr><th>Reason for Change</th><th>Force?</th><th>Timeline</th></tr></thead>
        <tbody>
          <tr><td><strong>Security vulnerability in old version</strong></td><td class="pro">Yes — immediately</td><td>Communicate now; give days to weeks, not months</td></tr>
          <tr><td><strong>GDPR / legal compliance requirement</strong></td><td class="pro">Yes — legal deadline</td><td>As short as technically feasible; the legal deadline is hard</td></tr>
          <tr><td><strong>Infrastructure cost reduction</strong></td><td class="pro">Yes — by Sunset date</td><td>Standard 6–12 months; announce early</td></tr>
          <tr><td><strong>Performance or reliability improvement</strong></td><td class="con">No</td><td>Encourage opt-in via migration guide; never force pure improvements</td></tr>
          <tr><td><strong>Developer experience improvement</strong></td><td class="con">No</td><td>Keep old version; new clients adopt naturally over time</td></tr>
          <tr><td><strong>Bug fix changing relied-upon behavior</strong></td><td>Version it</td><td>Check adoption data; coordinate with known callers first</td></tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <div class="card-title">The Sunset Lifecycle (Step by Step)</div>
      <ol class="text-sm" style="padding-left:20px;line-height:2.2">
        <li><strong>Release v2 alongside v1</strong> — never remove v1 the same day v2 ships</li>
        <li><strong>Add deprecation headers immediately</strong> to every v1 response: <code>Deprecation: true</code>, <code>Sunset: &lt;date&gt;</code>, <code>Link: rel="successor-version"</code></li>
        <li><strong>Publish migration guide</strong> — changelog, developer portal, email; include working code examples</li>
        <li><strong>60-day countdown</strong> — email all clients still calling v1 (find them via version tracking logs)</li>
        <li><strong>30-day countdown</strong> — email again; escalate to account manager for enterprise clients; offer migration office hours</li>
        <li><strong>Sunset date</strong> — return <code>410 Gone</code> with migration link in body; never <code>404</code> (hides the reason)</li>
        <li><strong>Post-sunset monitoring</strong> — watch for clients still hitting the retired endpoint; they need support, not just a 410</li>
      </ol>
      <div class="text-sm text-muted" style="margin-top:8px">
        Minimum: <strong>6 months</strong> for external APIs. <strong>12 months</strong> for enterprise B2B partners. Security/legal exceptions may shorten this — communicate why and offer direct migration support.
      </div>
    </div>

    <div class="card">
      <div class="card-title">Monitor Version Usage Before You Sunset</div>
      <div class="text-sm" style="line-height:1.9;margin-bottom:10px">Never sunset blind. Before removing any version you must know who is still calling it — and that they have migrated or have a confirmed plan.</div>
      <div class="demo-grid">
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:4px">Track per request:</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li>API version called (v1, v2)</li>
            <li>Partner or client identifier</li>
            <li>Timestamp of most recent call</li>
            <li>Endpoint and HTTP method</li>
          </ul>
        </div>
        <div>
          <div class="text-sm" style="font-weight:600;margin-bottom:4px">Dashboard signals:</div>
          <ul class="text-sm text-muted" style="padding-left:18px;line-height:2">
            <li><strong>Active v1 callers</strong> — distinct client IDs in last 30 days</li>
            <li><strong>V1 call share</strong> — v1 % of total traffic; watch for natural decline</li>
            <li><strong>Holdout list</strong> — clients still on v1 within 30 days of Sunset</li>
          </ul>
        </div>
      </div>
      <div class="text-sm text-muted" style="margin-top:8px">
        Zero v1 traffic for 60 consecutive days → high confidence to sunset. Any active partner → contact them before pulling the endpoint.
      </div>
    </div>

    <div class="card">
      <div class="card-title">Version Coexistence Cost</div>
      <div class="text-sm" style="margin-bottom:8px">Every live version multiplies your maintenance surface. Before accepting a new version, understand the full cost:</div>
      <table class="comparison-table">
        <thead><tr><th>Cost</th><th>What It Means in Practice</th></tr></thead>
        <tbody>
          <tr><td><strong>Duplicated business logic</strong></td><td>Bug fixes must be applied to every live version — or accepted as intentional version divergence</td></tr>
          <tr><td><strong>Test multiplication</strong></td><td>N versions = N integration test suites; flakiness and coverage gaps compound</td></tr>
          <tr><td><strong>Documentation debt</strong></td><td>Every doc page must describe behavior across all supported versions</td></tr>
          <tr><td><strong>Infrastructure lock-in</strong></td><td>Old versions may pin old runtimes, frameworks, or services you would otherwise retire</td></tr>
          <tr><td><strong>Security exposure</strong></td><td>Old versions may lack security hardening added later; each version is a separate attack surface</td></tr>
        </tbody>
      </table>
      <div class="text-sm text-muted" style="margin-top:8px">
        <strong>Rule of thumb:</strong> Support at most 2 major versions simultaneously. When v3 ships, v1 must have a firm Sunset date scheduled.
      </div>
    </div>

    <div class="card">
      <div class="card-title">Hard Questions &amp; Answers</div>
      <div class="text-sm" style="line-height:1.9">

        <div style="margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid var(--border)">
          <strong>Q: A bug fix changes behavior that some clients depend on. Is that a breaking change?</strong><br>
          <strong>A:</strong> Yes. If clients rely on the buggy behavior as a feature, it is a behavioral breaking change regardless of intent. Version it — keep the old behavior in v1, ship the fix in v2. Communicate what the correct behavior is and why the old behavior was wrong.
        </div>

        <div style="margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid var(--border)">
          <strong>Q: We must remove a field for GDPR compliance. Must we give the full 6-month notice?</strong><br>
          <strong>A:</strong> No. Legal requirements override the standard window. Communicate immediately, explain the legal obligation, and give the shortest timeline that is technically feasible — typically 30–90 days. Offer direct migration support to all known callers.
        </div>

        <div style="margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid var(--border)">
          <strong>Q: Only 5 partners still use V1. Can we sunset it?</strong><br>
          <strong>A:</strong> Only after individually notifying those 5 partners, getting acknowledgment of the migration deadline, and confirming at least some have already migrated. Never sunset on low traffic alone — "low traffic" may be a nightly batch job that runs once at 3am.
        </div>

        <div style="margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid var(--border)">
          <strong>Q: Should every microservice version independently?</strong><br>
          <strong>A:</strong> Yes. Service A at v2 and service B at v3 is completely normal. An API gateway presents a unified surface externally while services evolve independently. Trying to keep all services on the same version number creates false coupling.
        </div>

        <div style="margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid var(--border)">
          <strong>Q: Can I add required fields in v2 without affecting v1 clients?</strong><br>
          <strong>A:</strong> Yes. V1 and V2 are separate code paths. V2 can have fields — including required ones — that did not exist in V1. V1 clients call <code>/v1/</code> and never encounter the v2 schema.
        </div>

        <div style="margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid var(--border)">
          <strong>Q: A new enum value is non-breaking, right?</strong><br>
          <strong>A:</strong> Not always. Clients using exhaustive switch/match break on unrecognized values. Flag new enum values in release notes and recommend clients implement a safe default branch.
        </div>

        <div>
          <strong>Q: The change breaks 10% of users. Do I have to version it for the other 90%?</strong><br>
          <strong>A:</strong> The percentage is the wrong metric. The question is <em>who</em> the 10% are. B2B partners → version and coordinate individually. Internal services → coordinated deploy, no versioning. Mobile app users → you cannot force an update; version or use feature flags. Unknown public developers → version, because you cannot reach them.
        </div>

      </div>
    </div>`;
}

// ── PAGE: ERRORS ──────────────────────────────────────────────
function errorsPage() {
  return `
    <div class="page-title">⚠️ Error Handling</div>
    <div class="page-sub">RFC 7807 Problem Details — consistent, safe, structured error responses.</div>

    <div class="concept-box">
      Good errors: <strong>correct HTTP status</strong> + <strong>consistent format</strong> + <strong>no internal details leaked</strong>.<br>
      Spring Boot 3 supports <code>ProblemDetail</code> natively — all errors in this app use it.<br>
      <code>{"type":"/errors/not-found","title":"Not Found","status":404,"detail":"..."}</code>
    </div>

    <div class="demo-grid">
      <div class="card">
        <div class="card-title">Trigger Error Scenarios</div>
        <div class="btn-group" style="flex-direction:column;gap:10px">
          <button class="btn btn-secondary" data-action="errorTrigger"
            data-endpoint="/api/errors/400?input=" data-method="GET">
            400 Bad Request — missing required param
          </button>
          <button class="btn btn-secondary" data-action="errorTrigger"
            data-endpoint="/api/errors/404?id=999" data-method="GET">
            404 Not Found — resource doesn't exist
          </button>
          <button class="btn btn-secondary" data-action="errorTrigger"
            data-endpoint="/api/errors/409" data-method="GET">
            409 Conflict — duplicate resource
          </button>
          <button class="btn btn-secondary" data-action="errorTrigger"
            data-endpoint="/api/errors/500" data-method="GET">
            500 Internal Server Error — safe handling
          </button>
          <button class="btn btn-secondary" data-action="errorTrigger"
            data-endpoint="/api/errors/validate" data-method="POST"
            data-body='{}'>
            422 Validation Error — @Valid fails
          </button>
          <button class="btn btn-secondary" data-action="errorTrigger"
            data-endpoint="/api/basic/admin" data-method="GET"
            data-basic-auth="user:password">
            403 Forbidden — authenticated as user, ADMIN required
          </button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">Security: Never Leak Internals</div>
        <div class="alert alert-error text-sm">
          <strong>BAD:</strong> <code>PSQLException: duplicate key violates constraint on table users at jdbc:mysql://prod-db:3306</code><br>
          Reveals DB type, hostname, schema details.
        </div>
        <div class="alert alert-success text-sm">
          <strong>GOOD:</strong> <code>An account with this email already exists.</code><br>
          Client gets actionable info; internals stay server-side in logs.
        </div>
      </div>
    </div>

    <div class="http-exchange">
      <div id="error-request">${requestViewer(null)}</div>
      <div id="error-response">${responseViewer(null)}</div>
    </div>

    <div class="card">
      <div class="card-title">HTTP Status Code Guide</div>
      <table class="comparison-table">
        <thead><tr><th>Code</th><th>Name</th><th>When to use</th></tr></thead>
        <tbody>
          <tr><td><span class="tag tag-green">200</span></td><td>OK</td><td>Successful GET, PUT, PATCH</td></tr>
          <tr><td><span class="tag tag-green">201</span></td><td>Created</td><td>Successful POST — include <code>Location</code> header</td></tr>
          <tr><td><span class="tag tag-green">204</span></td><td>No Content</td><td>Successful DELETE or action with no body</td></tr>
          <tr><td><span class="tag tag-red">400</span></td><td>Bad Request</td><td>Invalid input, missing required fields</td></tr>
          <tr><td><span class="tag tag-red">401</span></td><td>Unauthorized</td><td>Not authenticated (no/invalid credentials)</td></tr>
          <tr><td><span class="tag tag-red">403</span></td><td>Forbidden</td><td>Authenticated but not authorized</td></tr>
          <tr><td><span class="tag tag-red">404</span></td><td>Not Found</td><td>Resource doesn't exist</td></tr>
          <tr><td><span class="tag tag-red">409</span></td><td>Conflict</td><td>Duplicate, version mismatch, state conflict</td></tr>
          <tr><td><span class="tag tag-red">422</span></td><td>Unprocessable Entity</td><td>Validation failed</td></tr>
          <tr><td><span class="tag tag-yellow">429</span></td><td>Too Many Requests</td><td>Rate limit exceeded — include Retry-After</td></tr>
          <tr><td><span class="tag tag-red">500</span></td><td>Internal Server Error</td><td>Server bug — log internally, return generic message</td></tr>
          <tr><td><span class="tag tag-red">503</span></td><td>Service Unavailable</td><td>Circuit open, maintenance — include Retry-After</td></tr>
        </tbody>
      </table>
    </div>`;
}

// ── PAGE: WRITE OPERATIONS ────────────────────────────────────
function writeOpsPage() {
  return `
    <div class="page-title">✏️ Write Operations & Error Design</div>
    <div class="page-sub">POST, PUT, PATCH, DELETE — architectural decisions, failure modes, and what clients should do.</div>

    <div class="concept-box">
      <strong>Core principle:</strong> The HTTP status code IS the error notification — clients must read and act on it.
      Always return the updated resource in the body (200/201) so clients never need a follow-up GET to confirm success.
      The key question is not "did it work?" but "is it safe to retry if I don't know?"
    </div>

    <div class="card">
      <div class="card-title">Method Semantics at a Glance</div>
      <table class="comparison-table">
        <thead>
          <tr><th>Method</th><th>Purpose</th><th>Idempotent?</th><th>Success code</th><th>Response body</th><th>Safe to retry on timeout?</th></tr>
        </thead>
        <tbody>
          <tr>
            <td><span class="tag tag-blue">POST</span></td>
            <td>Create new resource</td>
            <td>No</td>
            <td>201 Created</td>
            <td>Return the created resource</td>
            <td>Only with Idempotency-Key</td>
          </tr>
          <tr>
            <td><span class="tag tag-green">PUT</span></td>
            <td>Full replace (all fields)</td>
            <td>Yes</td>
            <td>200 OK</td>
            <td>Return the updated resource</td>
            <td>Yes — same result every time</td>
          </tr>
          <tr>
            <td><span class="tag tag-green">PATCH</span></td>
            <td>Partial update (only sent fields)</td>
            <td>Usually yes*</td>
            <td>200 OK</td>
            <td>Return the updated resource</td>
            <td>Yes for "set", No for "increment"</td>
          </tr>
          <tr>
            <td><span class="tag tag-red">DELETE</span></td>
            <td>Remove resource</td>
            <td>Yes</td>
            <td>204 No Content</td>
            <td>No body</td>
            <td>Yes — 404 on retry means it already worked</td>
          </tr>
        </tbody>
      </table>
      <div class="text-muted text-sm" style="margin-top:8px">
        * PATCH "set name to X" is idempotent. PATCH "add 10 to stock" is <em>not</em> — retrying doubles the increment.
      </div>
    </div>

    <div class="demo-grid">

      <!-- POST form -->
      <div class="card">
        <div class="card-title">POST — Create</div>
        <div class="alert alert-info text-sm" style="margin-bottom:12px">
          <strong>Not idempotent.</strong> Two identical POSTs create two resources (without an Idempotency-Key).
          On success, the <code>Location</code> header and response body give you the new resource — no extra GET needed.
        </div>
        <div class="form-row">
          <label class="form-label">Name</label>
          <input class="form-input" id="wo-name" placeholder="e.g. Widget Pro" value="Widget Pro">
        </div>
        <div class="form-row">
          <label class="form-label">Price</label>
          <input class="form-input" id="wo-price" placeholder="e.g. 29.99" value="29.99">
        </div>
        <div class="form-row">
          <label class="form-label">Category</label>
          <select class="form-select" id="wo-category">
            <option value="Electronics">Electronics</option>
            <option value="Books">Books</option>
            <option value="Clothing">Clothing</option>
            <option value="Sports">Sports</option>
            <option value="Kitchen">Kitchen</option>
            <option value="Toys">Toys</option>
          </select>
        </div>
        <div class="form-row">
          <label class="form-label">Stock</label>
          <input class="form-input" id="wo-stock" placeholder="e.g. 50" value="50">
        </div>
        <div class="btn-group" style="flex-direction:column;gap:8px;margin-top:12px">
          <button class="btn btn-primary" data-action="crudPost">
            POST valid data → 201 Created + Location
          </button>
          <button class="btn btn-secondary" data-action="crudPostInvalid">
            POST empty body → 422 Validation Failed
          </button>
          <button class="btn btn-secondary" data-action="crudPostDuplicate">
            POST duplicate "iPhone 15" → 409 Conflict
          </button>
        </div>
        <div class="concept-box text-sm" style="margin-top:14px">
          <strong>422</strong> → Fix the request. Do not retry the same body.<br>
          <strong>409</strong> → Already exists (e.g. "iPhone 15" is seeded). GET it, or PUT to update it.<br>
          <strong>201</strong> → ID auto-fills below so you can immediately PUT/PATCH/DELETE it.
        </div>
      </div>

      <!-- PUT / PATCH / DELETE form -->
      <div class="card">
        <div class="card-title">PUT / PATCH / DELETE — Modify</div>
        <div class="alert alert-info text-sm" style="margin-bottom:12px">
          <strong>All idempotent</strong> (for "set" operations). Safe to retry on timeout.
          Response body (200) always contains the current state — no follow-up GET required.
        </div>
        <div class="form-row">
          <label class="form-label">Product ID</label>
          <input class="form-input" id="wo-id" placeholder="ID from POST above" style="font-weight:600">
        </div>
        <div class="concept-box text-sm" style="margin-bottom:10px">
          <strong>PUT requires all fields</strong> (full replace). <strong>PATCH only sends what changes</strong> — leave name/price blank to skip them.
        </div>
        <div class="btn-group" style="flex-direction:column;gap:8px">
          <button class="btn btn-primary" data-action="crudPut">
            PUT (full replace) → 200 OK
          </button>
          <button class="btn btn-secondary" data-action="crudPutMissing">
            PUT to missing ID 99999 → 404 Not Found
          </button>
          <button class="btn btn-primary" data-action="crudPatch">
            PATCH (partial — only name/price) → 200 OK
          </button>
          <button class="btn btn-secondary" data-action="crudDelete" style="background:var(--danger,#e53e3e);color:#fff">
            DELETE → 204 No Content
          </button>
          <button class="btn btn-secondary" data-action="crudDeleteMissing">
            DELETE missing ID 99999 → 404 Not Found
          </button>
        </div>
        <div class="concept-box text-sm" style="margin-top:14px">
          <strong>PUT timeout?</strong> Retry freely — same body = same result.<br>
          <strong>PATCH "set" timeout?</strong> Retry freely.<br>
          <strong>DELETE timeout?</strong> Retry — a 404 on retry means it already worked.<br>
          <strong>After DELETE</strong> the ID field clears; you'll need to POST again.
        </div>
      </div>
    </div>

    <div class="http-exchange">
      <div id="wo-request">${requestViewer(null)}</div>
      <div id="wo-response">${responseViewer(null)}</div>
    </div>

    <!-- POST strategy comparison -->
    <div style="margin-top:32px">
      <div class="card-title" style="font-size:1.1rem;margin-bottom:4px">POST Strategies: Insert vs Upsert vs Merge</div>
      <div class="page-sub" style="margin-bottom:16px">Three different things POST can do — each with different trade-offs.</div>

      <div class="card" style="margin-bottom:16px">
        <div class="card-title">Strategy Comparison</div>
        <table class="comparison-table">
          <thead>
            <tr><th>Strategy</th><th>If resource exists</th><th>If resource missing</th><th>Idempotent?</th><th>409 possible?</th><th>Best for</th></tr>
          </thead>
          <tbody>
            <tr>
              <td><strong>POST + Insert</strong><br><code style="font-size:11px">POST /api/products</code></td>
              <td><span class="tag tag-red">409 Conflict</span> — fail loudly</td>
              <td><span class="tag tag-green">201 Created</span></td>
              <td>No — always needs Idempotency-Key</td>
              <td>Yes</td>
              <td>Strict create-only; audit trail; user-facing forms</td>
            </tr>
            <tr>
              <td><strong>POST + Upsert</strong><br><code style="font-size:11px">POST /api/products/upsert</code></td>
              <td><span class="tag tag-green">200 OK</span> — full replace</td>
              <td><span class="tag tag-green">201 Created</span></td>
              <td>Yes — safe to retry</td>
              <td>No</td>
              <td>Sync jobs; config management; bulk import</td>
            </tr>
            <tr>
              <td><strong>POST + Merge</strong><br><code style="font-size:11px">POST /api/products/merge</code></td>
              <td><span class="tag tag-green">200 OK</span> — patch in place</td>
              <td><span class="tag tag-green">201 Created</span></td>
              <td>Yes (for set ops)</td>
              <td>No</td>
              <td>Event-driven pipelines; partial ownership; incremental sync</td>
            </tr>
          </tbody>
        </table>
        <div class="text-muted text-sm" style="margin-top:8px">
          All three use <strong>name</strong> as the natural key for upsert/merge (no ID needed). Insert uses server-assigned ID.
        </div>
      </div>

      <div class="demo-grid">

        <div class="card">
          <div class="card-title">Upsert Demo</div>
          <div class="concept-box text-sm" style="margin-bottom:12px">
            <strong>Upsert = create or replace.</strong> The client doesn't need to know if the resource already exists.
            Same call, same body, always converges to the same state.
            <br><br>
            <strong>Key trade-off:</strong> silently overwrites. No 409 means no protection against stale clients
            sending outdated values. A client with yesterday's data can overwrite today's changes with no warning.
          </div>
          <div class="btn-group" style="flex-direction:column;gap:8px">
            <button class="btn btn-primary" data-action="strategyUpsertExisting">
              Upsert "iPhone 15" (exists) → 200 full replace
            </button>
            <button class="btn btn-primary" data-action="strategyUpsertNew">
              Upsert new unique name → 201 Created
            </button>
          </div>
          <div class="concept-box text-sm" style="margin-top:12px">
            <strong>Advantages</strong>
            <ul style="margin:6px 0 0 16px;line-height:1.8">
              <li>Idempotent — retry on timeout is always safe</li>
              <li>No client-side existence check needed</li>
              <li>Natural for sync: "make the server match this state"</li>
              <li>Batch-friendly: send 1000 records, server reconciles</li>
            </ul>
            <strong style="margin-top:8px;display:block">Disadvantages</strong>
            <ul style="margin:6px 0 0 16px;line-height:1.8">
              <li>Silently overwrites — stale data can overwrite fresh data</li>
              <li>No 409 signal — bugs (duplicate names) go undetected</li>
              <li>Audit trail blurred — can't distinguish first-create from re-sync</li>
              <li>Race condition: two upserts in-flight, last writer wins silently</li>
            </ul>
          </div>
        </div>

        <div class="card">
          <div class="card-title">Merge Demo</div>
          <div class="concept-box text-sm" style="margin-bottom:12px">
            <strong>Merge = create or patch.</strong> Only the fields you send are applied to an existing record.
            Omitted fields are untouched. Each service can own a different subset of fields.
            <br><br>
            <strong>Key trade-off:</strong> can't explicitly null a field. Omitting price means "leave it alone",
            not "set it to null". If you need to clear a field, you need a full PUT instead.
          </div>
          <div class="btn-group" style="flex-direction:column;gap:8px">
            <button class="btn btn-primary" data-action="strategyMergeExisting">
              Merge price+stock into "iPhone 15" → 200 (name/category unchanged)
            </button>
            <button class="btn btn-primary" data-action="strategyMergeNew">
              Merge new product (all fields) → 201 Created
            </button>
            <button class="btn btn-secondary" data-action="strategyMergeMissingRequired">
              Merge new product, missing price → 422 (create needs more fields)
            </button>
          </div>
          <div class="concept-box text-sm" style="margin-top:12px">
            <strong>Advantages</strong>
            <ul style="margin:6px 0 0 16px;line-height:1.8">
              <li>Services can own different fields without stepping on each other</li>
              <li>Small payloads: only send what changed</li>
              <li>Event-driven: each event is "apply this change"</li>
              <li>No risk of accidentally nulling unrelated fields</li>
            </ul>
            <strong style="margin-top:8px;display:block">Disadvantages</strong>
            <ul style="margin:6px 0 0 16px;line-height:1.8">
              <li>Intentional null is impossible (omit = leave alone)</li>
              <li>Dual-mode validation: required fields differ for create vs update</li>
              <li>Harder to reason about — must load existing state to compute final result</li>
              <li>Field ownership conflicts still possible if two services touch same field</li>
            </ul>
          </div>
        </div>

      </div>

      <div class="http-exchange" style="margin-top:16px">
        <div id="strategy-request">${requestViewer(null)}</div>
        <div id="strategy-response">${responseViewer(null)}</div>
      </div>

      <div class="demo-grid" style="margin-top:16px">

        <div class="card">
          <div class="card-title">When to Choose Insert (strict POST)</div>
          <ul class="text-sm" style="margin:8px 0 0 16px;line-height:1.9">
            <li>User submits a form — they expect to know if a duplicate exists</li>
            <li>You need a clear create event for auditing / event sourcing</li>
            <li>Duplicate names are a real bug that should surface as an error</li>
            <li>The client tracks IDs (server-assigned) and uses them for future calls</li>
            <li>Financial records, orders, invoices — accidental overwrites are dangerous</li>
          </ul>
          <div class="alert alert-error text-sm" style="margin-top:10px">
            <strong>Use Idempotency-Key</strong> to make POST + Insert safe to retry.
            Without it, a network timeout can create duplicates.
          </div>
        </div>

        <div class="card">
          <div class="card-title">When to Choose Upsert</div>
          <ul class="text-sm" style="margin:8px 0 0 16px;line-height:1.9">
            <li>Sync / reconciliation job: "make the server match this snapshot"</li>
            <li>Config management: "ensure this setting has this value"</li>
            <li>Importing from an external system that owns the identity</li>
            <li>Bulk operations: sending 1000 records, can't check each one first</li>
            <li>The client always has the full current state it wants to apply</li>
          </ul>
          <div class="alert alert-info text-sm" style="margin-top:10px">
            Consider adding <strong>a version / last-modified field</strong> so the server can reject
            upserts that are older than the current data (conditional upsert).
          </div>
        </div>

        <div class="card">
          <div class="card-title">When to Choose Merge</div>
          <ul class="text-sm" style="margin:8px 0 0 16px;line-height:1.9">
            <li>Multiple services co-own different fields on the same resource</li>
            <li>Event-driven: each event applies one kind of change ("price updated")</li>
            <li>Partial sync: client only knows some fields, not the whole record</li>
            <li>Mobile / low-bandwidth: only send the delta, not the full resource</li>
            <li>Progressive data collection: fill in fields as they become available</li>
          </ul>
          <div class="alert alert-info text-sm" style="margin-top:10px">
            If you need to <strong>clear a field</strong> (set to null), merge can't do it.
            Use a separate PATCH with an explicit null, or a dedicated "clear" endpoint.
          </div>
        </div>

        <div class="card">
          <div class="card-title">Stale-Write Problem (All Three)</div>
          <p class="text-sm">All write strategies share one vulnerability: a client with stale data can overwrite newer data.</p>
          <table class="comparison-table" style="margin-top:10px">
            <thead><tr><th>Strategy</th><th>Protection</th></tr></thead>
            <tbody>
              <tr><td>Insert</td><td>409 fires if it already exists — but gives no version info</td></tr>
              <tr><td>Upsert</td><td>No protection — last writer wins unconditionally</td></tr>
              <tr><td>Merge</td><td>No protection on shared fields — last writer wins</td></tr>
            </tbody>
          </table>
          <p class="text-sm" style="margin-top:10px"><strong>Solution: optimistic locking.</strong> Add a <code>version</code> field to the entity. Client sends the version it last read; server rejects the write if version has moved on.</p>
          <pre class="concept-box text-sm" style="white-space:pre-wrap;margin-top:8px">POST /api/products/upsert
{ "name": "iPhone 15", "price": 799.99, "version": 3 }

→ If current version is still 3: apply + increment to 4
→ If current version is 4+: 409 Conflict ("version mismatch")</pre>
        </div>

      </div>
    </div>

    <!-- Architectural deep-dive -->
    <div style="margin-top:32px">
      <div class="card-title" style="font-size:1.1rem;margin-bottom:16px">Architectural Decision Guide</div>

      <div class="demo-grid">

        <div class="card">
          <div class="card-title">When POST (Insert) Fails</div>
          <table class="comparison-table">
            <thead><tr><th>Status</th><th>Meaning</th><th>Client should</th></tr></thead>
            <tbody>
              <tr>
                <td><span class="tag tag-red">422</span></td>
                <td>Validation failed — bad input</td>
                <td>Show <code>fieldErrors</code> to user. Fix and resubmit. <strong>Do not retry the same body.</strong></td>
              </tr>
              <tr>
                <td><span class="tag tag-red">409</span></td>
                <td>Already exists (duplicate)</td>
                <td>Decide: GET the existing resource? PUT to update it? Show "already exists" error to user.</td>
              </tr>
              <tr>
                <td><span class="tag tag-red">400</span></td>
                <td>Malformed request</td>
                <td>Fix the request structure (bad JSON, wrong Content-Type). Do not retry.</td>
              </tr>
              <tr>
                <td><span class="tag tag-red">500</span></td>
                <td>Server error (transient)</td>
                <td>Retry with exponential backoff. <strong>Risk:</strong> if the response was lost, the insert may have already succeeded — check for the resource first, or use an Idempotency-Key.</td>
              </tr>
              <tr>
                <td><span class="tag tag-yellow">timeout</span></td>
                <td>Network lost — outcome unknown</td>
                <td>Do <em>not</em> blindly retry. GET first to see if the resource was created. If not, retry with an Idempotency-Key.</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card">
          <div class="card-title">When PUT / PATCH (Update) Fails</div>
          <table class="comparison-table">
            <thead><tr><th>Status</th><th>Meaning</th><th>Client should</th></tr></thead>
            <tbody>
              <tr>
                <td><span class="tag tag-red">404</span></td>
                <td>Resource was deleted</td>
                <td>Decide: re-create via POST? Show "no longer exists" to user? Log and skip?</td>
              </tr>
              <tr>
                <td><span class="tag tag-red">409</span></td>
                <td>Conflict (name taken, optimistic lock mismatch)</td>
                <td>GET the fresh resource, re-apply changes, retry PUT with updated version/name.</td>
              </tr>
              <tr>
                <td><span class="tag tag-red">422</span></td>
                <td>Validation failed</td>
                <td>Show <code>fieldErrors</code>. Fix and resubmit.</td>
              </tr>
              <tr>
                <td><span class="tag tag-yellow">timeout (PUT)</span></td>
                <td>Network lost — outcome unknown</td>
                <td>Retry freely — PUT is idempotent. Same body produces same result.</td>
              </tr>
              <tr>
                <td><span class="tag tag-yellow">timeout (PATCH "set")</span></td>
                <td>Network lost — outcome unknown</td>
                <td>Retry freely — "set" operations are idempotent.</td>
              </tr>
              <tr>
                <td><span class="tag tag-yellow">timeout (PATCH "increment")</span></td>
                <td>Network lost — outcome unknown</td>
                <td>GET first. If the increment already applied, skip. Otherwise retry. Or use Idempotency-Key.</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card">
          <div class="card-title">Idempotency Keys — Safe POST Retries</div>
          <p class="text-sm">POST is the only method that can create duplicates on retry. An <strong>Idempotency-Key</strong> header solves this:</p>
          <pre class="concept-box text-sm" style="white-space:pre-wrap;margin:10px 0">POST /api/products
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{ "name": "Widget Pro", "price": 29.99 }</pre>
          <p class="text-sm">The server:</p>
          <ol class="text-sm" style="margin:8px 0 0 16px;line-height:1.8">
            <li>Checks if this key was seen before</li>
            <li>If yes → return the stored response (no DB write)</li>
            <li>If no → execute normally, store result keyed by the UUID</li>
          </ol>
          <p class="text-sm" style="margin-top:10px">Client generates a UUID per <em>intent</em> (not per request). Retrying the same intent reuses the same key — safe to retry as many times as needed.</p>
          <div class="alert alert-info text-sm" style="margin-top:10px">
            <strong>Rule of thumb:</strong> Generate the UUID before the first attempt. Store it with the pending operation. Reuse it on every retry until you get a definitive response.
          </div>
        </div>

        <div class="card">
          <div class="card-title">Do I Need a GET After PUT/PATCH?</div>
          <div class="alert alert-success text-sm">
            <strong>No.</strong> If your API returns the full resource on 200, the response body IS the current state.
            A follow-up GET is an unnecessary round trip.
          </div>
          <p class="text-sm" style="margin-top:10px">Design rule: always return the updated resource on 200. This gives clients the final state in one call.</p>
          <table class="comparison-table" style="margin-top:12px">
            <thead><tr><th>Pattern</th><th>Round trips</th><th>Verdict</th></tr></thead>
            <tbody>
              <tr>
                <td>PUT → 200 (full body)</td>
                <td>1</td>
                <td><span class="tag tag-green">Best</span></td>
              </tr>
              <tr>
                <td>PUT → 204 (no body) → GET</td>
                <td>2</td>
                <td><span class="tag tag-yellow">Avoid</span></td>
              </tr>
              <tr>
                <td>PUT → 200 (empty {}) → GET</td>
                <td>2</td>
                <td><span class="tag tag-red">Wrong</span></td>
              </tr>
            </tbody>
          </table>
          <p class="text-sm" style="margin-top:12px"><strong>Exception:</strong> If the server applies side effects (auto-computed fields, timestamps, version increments), always return the post-write state so clients see exactly what was stored.</p>
        </div>

      </div>
    </div>`;
}

// ── DOM HELPERS ───────────────────────────────────────────────
function val(id) {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
}
function setHtml(id, html) {
  const el = document.getElementById(id);
  if (el) el.innerHTML = html;
}
function setLoading(btn, loading) {
  if (loading) { btn.classList.add('btn-loading'); btn.disabled = true; }
  else          { btn.classList.remove('btn-loading'); btn.disabled = false; }
}
function flashError(msg) {
  const el = document.createElement('div');
  el.className = 'alert alert-error text-sm';
  el.textContent = msg;
  el.style.cssText = 'position:fixed;top:16px;right:16px;z-index:9999;max-width:300px';
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 3000);
}
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// ── INIT ─────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  const page = location.hash.slice(1) || 'home';
  State.page = page;
  document.querySelectorAll('.nav-item').forEach(el => {
    el.classList.toggle('active', el.dataset.page === page);
  });
  render();
});

// Watch for pagination page renders
const origRender = render;
window.render = function() {
  origRender();
  if (State.page === 'pagination') {
    setTimeout(() => handlers.paginationLoad(), 50);
  }
};
