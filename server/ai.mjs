// camera-ai — a IA roda AQUI, uma vez por câmera, e manda o resultado para todas as telas
// (TV com nevtv, celular, navegador). Nenhuma imagem sai do PC.
//
// Dois detectores, o melhor que estiver disponível:
//   1. YOLO11s (ONNX Runtime): bem mais preciso. Na placa de vídeo pelo DirectML (~12 ms por quadro numa
//      RTX 3060), senão no processador (~150 ms). O modelo vem do server/get-yolo.ps1.
//   2. COCO-SSD lite (TensorFlow.js WASM), o mesmo que rodava no navegador (~75 ms, menos preciso).
// Sem nenhum dos dois (sem npm install), loadModel() devolve null e o resto segue funcionando sem IA.

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const SSD_URL = 'https://storage.googleapis.com/tfjs-models/savedmodel/ssdlite_mobilenet_v2/';

// Só pessoa e animais. label em pt/en para qualquer tela mostrar.
const PERSON = { pt: 'Pessoa', en: 'Person' };
const ANIMALS = {
  cat: { pt: 'Gato', en: 'Cat' }, dog: { pt: 'Cachorro', en: 'Dog' }, bird: { pt: 'Pássaro', en: 'Bird' },
  horse: { pt: 'Cavalo', en: 'Horse' }, sheep: { pt: 'Ovelha', en: 'Sheep' }, cow: { pt: 'Vaca', en: 'Cow' },
  elephant: { pt: 'Elefante', en: 'Elephant' }, bear: { pt: 'Urso', en: 'Bear' }, zebra: { pt: 'Zebra', en: 'Zebra' },
  giraffe: { pt: 'Girafa', en: 'Giraffe' },
};
// Índices COCO das classes que interessam (o YOLO devolve as 80)
const YOLO_CLASSES = { 0: 'person', 14: 'bird', 15: 'cat', 16: 'dog', 17: 'horse', 18: 'sheep', 19: 'cow', 20: 'elephant', 21: 'bear', 22: 'zebra', 23: 'giraffe' };

/**
 * O melhor detector disponível: { name, device, unsure, detect(rgb, w, h) → [{class, score, bbox:[x,y,w,h] em px]} }.
 * `root` é server/models.
 */
export async function loadModel(root) {
  const yolo = await loadYolo(path.join(root, 'yolo11s', 'yolo11s.onnx')).catch(e => { console.log('YOLO:', e.message); return null; });
  return yolo || await loadSsd(path.join(root, 'ssdlite_mobilenet_v2'));
}

// ---------- YOLO11 (ONNX Runtime) ----------
async function loadYolo(file) {
  if (!fs.existsSync(file)) return null;
  let ort;
  try { ort = require('onnxruntime-node'); } catch { return null; }
  let session = null, device = 'gpu';
  try { session = await ort.InferenceSession.create(file, { executionProviders: ['dml'] }); }
  catch { device = 'cpu'; session = await ort.InferenceSession.create(file, { executionProviders: ['cpu'] }); }
  const [, , H, W] = session.inputMetadata?.[0]?.shape || [1, 3, 736, 640];   // exportado com imgsz=(736, 640)
  const input = new Float32Array(3 * H * W);
  const plane = H * W;
  return {
    name: 'yolo11s', device, unsure: 0.5,
    async detect(rgb, w, h) {
      // Cabe o quadro no tamanho do modelo (encostado em cima/esquerda); o resto fica cinza 114
      const scale = Math.min(W / w, H / h), nw = Math.round(w * scale), nh = Math.round(h * scale);
      input.fill(114 / 255);
      for (let y = 0; y < nh; y++) {
        const sy = Math.min(h - 1, Math.floor(y / scale)) * w;
        for (let x = 0; x < nw; x++) {
          const s = (sy + Math.min(w - 1, Math.floor(x / scale))) * 3, d = y * W + x;
          input[d] = rgb[s] / 255; input[plane + d] = rgb[s + 1] / 255; input[2 * plane + d] = rgb[s + 2] / 255;
        }
      }
      const out = await session.run({ [session.inputNames[0]]: new ort.Tensor('float32', input, [1, 3, H, W]) });
      const o = out[session.outputNames[0]], data = o.data, n = o.dims[2];   // [1, 84, n]: cx, cy, w, h, 80 classes
      const found = [];
      for (let i = 0; i < n; i++) {
        let best = 0, cls = -1;
        for (const c in YOLO_CLASSES) {
          const sc = data[(4 + +c) * n + i];
          if (sc > best) { best = sc; cls = +c; }
        }
        if (best < 0.3) continue;
        const cx = data[i], cy = data[n + i], bw = data[2 * n + i], bh = data[3 * n + i];
        found.push({ cls, score: best, x: (cx - bw / 2) / scale, y: (cy - bh / 2) / scale, w: bw / scale, h: bh / scale });
      }
      return nms(found, 0.45).slice(0, 20).map(f => ({ class: YOLO_CLASSES[f.cls], score: f.score, bbox: [f.x, f.y, f.w, f.h] }));
    },
  };
}

