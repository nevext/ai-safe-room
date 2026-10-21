// cam-control — proxy local entre a interface mobile e a câmera XM/iCSee.
// Fala ONVIF (porta 8899) e RTSP (554) direto com a câmera, sem app e sem nuvem.
// Vídeo ao vivo: por padrão puxa JPEG (ONVIF snapshot) e a interface monta o "ao vivo";
// se houver ffmpeg no PATH, liga também o stream MJPEG contínuo (mais fluido) e gravação.
//
// Rodar:  node server/proxy.mjs
// Sem npm. Usa fetch/crypto nativos do Node 18+.

import http from 'node:http';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT = 8787;
const HERE = path.dirname(fileURLToPath(import.meta.url));
// ffmpeg local (estático) tem prioridade; cai pro PATH se não existir.
const LOCAL_FF = path.join(HERE, 'bin', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg');
const FFMPEG = fs.existsSync(LOCAL_FF) ? LOCAL_FF : 'ffmpeg';
const HAS_FFMPEG = spawnSync(FFMPEG, ['-version']).status === 0;
const REC_DIR = path.join(process.cwd(), 'server', 'gravacoes');

// Perfil da câmera (profiles/*.json): a fonte única do que sabemos dela, lida também pelo app nevhome.
const PROFILE = (() => {
  try { return JSON.parse(fs.readFileSync(path.join(HERE, '..', 'profiles', 'xm-dual-lens-8m.json'), 'utf8')); }
  catch { return {}; }
})();
// Credenciais comuns de câmera XM/iCSee, testadas em ordem se a informada falhar.
const COMMON_CREDS = PROFILE.credentials || [['admin', ''], ['admin', 'admin'], ['admin', '123456'], ['admin', 'tlJwpbo6']];

const cam = { addr: null, user: 'admin', pass: '', onvif: null, profiles: [], info: {}, snapUri: null, streamUri: null, videoSource: 'V_SRC_000' };

// ---------- helpers HTTP ----------
const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type', 'access-control-allow-methods': 'GET,POST,OPTIONS' };
const json = (res, code, obj) => { res.writeHead(code, { 'content-type': 'application/json', ...cors }); res.end(JSON.stringify(obj)); };
const readBody = (req) => new Promise((ok) => { let b = ''; req.on('data', d => b += d); req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch { ok({}); } }); });

// ---------- ONVIF ----------
function wsse(user, pass) {
  const nonce = crypto.randomBytes(16);
  const created = new Date().toISOString();
  const digest = crypto.createHash('sha1').update(Buffer.concat([nonce, Buffer.from(created), Buffer.from(pass)])).digest('base64');
  return `<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
    <UsernameToken><Username>${user}</Username>
    <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${digest}</Password>
    <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${nonce.toString('base64')}</Nonce>
    <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">${created}</Created>
    </UsernameToken></Security>`;
}
async function soap(service, body, user = cam.user, pass = cam.pass) {
  const url = service || `http://${cam.addr}:8899/onvif/device_service`;
  const env = `<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
  xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
  xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
  xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
  xmlns:tt="http://www.onvif.org/ver10/schema">
  <s:Header>${wsse(user, pass)}</s:Header><s:Body>${body}</s:Body></s:Envelope>`;
  const r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/soap+xml; charset=utf-8' }, body: env });
  const text = await r.text();
  return { ok: r.ok, status: r.status, text };
}
const tag = (xml, name) => { const m = xml.match(new RegExp(`<(?:\\w+:)?${name}[^>]*>([\\s\\S]*?)</(?:\\w+:)?${name}>`)); return m ? m[1].trim() : null; };
const tagAll = (xml, name) => [...xml.matchAll(new RegExp(`<(?:\\w+:)?${name}[^>]*>([\\s\\S]*?)</(?:\\w+:)?${name}>`, 'g'))].map(m => m[1]);
const attr = (xml, a) => { const m = xml.match(new RegExp(`${a}="([^"]*)"`)); return m ? m[1] : null; };

async function connect(addr, user, pass) {
  cam.addr = addr;
  const tries = (user != null) ? [[user, pass || ''], ...COMMON_CREDS] : COMMON_CREDS;
  for (const [u, p] of tries) {
    const info = await soap(null, `<tds:GetDeviceInformation/>`, u, p);
    if (info.ok && /Manufacturer/i.test(info.text)) {
      cam.user = u; cam.pass = p;
      cam.info = {
        manufacturer: tag(info.text, 'Manufacturer'), model: tag(info.text, 'Model'),
        firmware: tag(info.text, 'FirmwareVersion'), serial: tag(info.text, 'SerialNumber'),
      };
      await loadProfiles();
      const opt = await maybeOptimize();   // otimiza na conexão; no-op se já estiver bom
      return { ok: true, authUser: u, blankPass: p === '', optimized: opt.applied || false, ...publicState() };
    }
  }
  return { ok: false, error: 'ONVIF recusou o login. Informe usuário/senha do app iCSee.' };
}

