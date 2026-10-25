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
import dgram from 'node:dgram';
import os from 'node:os';
import { loadModel, analyzer } from './ai.mjs';
import { createLive, keyframeAt } from './live.mjs';
import { dvrip, xmHash } from './dvrip.mjs';
import { createMeter, createLevel, grade, tips, LEVELS } from './quality.mjs';
import { watchMotion } from './motion.mjs';
import { createClips } from './clips.mjs';
import { createProfiles } from './perfil.mjs';

const PORT = 8787;
const HERE = path.dirname(fileURLToPath(import.meta.url));
// ffmpeg local (estático) tem prioridade; cai pro PATH se não existir.
const LOCAL_FF = path.join(HERE, 'bin', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg');
const FFMPEG = fs.existsSync(LOCAL_FF) ? LOCAL_FF : 'ffmpeg';
const HAS_FFMPEG = spawnSync(FFMPEG, ['-version']).status === 0;

// Ao vivo com o menor atraso: sem o buffer de entrada do ffmpeg e sem esperar segundos analisando o
// stream antes de começar (o padrão junta ~5 s).
const LIVE_IN = ['-fflags', 'nobuffer', '-flags', 'low_delay', '-probesize', '500000', '-analyzeduration', '500000', '-rtsp_transport', 'tcp'];

// H.264 para as telas: na placa de vídeo se der (NVIDIA, Intel, AMD), senão no processador.
// Cada encoder testado com um quadro de mentira; o primeiro que funcionar fica.
const H264 = (() => {
  if (!HAS_FFMPEG) return null;
  const tries = [
    ['h264_nvenc', '-preset', 'p4', '-tune', 'll', '-zerolatency', '1', '-rc', 'cbr'],   // p4/ll: mais qualidade, ainda sem atraso perceptível
    ['h264_qsv', '-preset', 'veryfast', '-low_power', '1'],
    ['h264_amf', '-usage', 'ultralowlatency', '-quality', 'speed'],
    ['libx264', '-preset', 'ultrafast', '-tune', 'zerolatency'],
  ];
  for (const [name, ...opts] of tries) {
    const r = spawnSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=black:s=640x360', '-frames:v', '1',
      '-c:v', name, ...opts, '-f', 'null', '-'], { timeout: 15000 });
    if (r.status === 0) return { name, opts };
  }
  return null;
})();

// Manda só o quadro JPEG mais novo: se a tela não deu conta do anterior, os do meio são pulados.
// Sem isso os quadros fazem fila e o atraso só cresce (medido: 6 s em 25 s numa tela a 8 fps).
function sendLatestJpeg(source, res) {
  let buf = Buffer.alloc(0), latest = null, busy = false;
  const send = () => {
    if (busy || !latest || res.destroyed) return;
    const frame = latest;
    latest = null;
    busy = true;
    res.write(`--ffmpeg\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.length}\r\n\r\n`);
    res.write(frame);
    res.write('\r\n', () => { busy = false; send(); });
  };
  source.on('data', d => {
    buf = buf.length ? Buffer.concat([buf, d]) : d;
    for (;;) {
      const head = buf.indexOf('\r\n\r\n');
      if (head < 0) break;
      const m = /content-length:\s*(\d+)/i.exec(buf.subarray(0, head).toString('latin1'));
      if (!m) { buf = buf.subarray(head + 4); continue; }
      const end = head + 4 + +m[1];
      if (buf.length < end) break;
      latest = Buffer.from(buf.subarray(head + 4, end));
      buf = buf.subarray(end);
    }
    send();
  });
}
const REC_DIR = path.join(process.cwd(), 'server', 'gravacoes');

// Perfil da câmera (profiles/*.json): a fonte única do que sabemos dela, lida também pelo app nevhome.
const PROFILE = (() => {
  try { return JSON.parse(fs.readFileSync(path.join(HERE, '..', 'profiles', 'xm-dual-lens-8m.json'), 'utf8')); }
  catch { return {}; }
})();
// Credenciais comuns de câmera XM/iCSee, testadas em ordem se a informada falhar.
const COMMON_CREDS = PROFILE.credentials || [['admin', ''], ['admin', 'admin'], ['admin', '123456'], ['admin', 'tlJwpbo6']];

const cam = { addr: null, user: 'admin', pass: '', onvif: null, dvr: null, ptzDir: null, profiles: [], info: {}, snapUri: null, streamUri: null, videoSource: 'V_SRC_000' };
const follow = { wanted: (() => { try { return !!JSON.parse(fs.readFileSync(path.join(HERE, 'state.json'), 'utf8')).follow; } catch { return false; } })(),
  enabled: false, lastMove: 0, lostAt: 0, returned: false, hitFrames: 0, timer: null };

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
      cam.dvr?.close(); cam.dvr = await dvrip(addr, u, p).catch(e => { console.log('DVRIP indisponível:', e.message); return null; });
      cam.info = {
        manufacturer: tag(info.text, 'Manufacturer'), model: tag(info.text, 'Model'),
        firmware: tag(info.text, 'FirmwareVersion'), serial: tag(info.text, 'SerialNumber'),
      };
      await loadProfiles();
      await openProfile();
      if (activity.at(-1)?.type !== 'online') logActivity('online', 'Câmera online');
      refreshCameraThumb();   // a foto das listas já fica pronta
      saveState();
      const opt = await maybeOptimize();   // otimiza na conexão; no-op se já estiver bom
      return { ok: true, authUser: u, blankPass: p === '', optimized: opt.applied || false, ...publicState() };
    }
  }
  return { ok: false, error: 'ONVIF recusou o login. Informe usuário/senha do app iCSee.' };
}

// Troca a senha da própria câmera (DVRIP ModifyPassword, msg 1488) e reconecta com ela. Sem senha, qualquer um na
// rede entra na câmera e lê até a senha do Wi-Fi (NetWork.Wifi). O app iCSee e as TVs que falam direto com a
// câmera pedem a senha nova; as TVs pelo camera-ai não percebem nada.
async function setCameraPassword(password) {
  password = String(password ?? '');
  if (password.length < 6 || password.length > 15) return { ok: false, error: 'a senha da câmera precisa ter de 6 a 15 caracteres' };
  const old = cam.pass, addr = cam.addr, user = cam.user;
  const change = async (from, to) => {
    const r = await (await dvrSession()).request(1488, { EncryptType: 'MD5', UserName: user, PassWord: xmHash(from), NewPassWord: xmHash(to) });
    if (Number(r.Ret) !== 100) throw new Error('a câmera recusou (' + (r.Ret ?? 'sem código') + ')');
  };
  try { await change(old, password); } catch (e) { return { ok: false, error: e.message }; }
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Senha da câmera trocada; reconectando`);
  const r = await connect(addr, user, password);
  if (r.ok && cam.pass === password) { live?.restart(); return { ok: true }; }
  // Não entrou com a nova: volta a antiga, para não perder a câmera
  try { await change(password, old); await connect(addr, user, old); } catch {}
  return { ok: false, error: 'a câmera trocou a senha mas não aceitou a nova pelo ONVIF; a antiga foi posta de volta' };
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
// Sessão DVRIP viva: se a câmera fechou a anterior, loga de novo (uma tentativa por vez).
let dvrLogin = null;
async function dvrSession() {
  if (!cam.dvr) throw new Error('Controle pelo DVRIP indisponível');
  if (!cam.dvr.closed) return cam.dvr;
  dvrLogin ??= dvrip(cam.addr, cam.user, cam.pass)
    .then(d => { console.log(`${new Date().toLocaleTimeString('pt-BR')} DVRIP: sessão refeita`); return cam.dvr = d; })
    .finally(() => { dvrLogin = null; });
  return dvrLogin;
}
async function dvrPtz(command, preset, step = 5) {
  const payload = { Name: 'OPPTZControl', OPPTZControl: {
    Channel: 0, Command: command, Parameter: {
      AUX: { Number: 0, Status: 'On' }, Channel: 0, MenuOpts: 'Enter', Pattern: 'Start',
      Preset: preset, Step: step, Tour: 0,
    },
  } };
  let reply;
  try { reply = await (await dvrSession()).request(1400, payload); }
  catch { reply = await (await dvrSession()).request(1400, payload); }   // a sessão caiu no meio: mais uma vez

  if (![100, 150, 515].includes(Number(reply.Ret))) throw new Error('Comando PTZ recusado (' + (reply.Ret ?? 'sem código') + ')');
  return reply;
}
async function ptz(dir, action, step = 5) {
  if (cam.dvr) {
    if (dir === 'home') {
      if (cam.ptzDir) await dvrPtz(cam.ptzDir, -1).catch(() => {});
      cam.ptzDir = null;
      return dvrPtz('GotoPreset', 1);
    }
    // Onde a lente móvel está agora vira o preset 1: para onde ela volta quando não tem ninguém
    if (dir === 'sethome') {
      if (cam.ptzDir) await dvrPtz(cam.ptzDir, -1).catch(() => {});
      cam.ptzDir = null;
      console.log(`${new Date().toLocaleTimeString('pt-BR')} Preset 1 salvo onde a lente móvel está agora`);
      return dvrPtz('SetPreset', 1);
    }
    if (action === 'stop') {
      const command = cam.ptzDir;
      cam.ptzDir = null;
      if (command) await dvrPtz(command, -1);
      return;
    }
    const command = { left: 'DirectionRight', right: 'DirectionLeft', up: 'DirectionUp', down: 'DirectionDown' }[dir];
    if (command) { cam.ptzDir = command; return dvrPtz(command, 65535, step); }
  }
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
  if (live) return mjpegFromLive(res, 'whole', 1920, 1080);   // sem abrir outra conexão com a câmera
  const prof = which === 'main' ? mainProfile() : subProfile();
  const rtsp = prof?.rtsp; if (!rtsp) return json(res, 409, { error: 'sem RTSP' });
  res.writeHead(200, { 'content-type': 'multipart/x-mixed-replace; boundary=ffmpeg', 'cache-control': 'no-cache', 'connection': 'close', ...cors });
  const ff = spawn(FFMPEG, [...LIVE_IN, '-i', rtsp, '-an', '-f', 'mpjpeg', '-q:v', '5', '-boundary_tag', 'ffmpeg', '-']);
  sendLatestJpeg(ff.stdout, res); ff.stderr.on('data', () => {});
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

// ---------- Telas (TV nevtv, celular, navegador): a câmera com tudo o que o camera-ai sabe fazer ----------
// Toda tela pergunta /api/screen e monta o que vier: controles novos aqui aparecem em todas as TVs
// sem atualizar o nevtv. A IA roda uma vez só, aqui, e /api/screen/events manda o resultado para todas.
// Sem o camera-ai, o nevtv fala direto com a câmera e fica com o que consegue sozinho (sem IA).

const SCREEN_VERSION = 1;
const MODEL_DIR = path.join(HERE, 'models');
const ai = { status: 'loading', model: null, stop: null, last: null, listeners: new Set(), idle: null };

// A conexão única com a câmera (server/live.mjs): telas e IA saem todas dela.
const meter = createMeter();
const live = HAS_FFMPEG && H264 ? createLive({
  ffmpeg: FFMPEG, inputArgs: LIVE_IN, rtsp: () => (videoPick.using = pickedStream()) === 'sub' ? subProfile()?.rtsp : mainProfile()?.rtsp, encoder: H264, meter,
  views: PROFILE.lenses?.layout === 'stacked-vertical' ? ['whole', 'top', 'bottom'] : ['whole'],
  onViewers: () => viewersChanged(),
  aiFps: 10,   // a IA fica só com o quadro mais novo: num detector lento, ela mesma analisa menos
}) : null;

// O MJPEG (navegador, TVs sem H.264) também sai da conexão única: o ffmpeg lê o H.264 da vista aqui
// mesmo e manda JPEG a JPEG, sempre o mais novo.
function mjpegFromLive(res, view, width, height) {
  res.writeHead(200, { 'content-type': 'multipart/x-mixed-replace; boundary=ffmpeg', 'cache-control': 'no-cache', 'connection': 'close', ...cors });
  const src = `http://127.0.0.1:${PORT}/api/screen/video?format=ts&view=${live.views.includes(view) ? view : 'whole'}`;
  const ff = spawn(FFMPEG, ['-loglevel', 'error', '-fflags', 'nobuffer', '-flags', 'low_delay', '-probesize', '200000',
    '-analyzeduration', '200000', '-f', 'mpegts', '-i', src,
    '-vf', `scale=${width}:${height}:force_original_aspect_ratio=decrease:force_divisible_by=2`, '-r', '15',
    '-f', 'mpjpeg', '-q:v', '5', '-boundary_tag', 'ffmpeg', '-']);
  sendLatestJpeg(ff.stdout, res); ff.stderr.on('data', () => {});
  const kill = () => { try { ff.kill('SIGKILL'); } catch {} };
  res.on('close', kill); ff.on('close', () => { try { res.end(); } catch {} }); ff.on('error', () => { kill(); try { res.end(); } catch {} });
}

