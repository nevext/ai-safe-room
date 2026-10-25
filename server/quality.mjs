// camera-ai — a vistoria: o quanto a câmera entrega de verdade, e o nível que sai disso.
// Força do sinal sozinha não diz nada (a câmera de teste tinha -33 dBm e engasgava): aqui se mede o
// vídeo que chega. Para cada quadro: a hora em que chegou e a hora em que foi filmado (PTS). Daí saem
//   - quantos quadros por segundo chegam, e quantos a câmera diz que filmou;
//   - as rajadas: o quanto a chegada se desvia do ritmo da filmagem (= a reserva que a tela precisa);
//   - os travamentos: mais de 1 s sem chegar nada;
//   - os quadros perdidos: buracos na hora de filmagem.
// O nível desce depressa (a câmera engasgou) e sobe devagar (estável por 10 min), para não ficar trocando.

const TS = 188, VIDEO_PID = 0x100;
const pid = (p) => ((p[1] & 0x1f) << 8) | p[2];

/** Lê MPEG-TS (vídeo copiado da câmera, sem reencodar) e anota chegada e PTS de cada quadro. */
export function createMeter({ windowSec = 30 } = {}) {
  let frames = [];          // { at, pts } em ms
  let bytes = [];           // { at, n }
  let reconnects = [];      // horários em que a conexão com a câmera caiu
  let rest = Buffer.alloc(0);
  let since = 0;            // primeira chegada desde que conectou: a abertura vem em rajada e fica de fora

  function trim(now) {
    const from = now - windowSec * 1000;
    while (frames.length && frames[0].at < from) frames.shift();
    while (bytes.length && bytes[0].at < from) bytes.shift();
    reconnects = reconnects.filter(t => t > now - 3600e3);
  }

  return {
    /** Um pedaço do MPEG-TS copiado da câmera. */
    data(chunk, now = Date.now()) {
      if (!since) since = now;
      if (now - since < 3000) return;
      bytes.push({ at: now, n: chunk.length });
      let d = rest.length ? Buffer.concat([rest, chunk]) : chunk;
      if (d[0] !== 0x47) { const sync = d.indexOf(0x47); d = sync < 0 ? Buffer.alloc(0) : d.subarray(sync); }
      const whole = d.length - d.length % TS;
      rest = Buffer.from(d.subarray(whole));
      for (let i = 0; i < whole; i += TS) {
        const p = d.subarray(i, i + TS);
        if (pid(p) !== VIDEO_PID || !(p[1] & 0x40)) continue;   // só o começo de cada quadro
        const start = 4 + ((p[3] & 0x20) ? 1 + p[4] : 0);
        if (start + 14 > TS || p[start] !== 0 || p[start + 1] !== 0 || p[start + 2] !== 1) continue;
        if (!(p[start + 7] & 0x80)) continue;   // sem PTS
        const b = p.subarray(start + 9, start + 14);
        // PTS: 33 bits em 90 kHz, espalhados em 5 bytes
        const pts90 = ((b[0] >> 1) & 0x07) * 2 ** 30 + (b[1] << 22 | (b[2] >> 1) << 15 | b[3] << 7 | b[4] >> 1);
        frames.push({ at: now, pts: pts90 / 90 });
      }
      trim(now);
    },
    /** O ffmpeg parou: queda da câmera (counted) ou parada nossa (ninguém assistindo). Recomeça a janela. */
    dropped(counted = true, now = Date.now()) { if (counted) reconnects.push(now); since = 0; frames = []; bytes = []; rest = Buffer.alloc(0); },
    /** O resumo da janela (últimos windowSec segundos), ou null se ainda não deu para medir. */
    summary(now = Date.now()) {
      trim(now);
      if (frames.length < 10) return null;
      const span = (now - frames[0].at) / 1000;
      if (span < Math.min(15, windowSec - 5)) return null;
      // PTS volta ao zero quando a câmera reinicia o relógio: usa só a parte crescente final
      let first = 0;
      for (let i = 1; i < frames.length; i++) if (frames[i].pts < frames[i - 1].pts) first = i;
      const f = frames.slice(first);
      const deltas = [];
      for (let i = 1; i < f.length; i++) deltas.push(f[i].pts - f[i - 1].pts);
      const sorted = [...deltas].sort((a, b) => a - b);
      const typical = sorted[Math.floor(sorted.length / 2)] || 0;
      const lost = typical ? deltas.reduce((n, d) => n + (d > typical * 1.8 ? Math.round(d / typical) - 1 : 0), 0) : 0;
      // Rajadas: chegada menos filmagem, relativo ao quadro que chegou mais adiantado
      const offsets = f.map(x => x.at - x.pts);
      const min = Math.min(...offsets);
      const late = offsets.map(o => o - min).sort((a, b) => a - b);
      let maxGap = 0;
      for (let i = 1; i < frames.length; i++) maxGap = Math.max(maxGap, frames[i].at - frames[i - 1].at);
      maxGap = Math.max(maxGap, now - frames[frames.length - 1].at);
      const stalls = frames.reduce((n, x, i) => n + (i && x.at - frames[i - 1].at > 1000 ? 1 : 0), 0);
      const filmed = f.length > 1 ? (f.length - 1) / ((f[f.length - 1].pts - f[0].pts) / 1000) : 0;
      return {
        seconds: Math.round(span),
        fps: +(frames.length / span).toFixed(1),            // quadros que chegaram por segundo
        filmedFps: +filmed.toFixed(1),                      // o que a câmera diz que filmou
        kbps: Math.round(bytes.reduce((n, x) => n + x.n, 0) * 8 / 1000 / span),
        lostPct: Math.round(100 * lost / (f.length + lost)),
        burstMs: Math.round(late[Math.floor(late.length * 0.95)] || 0),   // reserva para tocar liso (p95)
        maxGapMs: Math.round(maxGap),
        stalls,
        reconnectsLastHour: reconnects.length,
        lastReconnect: reconnects.at(-1) || null,
      };
    },
  };
}

