// Google Drive: as fotos das atividades e os clipes vão também para o Drive da pessoa (pasta "AI Safe Room").
//
// Login sem navegador no computador: o "login com código" do Google (OAuth para TVs e aparelhos sem teclado). O
// camera-ai pede um código, o celular mostra "abra google.com/device e digite ABCD-EFGH", a pessoa entra com a conta
// dela no próprio celular. O escopo é drive.file: o app só vê os arquivos que ele mesmo criou, nada mais do Drive.
//
// O cliente OAuth (client_id e client_secret do projeto no Google Cloud) e o refresh token ficam em server/drive.json,
// fora do git. Sem o cliente, o Drive aparece como "falta configurar".
import fs from 'node:fs';
import path from 'node:path';

const SCOPE = 'https://www.googleapis.com/auth/drive.file';
const KEEP_DAYS = 30;   // no Drive também: 30 dias, como no computador

export function createDrive({ file, sources, log = () => {} }) {
  let cfg = (() => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return {}; } })();
  cfg.uploaded ??= {};      // caminho local → id no Drive
  let pending = null;       // o login com código em andamento: { userCode, url, until }
  let access = null;        // { token, until }
  let lastError = null, busy = false;
  const save = () => { try { fs.writeFileSync(file, JSON.stringify(cfg, null, 1)); } catch {} };

  const configured = () => !!(cfg.clientId && cfg.clientSecret);
  const connected = () => !!cfg.refreshToken;

  async function form(url, body) {
    const r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(body) });
    return { status: r.status, body: await r.json().catch(() => ({})) };
  }

  /** Começa o login com código: devolve o código e o endereço para a pessoa abrir no celular. */
  async function connect() {
    if (!configured()) return { ok: false, error: 'falta configurar o projeto do Google (client_id e client_secret em server/drive.json)' };
    const r = await form('https://oauth2.googleapis.com/device/code', { client_id: cfg.clientId, scope: SCOPE });
    if (r.status !== 200) return { ok: false, error: r.body.error_description || r.body.error || `o Google respondeu ${r.status}` };
    const { device_code, user_code, verification_url, expires_in, interval } = r.body;
    pending = { userCode: user_code, url: verification_url, until: Date.now() + expires_in * 1000 };
    lastError = null;
    // Espera a pessoa entrar, perguntando no ritmo que o Google pede
    (async () => {
      let wait = (interval || 5) * 1000;
      while (pending && Date.now() < pending.until) {
        await new Promise(ok => setTimeout(ok, wait));
        const t = await form('https://oauth2.googleapis.com/token', { client_id: cfg.clientId, client_secret: cfg.clientSecret,
          device_code, grant_type: 'urn:ietf:params:oauth:grant-type:device_code' }).catch(e => ({ status: 0, body: { error: e.message } }));
        if (t.status === 200 && t.body.refresh_token) {
          cfg.refreshToken = t.body.refresh_token; cfg.enabled = true;
          access = { token: t.body.access_token, until: Date.now() + (t.body.expires_in - 60) * 1000 };
          pending = null; lastError = null;
          cfg.email = await whoAmI().catch(() => null);
          save();
          log(`Google Drive conectado${cfg.email ? ' (' + cfg.email + ')' : ''}`);
          sync();
          return;
        }
        if (t.body.error === 'slow_down') wait += 5000;
        else if (t.body.error !== 'authorization_pending') { lastError = t.body.error === 'access_denied' ? 'a conta não permitiu' : t.body.error_description || t.body.error; pending = null; return; }
      }
      if (pending) { pending = null; lastError = 'o código venceu; peça outro'; }
    })();
    return { ok: true, userCode: user_code, url: verification_url };
  }

  async function token() {
    if (access && Date.now() < access.until) return access.token;
    const t = await form('https://oauth2.googleapis.com/token', { client_id: cfg.clientId, client_secret: cfg.clientSecret,
      refresh_token: cfg.refreshToken, grant_type: 'refresh_token' });
    if (t.status !== 200) {
      // Tiraram o acesso pelo Google (ou o token venceu): volta para "conectar"
      if (t.body.error === 'invalid_grant') { delete cfg.refreshToken; save(); }
      throw new Error(t.body.error_description || t.body.error || `o Google respondeu ${t.status}`);
    }
    access = { token: t.body.access_token, until: Date.now() + (t.body.expires_in - 60) * 1000 };
    return access.token;
  }

  async function api(method, url, body, headers = {}) {
    const r = await fetch(url, { method, headers: { authorization: `Bearer ${await token()}`, ...headers }, body });
    if (r.status === 204) return {};
    const j = await r.json().catch(() => ({}));
    if (r.status >= 400) throw new Error(j.error?.message || `o Drive respondeu ${r.status}`);
    return j;
  }
  const whoAmI = async () => (await api('GET', 'https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)')).user?.emailAddress;

  // As pastas: AI Safe Room / <câmera> / <dia>. Guardadas por caminho para não perguntar de novo.
  cfg.folders ??= {};
  async function folder(names) {
    let parent = 'root', key = '';
    for (const name of names) {
      key += '/' + name;
      if (!cfg.folders[key]) {
        const q = `name='${name.replace(/'/g, "\\'")}' and mimeType='application/vnd.google-apps.folder' and '${parent}' in parents and trashed=false`;
        const found = (await api('GET', `https://www.googleapis.com/drive/v3/files?fields=files(id)&q=${encodeURIComponent(q)}`)).files?.[0];
        cfg.folders[key] = found?.id || (await api('POST', 'https://www.googleapis.com/drive/v3/files?fields=id',
          JSON.stringify({ name, mimeType: 'application/vnd.google-apps.folder', parents: [parent] }), { 'content-type': 'application/json' })).id;
        save();
      }
      parent = cfg.folders[key];
    }
    return parent;
  }

  async function upload(local, names, name, mime) {
    const parent = await folder(names);
    const boundary = 'aisaferoom' + Date.now();
    const meta = JSON.stringify({ name, parents: [parent] });
    const body = Buffer.concat([
      Buffer.from(`--${boundary}\r\ncontent-type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--${boundary}\r\ncontent-type: ${mime}\r\n\r\n`),
      fs.readFileSync(local), Buffer.from(`\r\n--${boundary}--`)]);
    return (await api('POST', 'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id', body,
      { 'content-type': `multipart/related; boundary=${boundary}` })).id;
  }

  /** Manda o que falta (sources() diz o que existe: { path, folders, name, mime, at }) e apaga do Drive o que passou de 30 dias. */
  async function sync() {
    if (busy || !cfg.enabled || !connected()) return;
    busy = true;
    try {
      for (const f of sources()) {
        if (cfg.uploaded[f.path] || Date.now() - f.at > KEEP_DAYS * 864e5 || !fs.existsSync(f.path)) continue;
        cfg.uploaded[f.path] = { id: await upload(f.path, f.folders, f.name, f.mime), at: f.at };
        save();
      }
      for (const [local, u] of Object.entries(cfg.uploaded)) {
        if (Date.now() - (u.at || 0) <= KEEP_DAYS * 864e5) continue;
        await api('DELETE', `https://www.googleapis.com/drive/v3/files/${u.id}`).catch(() => {});
        delete cfg.uploaded[local]; save();
      }
      lastError = null;
    } catch (e) {
      lastError = e.message;
      log(`Google Drive: ${e.message}`);
    } finally { busy = false; }
  }
  setInterval(sync, 60000).unref();
  setTimeout(sync, 10000).unref();

  function info() {
    return { ok: true, configured: configured(), connected: connected(), enabled: !!cfg.enabled, email: cfg.email || null,
      pending: pending && Date.now() < pending.until ? { userCode: pending.userCode, url: pending.url } : null,
      uploaded: Object.keys(cfg.uploaded).length, keepDays: KEEP_DAYS, error: lastError, busy };
  }
  async function set(b) {
    if (b.connect) { const r = await connect(); return { ...info(), ...r }; }
    if (b.disconnect) {
      if (cfg.refreshToken) await form('https://oauth2.googleapis.com/revoke', { token: cfg.refreshToken }).catch(() => {});
      delete cfg.refreshToken; delete cfg.email; cfg.enabled = false; access = null; pending = null; save();
      log('Google Drive desconectado (o que já foi enviado continua no Drive)');
    }
    if (b.enabled != null) { cfg.enabled = !!b.enabled; save(); if (cfg.enabled) sync(); }
    return info();
  }
  return { info, set, sync };
}