async function loadAi() {
  try {
    ai.model = await loadModel(MODEL_DIR);
    ai.status = ai.model ? 'on' : 'unavailable';
  } catch (e) {
    ai.status = 'unavailable';
    console.log('IA indisponível:', e.message);
  }
  console.log('IA: ' + (ai.status === 'on' ? `ligada (${ai.model.name}, ${ai.model.device === 'gpu' ? 'placa de vídeo' : 'processador'})` : 'sem IA (rode npm install)'));
  if (ai.listeners.size) startAi();
  resumeFollow();
}

// Analisa só enquanto alguma tela está assistindo.
function updateFollow(result) {
  if (!follow.enabled || !cam.dvr) return;
  const people = (result.items || []).filter(it => it.kind === 'person' && it.score >= 0.4);
  const bottom = people.filter(it => it.box[1] + it.box[3] / 2 >= 0.5).sort((a, b) => b.score - a.score)[0];
  const now = Date.now();

  // A detecção na metade inferior vem da lente que gira: use-a como feedback fechado. A lente fixa pode
  // ver a pessoa deslocada mesmo quando a móvel já está apontada corretamente.
  if (bottom) {
    follow.hitFrames++;
    follow.lostAt = 0; follow.returned = false;
    const centerX = bottom.box[0] + bottom.box[2] / 2;
    const centerY = (bottom.box[1] + bottom.box[3] / 2 - 0.5) * 2;
    const dir = centerX < 0.38 ? 'left' : centerX > 0.62 ? 'right'
      : centerY < 0.34 ? 'up' : centerY > 0.66 ? 'down' : null;
    if (!dir || follow.hitFrames < 3 || now - follow.lastMove < 1200) return;
    followPulse(dir, `lente móvel x=${centerX.toFixed(2)} y=${centerY.toFixed(2)}`);
    return;
  }

  follow.hitFrames = 0;
  if (!follow.lostAt) follow.lostAt = now;
  // Só a lente móvel guia o motor. Procurar pela lente fixa não funciona: onde a pessoa está na fixa não diz para
  // onde virar a móvel (depende de onde o motor está), e os pulsos tiravam a móvel da vista do quarto (medido,
  // 25/10). Sem pessoa na móvel, ela volta ao preset 1, que vê o quarto todo, e espera alguém aparecer ali.
  if (!follow.returned && now - follow.lostAt >= 12000 && now - follow.lastMove >= 1000) {
    follow.returned = true; follow.lastMove = now;
    console.log(`${new Date().toLocaleTimeString('pt-BR')} Seguir: 12 s sem pessoa, voltando ao preset 1`);
    ptz('home', 'start').catch(e => console.log('Rastreamento: retorno ao preset falhou:', e.message));
  }
}
// Um pulso curto (~1/4 da imagem). Registrado no log para dar para conferir depois como o seguir se comportou.
function followPulse(dir, why) {
  follow.lastMove = Date.now();
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Seguir: ${dir} (${why})`);
  ptz(dir, 'start', 3).catch(e => console.log('Rastreamento PTZ:', e.message));
  clearTimeout(follow.timer);
  follow.timer = setTimeout(() => ptz(dir, 'stop', 3).catch(() => {}), 450);
}
function resumeFollow() {
  if (privacy.on) return;
  if (follow.wanted && !follow.enabled && setFollow(true).enabled) console.log('Seguir pessoa: ligado (estava ligado antes)');
  if (clipsOn.wanted) startAi();   // os clipes também precisam da IA olhando
}
function setFollow(enabled) {
  if (follow.wanted !== !!enabled) { follow.wanted = !!enabled; saveState(); }   // lembrado: volta ligado se o camera-ai reiniciar
  follow.enabled = !!enabled && !!cam.dvr && !!ai.model && !!live;
  follow.lastMove = 0; follow.lostAt = 0; follow.returned = false; follow.hitFrames = 0;
  clearTimeout(follow.timer); follow.timer = null;
  if (!follow.enabled && cam.ptzDir) ptz('left', 'stop').catch(() => {});
  if (follow.enabled) startAi(); else stopAiSoon();
  return { enabled: follow.enabled, available: !!cam.dvr && !!ai.model && !!live, reason: !cam.dvr ? 'DVRIP indisponível' : !ai.model ? 'IA indisponível' : !live ? 'Análise de vídeo indisponível' : null };
}
function startAi() {
  clearTimeout(ai.idle);
  if (ai.stop || !ai.model || !live || directWatch.on || privacy.on) return;
  const analyze = analyzer({ model: ai.model, onResult: r => {
    r = ignoreZones(r);
    ai.last = r; updateFollow(r); if (clipsOn.wanted) clips?.seen(r); broadcast({ type: 'detections', ...r });
    arrivals(r);
  } });
  // O quadro que a IA olha fica guardado: a miniatura de uma pessoa sai dele, do mesmo instante (o snapshot da
  // câmera demora ~7 s e já mostrava outra coisa)
  ai.stop = live.onAiFrame((frame, w, h) => { lastAiFrame = { frame, w, h, at: Date.now() }; analyze(frame, w, h); });
}
// Uma TV assistindo direto da câmera: esta câmera só aguenta uma conexão de vídeo (com a segunda, a TV caiu para
// 5 quadros/s e o camera-ai travou; medido em 25/10). Então o camera-ai solta a câmera enquanto a TV avisa (a cada
// 20 s) e volta ao seguir quando ela para. Não pede senha: o máximo que alguém faz com isso é pausar o seguir.
const directWatch = { until: 0, on: false };
function setDirectWatch(watching) {
  directWatch.until = watching ? Date.now() + 45000 : 0;
  checkDirectWatch();
  return { ok: true };
}
function checkDirectWatch() {
  const on = Date.now() < directWatch.until;
  if (on === directWatch.on) return;
  directWatch.on = on;
  if (on) {
    if (ai.stop) { ai.stop(); ai.stop = null; }
    live?.stopNow();
    console.log(`${new Date().toLocaleTimeString('pt-BR')} Uma TV está vendo direto da câmera: camera-ai soltou a câmera (seguir pausado)`);
  } else {
    console.log(`${new Date().toLocaleTimeString('pt-BR')} A TV parou de ver direto: camera-ai de volta à câmera`);
    if (follow.enabled || clipsOn.wanted || ai.listeners.size) startAi();
  }
}
setInterval(checkDirectWatch, 3000);

function stopAiSoon() {
  clearTimeout(ai.idle);
  ai.idle = setTimeout(() => { if (!ai.listeners.size && !follow.enabled && !clipsOn.wanted && ai.stop) { ai.stop(); ai.stop = null; } }, 10000);
}
function broadcast(event) {
  const line = JSON.stringify(event) + '\n';
  for (const res of ai.listeners) res.write(line);
}

// ---------- Clipes de quando aparece alguém (server/clips.mjs) ----------
// Desligado até alguém ligar (menu do navegador ou painel da TV): é a câmera de dentro de casa. Ligado, a IA
// fica olhando o tempo todo e o clipe sai do vídeo que o ao vivo já faz (a vista inteira, as duas lentes).
const clipsOn = { wanted: (() => { try { return !!JSON.parse(fs.readFileSync(path.join(HERE, 'state.json'), 'utf8')).clips; } catch { return false; } })() };
const clips = live ? createClips({
  dir: path.join(HERE, 'eventos'), ffmpeg: FFMPEG,
  onEvent: e => broadcast({ type: 'event', ...e }),
}) : null;
if (clips) live.onTs('whole', clips.ts);
function setClips(enabled) {
  if (clipsOn.wanted !== !!enabled) { clipsOn.wanted = !!enabled; saveState(); }
  if (clipsOn.wanted) startAi(); else stopAiSoon();
  return clipsInfo();
}
function clipsInfo() {
  return { enabled: clipsOn.wanted, available: !!clips && !!ai.model, recording: !!clips?.recording, events: clips ? clips.list() : [] };
}
// Um clipe ou a foto dele, com Range (o <video> do navegador pede pedaços para poder avançar)
function serveClip(req, res, rel) {
  const f = clips?.file(rel);
  if (!f) return json(res, 404, { error: 'clipe não encontrado' });
  const size = fs.statSync(f).size, type = f.endsWith('.mp4') ? 'video/mp4' : 'image/jpeg';
  const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
  if (!range) {
    res.writeHead(200, { 'content-type': type, 'content-length': size, 'accept-ranges': 'bytes', ...cors });
    return fs.createReadStream(f).pipe(res);
  }
  const start = range[1] ? +range[1] : Math.max(0, size - +range[2]), end = range[1] && range[2] ? Math.min(+range[2], size - 1) : size - 1;
  res.writeHead(206, { 'content-type': type, 'content-length': end - start + 1, 'content-range': `bytes ${start}-${end}/${size}`, 'accept-ranges': 'bytes', ...cors });
  fs.createReadStream(f, { start, end }).pipe(res);
}

// O aviso de movimento da própria câmera (server/motion.mjs): vai para as telas junto com a IA.
const motion = { since: null, last: null };
const motionWatch = watchMotion({
  host: () => cam.addr,
  onChange: active => {
    const now = Date.now();
    if (active) {
      if (now - (motion.last || 0) > 60000) logActivity('motion', 'Movimento detectado', { thumb: true });
      motion.since = now; motion.last = now;
      if (perfil && perfil.status('motion') !== 'ok') { perfil.verify('motion', 'ok', 'a câmera avisou movimento'); perfil.save(); }
    }
    else if (motion.since) console.log(`${new Date().toLocaleTimeString('pt-BR')} Movimento na câmera (${Math.round((now - motion.since) / 1000)} s)`);
    if (!active) motion.since = null;
    broadcast({ type: 'motion', active, t: now });
  },
  onError: e => console.log('Movimento da câmera indisponível:', e.message),
});

const L = (pt, en) => ({ pt, en });
const IMAGING_OPTIONS = {};
async function imagingOptions() {
  if (IMAGING_OPTIONS.loaded) return IMAGING_OPTIONS;
  const r = await soap(null, `<timg:GetOptions xmlns:timg="http://www.onvif.org/ver20/imaging/wsdl"><timg:VideoSourceToken>${cam.videoSource}</timg:VideoSourceToken></timg:GetOptions>`);
  for (const name of ['Brightness', 'Contrast', 'ColorSaturation', 'Sharpness']) {
    const m = r.text.match(new RegExp(`:${name}>\\s*<[^>]*Min>([-\\d.]+)<[^>]*>\\s*<[^>]*Max>([-\\d.]+)<`));
    IMAGING_OPTIONS[name] = m ? [+m[1], +m[2]] : [0, 100];
  }
  IMAGING_OPTIONS.loaded = r.ok;
  return IMAGING_OPTIONS;
}

// Visão noturna pelo DVRIP (Camera.ParamEx DayNightSwitch): esta câmera aceita o IrCutFilter do ONVIF e ignora
// (conferido em 25/10, a imagem não muda). Ligada = automática (a câmera passa para o infravermelho quando
// escurece); desligada = "dia o tempo todo" (sempre colorida). Leva ~10 s para a imagem mudar.
const ALWAYS_DAY = '0 00:00:00-23:59:59';
async function nightVision() {
  const r = await (await dvrSession()).request(1042, { Name: 'Camera.ParamEx.[0]' });
  const s = r['Camera.ParamEx.[0]']?.DayNightSwitch;
  return !(s?.SwitchMode === 1 && s?.KeepDayPeriod === ALWAYS_DAY);
}
async function setNightVision(on) {
  const name = 'Camera.ParamEx.[0]';
  const session = await dvrSession();
  const cur = (await session.request(1042, { Name: name }))[name];
  if (!cur) return { ok: false, error: 'a câmera não respondeu' };
  cur.DayNightSwitch = on ? { KeepDayPeriod: '0 07:00:00-18:00:00', SwitchMode: 0 } : { KeepDayPeriod: ALWAYS_DAY, SwitchMode: 1 };
  const r = await session.request(1040, { Name: name, [name]: cur });
  if (Number(r.Ret) !== 100) return { ok: false, error: 'a câmera recusou (' + r.Ret + ')' };
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Visão noturna: ${on ? 'ligada (automática)' : 'desligada (sempre colorida)'}`);
  logActivity('night', on ? 'Visão noturna ativada' : 'Visão noturna desativada');
  return { ok: true };
}