/**
 * A nota de um resumo de medição: 3 = liso, 2 = funciona com reserva, 1 = fraco. Compara com o que a
 * câmera filmou, não com o fps configurado: à noite ela filma menos de propósito, e isso não é defeito.
 */
export function grade(s) {
  if (!s) return null;
  const reasons = [];
  const delivery = s.filmedFps ? s.fps / s.filmedFps : 1;
  if (s.stalls) reasons.push(`${s.stalls} travamento(s) de mais de 1 s`);
  if (s.lostPct >= 5) reasons.push(`${s.lostPct}% dos quadros perdidos`);
  if (delivery < 0.85) reasons.push(`chegam ${s.fps} de ${s.filmedFps} quadros/s`);
  if (s.burstMs > 500) reasons.push(`vídeo em rajadas (precisa de ${(s.burstMs / 1000).toFixed(1)} s de reserva)`);
  if (s.fps < 5) reasons.push(`só ${s.fps} quadros/s`);
  const bad = s.stalls > 2 || s.lostPct >= 15 || delivery < 0.6 || s.burstMs > 1500 || s.fps < 5;
  const level = bad ? 1 : reasons.length ? 2 : 3;
  return { level, reasons };
}

export const LEVELS = {
  1: { name: 'Básico', pt: 'Nível 1 – Básico', en: 'Level 1 – Basic' },
  2: { name: 'Bom', pt: 'Nível 2 – Bom', en: 'Level 2 – Good' },
  3: { name: 'Completo', pt: 'Nível 3 – Completo', en: 'Level 3 – Full' },
};

/** Dicas para subir de nível, a partir do que deu errado. */
export function tips(s, level) {
  if (!s || level === 3) return [];
  const t = [];
  if (s.stalls || s.reconnectsLastHour > 2) t.push('A conexão está caindo: aproxime a câmera do roteador ou ponha um repetidor perto dela.');
  if (s.burstMs > 500) t.push('O vídeo chega em rajadas: o Wi-Fi da casa está congestionado ou a câmera está longe. Teste com menos aparelhos usando a internet.');
  if (s.lostPct >= 5) t.push('Quadros se perdendo no caminho: troque o canal do Wi-Fi do roteador ou use a rede de 2,4 GHz mais perto da câmera.');
  if (!t.length) t.push('Use o vídeo pequeno da câmera: é mais leve para o Wi-Fi dela.');
  return t;
}

/**
 * O nível em segundo plano: avalia a cada 10 s; desce depois de 6 avaliações ruins seguidas (~60 s) e
 * sobe só depois de 10 min sem nenhuma avaliação abaixo do nível novo. 6, e não 3: a janela é de 30 s, então
 * um engasgo só fica em 3 avaliações seguidas; 6 = ruim em duas janelas diferentes (engasgou mais de uma vez).
 */
export function createLevel({ riseMs = 10 * 60e3, dropAfter = 6 } = {}) {
  let level = null, badStreak = 0, history = [];   // { at, level }
  return {
    get level() { return level; },
    /** O teste pedido pela pessoa vale na hora (e daí em diante segue as mesmas regras). */
    set(l, now = Date.now()) { level = l; badStreak = 0; history = [{ at: now, level: l }]; },
    push(g, now = Date.now()) {
      if (!g) return level;
      history.push({ at: now, level: g.level });
      history = history.filter(h => h.at > now - riseMs);
      // O começo da conexão engasga: a primeira medida não derruba para o 1 (cai depois, pelas mesmas regras)
      if (level === null) { level = Math.max(g.level, 2); return level; }
      if (g.level < level) {
        if (++badStreak >= dropAfter) { level = Math.max(...history.slice(-dropAfter).map(h => h.level)); badStreak = 0; }
      } else {
        badStreak = 0;
        const steady = history.length && history[0].at <= now - riseMs + 15e3 && Math.min(...history.map(h => h.level));
        if (steady > level) level = steady;
      }
      return level;
    },
  };
}