const unesc = (s) => s ? s.replace(/&amp;/g, '&').trim() : s;

async function loadProfiles() {
  const pr = await soap(null, `<trt:GetProfiles/>`);
  // O encoder vem embutido em cada <trt:Profiles> — parse direto, sem chamadas extras.
  cam.videoSource = pr.text.match(/<tt:SourceToken>([^<]+)/)?.[1] || cam.videoSource;
  const blocks = [...pr.text.matchAll(/<trt:Profiles[^>]*token="([^"]+)"[\s\S]*?<\/trt:Profiles>/g)];
  cam.profiles = [];
  for (const b of blocks) {
    const s = b[0], token = b[1];
    const su = await soap(null, `<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>${token}</trt:ProfileToken></trt:GetStreamUri>`).catch(() => null);
    cam.profiles.push({
      token,
      name: s.match(/<tt:Name>([^<]+)/)?.[1] || token,
      encToken: s.match(/<tt:VideoEncoderConfiguration token="([^"]+)"/)?.[1] || null,
      encoding: s.match(/<tt:Encoding>([^<]+)/)?.[1] || null,
      width: +(s.match(/<tt:Width>(\d+)/)?.[1] || 0),
      height: +(s.match(/<tt:Height>(\d+)/)?.[1] || 0),
      resolution: `${s.match(/<tt:Width>(\d+)/)?.[1] || '?'}x${s.match(/<tt:Height>(\d+)/)?.[1] || '?'}`,
      fps: s.match(/<tt:FrameRateLimit>(\d+)/)?.[1] || null,
      bitrate: s.match(/<tt:BitrateLimit>(\d+)/)?.[1] || null,
      gop: s.match(/<tt:GovLength>(\d+)/)?.[1] || null,
      rtsp: su ? unesc(tag(su.text, 'Uri')) : null,
    });
  }
  const first = cam.profiles.find(p => p.encoding !== 'JPEG') || cam.profiles[0];
  const sn = await soap(null, `<trt:GetSnapshotUri><trt:ProfileToken>${(cam.profiles.find(p=>p.encoding==='JPEG')||first).token}</trt:ProfileToken></trt:GetSnapshotUri>`).catch(() => null);
  cam.snapUri = sn ? unesc(tag(sn.text, 'Uri')) : null;      // XM: webcapture.jpg com user/password na query
  cam.streamUri = first?.rtsp || null;
}
// XM embute as credenciais no próprio URI; usa verbatim.
const mainProfile = () => cam.profiles.find(p => p.name === 'mainStream') || cam.profiles.find(p => p.encoding !== 'JPEG') || cam.profiles[0];
const subProfile  = () => cam.profiles.find(p => p.name === 'subStream') || cam.profiles.filter(p => p.encoding !== 'JPEG')[1] || mainProfile();
const publicState = () => ({
  ffmpeg: HAS_FFMPEG, info: cam.info, profiles: cam.profiles.map(({ encToken, rtsp, ...p }) => p),
  snapshot: !!cam.snapUri, proto: 'onvif',
});

