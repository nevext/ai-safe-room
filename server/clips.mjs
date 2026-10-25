// camera-ai — os clipes de quando aparece alguém. Do vídeo que o ao vivo já faz (H.264 em MPEG-TS, sem
// reencodar nada a mais): guarda sempre os últimos segundos e, quando a IA vê uma pessoa chegar, grava desde
// um pouco antes até um pouco depois de ela sair de vista. No fim vira MP4 (cópia, sem perder qualidade) com
// uma foto para a lista. Tudo fica no PC (server/eventos/) e some sozinho depois de `keepDays`.
//
// "Chegar" e não "estar": quem fica parado na frente da câmera (dormindo, no computador) dá um clipe só, e
// não um atrás do outro. Um novo só depois de `quietMs` sem ninguém.

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const PREROLL_MS = 5000, AFTER_MS = 10000, MAX_MS = 60000;

export function createClips({ dir, ffmpeg, keepDays = 7, quietMs = 60000, onEvent = () => {} }) {
  fs.mkdirSync(dir, { recursive: true });
  let gops = [];             // os últimos segundos: [{ at, chunks: [Buffer] }], cada um começando num quadro inteiro
  let rec = null;            // gravando: { file, out, started, lastSeen, kind, label }
  let lastPresent = 0;       // a última vez que a IA confirmou alguém (2 análises seguidas): para saber quando alguém "chega"
  let hits = 0;              // análises seguidas com alguém (2 = chegou de verdade, não um palpite solto)
  let arriving = false;      // essa sequência começou depois de quietMs sem ninguém

  /** Cada pedaço do MPEG-TS do ao vivo (live.onTs). */
  function ts(d, key) {
    const now = Date.now();
    if (key >= 0) {
      if (key > 0) push(d.subarray(0, key), now);
      gops.push({ at: now, chunks: [] });
      push(d.subarray(key), now);
      // Fica com o suficiente para PREROLL_MS antes de agora (o GOP que começa antes dele também)
      while (gops.length > 1 && gops[1].at <= now - PREROLL_MS) gops.shift();
    } else {
      push(d, now);
    }
  }
  function push(part, now) {
    if (!part.length) return;
    const copy = Buffer.from(part);
    if (gops.length) gops[gops.length - 1].chunks.push(copy);
    if (rec) rec.out.write(copy);
    if (rec && (now - rec.lastSeen > AFTER_MS || now - rec.started > MAX_MS)) finish();
  }

  /** O resultado de cada análise da IA ({items}). */
  function seen(result) {
    const now = Date.now();
    const person = (result.items || []).some(it => it.kind === 'person' && !it.uncertain);
    if (!person) { hits = 0; return; }
    // Só a presença confirmada conta: um palpite solto (a IA pisca) não pode apagar a chegada de quem vem
    if (++hits === 1) arriving = now - lastPresent > quietMs;
    if (hits < 2) return;
    lastPresent = now;
    if (rec) { rec.lastSeen = now; return; }
    if (hits >= 2 && arriving) {   // quem continua ali (arriving falso) não dá clipe novo
      arriving = false;
      start('pessoa', { pt: 'Pessoa', en: 'Person' });
    }
  }

  function start(kind, label) {
    if (rec || !gops.length) return false;
    const now = new Date();
    const day = new Date(now - now.getTimezoneOffset() * 60e3).toISOString().slice(0, 10);   // o dia daqui, não o de Greenwich
    const stamp = now.toTimeString().slice(0, 8).replace(/:/g, '');
    fs.mkdirSync(path.join(dir, day), { recursive: true });
    const file = path.join(dir, day, `${stamp}-${kind}`);
    const out = fs.createWriteStream(file + '.ts');
    for (const g of gops) for (const c of g.chunks) out.write(c);
    rec = { file, out, started: Date.now(), lastSeen: Date.now(), kind, label, at: now.getTime() };
    console.log(`${now.toLocaleTimeString('pt-BR')} Clipe: gravando (${kind})`);
    return true;
  }

  function finish() {
    const r = rec;
    rec = null;
    r.out.end(() => {
      // MP4 (toca em qualquer lugar) e a foto do meio do clipe, para a lista
      const mp4 = r.file + '.mp4', jpg = r.file + '.jpg';
      // +genpts: os segundos de antes emendam com o resto e repetiam um DTS na emenda
      const ff = spawn(ffmpeg, ['-loglevel', 'error', '-y', '-fflags', '+genpts', '-i', r.file + '.ts', '-c', 'copy', '-movflags', '+faststart', mp4]);
      ff.on('error', () => {});
      ff.on('close', code => {
        if (code !== 0) { console.log('Clipe: não deu para fechar o MP4'); return; }
        fs.rm(r.file + '.ts', () => {});
        const seconds = Math.round((Date.now() - r.started) / 1000) + PREROLL_MS / 1000;
        const th = spawn(ffmpeg, ['-loglevel', 'error', '-y', '-ss', String(Math.min(PREROLL_MS / 1000 + 1, seconds / 2)), '-i', mp4,
          '-frames:v', '1', '-vf', 'scale=480:-2', jpg]);
        th.on('error', () => {});
        th.on('close', () => {
          const event = describe(mp4);
          console.log(`${new Date().toLocaleTimeString('pt-BR')} Clipe: pronto, ${event.seconds} s (${r.kind})`);
          onEvent(event);
        });
      });
    });
  }

  // O caminho relativo (dia/arquivo) é o id: dá para servir e apagar sem banco de dados.
  function describe(mp4) {
    const rel = path.relative(dir, mp4).replace(/\\/g, '/').replace(/\.mp4$/, '');
    const [day, name] = rel.split('/');
    const [hhmmss, kind] = name.split('-');
    const at = new Date(`${day}T${hhmmss.slice(0, 2)}:${hhmmss.slice(2, 4)}:${hhmmss.slice(4, 6)}`).getTime();
    let bytes = null;
    try { bytes = fs.statSync(mp4).size; } catch {}
    return {
      id: rel, at, kind,
      label: kind === 'pessoa' ? { pt: 'Pessoa', en: 'Person' } : { pt: 'Teste', en: 'Test' },
      video: `/api/events/${rel}.mp4`, thumb: fs.existsSync(mp4.replace(/\.mp4$/, '.jpg')) ? `/api/events/${rel}.jpg` : null,
      bytes, seconds: duration(mp4),
    };
  }
  // A duração, lida do próprio MP4 (caixa mvhd), sem chamar o ffprobe.
  function duration(mp4) {
    try {
      const fd = fs.openSync(mp4, 'r');
      const buf = Buffer.alloc(Math.min(fs.fstatSync(fd).size, 1 << 20));
      fs.readSync(fd, buf, 0, buf.length, 0);
      fs.closeSync(fd);
      const i = buf.indexOf('mvhd');
      if (i < 0) return null;
      const v = buf[i + 4];
      const scale = v === 1 ? buf.readUInt32BE(i + 24) : buf.readUInt32BE(i + 16);
      const dur = v === 1 ? Number(buf.readBigUInt64BE(i + 28)) : buf.readUInt32BE(i + 20);
      return scale ? Math.round(dur / scale) : null;
    } catch { return null; }
  }

  /** Os clipes guardados, do mais novo para o mais velho. */
  function list() {
    const out = [];
    for (const day of fs.readdirSync(dir).sort().reverse()) {
      const d = path.join(dir, day);
      if (!fs.statSync(d).isDirectory()) continue;
      for (const f of fs.readdirSync(d).filter(f => f.endsWith('.mp4')).sort().reverse()) out.push(describe(path.join(d, f)));
    }
    return out;
  }

  /** O arquivo de um clipe (`dia/hhmmss-tipo.mp4|.jpg`), ou null se o nome não for de um. */
  function file(rel) {
    if (!/^\d{4}-\d{2}-\d{2}\/\d{6}-[a-z]+\.(mp4|jpg)$/.test(rel)) return null;
    const f = path.join(dir, rel);
    return fs.existsSync(f) ? f : null;
  }

  function remove(id) {
    const f = file(id + '.mp4');
    if (!f) return false;
    fs.rmSync(f, { force: true });
    fs.rmSync(f.replace(/\.mp4$/, '.jpg'), { force: true });
    return true;
  }

  // Apaga o que passou do prazo (uma vez por hora, e ao ligar)
  function prune() {
    const limit = Date.now() - keepDays * 86400e3;
    for (const day of fs.readdirSync(dir)) {
      const t = Date.parse(day);
      if (!isNaN(t) && t + 86400e3 < limit) fs.rmSync(path.join(dir, day), { recursive: true, force: true });
    }
  }
  prune();
  setInterval(prune, 3600e3).unref();

  return {
    ts, seen, list, file, remove,
    get recording() { return !!rec; },
    /** Grava um clipe agora, sem esperar a IA (para testar que funciona). */
    test() {
      if (!start('teste', { pt: 'Teste', en: 'Test' })) return false;
      rec.lastSeen = Date.now() - AFTER_MS + 5000;   // ~5 s depois de agora, mais os segundos de antes
      return true;
    },
  };
}