// Luz branca (Camera.WhiteLight): ligada = acesa direto (KeepOpen, o dia todo: a câmera só obedece dentro do
// WorkPeriod); desligada = só infravermelho à noite, sem luz branca (Infrared, 18h–6h, como ficou em 25/10).
async function whiteLight() {
  const r = await (await dvrSession()).request(1042, { Name: 'Camera.WhiteLight' });
  return r['Camera.WhiteLight']?.WorkMode === 'KeepOpen';
}
async function setWhiteLight(on) {
  const name = 'Camera.WhiteLight';
  const session = await dvrSession();
  const cur = (await session.request(1042, { Name: name }))[name];
  if (!cur) return { ok: false, error: 'a câmera não respondeu' };
  cur.WorkMode = on ? 'KeepOpen' : 'Infrared';
  cur.WorkPeriod = on ? { EHour: 23, EMinute: 59, Enable: 1, SHour: 0, SMinute: 0 } : { EHour: 6, EMinute: 0, Enable: 1, SHour: 18, SMinute: 0 };
  const r = await session.request(1040, { Name: name, [name]: cur });
  if (Number(r.Ret) !== 100) return { ok: false, error: 'a câmera recusou (' + r.Ret + ')' };
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Luz branca: ${on ? 'acesa' : 'apagada'}`);
  logActivity('light', on ? 'Luz branca acesa' : 'Luz branca apagada');
  return { ok: true };
}

// O que a tela mostra no painel, na ordem. type: choice (options), level (min/max/step), toggle.
async function screenControls() {
  if (!cam.addr) return [];
  // Só o que se usa: o resto (brilho, contraste, cor, nitidez, contraluz, vídeo, clipes) fica no navegador
  const night = cam.dvr && perfil?.has('night') !== false ? await nightVision().catch(() => null) : null;
  const light = cam.dvr && perfil?.has('light') !== false ? await whiteLight().catch(() => null) : null;
  return [
    ...(night == null ? [] : [{ id: 'night', type: 'toggle', label: L('Visão noturna', 'Night vision'), value: night }]),
    ...(light == null ? [] : [{ id: 'light', type: 'toggle', label: L('Luz branca', 'White light'), value: light }]),
    ...(PROFILE.ptz?.personFollow && perfil?.has('motor') !== false ? [{ id: 'follow', type: 'toggle', label: L('Seguir pessoa', 'Follow person'), value: follow.enabled }] : []),
  ];
}

async function screenControl(id, value) {
  const set = {
    ircut: async () => {
      dayNight = { ...dayNight, mode: String(value) === 'SCHEDULE' ? 'SCHEDULE' : 'MANUAL' };
      saveState();
      if (dayNight.mode === 'SCHEDULE') { lastScheduled = null; await applySchedule(); return { ok: true }; }
      return setImaging({ ircut: String(value) });
    },
    night: () => setNightVision(!!value),
    light: () => setWhiteLight(!!value),
    wdr: () => setImaging({ wdr: value ? 'ON' : 'OFF' }),
    brightness: () => setImaging({ brightness: +value }),
    contrast: () => setImaging({ contrast: +value }),
    saturation: () => setImaging({ saturation: +value }),
    sharpness: () => setImaging({ sharpness: +value }),
    clips: () => { setClips(!!value); return { ok: true }; },
    video: () => {
      if (!['auto', 'main', 'sub'].includes(String(value))) return { ok: false, error: 'vídeo desconhecido' };
      videoPick.choice = String(value);
      saveState();
      applyVideoPick();
      return { ok: true };
    },
    follow: () => {
      const state = setFollow(!!value);
      return state.enabled === !!value ? { ok: true } : { ok: false, error: state.reason || 'Seguir pessoa indisponível' };
    },
  }[id];
  if (!set) return { ok: false, error: 'controle desconhecido: ' + id };
  const r = await set();
  if (!r.ok) return { ok: false, error: r.error || 'não foi possível alterar o controle' };
  return { ok: true, controls: await screenControls() };
}

// ---------- Vistoria: o nível da câmera (server/quality.mjs) ----------
// Em segundo plano: enquanto o vídeo ao vivo roda (alguém assistindo ou o seguir ligado), o medidor
// olha o vídeo como a câmera manda e o nível é reavaliado a cada 10 s. Não abre conexão a mais.
const quality = { level: createLevel(), last: null, test: null, testing: false, bursts: [] };
// Quanto a TV precisa segurar para tocar liso: a maior rajada dos últimos 5 min, com folga. Com a câmera entregando
// liso, o atraso cai para ~0,6 s; com rajadas, cresce até 4 s em vez de engasgar.
function bufferMs() {
  if (quality.bursts.length < 3) return null;
  return Math.min(4000, Math.max(500, Math.round(Math.max(...quality.bursts) * 1.3 + 250)));
}
setInterval(() => {
  if (!live?.active) return;
  const s = meter.summary();
  let g = grade(s);
  if (!g) return;
  // No vídeo pequeno, liso é no máximo Nível 2: o 3 é o grande liso. Assim, 10 min estável no pequeno sobe
  // para o 2, e o automático volta a tentar o grande
  if (videoPick.using === 'sub' && g.level === 3) g = { ...g, level: 2 };
  const before = quality.level.level;
  const now = quality.level.push(g);
  quality.last = { at: Date.now(), stream: videoPick.using, ...s, ...g };
  quality.bursts = [...quality.bursts, s.burstMs].slice(-30);
  if (s.fps > 0 && perfil && perfil.status('video') !== 'ok') { perfil.verify('video', 'ok', 'as imagens chegam'); perfil.save(); }
  if (before !== null && now !== before) {
    console.log(`${new Date().toLocaleTimeString('pt-BR')} Vistoria: ${LEVELS[before].pt} → ${LEVELS[now].pt} (${g.reasons.join('; ') || 'estável'})`);
    perfil?.event('nivel', `${LEVELS[before].pt} → ${LEVELS[now].pt}`, { level: now, reasons: g.reasons });
    if (now === 1) logActivity('quality', 'Conexão da câmera fraca');
    else if (before === 1) logActivity('quality', 'Conexão da câmera boa de novo');
  }
  applyVideoPick();
}, 10000);

// Qual vídeo da câmera o ao vivo puxa: no automático, o pequeno só no Nível 1 (a câmera engasgando no grande).
const videoPick = { choice: (() => { try { return JSON.parse(fs.readFileSync(path.join(HERE, 'state.json'), 'utf8')).video || 'auto'; } catch { return 'auto'; } })(), using: 'main' };
// Uma tela chegou: o vídeo grande já. Saiu a última: espera 30 s antes do pequeno, porque trocar de tela no app
// (ou de lente) fecha um vídeo e abre outro, e cada troca reconectava a câmera (~10 s de tela preta)
let quietTimer = null, viewersGoneAt = 0;
function viewersChanged() {
  clearTimeout(quietTimer);
  if (live?.viewers) { viewersGoneAt = 0; return setTimeout(applyVideoPick, 0); }
  if (!viewersGoneAt) viewersGoneAt = Date.now();
  quietTimer = setTimeout(applyVideoPick, 30500);
}
function pickedStream() {
  // Só a IA (seguir, clipes): o vídeo pequeno basta e gasta quase nada da câmera (~0,15 Mbps). O grande só com
  // uma tela assistindo por aqui
  if (!live?.viewers) return viewersGoneAt && Date.now() - viewersGoneAt < 30000 ? videoPick.using : 'sub';
  if (videoPick.choice !== 'auto') return videoPick.choice;
  return quality.level.level === 1 && subProfile() !== mainProfile() ? 'sub' : 'main';
}
function applyVideoPick() {
  const want = pickedStream();
  if (!live?.active || want === videoPick.using) return;
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Vídeo da câmera: ${want === 'sub' ? 'pequeno' : 'grande'} (${videoPick.choice === 'auto' ? 'automático, ' + LEVELS[quality.level.level]?.pt : 'escolhido'})`);
  live.restart();   // volta sozinho em 2 s, já no outro vídeo
}

// Mede um vídeo da câmera por uma conexão própria (só quando o ao vivo não está usando a câmera:
// ela não aguenta duas). Devolve o resumo da medição.
function measureStream(rtsp, seconds) {
  return new Promise(ok => {
    const m = createMeter({ windowSec: seconds + 5 });
    const ff = spawn(FFMPEG, ['-loglevel', 'error', ...LIVE_IN, '-i', rtsp, '-map', '0:v', '-c:v', 'copy', '-f', 'mpegts', 'pipe:1']);
    const started = Date.now();
    ff.stdout.on('data', d => m.data(d));
    ff.on('error', () => {});
    const timer = setTimeout(() => ff.kill('SIGKILL'), seconds * 1000);
    ff.on('close', () => {
      clearTimeout(timer);
      const s = m.summary();
      ok(s && Date.now() - started >= seconds * 900 ? s : s ? { ...s, droppedEarly: true } : null);
    });
  });
}