// ---------- HTTP Digest (para snapshot JPEG) ----------
async function httpGetDigest(uri) {
  let r = await fetch(uri);
  if (r.status === 401) {
    const wa = r.headers.get('www-authenticate') || '';
    const h = Object.fromEntries([...wa.matchAll(/(\w+)="?([^",]+)"?/g)].map(m => [m[1], m[2]]));
    const u = new URL(uri);
    const ha1 = crypto.createHash('md5').update(`${cam.user}:${h.realm}:${cam.pass}`).digest('hex');
    const ha2 = crypto.createHash('md5').update(`GET:${u.pathname}${u.search}`).digest('hex');
    const nc = '00000001', cnonce = crypto.randomBytes(8).toString('hex');
    const resp = crypto.createHash('md5').update(`${ha1}:${h.nonce}:${nc}:${cnonce}:${h.qop || 'auth'}:${ha2}`).digest('hex');
    const auth = `Digest username="${cam.user}", realm="${h.realm}", nonce="${h.nonce}", uri="${u.pathname}${u.search}", qop=${h.qop || 'auth'}, nc=${nc}, cnonce="${cnonce}", response="${resp}"${h.opaque ? `, opaque="${h.opaque}"` : ''}`;
    r = await fetch(uri, { headers: { Authorization: auth } });
  } else if (r.status === 401) {
    const basic = 'Basic ' + Buffer.from(`${cam.user}:${cam.pass}`).toString('base64');
    r = await fetch(uri, { headers: { Authorization: basic } });
  }
  if (!r.ok) throw new Error('snapshot HTTP ' + r.status);
  return Buffer.from(await r.arrayBuffer());
}
async function snapshot() {
  if (cam.snapUri) { try { return await httpGetDigest(cam.snapUri); } catch (e) { /* cai pro ffmpeg */ } }
  if (HAS_FFMPEG && cam.streamUri) return ffmpegFrame(cam.streamUri);
  throw new Error('sem fonte de snapshot');
}
function ffmpegFrame(rtsp) {
  return new Promise((ok, err) => {
    const ff = spawn(FFMPEG, ['-rtsp_transport', 'tcp', '-i', rtsp, '-frames:v', '1', '-f', 'image2', '-']);
    const chunks = []; ff.stdout.on('data', d => chunks.push(d));
    ff.on('close', c => c === 0 ? ok(Buffer.concat(chunks)) : err(new Error('ffmpeg frame ' + c)));
    ff.on('error', err);
  });
}

// ---------- PTZ (ONVIF) ----------
async function ptz(dir, action) {
  const token = mainProfile()?.token; if (!token) return;
  if (action === 'stop' || dir === 'home') {
    await soap(null, `<tptz:Stop><tptz:ProfileToken>${token}</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>`);
    if (dir === 'home') await soap(null, `<tptz:GotoHomePosition><tptz:ProfileToken>${token}</tptz:ProfileToken></tptz:GotoHomePosition>`).catch(() => {});
    return;
  }
  const v = { left: [-0.6, 0, 0], right: [0.6, 0, 0], up: [0, 0.6, 0], down: [0, -0.6, 0], zoom_in: [0, 0, 0.6], zoom_out: [0, 0, -0.6] }[dir];
  if (!v) return;
  await soap(null, `<tptz:ContinuousMove><tptz:ProfileToken>${token}</tptz:ProfileToken><tptz:Velocity><tt:PanTilt x="${v[0]}" y="${v[1]}"/><tt:Zoom x="${v[2]}"/></tptz:Velocity></tptz:ContinuousMove>`);
}

// ---------- Otimização de encoder (FPS) ----------
async function applyEncoder(opts) {
  // opts: { fps, bitrate, gop, stream }
  const prof = opts.stream === 'main' ? mainProfile() : subProfile();
  if (!prof?.encToken || prof.encoding === 'JPEG') return { ok: false, error: 'stream sem encoder configurável' };
  const enc = prof.encoding || 'H264';
  const w = prof.width || 0, h = prof.height || 0;
  const gop = opts.gop || Math.max(10, opts.fps * 2);
  const body = `<trt:SetVideoEncoderConfiguration><trt:Configuration token="${prof.encToken}">
      <tt:Encoding>${enc}</tt:Encoding>
      <tt:Resolution><tt:Width>${w}</tt:Width><tt:Height>${h}</tt:Height></tt:Resolution>
      <tt:Quality>4</tt:Quality>
      <tt:RateControl><tt:FrameRateLimit>${opts.fps}</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>${opts.bitrate}</tt:BitrateLimit></tt:RateControl>
      ${enc === 'H264' ? `<tt:H264><tt:GovLength>${gop}</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>`
                       : `<tt:H265><tt:GovLength>${gop}</tt:GovLength></tt:H265>`}
    </trt:Configuration><trt:ForcePersistence>true</trt:ForcePersistence></trt:SetVideoEncoderConfiguration>`;
  const r = await soap(null, body);
  const fault = !r.ok && (tag(r.text, 'Text') || tag(r.text, 'Reason'));
  if (r.ok) await loadProfiles();
  return { ok: r.ok, status: r.status, stream: opts.stream, applied: { fps: opts.fps, bitrate: opts.bitrate, gop }, fault };
}
// Otimização: sub fluido (25fps) para o ao vivo; principal com bitrate alto p/ parar de picotar.
// Testa o teto de FPS do sensor em passos decrescentes até a câmera aceitar.
async function applyBestFps(stream, candidates, bitrate) {
  for (const fps of candidates) {
    const r = await applyEncoder({ fps, bitrate, stream });
    if (r.ok) return r;
  }
  return { ok: false, stream, error: 'nenhum FPS aceito' };
}
async function autoOptimize() {
  const results = [];
  if (subProfile() && subProfile() !== mainProfile())
    results.push(await applyBestFps('sub', TARGET.sub.fpsCandidates || [25, 20, 15], TARGET.sub.bitrate));   // fluido
  results.push(await applyBestFps('main', TARGET.main.fpsCandidates || [20, 15, 12], TARGET.main.bitrate)); // nítido, sem picotar
  return { ok: results.some(r => r.ok), results, profiles: publicState().profiles };
}
// Otimiza só se ainda não estiver otimizada. Como o ajuste persiste na câmera, depois da
// 1ª vez isto vira no-op — a câmera já nasce (reconecta) otimizada, sem botão.
const TARGET = PROFILE.encoderTargets || { main: { fps: 20, bitrate: 4096 }, sub: { fps: 25, bitrate: 1024 } };
async function maybeOptimize() {
  const m = mainProfile(), s = subProfile();
  const below = (p, t) => p && p.encoding !== 'JPEG' && (+p.fps < t.fps || +p.bitrate < t.bitrate);
  if (below(m, TARGET.main) || (s !== m && below(s, TARGET.sub))) {
    await autoOptimize();
    return { applied: true };
  }
  return { applied: false };
}

// ---------- Imagem (ONVIF Imaging) ----------
async function getImaging() {
  const r = await soap(null, `<timg:GetImagingSettings><timg:VideoSourceToken>${cam.videoSource}</timg:VideoSourceToken></timg:GetImagingSettings>`);
  const g = (t) => r.text.match(new RegExp(`<[^>]*${t}>([^<]+)`))?.[1];
  return {
    brightness: +g('Brightness') || 0, contrast: +g('Contrast') || 0,
    saturation: +g('ColorSaturation') || 0, sharpness: +g('Sharpness') || 0,
    ircut: g('IrCutFilter') || 'AUTO',
    wdr: /WideDynamicRange/.test(r.text) ? (r.text.match(/WideDynamicRange>[\s\S]*?<[^>]*Mode>([^<]+)/)?.[1] || 'OFF') : null,
  };
}
async function setImaging(o) {
  // ordem do schema ONVIF ImagingSettings: Brightness, ColorSaturation, Contrast, IrCutFilter, Sharpness, WDR
  const parts = [];
  if (o.brightness != null) parts.push(`<tt:Brightness>${o.brightness}</tt:Brightness>`);
  if (o.saturation != null) parts.push(`<tt:ColorSaturation>${o.saturation}</tt:ColorSaturation>`);
  if (o.contrast != null) parts.push(`<tt:Contrast>${o.contrast}</tt:Contrast>`);
  if (o.ircut) parts.push(`<tt:IrCutFilter>${o.ircut}</tt:IrCutFilter>`);
  if (o.sharpness != null) parts.push(`<tt:Sharpness>${o.sharpness}</tt:Sharpness>`);
  if (o.wdr) parts.push(`<tt:WideDynamicRange><tt:Mode>${o.wdr}</tt:Mode></tt:WideDynamicRange>`);
  const r = await soap(null, `<timg:SetImagingSettings><timg:VideoSourceToken>${cam.videoSource}</timg:VideoSourceToken><timg:ImagingSettings>${parts.join('')}</timg:ImagingSettings><timg:ForcePersistence>true</timg:ForcePersistence></timg:SetImagingSettings>`);
  return { ok: r.ok, status: r.status };
}

// ---------- Áudio: ouvir o microfone (RTSP -> MP3 via ffmpeg) ----------
function startAudio(res) {
  const rtsp = mainProfile()?.rtsp;
  if (!rtsp || !HAS_FFMPEG) return json(res, 501, { error: 'áudio precisa de ffmpeg' });
  res.writeHead(200, { 'content-type': 'audio/mpeg', 'cache-control': 'no-cache', 'connection': 'close', ...cors });
  const ff = spawn(FFMPEG, ['-rtsp_transport', 'tcp', '-i', rtsp, '-vn', '-acodec', 'libmp3lame', '-b:a', '64k', '-f', 'mp3', '-']);
  ff.stdout.pipe(res); ff.stderr.on('data', () => {});
  const kill = () => { try { ff.kill('SIGKILL'); } catch {} };
  res.on('close', kill); ff.on('error', () => { kill(); try { res.end(); } catch {} });
}

// ---------- Stream MJPEG (só com ffmpeg) ----------
function startStream(res, which) {
  const prof = which === 'main' ? mainProfile() : subProfile();
  const rtsp = prof?.rtsp; if (!rtsp) return json(res, 409, { error: 'sem RTSP' });
  res.writeHead(200, { 'content-type': 'multipart/x-mixed-replace; boundary=ffmpeg', 'cache-control': 'no-cache', 'connection': 'close', ...cors });
  const ff = spawn(FFMPEG, ['-rtsp_transport', 'tcp', '-i', rtsp, '-f', 'mpjpeg', '-q:v', '5', '-boundary_tag', 'ffmpeg', '-']);
  ff.stdout.pipe(res); ff.stderr.on('data', () => {});
  const kill = () => { try { ff.kill('SIGKILL'); } catch {} };
  res.on('close', kill); ff.on('error', () => { kill(); try { res.end(); } catch {} });
}

// ---------- Gravação local (ffmpeg, -c copy = sem recomprimir, tira da nuvem) ----------
let rec = null;
function toggleRecord(on) {
  if (on && !rec && HAS_FFMPEG && cam.streamUri) {
    fs.mkdirSync(REC_DIR, { recursive: true });
    const file = path.join(REC_DIR, `cam-${Date.now()}.mp4`);
    rec = spawn(FFMPEG, ['-rtsp_transport', 'tcp', '-i', cam.streamUri, '-c', 'copy', file]);
    rec.file = file; rec.on('close', () => { rec = null; });
    return { ok: true, file };
  }
  if (!on && rec) { try { rec.stdin.write('q'); } catch {} try { rec.kill('SIGINT'); } catch {} const f = rec.file; rec = null; return { ok: true, file: f }; }
  return { ok: false, error: HAS_FFMPEG ? 'nada a fazer' : 'gravação precisa de ffmpeg' };
}

// ---------- servidor ----------
const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  if (req.method === 'OPTIONS') { res.writeHead(204, cors); return res.end(); }
  try {
    switch (u.pathname) {
      case '/api/connect': { const b = await readBody(req); return json(res, 200, await connect(b.addr, b.user, b.pass)); }
      case '/api/state':   return json(res, 200, publicState());
      case '/api/stream':  if (!HAS_FFMPEG) return json(res, 501, { error: 'sem ffmpeg; use snapshot' }); return startStream(res, u.searchParams.get('stream') || 'sub');
      case '/api/snapshot': { const buf = await snapshot(); res.writeHead(200, { 'content-type': 'image/jpeg', ...cors }); return res.end(buf); }
      case '/api/ptz':     { const b = await readBody(req); await ptz(b.dir, b.action); return json(res, 200, { ok: true }); }
      case '/api/encoder': { const b = await readBody(req); return json(res, 200, await applyEncoder(b)); }
      case '/api/optimize': return json(res, 200, await autoOptimize());
      case '/api/record':  { const b = await readBody(req); return json(res, 200, toggleRecord(b.on)); }
      case '/api/imaging':
        if (req.method === 'GET') return json(res, 200, await getImaging());
        { const b = await readBody(req); return json(res, 200, await setImaging(b)); }
      case '/api/daynight': { const b = await readBody(req); return json(res, 200, await setImaging({ ircut: b.mode })); }
      case '/api/audio':   return startAudio(res);
      default: return serveStatic(u.pathname, res);   // UI na mesma porta
    }
  } catch (e) { return json(res, 500, { error: String(e.message || e) }); }
});

// Serve a interface (index.html + assets) da própria porta do proxy.
const UI_ROOT = path.join(HERE, '..');
const MIME = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.jpg': 'image/jpeg', '.png': 'image/png', '.json': 'application/json', '.svg': 'image/svg+xml' };
function serveStatic(pathname, res) {
  let rel = decodeURIComponent(pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  const file = path.normalize(path.join(UI_ROOT, rel));
  if (!file.startsWith(UI_ROOT)) return json(res, 403, { error: 'fora da pasta' });     // anti path traversal
  fs.readFile(file, (e, data) => {
    if (e) return json(res, 404, { error: 'não encontrado' });
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', ...cors });
    res.end(data);
  });
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`cam-control no PC:     http://127.0.0.1:${PORT}`);
  console.log(`cam-control no celular: http://192.168.0.33:${PORT}   (mesma rede Wi-Fi)`);
  console.log(`ffmpeg: ${HAS_FFMPEG ? 'sim' : 'não'}`);
});
