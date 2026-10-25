// camera-ai — UMA conexão com a câmera para tudo (como o go2rtc/Frigate fazem). A câmera não aguenta
// várias ao mesmo tempo pelo Wi-Fi: com duas, o vídeo principal caiu para 0,2 quadro por segundo
// (medido, 2026-10-24). Então um ffmpeg só lê o vídeo principal e entrega:
//   - cada vista (inteira, lente de cima, lente de baixo) em H.264 de baixo atraso, dentro de MPEG-TS
//     para levar a hora de cada quadro (a câmera manda em rajadas: com a hora, a tela mostra cada um no
//     ritmo em que foi filmado, com atraso fixo);
//   - quadros pequenos para a IA;
//   - o som do microfone (PCM 8 kHz), para a TV tocar sem abrir outra conexão;
//   - uma cópia crua do vídeo da câmera (sem reencodar) para o medidor da vistoria (server/quality.mjs).
// Cada tela que chega entra no próximo quadro inteiro; a que fica para trás pula para o ao vivo.
// O ffmpeg só roda enquanto alguém assiste (tela ou IA) e volta sozinho se a câmera cair.

import net from 'node:net';
import { spawn } from 'node:child_process';

// Onde começa um SPS (NAL tipo 7, sempre antes de um quadro inteiro aqui) neste pedaço, ou -1.
export function keyframeAt(d, from = 0) {
  for (let i = from; i + 3 < d.length; i++) {
    if (d[i] === 0 && d[i + 1] === 0 && d[i + 2] === 1 && (d[i + 3] & 0x1f) === 7) return i > 0 && d[i - 1] === 0 ? i - 1 : i;
  }
  return -1;
}

// MPEG-TS: pacotes de 188 bytes. O vídeo vai no PID 0x100 (o padrão do ffmpeg com um stream só).
const TS = 188, VIDEO_PID = 0x100;
const pid = (p) => ((p[1] & 0x1f) << 8) | p[2];
const unitStart = (p) => (p[1] & 0x40) !== 0;
// Pacote que começa um quadro inteiro: início de PES com random_access_indicator (o ffmpeg marca os IDR).
const keyStart = (p) => pid(p) === VIDEO_PID && unitStart(p) && (p[3] & 0x20) !== 0 && p[4] > 0 && (p[5] & 0x40) !== 0;

const listen = () => new Promise((ok, err) => {
  const server = net.createServer();
  server.on('error', err);
  server.listen(0, '127.0.0.1', () => ok(server));
});

/**
 * views: as vistas a gerar ('whole', 'top', 'bottom'). size: [largura, altura] máximos de cada vista.
 * ai: [largura, altura] dos quadros da IA (RGB). rtsp(): o endereço do vídeo principal agora.
 * meter: o medidor da vistoria (createMeter), que recebe o vídeo como a câmera mandou.
 */