// Tira as caixas repetidas da mesma coisa (sobreposição > limite, mesma classe), fica a de maior nota.
function nms(boxes, limit) {
  boxes.sort((a, b) => b.score - a.score);
  const keep = [];
  for (const b of boxes) {
    if (keep.every(k => k.cls !== b.cls || iou(k, b) <= limit)) keep.push(b);
  }
  return keep;
}
function iou(a, b) {
  const x1 = Math.max(a.x, b.x), y1 = Math.max(a.y, b.y), x2 = Math.min(a.x + a.w, b.x + b.w), y2 = Math.min(a.y + a.h, b.y + b.h);
  const inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  return inter / (a.w * a.h + b.w * b.h - inter || 1);
}

// ---------- COCO-SSD lite (TensorFlow.js WASM) ----------
async function loadSsd(dir) {
  let tf, coco;
  try {
    tf = await import('@tensorflow/tfjs');
    await import('@tensorflow/tfjs-backend-wasm');
    coco = await import('@tensorflow-models/coco-ssd');
  } catch {
    return null;
  }
  await tf.setBackend('wasm');
  await tf.ready();
  // Baixa na 1ª vez e guarda em `dir`; depois funciona sem internet
  const json = await cached(dir, 'model.json', () => fetchBuffer(SSD_URL + 'model.json'));
  const spec = JSON.parse(json.toString('utf8'));
  const shards = [];
  for (const group of spec.weightsManifest) {
    for (const p of group.paths) shards.push(await cached(dir, p, () => fetchBuffer(SSD_URL + p)));
  }
  const all = Buffer.concat(shards);
  const { weightsManifest, ...rest } = spec;
  const model = await coco.load({ base: 'lite_mobilenet_v2', modelUrl: tf.io.fromMemory({
    ...rest,
    weightSpecs: weightsManifest.flatMap(g => g.weights),
    weightData: all.buffer.slice(all.byteOffset, all.byteOffset + all.byteLength),
  }) });
  return {
    name: 'coco-ssd', device: 'cpu', unsure: 0.6,
    async detect(rgb, w, h) {
      const image = tf.tensor3d(new Uint8Array(rgb.buffer, rgb.byteOffset, w * h * 3), [h, w, 3]);
      try { return await model.detect(image, 20, 0.4); } finally { image.dispose(); }
    },
  };
}

async function cached(dir, name, get) {
  const file = path.join(dir, name);
  if (fs.existsSync(file)) return fs.readFileSync(file);
  const data = await get();
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(file, data);
  return data;
}

async function fetchBuffer(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(url + ' → HTTP ' + r.status);
  return Buffer.from(await r.arrayBuffer());
}

/**
 * Analisa os quadros que chegam (RGB) e chama onResult com o que viu. Se chegar quadro enquanto
 * analisa, fica só o mais novo. Caixas em fração do quadro inteiro (0..1), assim cada tela recorta e
 * mapeia para o que está mostrando (lente de cima, de baixo ou as duas). Devolve push(frame, w, h).
 */
export function analyzer({ model, onResult }) {
  let pending = null, busy = false;
  const analyze = async () => {
    if (busy || !pending) return;
    busy = true;
    const { frame, width, height } = pending;
    pending = null;
    try {
      const preds = await model.detect(frame, width, height);
      const items = [];
      for (const p of preds) {
        const label = p.class === 'person' ? PERSON : ANIMALS[p.class];
        if (!label) continue;
        const [x, y, w, h] = p.bbox;
        items.push({
          kind: p.class === 'person' ? 'person' : 'animal', class: p.class, label,
          score: Math.round(p.score * 100) / 100, uncertain: p.score < model.unsure,
          box: [x / width, y / height, w / width, h / height].map(v => Math.round(Math.max(0, Math.min(1, v)) * 1000) / 1000),
        });
      }
      onResult({ t: Date.now(), items });
    } catch (e) {
      console.log('IA:', e.message);
    } finally {
      busy = false;
      if (pending) analyze();
    }
  };
  return (frame, width, height) => { pending = { frame, width, height }; analyze(); };
}