// O teste completo ("Teste sua câmera"): vídeo grande e pequeno, ~30 s cada.
async function runVistoria(seconds = 30) {
  if (quality.testing) return { error: 'já testando' };
  if (!cam.addr) return { error: 'sem câmera' };
  quality.testing = true;
  try {
    const result = { at: Date.now(), streams: {} };
    for (const [name, prof] of [['main', mainProfile()], ['sub', subProfile()]]) {
      if (!prof?.rtsp) continue;
      if (name === 'main' && live?.active) {
        // O ao vivo já está conectado no vídeo grande: mede por ele (abrir outra conexão derrubaria os dois)
        await new Promise(r => setTimeout(r, seconds * 1000));
        result.streams.main = { via: 'ao vivo', ...meter.summary(), ...grade(meter.summary()) };
      } else if (live?.active) {
        result.streams[name] = { skipped: 'a câmera está em uso pelo ao vivo; ela não aguenta duas conexões' };
      } else {
        const s = await measureStream(prof.rtsp, seconds);
        result.streams[name] = s ? { ...s, ...grade(s) } : { failed: 'a câmera não mandou vídeo' };
      }
      result.streams[name].resolution = prof.resolution;
      result.streams[name].configuredFps = +prof.fps || null;
    }
    // Nível: 3 se o grande vai liso; 2 se o grande ou o pequeno funciona; 1 se nada vai bem.
    const main = result.streams.main?.level, sub = result.streams.sub?.level;
    result.level = main === 3 ? 3 : (main >= 2 || sub >= 2) ? 2 : (main || sub) ? 1 : null;
    quality.test = result;
    if (result.level) quality.level.set(result.level);
    console.log(`${new Date().toLocaleTimeString('pt-BR')} Vistoria completa: ${result.level ? LEVELS[result.level].pt : 'sem vídeo'}`);
    return result;
  } finally {
    quality.testing = false;
  }
}

// O que a câmera tem: a lista ✅/❌ da vistoria.
async function cameraChecks() {
  const caps = perfil?.data.caps || {};
  return [
    ...Object.entries(CAP_LABELS).filter(([id]) => caps[id]).map(([id, label]) => ({ id, label, status: caps[id].status, how: caps[id].how,
      ok: caps[id].status !== 'no' })),
    { id: 'ai', label: L('IA de pessoa e animal (camera-ai)', 'Person/animal AI (camera-ai)'), status: ai.status === 'on' ? 'ok' : 'no', ok: ai.status === 'on' },
  ];
}

// ---------- Atividades: o que aconteceu, para o app (GET /api/activity) ----------
// Movimento (da câmera), pessoa chegando (da IA), câmera online/offline, visão noturna e luz. As de movimento e
// pessoa levam uma miniatura (320 px, do snapshot HTTP: sem outra conexão de vídeo), guardada 7 dias no PC.
const ACT_DIR = path.join(HERE, 'atividades');
fs.mkdirSync(ACT_DIR, { recursive: true });
const ACT_FILE = path.join(ACT_DIR, 'atividades.json');
let activity = (() => { try { return JSON.parse(fs.readFileSync(ACT_FILE, 'utf8')); } catch { return []; } })();
// Movimentos seguidos (menos de 3 min entre um e outro) viram um evento só, com até quando e quantas vezes
const GROUPED = new Set(['motion']);
async function logActivity(type, text, { thumb = false, box = null, label = null, people } = {}) {
  if (privacy.on && type !== 'privacy') return;   // modo privacidade: a câmera não olha, nada vira atividade
  const now = Date.now(), last = activity.at(-1);
  if (GROUPED.has(type) && last?.type === type && now - (last.until || last.at) < 180000) {
    last.until = now; last.count = (last.count || 1) + 1;
    if (now - presence.person.lastSeen < 60000 && !last.withPerson) { last.withPerson = true; last.text = 'Movimento com pessoa'; }
    try { fs.writeFileSync(ACT_FILE, JSON.stringify(activity)); } catch {}
    return broadcast({ type: 'activity', update: true, item: last });
  }
  const entry = { at: now, type, text, camera: perfil?.data.name || 'Câmera' };
  if (box) entry.box = box;
  if (label) entry.label = label;
  if (people) entry.people = people;
  // Movimento: diz se a IA via alguém junto (a câmera avisa de qualquer coisa que se mexe: cortina, luz da TV)
  if (type === 'motion' && now - presence.person.lastSeen < 60000) { entry.withPerson = true; entry.text = 'Movimento com pessoa'; }
  if (type === 'person' || type === 'animal') entry.alert = protection.on;   // com a Proteção ligada, avisa no celular
  if (thumb) entry.thumb = await activityThumb(type === 'person' || type === 'animal');
  activity = [...activity, entry].slice(-500);
  try { fs.writeFileSync(ACT_FILE, JSON.stringify(activity)); } catch {}
  broadcast({ type: 'activity', item: entry });   // a atividade vai em item: ela tem o próprio type (person, motion…)
  return entry;
}
let lastAiFrame = null;
async function activityThumb(fromAi = false) {
  if (!HAS_FFMPEG || !cam.addr) return null;
  if (fromAi && lastAiFrame && Date.now() - lastAiFrame.at < 3000) return aiFrameThumb(lastAiFrame);
  try {
    const jpg = await snapshot();
    const name = `${Date.now()}.jpg`;
    await new Promise((ok, err) => {
      const ff = spawn(FFMPEG, ['-loglevel', 'error', '-i', 'pipe:0', '-vf', 'scale=320:-2', '-q:v', '6', '-y', path.join(ACT_DIR, name)]);
      ff.on('close', c => c === 0 ? ok() : err(new Error('ffmpeg ' + c))); ff.on('error', err); ff.stdin.end(jpg);
    });
    return name;
  } catch { return null; }
}
// O quadro da IA (RGB, esticado para 640×720) volta à proporção da câmera e vira JPEG de 320 px
async function aiFrameThumb({ frame, w, h }) {
  const prof = videoPick.using === 'sub' ? subProfile() : mainProfile();
  const aspect = prof?.width && prof?.height ? prof.width / prof.height : 1.5;
  const name = `${Date.now()}.jpg`;
  try {
    await new Promise((ok, err) => {
      const ff = spawn(FFMPEG, ['-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', `${w}x${h}`, '-i', 'pipe:0',
        '-vf', `scale=320:${Math.round(320 / aspect / 2) * 2}`, '-q:v', '6', '-y', path.join(ACT_DIR, name)]);
      ff.on('close', c => c === 0 ? ok() : err(new Error('ffmpeg ' + c))); ff.on('error', err); ff.stdin.end(frame);
    });
    return name;
  } catch { return null; }
}

// Miniaturas com mais de 7 dias saem
setInterval(() => {
  const old = Date.now() - 7 * 864e5;
  for (const f of fs.readdirSync(ACT_DIR)) if (/^\d+\.jpg$/.test(f) && +f.slice(0, -4) < old) fs.rm(path.join(ACT_DIR, f), () => {});
  activity = activity.filter(a => a.at > Date.now() - 30 * 864e5);
}, 3600e3).unref();
// Pessoa ou animal: uma VISITA, não uma aparição. Enquanto continua por perto (até 10 min sem ser visto) é o mesmo
// evento, que vai até a última vez visto e guarda quantas pessoas apareceram juntas. Antes, quem ficava parado atrás
// do monitor sumia da IA por uns minutos e voltava como "chegada" nova: 9 "pessoas" num quarto de 2 (25/10).
// Só conta o que a IA viu na maioria das últimas análises (~1 s), não uma imagem solta.
const VISIT_GAP = 10 * 60e3;
const presence = { person: { recent: [], lastSeen: 0, visit: null, savedAt: 0 }, animal: { recent: [], lastSeen: 0, visit: null, savedAt: 0 } };
const RULES = {
  person: { min: 0.6, of10: 6 },
  // Animal pede muito mais: um cobertor e uma sombra viraram "Cachorro" com nota 0,5–0,7 (25/10)
  animal: { min: 0.8, of10: 8, minArea: 0.004 },
};
function arrivals(r) {
  const now = Date.now(), where = perfil?.data.name || 'Câmera';
  for (const kind of ['person', 'animal']) {
    if (kind === 'animal' && perfil?.data.pets === false) continue;   // "não tem animais em casa"
    const p = presence[kind], rule = RULES[kind];
    const found = (r.items || []).filter(it => it.kind === kind && it.score >= rule.min && !it.uncertain
      && (!rule.minArea || it.box[2] * it.box[3] >= rule.minArea)).sort((a, b) => b.score - a.score);
    p.recent.push(found.length > 0);
    if (p.recent.length > 10) p.recent.shift();
    if (!found.length || p.recent.filter(Boolean).length < rule.of10) continue;
    // Quantas ao mesmo tempo: a lente de cima vê o quarto todo e a de baixo uma parte; vale a que vê mais
    const half = (it) => it.box[1] + it.box[3] / 2 < 0.5;
    const count = PROFILE.lenses?.layout === 'stacked-vertical'
      ? Math.max(found.filter(half).length, found.filter(it => !half(it)).length) : found.length;
    const name = kind === 'person' ? (count > 1 ? `${count} pessoas` : 'Pessoa') : found[0].label?.pt || 'Animal';
    const v = p.visit;
    if (v && !v.pending && now - p.lastSeen < VISIT_GAP) {
      v.until = now;
      if (count > (v.people || 1)) { v.people = count; v.text = `${name} no ${where}`; p.savedAt = 0; }
      // Guarda e avisa as telas no máximo a cada 30 s (a visita pode durar horas)
      if (now - p.savedAt > 30000) {
        p.savedAt = now;
        try { fs.writeFileSync(ACT_FILE, JSON.stringify(activity)); } catch {}
        broadcast({ type: 'activity', update: true, item: v });
      }
    } else if (!v?.pending || now - p.lastSeen >= VISIT_GAP) {
      p.visit = { pending: true };
      logActivity(kind, `${name} no ${where}`, { thumb: true, box: found[0].box, label: name, people: kind === 'person' ? count : undefined })
        .then(entry => { p.visit = entry || null; p.savedAt = Date.now(); });
    }
    p.lastSeen = now;
  }
}
// "Foi engano": a atividade sai da lista; fica anotado no perfil (o que era e com que nota) para ajustar a IA
function forgetActivity(at, type) {
  const i = activity.findIndex(a => a.at === +at && a.type === String(type));
  if (i < 0) return { ok: false, error: 'atividade não encontrada' };
  const [a] = activity.splice(i, 1);
  for (const k of ['person', 'animal']) if (presence[k].visit === a) presence[k].visit = null;
  try { fs.writeFileSync(ACT_FILE, JSON.stringify(activity)); } catch {}
  if (a.thumb) fs.rm(path.join(ACT_DIR, a.thumb), () => {});
  perfil?.event('engano', `Engano marcado: ${a.text}`, { kind: a.type, label: a.label, box: a.box });
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Atividade marcada como engano: ${a.text}`);
  return { ok: true };
}
// Zonas para ignorar (desenhadas no app): o que a IA vê com o centro dentro delas não conta (a TV, o ventilador)
function ignoreZones(r) {
  const zones = perfil?.data.zones;
  if (!zones?.length) return r;
  const inside = (it) => {
    const cx = it.box[0] + it.box[2] / 2, cy = it.box[1] + it.box[3] / 2;
    return zones.some(([x, y, w, h]) => cx >= x && cx <= x + w && cy >= y && cy <= y + h);
  };
  return { ...r, items: (r.items || []).filter(it => !inside(it)) };
}

// ---------- Proteção (o modo ausente) ----------
// Ligada: pessoa ou animal vira aviso no celular. Desligada (gente em casa): as atividades ficam, sem aviso. Pode
// ligar e desligar sozinha por horário.
const protection = (() => {
  try { return { on: false, schedule: null, ...JSON.parse(fs.readFileSync(path.join(HERE, 'state.json'), 'utf8')).protection }; } catch { return { on: false, schedule: null }; }
})();
function setProtection(on, why = '') {
  if (protection.on === !!on) return;
  protection.on = !!on;
  saveState();
  logActivity('protection', on ? 'Proteção ativada' : 'Proteção desativada');
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Proteção: ${on ? 'ativada' : 'desativada'}${why ? ' (' + why + ')' : ''}`);
}
let protectionTurn = null;
setInterval(() => {
  const s = protection.schedule;
  if (!s?.enabled) return;
  const now = new Date(), hm = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
  if (hm === protectionTurn) return;
  if (hm === s.on) { protectionTurn = hm; setProtection(true, 'horário'); }
  if (hm === s.off) { protectionTurn = hm; setProtection(false, 'horário'); }
}, 20000).unref();

