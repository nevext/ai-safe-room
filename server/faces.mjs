// camera-ai — rostos: achar (YuNet), reconhecer (SFace) e guardar as pessoas conhecidas. Os dois modelos são do
// OpenCV Zoo, licença Apache 2.0 (podem ir num produto vendido; muitos modelos de rosto famosos só para uso não
// comercial). Rodam no PC; nada sai de casa. Baixar com server/get-faces.ps1.
//
// Rosto é dado biométrico (dado sensível na LGPD): cada pessoa cadastrada concorda (consentAt) e sai quando quiser
// (remove). O arquivo fica em server/perfis/rostos.json, fora do git.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

// Onde os 5 pontos do rosto ficam num rosto de 112×112 alinhado (o molde do ArcFace, que o SFace usa)
const TEMPLATE = [[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]];
export const SAME_PERSON = 0.363;   // semelhança (cosseno) a partir da qual o SFace diz "mesma pessoa"

export async function loadFaces(dir) {
  let ort;
  try { ort = require('onnxruntime-node'); } catch { return null; }
  const yunetFile = path.join(dir, 'yunet.onnx'), sfaceFile = path.join(dir, 'sface.onnx');
  if (!fs.existsSync(yunetFile) || !fs.existsSync(sfaceFile)) return null;
  const opts = { logSeverityLevel: 3 };
  const detector = await ort.InferenceSession.create(yunetFile, opts);
  const recognizer = await ort.InferenceSession.create(sfaceFile, opts);

  /**
   * Rostos numa imagem RGB (w × h): [{ box: [x, y, l, a] em pixels, score, points: [[x, y] × 5] }], do maior ao menor.
   * Os pontos: olho direito, olho esquerdo, nariz, canto direito e esquerdo da boca (direito da pessoa).
   */
  /**
   * Rostos numa imagem RGB grande: procura em pedaços de 640×640 no tamanho cheio (rosto longe fica com poucos
   * pixels; reduzir a imagem inteira o apagava) e junta o que achou.
   */
  async function detect(rgb, w, h, minScore = 0.8) {
    const S = 640, step = 448;
    const xs = [], ys = [];
    for (let x = 0; ; x += step) { xs.push(Math.min(x, Math.max(0, w - S))); if (x + S >= w) break; }
    for (let y = 0; ; y += step) { ys.push(Math.min(y, Math.max(0, h - S))); if (y + S >= h) break; }
    const all = [];
    for (const y0 of [...new Set(ys)]) for (const x0 of [...new Set(xs)]) all.push(...await detectTile(rgb, w, h, x0, y0, minScore));
    return nms(all, 0.3).sort((a, b) => b.box[2] * b.box[3] - a.box[2] * a.box[3]);
  }

  // Um pedaço de 640×640 a partir de (x0, y0), sem reduzir. O YuNet quer BGR 0–255.
  async function detectTile(rgb, w, h, x0, y0, minScore) {
    const S = 640;
    const input = new Float32Array(3 * S * S);
    for (let y = 0; y < S && y0 + y < h; y++) {
      for (let x = 0; x < S && x0 + x < w; x++) {
        const i = ((y0 + y) * w + x0 + x) * 3, o = y * S + x;
        input[o] = rgb[i + 2]; input[S * S + o] = rgb[i + 1]; input[2 * S * S + o] = rgb[i];
      }
    }
    const out = await detector.run({ input: new ort.Tensor('float32', input, [1, 3, S, S]) });
    const faces = [];
    for (const s of [8, 16, 32]) {
      const cols = S / s, rows = S / s;
      const cls = out[`cls_${s}`].data, obj = out[`obj_${s}`].data, bbox = out[`bbox_${s}`].data, kps = out[`kps_${s}`].data;
      for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
        const i = r * cols + c;
        const score = Math.sqrt(Math.min(1, Math.max(0, cls[i])) * Math.min(1, Math.max(0, obj[i])));
        if (score < minScore) continue;
        const cx = (c + bbox[i * 4]) * s + x0, cy = (r + bbox[i * 4 + 1]) * s + y0;
        const bw = Math.exp(bbox[i * 4 + 2]) * s, bh = Math.exp(bbox[i * 4 + 3]) * s;
        const points = [0, 1, 2, 3, 4].map(n => [(kps[i * 10 + 2 * n] + c) * s + x0, (kps[i * 10 + 2 * n + 1] + r) * s + y0]);
        faces.push({ box: [cx - bw / 2, cy - bh / 2, bw, bh], score, points });
      }
    }
    return faces;
  }

  /** O código do rosto (128 números, já normalizado) e o rosto alinhado 112×112 (RGB), para guardar a foto. */
  async function embed(rgb, w, h, face) {
    const aligned = align(rgb, w, h, face.points);
    // O SFace recebe RGB 0–255 (o OpenCV troca BGR→RGB antes)
    const input = new Float32Array(3 * 112 * 112);
    for (let i = 0; i < 112 * 112; i++) {
      input[i] = aligned[i * 3]; input[112 * 112 + i] = aligned[i * 3 + 1]; input[2 * 112 * 112 + i] = aligned[i * 3 + 2];
    }
    const out = await recognizer.run({ data: new ort.Tensor('float32', input, [1, 3, 112, 112]) });
    const v = Array.from(out.fc1.data);
    const n = Math.hypot(...v) || 1;
    return { vector: v.map(x => x / n), aligned };
  }

  return { detect, embed };
}