export function createLive({ ffmpeg, inputArgs, rtsp, encoder, views, size = [1920, 1080], ai = [640, 720], aiFps = 5, meter = null }) {
  const subs = Object.fromEntries(views.map(v => [v, new Set()]));
  const aiListeners = new Set();
  const audioSubs = new Set();
  const tsTaps = Object.fromEntries(views.map(v => [v, new Set()]));   // quem guarda o vídeo (clipes): não liga o ffmpeg sozinho
  const stats = Object.fromEntries(views.map(v => [v, { fps: 0, kbps: 0, frames: 0, bytes: 0, lastData: 0, maxGapMs: 0 }]));
  let ff = null, servers = [], starting = false, idle = null, restart = null, quitting = false;   // quitting: fomos nós que paramos

  const anyone = () => aiListeners.size > 0 || audioSubs.size > 0 || views.some(v => subs[v].size > 0);

  // A cada segundo: fps e kbps de cada vista; o maior buraco sem dados nos últimos segundos.
  setInterval(() => {
    for (const v of views) {
      const s = stats[v];
      s.fps = s.frames; s.kbps = Math.round(s.bytes * 8 / 1000);
      s.frames = 0; s.bytes = 0;
      s.maxGapMs = Math.round(s.maxGapMs * 0.8);
    }
  }, 1000).unref();

  const rest = Object.fromEntries(views.map(v => [v, Buffer.alloc(0)]));
  function fanOut(view, chunk) {
    const s = stats[view], now = Date.now();
    if (s.lastData) s.maxGapMs = Math.max(s.maxGapMs, now - s.lastData);
    s.lastData = now; s.bytes += chunk.length;
    // Só pacotes inteiros, e onde começa cada quadro inteiro neles
    let d = rest[view].length ? Buffer.concat([rest[view], chunk]) : chunk;
    if (d[0] !== 0x47) { const sync = d.indexOf(0x47); d = sync < 0 ? Buffer.alloc(0) : d.subarray(sync); }
    const whole = d.length - d.length % TS;
    rest[view] = Buffer.from(d.subarray(whole));
    d = d.subarray(0, whole);
    let key = -1;
    for (let i = 0; i < d.length; i += TS) {
      const p = d.subarray(i, i + TS);
      if (pid(p) === VIDEO_PID && unitStart(p)) s.frames++;
      if (key < 0 && keyStart(p)) key = i;
    }
    for (const fn of tsTaps[view]) fn(d, key);
    for (const sub of subs[view]) {
      const res = sub.res;
      if (res.writableLength > 8e6) { res.destroy(); continue; }   // parou de ler
      if (!sub.waiting && res.writableLength > 600000) sub.waiting = true;   // para trás: pula para o ao vivo
      let out = d;
      if (sub.waiting) {
        if (res.writableLength > 100000 || key < 0) continue;
        out = d.subarray(key);
        sub.waiting = false;
      }
      if (out.length) res.write(out);
    }
  }

  function aiReader(socket) {
    const frameSize = ai[0] * ai[1] * 3;
    let buf = Buffer.alloc(0);
    socket.on('data', d => {
      buf = buf.length ? Buffer.concat([buf, d]) : d;
      while (buf.length >= frameSize) {
        const frame = Buffer.from(buf.subarray(0, frameSize));
        buf = buf.subarray(frameSize);
        for (const fn of aiListeners) fn(frame, ai[0], ai[1]);
      }
    });
  }

  async function start() {
    if (ff || starting || !anyone()) return;
    const url = rtsp();
    if (!url) return;
    starting = true;
    try {
      const outputs = [...views.map(v => ({ v })), { v: 'ai' }, { v: 'audio' }, { v: 'meter' }];
      servers = await Promise.all(outputs.map(listen));
      outputs.forEach((o, i) => servers[i].on('connection', socket => {
        socket.on('error', () => {});
        if (o.v === 'ai') aiReader(socket);
        else if (o.v === 'audio') socket.on('data', d => { for (const res of audioSubs) if (res.writableLength < 64000) res.write(d); });
        else if (o.v === 'meter') socket.on('data', d => meter?.data(d));
        else socket.on('data', d => fanOut(o.v, d));
      }));
      const [w, h] = size;
      const fit = `scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2`;
      const chains = views.map((v, i) => `[s${i}]${v === 'top' ? 'crop=iw:ih/2:0:0,' : v === 'bottom' ? 'crop=iw:ih/2:0:ih/2,' : ''}${fit}[o${i}]`);
      const graph = `[0:v]split=${views.length + 1}${views.map((_, i) => `[s${i}]`).join('')}[sa];` + chains.join(';')
        + `;[sa]fps=${aiFps},scale=${ai[0]}:${ai[1]}[oa]`;
      const args = ['-loglevel', 'error', ...inputArgs, '-i', url, '-filter_complex', graph];
      views.forEach((v, i) => args.push('-map', `[o${i}]`, '-c:v', encoder.name, ...encoder.opts, '-profile:v', 'main', '-bf', '0',
        '-g', '30', '-b:v', '6M', '-maxrate', '6M', '-bufsize', '2M', '-bsf:v', 'dump_extra',
        '-f', 'mpegts', '-mpegts_flags', '+resend_headers', '-muxdelay', '0', '-muxpreload', '0',
        `tcp://127.0.0.1:${servers[i].address().port}`));
      args.push('-map', '[oa]', '-f', 'rawvideo', '-pix_fmt', 'rgb24', `tcp://127.0.0.1:${servers[views.length].address().port}`);
      args.push('-map', '0:a?', '-c:a', 'pcm_s16le', '-ar', '8000', '-ac', '1', '-f', 's16le', `tcp://127.0.0.1:${servers[views.length + 1].address().port}`);
      args.push('-map', '0:v', '-c:v', 'copy', '-f', 'mpegts', '-muxdelay', '0', '-muxpreload', '0', `tcp://127.0.0.1:${servers[views.length + 2].address().port}`);
      let errors = '';
      ff = spawn(ffmpeg, args);
      ff.stderr.on('data', d => { errors = (errors + d).slice(-500); });
      ff.on('error', () => {});
      ff.on('close', code => {
        ff = null;
        meter?.dropped(!quitting);
        quitting = false;
        for (const s of servers) s.close();
        servers = [];
        // Sempre com a hora: é por aqui que se descobre quando e por que a câmera derruba a conexão
        console.log(`${new Date().toLocaleTimeString('pt-BR')} vídeo ao vivo parou (código ${code}): ${errors.trim() || 'a câmera fechou a conexão'}`);
        if (anyone()) restart = setTimeout(start, 2000);   // a câmera caiu ou reiniciou: de novo
      });
      console.log(`${new Date().toLocaleTimeString('pt-BR')} vídeo ao vivo: 1 conexão com a câmera para ${views.join(', ')} e a IA`);
    } finally {
      starting = false;
    }
  }

  function stopIfIdle() {
    clearTimeout(idle);
    idle = setTimeout(() => {
      if (anyone() || !ff) return;
      clearTimeout(restart);
      quitting = true;
      try { ff.kill('SIGKILL'); } catch {}
    }, 10000);
  }

  return {
    views,
    stats: () => stats,
    /** Conectado na câmera agora (alguém assistindo ou a IA ligada). */
    get active() { return !!ff; },
    /** Manda a vista para esta resposta HTTP (H.264, a partir do próximo quadro inteiro). */
    watch(view, res) {
      const sub = { res, waiting: true };
      subs[view].add(sub);
      res.on('close', () => { subs[view].delete(sub); stopIfIdle(); });
      start();
    },
    /** Manda o som (PCM 16 bits, 8 kHz, mono) para esta resposta HTTP. */
    listen(res) {
      audioSubs.add(res);
      res.on('close', () => { audioSubs.delete(res); stopIfIdle(); });
      start();
    },
    /**
     * Recebe o MPEG-TS da vista (pacotes inteiros; key = onde começa um quadro inteiro, ou -1) enquanto o
     * ffmpeg roda por outro motivo; devolve a função que para. O pedaço é emprestado: copie se for guardar.
     */
    onTs(view, fn) {
      tsTaps[view]?.add(fn);
      return () => tsTaps[view]?.delete(fn);
    },
    /** Recebe os quadros da IA (RGB); devolve a função que para de receber. */
    onAiFrame(fn) {
      aiListeners.add(fn);
      start();
      return () => { aiListeners.delete(fn); stopIfIdle(); };
    },
    restart() { if (ff) { quitting = true; try { ff.kill('SIGKILL'); } catch {} } },
  };
}