// ---------- Modo privacidade ----------
// A câmera cobre a própria imagem (duas capas pretas do AVEnc.VideoWidget: vale para todo mundo, até a TV direto e o
// iCSee) e o camera-ai para de olhar (sem IA, sem seguir, sem atividades). Conferido em 25/10: a imagem fica preta.
const privacy = (() => {
  try { return { on: !!JSON.parse(fs.readFileSync(path.join(HERE, 'state.json'), 'utf8')).privacy?.on }; } catch { return { on: false }; }
})();
async function applyCovers(on) {
  const name = 'AVEnc.VideoWidget.[0]';
  const session = await dvrSession();
  const cur = (await session.request(1042, { Name: name }))[name];
  if (!cur?.Covers?.length) throw new Error('esta câmera não tem capa de privacidade');
  const cover = (c, pos) => ({ ...c, EncodeBlend: on, PreviewBlend: on, BackColor: on ? '0xFF000000' : '0x40000000',
    RelativePos: on ? pos : [1024, 1024, 2048, 2048] });
  cur.Covers[0] = cover(cur.Covers[0], [0, 0, 8192, 8192]);
  if (cur.Covers[1]) cur.Covers[1] = cover(cur.Covers[1], [4096, 0, 8192, 8192]);   // a primeira não chega na borda direita
  const r = await session.request(1040, { Name: name, [name]: cur });
  if (Number(r.Ret) !== 100) throw new Error('a câmera recusou (' + r.Ret + ')');
}
async function setPrivacy(on) {
  on = !!on;
  try { await applyCovers(on); } catch (e) { return { ok: false, error: e.message }; }
  if (on) logActivity('privacy', 'Modo privacidade ligado');
  privacy.on = on;
  saveState();
  if (on) {
    if (ai.stop) { ai.stop(); ai.stop = null; }
    follow.enabled = false;
  } else {
    logActivity('privacy', 'Modo privacidade desligado');
    resumeFollow();
    if (follow.enabled || clipsOn.wanted || ai.listeners.size) startAi();
  }
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Modo privacidade: ${on ? 'ligado (imagem coberta)' : 'desligado'}`);
  return { ok: true };
}
// A foto pequena da câmera para as listas: o snapshot da câmera demora ~7 s, então fica uma pronta (320 px), servida
// na hora e renovada por trás quando tem mais de 1 min
const cameraThumb = { jpg: null, at: 0, busy: null };
async function refreshCameraThumb() {
  cameraThumb.busy ??= (async () => {
    try {
      const jpg = await snapshot();
      cameraThumb.jpg = await new Promise((ok, err) => {
        const ff = spawn(FFMPEG, ['-loglevel', 'error', '-i', 'pipe:0', '-vf', 'scale=320:-2', '-q:v', '5', '-f', 'mjpeg', 'pipe:1']);
        const out = []; ff.stdout.on('data', d => out.push(d));
        ff.on('close', c => c === 0 ? ok(Buffer.concat(out)) : err(new Error('ffmpeg ' + c))); ff.on('error', err); ff.stdin.end(jpg);
      });
      cameraThumb.at = Date.now();
    } catch {} finally { cameraThumb.busy = null; }
  })();
  return cameraThumb.busy;
}
async function serveCameraThumb(res) {
  if (!cameraThumb.jpg) await refreshCameraThumb();
  else if (Date.now() - cameraThumb.at > 60000) refreshCameraThumb();
  if (!cameraThumb.jpg) return json(res, 503, { error: 'sem foto da câmera agora' });
  res.writeHead(200, { 'content-type': 'image/jpeg', 'cache-control': 'no-cache' });
  res.end(cameraThumb.jpg);
}

function serveThumb(res, name) {
  if (!/^\d+\.jpg$/.test(name)) return json(res, 404, { error: 'não encontrado' });
  fs.readFile(path.join(ACT_DIR, name), (e, data) => {
    if (e) return json(res, 404, { error: 'não encontrado' });
    res.writeHead(200, { 'content-type': 'image/jpeg', 'cache-control': 'max-age=604800' });
    res.end(data);
  });
}

// ---------- Perfil da câmera (server/perfil.mjs) e a vistoria completa ----------
// Ao conectar: lê o que a câmera diz que tem (ONVIF e, nas XM, a lista SystemFunction pelo DVRIP). A vistoria
// completa (pedida pela pessoa) confere de verdade: mexe, olha a imagem e desfaz. As telas só mostram o que não
// deu "não tem". Tudo fica no perfil, com o histórico, achado pelo número de série.
const perfis = createProfiles(path.join(HERE, 'perfis'));
let perfil = null;
const CAP_LABELS = {
  video: L('Vídeo ao vivo', 'Live video'),
  twoLenses: L('Duas lentes', 'Two lenses'),
  motor: L('Motor (girar a lente)', 'Motor (pan/tilt)'),
  light: L('Luz branca', 'White light'),
  night: L('Visão noturna', 'Night vision'),
  motion: L('Aviso de movimento da câmera', 'Camera motion alerts'),
  camPerson: L('A câmera detecta pessoa sozinha', 'Camera detects people'),
  camTrack: L('Seguir de fábrica', 'Built-in tracking'),
  audio: L('Microfone', 'Microphone'),
  talk: L('Falar pela câmera', 'Talk through the camera'),
  siren: L('Alarme de voz da câmera', 'Camera voice alarm'),
};
async function openProfile() {
  const before = perfil?.data.serial;
  perfil = perfis.open({ serial: cam.info.serial, model: cam.info.model, firmware: cam.info.firmware });
  if (perfil.data.lastAddr !== cam.addr) {
    perfil.data.lastAddr = cam.addr;
    perfil.event('conexao', `Conectada em ${cam.addr}`);
  }
  // O que a câmera diz que tem
  const fn = new Set();
  if (cam.dvr) {
    try {
      const r = await (await dvrSession()).request(1360, { Name: 'SystemFunction' });
      const walk = (o) => { for (const [k, v] of Object.entries(o || {})) { if (v === true) fn.add(k); else if (v && typeof v === 'object') walk(v); } };
      walk(r.SystemFunction);
    } catch {}
  }
  const events = PROFILE.events || [];
  const said = fn.size ? 'a câmera diz (SystemFunction)' : 'o padrão ONVIF diz';
  perfil.declare('video', !!mainProfile()?.rtsp, 'RTSP pelo ONVIF');
  perfil.declare('twoLenses', fn.has('SupportGunBallTwoSensorCamera') || PROFILE.lenses?.count === 2, said);
  perfil.declare('motor', fn.has('SupportPTZDirectionControl') || !!PROFILE.ptz, said);
  perfil.declare('light', fn.has('SupportCameraWhiteLight'), said);
  perfil.declare('night', !!cam.dvr && (fn.has('SupManualSwitchDayNight') || fn.has('SupportSoftPhotosensitive')), said);
  perfil.declare('motion', fn.has('MotionDetect') || events.some(e => /Motion/i.test(e)), said);
  perfil.declare('camPerson', fn.has('HumanDection') || events.some(e => /People|Human|Person/i.test(e)), said);
  perfil.declare('camTrack', fn.has('SupportDetectTrack'), said);
  perfil.declare('audio', PROFILE.audio?.microphone !== false, 'perfil do modelo');
  perfil.declare('talk', fn.has('SupportTwoWayVoiceTalk') || fn.has('Talk'), said);
  perfil.declare('siren', fn.has('SupportAlarmVoiceTips'), said);
  perfil.save();
  if (before !== perfil.data.serial) {
    const caps = perfil.data.caps, n = (s) => Object.values(caps).filter(c => c.status === s).length;
    console.log(`Perfil da câmera ${cam.info.serial}: ${n('ok')} conferidos, ${n('declared') + n('model') + n('unverified')} a conferir, ${n('no')} que ela não tem`);
  }
}

// A imagem agora, pequena, para os testes: brilho, quanta cor e os pixels (para comparar antes/depois).
// Usa o snapshot HTTP da câmera: não abre outra conexão de vídeo.
async function imageStats(part = 'whole') {
  const jpg = await snapshot();
  const crop = part === 'bottom' ? 'crop=iw:ih/2:0:ih/2,' : part === 'top' ? 'crop=iw:ih/2:0:0,' : '';
  const rgb = await new Promise((ok, err) => {
    const ff = spawn(FFMPEG, ['-loglevel', 'error', '-i', 'pipe:0', '-vf', `${crop}scale=160:-2,format=rgb24`, '-f', 'rawvideo', 'pipe:1']);
    const out = []; ff.stdout.on('data', d => out.push(d));
    ff.on('close', c => c === 0 ? ok(Buffer.concat(out)) : err(new Error('ffmpeg ' + c)));
    ff.on('error', err); ff.stdin.end(jpg);
  });
  let luma = 0, color = 0;
  const gray = new Uint8Array(rgb.length / 3);
  for (let i = 0, j = 0; i < rgb.length; i += 3, j++) {
    const r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
    gray[j] = (r * 3 + g * 6 + b) / 10;
    luma += gray[j]; color += Math.abs(r - g) + Math.abs(g - b);
  }
  return { luma: luma / gray.length, color: color / gray.length, gray };
}
const wait = (ms) => new Promise(ok => setTimeout(ok, ms));
const diff = (a, b) => { let s = 0; for (let i = 0; i < Math.min(a.length, b.length); i++) s += Math.abs(a[i] - b[i]); return s / Math.min(a.length, b.length); };

const fullVistoria = { running: false, last: null };
async function runFullVistoria() {
  if (fullVistoria.running) return { ok: false, error: 'já está rodando' };
  if (!perfil || !cam.addr) return { ok: false, error: 'sem câmera' };
  fullVistoria.running = true;
  const log = (t) => console.log(`${new Date().toLocaleTimeString('pt-BR')} Vistoria completa: ${t}`);
  const followWas = follow.enabled;
  follow.enabled = false;   // o seguir não mexe no motor durante os testes
  const results = [];
  const note = (id, status, how) => { perfil.verify(id, status, how); results.push({ id, status, how }); log(`${id} → ${status} (${how})`); };
  // Só o que falta conferir: o que já deu certo não pisca nem gira de novo (firmware novo volta a conferir)
  const todo = (id) => perfil.has(id) && perfil.status(id) !== 'ok';
  try {
    log('começando (a luz pode acender e a lente girar por uns segundos)');
    // Luz branca: acende, a imagem tem que clarear
    // A exposição automática da câmera compensa parte da luz: clarear OU ganhar cor conta. Sem mudança na imagem
    // mas com a câmera aceitando o comando, fica "não deu para conferir" (o botão continua)
    if (todo('light') && cam.dvr) {
      const was = await whiteLight();
      const a = await imageStats();
      const set = await setWhiteLight(true); await wait(8000);
      const accepted = set.ok && await whiteLight();
      const b = await imageStats();
      await setWhiteLight(was);
      const up = b.luma - a.luma, colored = b.color - a.color;
      if (up >= 15 || colored >= 10) note('light', 'ok', `acesa, a imagem ${up >= 15 ? `clareou ${Math.round(up)}` : 'ganhou cor'}`);
      else if (accepted) note('light', 'unverified', 'a câmera aceitou acender, mas a imagem não mostrou (exposição automática?)');
      else note('light', 'no', 'a câmera recusou acender a luz');
    }
    // Visão noturna: só dá para ver com a imagem em preto e branco (escuro): força o dia e ela tem que ganhar cor
    if (todo('night') && cam.dvr) {
      const was = await nightVision();
      const a = await imageStats();
      if (a.color < 30) {
        await setNightVision(false); await wait(12000);
        const b = await imageStats();
        await setNightVision(was);
        if (b.color - a.color >= 10) note('night', 'ok', 'saiu do preto e branco para colorido');
        else note('night', 'unverified', 'mandou trocar e a imagem não mudou ainda');
      } else if (perfil.status('night') !== 'ok') note('night', 'unverified', 'a imagem está colorida (claro): conferir com o ambiente escuro');
    }
    // Motor: um pulso para o lado, a imagem da lente que gira tem que mudar; depois volta ao preset 1
    if ((todo('motor') || (todo('twoLenses') && perfil.has('motor'))) && cam.dvr) {
      const stacked = PROFILE.lenses?.layout === 'stacked-vertical';
      const a = await imageStats(stacked ? 'bottom' : 'whole'), aTop = stacked ? await imageStats('top') : null;
      await ptz('right', 'start', 3); await wait(600); await ptz('right', 'stop', 3); await wait(2000);
      const b = await imageStats(stacked ? 'bottom' : 'whole'), bTop = stacked ? await imageStats('top') : null;
      await ptz('home', 'start').catch(() => {});
      const d = diff(a.gray, b.gray);
      if (d >= 10) { if (todo('motor')) note('motor', 'ok', `girou, a imagem mudou ${Math.round(d)}`); }
      else note('motor', 'no', `mandou girar e a imagem não mudou (${Math.round(d)})`);
      // Duas lentes: a de baixo mudou e a de cima ficou parada
      if (stacked && d >= 10 && todo('twoLenses') && diff(aTop.gray, bTop.gray) < d / 3) note('twoLenses', 'ok', 'a lente de baixo girou e a de cima ficou parada');
    }
    // Movimento: conferido quando a câmera avisar de verdade (fica marcado sozinho)
    if (perfil.has('motion') && perfil.status('motion') !== 'ok') results.push({ id: 'motion', status: perfil.status('motion'), how: 'confere sozinho no primeiro aviso de movimento' });
    const ok = results.filter(r => r.status === 'ok').length;
    perfil.event('vistoria', `Vistoria completa: ${ok} de ${results.length} conferidos`, { results, level: quality.level.level });
    fullVistoria.last = { at: Date.now(), results };
    log(`pronta: ${ok} de ${results.length} conferidos`);
    return { ok: true, results };
  } catch (e) {
    log('parou: ' + e.message);
    return { ok: false, error: e.message };
  } finally {
    follow.enabled = followWas;
    follow.lostAt = 0; follow.returned = false;
    fullVistoria.running = false;
  }
}
// O que cada recurso é, em palavras simples, e onde se usa (null: a câmera tem, mas o camera-ai ainda não usa)
const CAP_INFO = {
  video: { desc: 'A imagem ao vivo da câmera.', use: 'Na TV e aqui no app.' },
  twoLenses: { desc: 'Duas lentes: uma fixa, que vê o quarto todo, e outra que gira e aproxima.', use: 'Na TV: escolha a imagem de cima, de baixo ou as duas.' },
  motor: { desc: 'A lente de baixo gira para os lados e para cima e baixo.', use: 'Seguir pessoa, no app e na TV (a lente vai atrás de quem aparece).' },
  light: { desc: 'Uma luz branca na câmera, que ilumina o lugar e deixa a imagem colorida no escuro.', use: 'No app e nos ajustes da câmera na TV: Luz branca.' },
  night: { desc: 'No escuro, a câmera usa infravermelho e enxerga em preto e branco, sem acender luz nenhuma.', use: 'No app e nos ajustes da câmera na TV: Visão noturna.' },
  motion: { desc: 'A própria câmera percebe quando algo se mexe e avisa.', use: 'O aviso de movimento na tela da câmera.' },
  camPerson: { desc: 'A própria câmera reconhece pessoas (além da IA do camera-ai).', use: null },
  camTrack: { desc: 'A câmera tem um "seguir pessoa" de fábrica. O camera-ai usa o dele, que volta para a posição de descanso.', use: null },
  audio: { desc: 'Um microfone: dá para ouvir o som do lugar.', use: 'Nos ajustes da câmera na TV: Som.' },
  talk: { desc: 'Um alto-falante: você fala pelo celular ou pela TV e sua voz sai na câmera, como um interfone.', use: null },
  siren: { desc: 'A câmera toca um aviso sonoro sozinha (sirene ou voz) quando aparece alguém.', use: null },
};

function perfilInfo() {
  if (!perfil) return { ok: false, error: 'sem câmera' };
  const p = perfil.data;
  return { ok: true, name: p.name || 'Câmera', zones: p.zones || [], pets: p.pets !== false, kind: PROFILE.name || null, serial: p.serial, model: p.model, firmware: p.firmware, firstSeen: p.firstSeen, running: fullVistoria.running,
    caps: Object.entries(CAP_LABELS).filter(([id]) => p.caps[id]).map(([id, label]) => ({ id, label, ...CAP_INFO[id], ...p.caps[id] })),
    history: p.history.slice(-50).reverse() };
}

async function vistoriaInfo() {
  const level = quality.level.level;
  const s = quality.last;
  return {
    level, label: level ? LEVELS[level] : null,
    testing: quality.testing,
    measuring: !!live?.active,
    now: s ? { fps: s.fps, filmedFps: s.filmedFps, kbps: s.kbps, lostPct: s.lostPct, burstMs: s.burstMs, maxGapMs: s.maxGapMs,
      stalls: s.stalls, reconnectsLastHour: s.reconnectsLastHour, reasons: s.reasons, at: s.at } : null,
    test: quality.test,
    checks: await cameraChecks(),
    tips: tips(s || quality.test?.streams?.main, level),
  };
}

async function screenInfo() {
  return {
    version: SCREEN_VERSION, hub: 'camera-ai',
    camera: { connected: !!cam.addr, name: perfil?.data.name || 'Câmera', kind: PROFILE.name || cam.info.model || null, model: cam.info.model || null,
      serial: cam.info.serial || null, hosts: cam.addr ? [cam.addr] : [] },
    lenses: PROFILE.lenses || null,
    video: HAS_FFMPEG ? { mjpeg: '/api/screen/video', formats: live ? ['ts', 'mjpeg'] : H264 ? ['h264', 'mjpeg'] : ['mjpeg'],
      views: PROFILE.lenses?.layout === 'stacked-vertical' ? ['whole', 'top', 'bottom'] : ['whole'] } : null,
    ai: { status: ai.status, model: ai.model?.name || null, device: ai.model?.device || null, events: '/api/screen/events' },
    audio: live && PROFILE.audio?.microphone !== false ? { pcm: '/api/screen/audio', rate: 8000, channels: 1 } : null,
    controls: await screenControls().catch(() => []),
    motion: { active: motionWatch.active, last: motion.last },
    events: clips ? { list: '/api/events', recording: clipsOn.wanted } : null,
    protection: { on: protection.on, schedule: protection.schedule },
    privacy: { on: privacy.on },
    quality: { level: quality.level.level, label: quality.level.level ? LEVELS[quality.level.level] : null, bufferMs: bufferMs(), vistoria: '/api/vistoria' },
  };
}

// Vídeo para a tela já no tamanho dela: o PC decodifica o main (2304×2592, grande demais para o
// decodificador da maioria das TVs), recorta a lente pedida e manda MJPEG.
// format=h264: H.264 de baixo atraso (o decodificador da TV mostra, ~3 Mbps). mjpeg: JPEG a JPEG,
// só o mais novo (para navegador e TVs cujo decodificador não aceite o H.264).
function screenVideo(res, view, width, height, format) {
  const main = mainProfile();
  if (!main?.rtsp) return json(res, 409, { error: 'sem câmera' });
  if (live) {
    if (format !== 'ts') return mjpegFromLive(res, view, width, height);
    res.writeHead(200, { 'content-type': 'video/mp2t', 'cache-control': 'no-cache', 'connection': 'close', ...cors });
    return live.watch(live.views.includes(view) ? view : 'whole', res);
  }
  const crop = view === 'top' ? 'crop=iw:ih/2:0:0,' : view === 'bottom' ? 'crop=iw:ih/2:0:ih/2,' : '';
  const vf = `${crop}scale=${width}:${height}:force_original_aspect_ratio=decrease:force_divisible_by=2`;
  const h264 = format === 'h264' && H264;
  res.writeHead(200, { 'content-type': h264 ? 'video/h264' : 'multipart/x-mixed-replace; boundary=ffmpeg',
    'cache-control': 'no-cache', 'connection': 'close', ...cors });
  const out = h264
    ? ['-c:v', H264.name, ...H264.opts, '-profile:v', 'main', '-bf', '0', '-g', '30', '-b:v', '3M', '-maxrate', '3M', '-bufsize', '500k',
       '-bsf:v', 'dump_extra', '-f', 'h264', '-']
    : ['-r', '15', '-f', 'mpjpeg', '-q:v', '5', '-boundary_tag', 'ffmpeg', '-'];
  const ff = spawn(FFMPEG, ['-loglevel', 'error', ...LIVE_IN, '-i', main.rtsp, '-an', '-vf', vf, ...out]);
  let errors = '';
  ff.stderr.on('data', d => { errors = (errors + d).slice(-600); });
  if (h264) {
    // Se a tela ficar para trás (Wi-Fi engasgou), pula até o próximo quadro inteiro (SPS antes do
    // IDR, a cada 30 quadros) em vez de acumular atraso: H.264 não deixa pular um quadro solto.
    let skipping = false;
    ff.stdout.on('data', d => {
      if (res.writableLength > 8e6) { console.log('tela parada: fechando o vídeo'); return res.destroy(); }
      if (!skipping && res.writableLength > 600000) { skipping = true; console.log('tela atrasada: pulando para o ao vivo'); }
      if (skipping) {
        if (res.writableLength > 100000) return;
        const at = keyframeAt(d);
        if (at < 0) return;
        d = d.subarray(at);
        skipping = false;
      }
      res.write(d);
    });
  } else {
    sendLatestJpeg(ff.stdout, res);
  }
  const kill = () => { try { ff.kill('SIGKILL'); } catch {} };
  res.on('close', kill);
  ff.on('close', code => {
    if (code) console.log(`vídeo da tela (${view} ${width}x${height}) parou: ${code} ${errors.trim()}`);
    try { res.end(); } catch {}
  });
  ff.on('error', () => { kill(); try { res.end(); } catch {} });
}

function screenEvents(req, res) {
  res.writeHead(200, { 'content-type': 'application/x-ndjson', 'cache-control': 'no-cache', 'connection': 'close', ...cors });
  res.write(JSON.stringify({ type: 'hello', ai: ai.status }) + '\n');
  if (ai.last) res.write(JSON.stringify({ type: 'detections', ...ai.last }) + '\n');
  ai.listeners.add(res);
  startAi();
  // A cada 2 s: a TV percebe se caiu, e mostra como o vídeo está chegando da câmera
  const ping = setInterval(() => res.write(JSON.stringify({ type: 'ping', ai: ai.status, video: live ? live.stats() : null }) + '\n'), 2000);
  res.on('close', () => { clearInterval(ping); ai.listeners.delete(res); if (!ai.listeners.size) stopAiSoon(); });
}

// A TV acha o camera-ai na rede: manda "camera-ai?" em broadcast UDP na porta 8788 e recebe o endereço.
const DISCOVERY_PORT = 8788;
function startDiscovery() {
  const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  sock.on('message', (msg, from) => {
    if (msg.toString().trim() !== 'camera-ai?') return;
    const reply = JSON.stringify({ hub: 'camera-ai', version: SCREEN_VERSION, port: PORT, auth: true,
      serial: cam.info.serial || null, hosts: cam.addr ? [cam.addr] : [] });
    sock.send(reply, from.port, from.address);
  });
  sock.on('error', e => console.log('descoberta UDP:', e.message));
  sock.bind(DISCOVERY_PORT);
}

// Conecta sozinho na câmera ao ligar: CAMERA=ip, ou a última que conectou (server/state.json), ou o
// endereço do perfil. Assim as telas acham a câmera pronta, sem abrir a interface no navegador antes.
const STATE_FILE = path.join(HERE, 'state.json');

// ---------- Senha ----------
// Quem não está no próprio PC precisa da senha do camera-ai uma vez: o navegador guarda um cookie, a TV guarda a
// chave e a põe no começo do endereço (/k/<chave>/api/...). Trocar a senha troca a chave e desconecta todos.
// Fica em state.json só o hash (scrypt) da senha e a chave.
const access = (() => {
  try { const a = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')).access; if (a?.hash && a?.key) return a; } catch {}
  return null;
})() || newAccess(String(crypto.randomInt(0, 1e8)).padStart(8, '0'), true);
function newAccess(password, announce) {
  const salt = crypto.randomBytes(16).toString('hex');
  const a = { salt, hash: crypto.scryptSync(password, salt, 32).toString('hex'), key: crypto.randomBytes(24).toString('hex') };
  if (announce) console.log(`Senha do camera-ai: ${password}   (para entrar pelo celular e pela TV; troque no menu ☰ → Senha)`);
  return a;
}
const same = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
// Cada aparelho que entra (pela senha ou pelo Permitir) ganha a própria chave: dá para ver quem tem acesso e tirar um
// só. A chave antiga (access.key) continua valendo para quem entrou antes disto.
access.devices ??= [];
function newDevice(name) {
  const d = { id: crypto.randomBytes(6).toString('hex'), name: String(name || 'Aparelho').slice(0, 40), key: crypto.randomBytes(24).toString('hex'), at: Date.now(), lastSeen: Date.now() };
  access.devices.push(d);
  saveState();
  return d;
}
function knownKey(k) {
  if (same(k, access.key)) return true;
  const d = access.devices.find(d => same(k, d.key));
  if (d && Date.now() - d.lastSeen > 600000) { d.lastSeen = Date.now(); saveState(); }
  return !!d;
}
function devicesInfo() {
  return { ok: true, devices: access.devices.map(({ id, name, at, lastSeen }) => ({ id, name, at, lastSeen })) };
}
function removeDevice(id) {
  const n = access.devices.length;
  access.devices = access.devices.filter(d => d.id !== String(id));
  if (access.devices.length === n) return { ok: false, error: 'aparelho não encontrado' };
  saveState();
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Acesso de um aparelho removido`);
  return { ok: true, ...devicesInfo() };
}
const passwordOk = (password) => same(crypto.scryptSync(String(password ?? ''), access.salt, 32).toString('hex'), access.hash);
function setPassword(password) {
  password = String(password ?? '');
  if (password.length < 4) return { ok: false, error: 'a senha precisa de pelo menos 4 caracteres' };
  Object.assign(access, newAccess(password, false), { devices: [] });
  saveState();
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Senha do camera-ai trocada: todos os aparelhos entram de novo`);
  return { ok: true, key: access.key };
}
const isLoopback = (req) => ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
// O próprio PC entra sem senha, mas não uma página de outro site aberta nele (ela mandaria Origin de fora)
function fromThisPc(req) {
  if (!isLoopback(req)) return false;
  const origin = req.headers.origin;
  if (!origin) return true;
  try { return ['127.0.0.1', 'localhost'].includes(new URL(origin).hostname); } catch { return false; }
}
const cookieKey = (req) => /(?:^|;\s*)camkey=([0-9a-f]+)/.exec(req.headers.cookie || '')?.[1];
// Errou a senha 5 vezes: aquele aparelho espera 1 minuto
const tries = new Map();
function login(req, res, password, name) {
  const ip = req.socket.remoteAddress, t = tries.get(ip) || { n: 0, until: 0 };
  if (Date.now() < t.until) return json(res, 429, { ok: false, error: 'muitas tentativas; espere 1 minuto' });
  if (!passwordOk(password)) {
    t.n++; if (t.n >= 5) { t.n = 0; t.until = Date.now() + 60000; }
    tries.set(ip, t);
    console.log(`${new Date().toLocaleTimeString('pt-BR')} Senha errada de ${ip}`);
    return json(res, 401, { ok: false, error: 'senha errada' });
  }
  tries.delete(ip);
  const d = newDevice(name || 'Navegador');
  res.setHeader('set-cookie', `camkey=${d.key}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax`);
  return json(res, 200, { ok: true, key: d.key });
}
// Pedido de acesso: a TV (ou um celular sem a senha) pede, e quem já entrou toca em Permitir no navegador.
// Ninguém precisa saber a senha. Um pedido por aparelho, vale 10 min.
const requests = new Map();   // id → { id, name, ip, at, status: 'pending' | 'ok' | 'no' }
function accessRequest(req, name) {
  const ip = req.socket.remoteAddress.replace(/^::ffff:/, '');
  for (const [id, r] of requests) if (r.ip === ip || Date.now() - r.at > 600000) requests.delete(id);
  if (requests.size >= 5) return { ok: false, error: 'pedidos demais agora' };
  const r = { id: crypto.randomBytes(16).toString('hex'), name: String(name || 'Aparelho').slice(0, 40), ip, at: Date.now(), status: 'pending' };
  requests.set(r.id, r);
  console.log(`${new Date().toLocaleTimeString('pt-BR')} ${r.name} (${ip}) pediu acesso: permita no navegador do PC ou do celular`);
  return { ok: true, id: r.id };
}
function accessStatus(id) {
  const r = requests.get(String(id));
  if (!r || Date.now() - r.at > 600000) return { status: 'gone' };
  if (r.status === 'ok') { requests.delete(r.id); return { status: 'ok', key: newDevice(r.name).key }; }
  return { status: r.status };
}
function accessAnswer(id, allow) {
  const r = requests.get(String(id));
  if (!r) return { ok: false, error: 'o pedido expirou' };
  r.status = allow ? 'ok' : 'no';
  console.log(`${new Date().toLocaleTimeString('pt-BR')} ${r.name} (${r.ip}): acesso ${allow ? 'permitido' : 'recusado'}`);
  return { ok: true };
}
const pendingRequests = () => [...requests.values()].filter(r => r.status === 'pending' && Date.now() - r.at < 600000).map(({ id, name, ip, at }) => ({ id, name, ip, at }));

const LOGIN_PAGE = `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>camera-ai</title><style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0d0f12;color:#e8eaed;font:16px system-ui,sans-serif}
form{display:grid;gap:12px;width:min(320px,calc(100vw - 32px))}h1{font-size:20px;margin:0 0 4px}
input,button{font:inherit;padding:12px;border-radius:10px;border:1px solid #333;background:#16191d;color:inherit}
button{background:#2f6fed;border-color:#2f6fed;font-weight:600}p{margin:0;color:#ff8a80;min-height:1.2em}</style></head>
<body><form id="f"><h1>camera-ai</h1><input id="p" type="password" inputmode="numeric" autocomplete="current-password" placeholder="Senha" autofocus>
<button>Entrar</button><button type="button" id="ask" style="background:#16191d;border-color:#333">Sem a senha? Pedir para quem já entrou</button><p id="e"></p></form><script>
ask.onclick=async()=>{const r=await(await fetch('/api/access/request',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({name:'Celular ou navegador'})})).json();
if(!r.ok){e.textContent=r.error;return;}e.style.color='#9aa0a6';e.textContent='Pedido enviado: toque em Permitir no camera-ai do PC ou de outro celular.';
const t=setInterval(async()=>{const s=await(await fetch('/api/access/status?id='+r.id)).json();if(s.status==='ok'){clearInterval(t);location.reload();}
else if(s.status!=='pending'){clearInterval(t);e.style.color='#ff8a80';e.textContent=s.status==='no'?'Recusado.':'O pedido expirou.';}},2000);};
f.onsubmit=async ev=>{ev.preventDefault();const r=await fetch('/api/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({password:p.value})});
const d=await r.json().catch(()=>({}));if(d.ok)location.reload();else e.textContent=d.error||'não deu';};</script></body></html>`;

function saveState() {
  try { fs.writeFileSync(STATE_FILE, JSON.stringify({ addr: cam.addr, user: cam.user, pass: cam.pass, dayNight, follow: follow.wanted, clips: clipsOn.wanted, video: videoPick.choice, access, protection, privacy })); } catch {}
}
setImmediate(saveState);   // a senha nova (primeira vez) fica guardada já, depois que tudo acima e abaixo existe

// ---------- Dia/noite por horário ----------
// No automático a câmera decide pela luz e fica trocando no fim de tarde (e a luz branca acende e
// apaga junto). Por horário: noite das 19h às 6h, dia no resto. Escolher Automático/Dia/Noite à mão
// desliga o horário. Só roda com o camera-ai ligado; a câmera guarda o último modo aplicado.
let dayNight = (() => {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')).dayNight || null; } catch { return null; }
})() || { mode: 'SCHEDULE', night: '19:00', day: '06:00' };

const hour = (hhmm) => { const [h, m] = String(hhmm).split(':').map(Number); return h + 'h' + (m ? String(m).padStart(2, '0') : ''); };
const minutes = (hhmm) => { const [h, m] = String(hhmm).split(':').map(Number); return h * 60 + (m || 0); };
function scheduledIrCut(now = new Date()) {
  const t = now.getHours() * 60 + now.getMinutes(), night = minutes(dayNight.night), day = minutes(dayNight.day);
  const isNight = night > day ? (t >= night || t < day) : (t >= night && t < day);
  return isNight ? 'OFF' : 'ON';   // ONVIF: IrCutFilter OFF = noite (infravermelho), ON = dia
}
let lastScheduled = null;   // o horário só manda na virada (e ao ligar): quem ligou à mão fica assim até lá
async function applySchedule() {
  if (dayNight.mode !== 'SCHEDULE' || !cam.addr) return;
  const want = scheduledIrCut();
  if (want === lastScheduled) return;
  lastScheduled = want;
  try {
    const now = (await getImaging()).ircut;
    if (now !== want) {
      await setImaging({ ircut: want });
      console.log(`Dia/noite: ${want === 'OFF' ? 'noite' : 'dia'} (horário ${dayNight.night}–${dayNight.day})`);
    }
  } catch (e) { console.log('Dia/noite:', e.message); }
}
// WS-Discovery (ONVIF): a primeira câmera que responder na rede. Um socket por rede do PC: sem
// isso o Windows pode mandar a busca só pela VPN (Tailscale, etc.) e nenhuma câmera responde.
function findCamera(timeout = 3000) {
  return new Promise(ok => {
    const probe = `<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${crypto.randomUUID()}</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>`;
    const socks = [];
    let finished = false;
    const done = (host) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      for (const s of socks) { try { s.close(); } catch {} }
      ok(host);
    };
    const timer = setTimeout(() => done(null), timeout);
    for (const list of Object.values(os.networkInterfaces())) {
      for (const a of list || []) {
        if (a.family !== 'IPv4' || a.internal) continue;
        const sock = dgram.createSocket('udp4');
        socks.push(sock);
        sock.on('message', m => { const x = m.toString().match(/XAddrs>\s*https?:\/\/([^:/\s<]+)/); if (x) done(x[1]); });
        sock.on('error', () => {});
        sock.bind(0, a.address, () => sock.send(probe, 3702, '239.255.255.250', () => {}));
      }
    }
    if (!socks.length) done(null);
  });
}

// Não conseguiu (câmera reiniciando, Wi-Fi dela oscilando): tenta de novo a cada 15 s, primeiro o
// endereço salvo e depois quem responder na rede (o IP dela pode ter mudado).
async function autoConnect(tries = 0) {
  if (cam.addr) return;
  let saved = {};
  try { saved = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch {}
  const addr = process.env.CAMERA || (tries % 2 === 0 && saved.addr) || await findCamera() || saved.addr;
  let ok = false;
  if (addr) {
    try {
      const r = await connect(addr, saved.user, saved.pass);
      ok = r.ok;
      console.log(ok ? `Câmera: ${addr} conectada` : `Câmera: ${addr} não respondeu (${r.error})`);
      if (ok) resumeFollow();
    } catch (e) {
      console.log(`Câmera: ${addr} não respondeu (${e.message})`);
    }
  } else if (tries === 0) {
    console.log('Câmera: nenhuma achada na rede ainda; tentando de novo.');
  }
  if (!ok) { cam.addr = null; setTimeout(() => autoConnect(tries + 1), 15000); }
}

// Já conectada, a câmera pode reiniciar e voltar com outro IP (DHCP): sem isso o camera-ai ficaria
// tentando o endereço velho para sempre. 3 falhas seguidas (~90 s) = soltar e procurar de novo.
let camMisses = 0, camChecking = false;
setInterval(async () => {
  if (!cam.addr) { camMisses = 0; return; }
  if (camChecking) return;   // a pergunta anterior ainda esperando o tempo esgotar
  camChecking = true;
  const r = await soap(null, `<tds:GetDeviceInformation/>`).catch(() => ({ ok: false })).finally(() => { camChecking = false; });
  if (!cam.addr) return;
  if (r.ok) { camMisses = 0; return; }
  if (++camMisses < 3) return;
  console.log(`${new Date().toLocaleTimeString('pt-BR')} Câmera: ${cam.addr} sumiu; procurando de novo na rede`);
  logActivity('offline', 'Câmera offline');
  camMisses = 0;
  cam.addr = null; cam.dvr?.close(); cam.dvr = null;
  autoConnect(1);   // ímpar: procura na rede primeiro (o IP pode ter mudado)
}, 30000);

// ---------- servidor ----------
const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  if (req.method === 'OPTIONS') { res.writeHead(204, cors); return res.end(); }
  try {
    // A TV põe a chave no começo do endereço: /k/<chave>/api/...
    const prefixed = /^\/k\/([0-9a-f]+)(\/.*)$/.exec(u.pathname);
    if (prefixed) u.pathname = prefixed[2];
    if (u.pathname === '/api/login' && req.method === 'POST') { const b = await readBody(req); return login(req, res, b.password, b.name); }
    if (u.pathname === '/api/access/request' && req.method === 'POST') return json(res, 200, accessRequest(req, (await readBody(req)).name));
    if (u.pathname === '/api/screen/direct' && req.method === 'POST') return json(res, 200, setDirectWatch(!!(await readBody(req)).watching));
    if (u.pathname === '/api/access/status') {
      const r = accessStatus(u.searchParams.get('id'));
      if (r.key) res.setHeader('set-cookie', `camkey=${r.key}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax`);
      return json(res, 200, r);
    }
    if (!fromThisPc(req) && !knownKey(prefixed?.[1]) && !knownKey(cookieKey(req)) && !knownKey(req.headers['x-key'])) {
      if (u.pathname.startsWith('/api/')) return json(res, 401, { error: 'senha', login: '/api/login' });
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return res.end(LOGIN_PAGE);
    }
    if (u.pathname === '/api/password' && req.method === 'POST') {
      const r = setPassword((await readBody(req)).password);
      if (r.ok) res.setHeader('set-cookie', `camkey=${access.key}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax`);
      return json(res, 200, r);
    }
    if (u.pathname.startsWith('/api/events/') && req.method === 'GET') return serveClip(req, res, decodeURIComponent(u.pathname.slice(12)));
    if (u.pathname.startsWith('/api/activity/') && req.method === 'GET') return serveThumb(res, decodeURIComponent(u.pathname.slice(14)));
    switch (u.pathname) {
      case '/api/events': {
        if (req.method === 'GET') return json(res, 200, clipsInfo());
        const b = await readBody(req);
        if (b.test) return json(res, 200, clips?.test() ? { ok: true } : { ok: false, error: 'sem vídeo ao vivo agora (abra a câmera ou ligue a IA)' });
        if (b.delete) return json(res, 200, { ok: !!clips?.remove(String(b.delete)) });
        return json(res, 200, setClips(b.enabled));
      }
      case '/api/perfil': {
        // O nome que a pessoa dá à câmera ("Quarto")
        if (req.method === 'POST') {
          const b = await readBody(req);
          if (perfil && b.name) perfil.data.name = String(b.name).slice(0, 40);
          if (perfil && typeof b.pets === 'boolean') perfil.data.pets = b.pets;
          // Zonas para ignorar: retângulos [x, y, l, a] de 0 a 1 na imagem inteira
          if (perfil && Array.isArray(b.zones)) perfil.data.zones = b.zones.slice(0, 8)
            .map(z => z.slice(0, 4).map(n => Math.max(0, Math.min(1, +n || 0)))).filter(z => z[2] > 0.01 && z[3] > 0.01);
          perfil?.save();
        }
        return json(res, 200, perfilInfo());
      }
      case '/api/activity': return json(res, 200, { activity: activity.slice(-200).reverse() });
      case '/api/activity/forget': { const b = await readBody(req); return json(res, 200, forgetActivity(b.at, b.type)); }
      case '/api/screen/thumb': return serveCameraThumb(res);
      case '/api/protection': {
        if (req.method === 'POST') {
          const b = await readBody(req);
          if (b.on != null) setProtection(b.on, 'pedido');
          if (b.schedule !== undefined) { protection.schedule = b.schedule; saveState(); }
        }
        return json(res, 200, { ok: true, ...protection });
      }
      case '/api/privacy': return json(res, 200, req.method === 'POST' ? await setPrivacy((await readBody(req)).on) : { ok: true, ...privacy });
      case '/api/devices': return json(res, 200, devicesInfo());
      case '/api/devices/remove': return json(res, 200, removeDevice((await readBody(req)).id));
      case '/api/perfil/vistoria': return json(res, 200, await runFullVistoria());
      case '/api/access/pending': return json(res, 200, { requests: pendingRequests() });
      case '/api/access/answer': { const b = await readBody(req); return json(res, 200, accessAnswer(b.id, !!b.allow)); }
      case '/api/camera-password': { const b = await readBody(req); return json(res, 200, await setCameraPassword(b.password)); }
      case '/api/connect': { const b = await readBody(req); return json(res, 200, await connect(b.addr, b.user, b.pass)); }
      case '/api/state':   return json(res, 200, publicState());
      case '/api/stream':  if (!HAS_FFMPEG) return json(res, 501, { error: 'sem ffmpeg; use snapshot' }); return startStream(res, u.searchParams.get('stream') || 'sub');
      case '/api/snapshot': { const buf = await snapshot(); res.writeHead(200, { 'content-type': 'image/jpeg', ...cors }); return res.end(buf); }
      case '/api/ptz':     { const b = await readBody(req); await ptz(b.dir, b.action); return json(res, 200, { ok: true }); }
      case '/api/follow':  { const b = req.method === 'GET' ? {} : await readBody(req); return json(res, 200, req.method === 'GET' ? { enabled: follow.enabled, available: !!cam.dvr && !!ai.model && !!live } : setFollow(b.enabled)); }
      case '/api/encoder': { const b = await readBody(req); return json(res, 200, await applyEncoder(b)); }
      case '/api/optimize': return json(res, 200, await autoOptimize());
      case '/api/record':  { const b = await readBody(req); return json(res, 200, toggleRecord(b.on)); }
      case '/api/imaging':
        if (req.method === 'GET') return json(res, 200, await getImaging());
        { const b = await readBody(req); return json(res, 200, await setImaging(b)); }
      case '/api/daynight': { const b = await readBody(req); return json(res, 200, await screenControl('ircut', b.mode)); }
      case '/api/audio':   return startAudio(res);
      case '/api/vistoria': return json(res, 200, req.method === 'POST' ? await runVistoria(Math.min(60, Math.max(10, +(await readBody(req)).seconds || 30))) : await vistoriaInfo());
      case '/api/screen':  return json(res, 200, await screenInfo());
      case '/api/screen/control': { const b = await readBody(req); return json(res, 200, await screenControl(b.id, b.value)); }
      case '/api/screen/events': return screenEvents(req, res);
      case '/api/screen/audio':
        if (!live) return json(res, 501, { error: 'sem a conexão única' });
        res.writeHead(200, { 'content-type': 'audio/L16;rate=8000;channels=1', 'cache-control': 'no-cache', 'connection': 'close', ...cors });
        return live.listen(res);
      case '/api/screen/video': {
        if (!HAS_FFMPEG) return json(res, 501, { error: 'sem ffmpeg' });
        const n = (k, d, max) => Math.max(160, Math.min(max, parseInt(u.searchParams.get(k), 10) || d));
        return screenVideo(res, u.searchParams.get('view') || 'whole', n('width', 1920, 3840), n('height', 1080, 2160),
          u.searchParams.get('format') || 'mjpeg');
      }
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
  // Só a página e assets/: o resto da pasta tem server/state.json (senhas) e os clipes
  if (file !== path.join(UI_ROOT, 'index.html') && !file.startsWith(path.join(UI_ROOT, 'assets') + path.sep)) return json(res, 404, { error: 'não encontrado' });
  fs.readFile(file, (e, data) => {
    if (e) return json(res, 404, { error: 'não encontrado' });
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', ...cors });
    res.end(data);
  });
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`cam-control no PC:     http://127.0.0.1:${PORT}`);
  const lan = Object.values(os.networkInterfaces()).flat().filter(i => i?.family === 'IPv4' && !i.internal && /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(i.address)).map(i => i.address);
  console.log(`cam-control no celular: ${lan.map(ip => `http://${ip}:${PORT}`).join('  ') || '(sem rede)'}   (mesma rede Wi-Fi, com a senha)`);
  console.log(`ffmpeg: ${HAS_FFMPEG ? 'sim' : 'não'} · H.264 para as telas: ${H264 ? H264.name : 'não'}`);
  startDiscovery();
  autoConnect();
  loadAi();
});