/** Semelhança entre dois códigos normalizados (1 = idênticos). */
export const similarity = (a, b) => { let s = 0; for (let i = 0; i < a.length; i++) s += a[i] * b[i]; return s; };

/** Para onde o rosto aponta, pelos pontos: yaw > 0 vira para a direita da imagem, pitch pequeno = queixo para cima. */
export function pose(points) {
  const [re, le, nose] = points;
  const mid = [(re[0] + le[0]) / 2, (re[1] + le[1]) / 2];
  const eyes = Math.hypot(le[0] - re[0], le[1] - re[1]) || 1;
  return { yaw: (nose[0] - mid[0]) / eyes, pitch: (nose[1] - mid[1]) / eyes };
}

function nms(faces, iou) {
  faces.sort((a, b) => b.score - a.score);
  const kept = [];
  for (const f of faces) {
    if (kept.every(k => overlap(k.box, f.box) < iou)) kept.push(f);
  }
  return kept;
}
function overlap(a, b) {
  const x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
  const x2 = Math.min(a[0] + a[2], b[0] + b[2]), y2 = Math.min(a[1] + a[3], b[1] + b[3]);
  const inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  return inter / (a[2] * a[3] + b[2] * b[3] - inter || 1);
}

// Leva os 5 pontos do rosto até o molde (transformação de semelhança: girar, escalar, mover; Umeyama) e amostra a
// imagem: o rosto sai de frente, do mesmo tamanho, como o SFace foi treinado
function align(rgb, w, h, points) {
  const n = 5;
  let sx = 0, sy = 0, dx = 0, dy = 0;
  for (let i = 0; i < n; i++) { sx += points[i][0]; sy += points[i][1]; dx += TEMPLATE[i][0]; dy += TEMPLATE[i][1]; }
  sx /= n; sy /= n; dx /= n; dy /= n;
  let a = 0, b = 0, norm = 0;
  for (let i = 0; i < n; i++) {
    const px = points[i][0] - sx, py = points[i][1] - sy, qx = TEMPLATE[i][0] - dx, qy = TEMPLATE[i][1] - dy;
    a += px * qx + py * qy; b += px * qy - py * qx; norm += px * px + py * py;
  }
  a /= norm || 1; b /= norm || 1;   // [a −b; b a] leva a imagem até o molde
  // Para cada pixel do rosto de saída, de onde ele vem na imagem (a transformação inversa)
  const det = a * a + b * b || 1;
  const out = new Uint8Array(112 * 112 * 3);
  for (let y = 0; y < 112; y++) for (let x = 0; x < 112; x++) {
    const qx = x - dx, qy = y - dy;
    const px = (a * qx + b * qy) / det + sx, py = (-b * qx + a * qy) / det + sy;
    const x0 = Math.floor(px), y0 = Math.floor(py), fx = px - x0, fy = py - y0;
    for (let ch = 0; ch < 3; ch++) {
      const at = (xx, yy) => (xx < 0 || yy < 0 || xx >= w || yy >= h) ? 0 : rgb[(yy * w + xx) * 3 + ch];
      const v = at(x0, y0) * (1 - fx) * (1 - fy) + at(x0 + 1, y0) * fx * (1 - fy) + at(x0, y0 + 1) * (1 - fx) * fy + at(x0 + 1, y0 + 1) * fx * fy;
      out[(y * 112 + x) * 3 + ch] = v;
    }
  }
  return out;
}

// ---------- As pessoas conhecidas ----------
export function createPeople(file) {
  let people = (() => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return []; } })();
  const save = () => { try { fs.writeFileSync(file, JSON.stringify(people)); } catch {} };
  return {
    list: () => people,
    add(name, samples, avatar) {
      const p = { id: crypto.randomBytes(6).toString('hex'), name: String(name).slice(0, 40), consentAt: Date.now(), samples, avatar, lastSeen: 0 };
      people.push(p); save(); return p;
    },
    remove(id) { const n = people.length; people = people.filter(p => p.id !== id); save(); return people.length < n; },
    rename(id, name) { const p = people.find(p => p.id === id); if (p) { p.name = String(name).slice(0, 40); save(); } return !!p; },
    /** Mais uma foto confirmada ("esse é o Deivid"): ajuda a acertar com o tempo. Guarda no máximo 40. */
    learn(id, vector) { const p = people.find(p => p.id === id); if (!p) return false; p.samples = [...p.samples, vector].slice(-40); save(); return true; },
    seen(id) { const p = people.find(p => p.id === id); if (p) { p.lastSeen = Date.now(); save(); } },
    /** Quem é: a pessoa com a foto mais parecida, se passar do limite do SFace. */
    match(vector) {
      let best = null, score = 0;
      for (const p of people) for (const s of p.samples) {
        const sim = similarity(vector, s);
        if (sim > score) { score = sim; best = p; }
      }
      return score >= SAME_PERSON ? { person: best, score } : { person: null, score };
    },
  };
}
