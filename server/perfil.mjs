// camera-ai — o perfil de cada câmera: o que ela tem, o que foi conferido de verdade e o histórico.
// Um arquivo por câmera (server/perfis/<serial>.json), achado pelo número de série: mudar de IP não perde nada.
// Câmeras do mesmo modelo começam com o que já se sabe dele (server/perfis/modelos/<modelo>.json).
//
// Cada recurso tem um estado:
//   'ok'         conferido na vistoria completa (mexeu e a imagem mostrou);
//   'no'         a câmera não tem, ou a vistoria tentou e não funcionou;
//   'declared'   a câmera diz que tem, ainda não conferido;
//   'unverified' tentou conferir mas não deu para saber agora (ex.: visão noturna com o quarto claro);
//   'model'      herdado de outra câmera do mesmo modelo, ainda não conferido nesta.
// As telas mostram o botão para tudo que não é 'no'.

import fs from 'node:fs';
import path from 'node:path';

const HISTORY_MAX = 300;
const safe = (s) => String(s || 'desconhecido').replace(/[^A-Za-z0-9_.-]/g, '_');

export function createProfiles(dir) {
  const modelsDir = path.join(dir, 'modelos');
  fs.mkdirSync(modelsDir, { recursive: true });
  const read = (file) => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
  const write = (file, data) => { try { fs.writeFileSync(file, JSON.stringify(data, null, 2)); } catch {} };

  /** O perfil desta câmera (cria na primeira vez). Firmware novo: o conferido volta a "declarado". */
  function open({ serial, model, firmware }) {
    const file = path.join(dir, safe(serial) + '.json');
    const modelFile = path.join(modelsDir, safe(model) + '.json');
    const now = Date.now();
    let p = read(file);
    if (!p) {
      p = { serial, model, firmware, firstSeen: now, caps: {}, history: [] };
      for (const [id, c] of Object.entries(read(modelFile)?.caps || {})) {
        if (c.status === 'ok' || c.status === 'no') p.caps[id] = { status: 'model', how: `herdado do modelo ${model}`, at: now };
      }
      p.history.push({ at: now, type: 'nova', text: `Câmera nova (${model})` + (Object.keys(p.caps).length ? ', com o que já se sabe do modelo' : '') });
    } else if (firmware && p.firmware && firmware !== p.firmware) {
      for (const c of Object.values(p.caps)) if (c.status === 'ok' || c.status === 'no') { c.status = 'declared'; c.how = 'firmware novo: conferir de novo'; }
      p.history.push({ at: now, type: 'firmware', text: `Firmware ${p.firmware} → ${firmware}: a vistoria completa vai conferir de novo` });
    }
    p.model = model || p.model;
    p.firmware = firmware || p.firmware;
    p.lastSeen = now;

    const save = () => {
      p.history = p.history.slice(-HISTORY_MAX);
      write(file, p);
      // O modelo guarda só o que foi conferido, para as próximas câmeras iguais
      const m = read(modelFile) || { model, caps: {} };
      for (const [id, c] of Object.entries(p.caps)) if (c.status === 'ok' || c.status === 'no') m.caps[id] = { status: c.status, from: serial, at: c.at };
      write(modelFile, m);
    };

    const profile = {
      get data() { return p; },
      status: (id) => p.caps[id]?.status ?? null,
      has: (id) => p.caps[id] && p.caps[id].status !== 'no',
      /** O que a câmera diz (não desfaz um conferido). */
      declare(id, yes, how) {
        const c = p.caps[id];
        if (c && (c.status === 'ok' || (c.status === 'no' && c.verified))) return;
        if (!yes && c?.status === 'model') return;
        p.caps[id] = { status: yes ? (c?.status === 'model' || c?.status === 'unverified' ? c.status : 'declared') : 'no', how, at: Date.now() };
      },
      /** O resultado de um teste de verdade. */
      verify(id, status, how) {
        const before = p.caps[id]?.status;
        p.caps[id] = { status, how, at: Date.now(), verified: status === 'ok' || status === 'no' };
        if (before !== status) p.history.push({ at: Date.now(), type: 'recurso', id, text: `${id}: ${before || '—'} → ${status} (${how})` });
      },
      event(type, text, extra = {}) { p.history.push({ at: Date.now(), type, text, ...extra }); save(); },
      save,
    };
    save();
    return profile;
  }

  return { open };
}
