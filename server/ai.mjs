// camera-ai — a IA roda AQUI, uma vez por câmera, e manda o resultado para todas as telas
// (TV com nevtv, celular, navegador). Nenhuma imagem sai do PC.
//
// O melhor detector que estiver disponível:
//   1. RF-DETR medium (ONNX Runtime, licença Apache 2.0: pode ir num produto). Na placa de vídeo pelo
//      DirectML (~40 ms por quadro numa RTX 3060). O modelo vem do server/get-rfdetr.ps1. Na câmera de teste
//      deu nota maior que o YOLO11s à mesma pessoa (0,51 × 0,38, só a cabeça atrás do monitor) e nenhum alarme
//      falso em 10 quadros vazios.
//   2. YOLO11s (ONNX Runtime): ~12 ms, mas os pesos são AGPL-3.0 (em casa, tudo bem; num produto, não).
//      O modelo vem do server/get-yolo.ps1.
//   3. COCO-SSD lite (TensorFlow.js WASM), o mesmo que rodava no navegador (~75 ms, menos preciso).
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
  const rfdetr = await loadRfdetr(path.join(root, 'rfdetr_medium')).catch(e => { console.log('RF-DETR:', e.message); return null; });
  if (rfdetr) return rfdetr;
  const yolo = await loadYolo(path.join(root, 'yolo11s', 'yolo11s.onnx')).catch(e => { console.log('YOLO:', e.message); return null; });
  return yolo || await loadSsd(path.join(root, 'ssdlite_mobilenet_v2'));
}

async function onnxSession(file) {
  let ort;
  try { ort = require('onnxruntime-node'); } catch { return null; }
  try { return { ort, device: 'gpu', session: await ort.InferenceSession.create(file, { executionProviders: ['dml'], logSeverityLevel: 3 }) }; }
  catch { return { ort, device: 'cpu', session: await ort.InferenceSession.create(file, { executionProviders: ['cpu'] }) }; }
}

// ---------- RF-DETR (ONNX Runtime) ----------
// Saídas no estilo DETR: 300 palpites, cada um com a caixa (cx, cy, w, h em fração) e a nota de cada classe
// (logits; o índice é o id COCO, 1 = pessoa). Sem NMS em princípio, mas tirar repetidas não custa nada.
// O quadro entra esticado no quadrado do modelo, sem normalizar (preprocessor_config: do_normalize false,
// a normalização já está dentro do modelo exportado). Testado com e sem: assim deu as melhores notas.
const RFDETR_CLASSES = { 1: 'person', 16: 'bird', 17: 'cat', 18: 'dog', 19: 'horse', 20: 'sheep', 21: 'cow', 22: 'elephant', 23: 'bear', 24: 'zebra', 25: 'giraffe' };
async function loadRfdetr(dir) {
  const file = path.join(dir, 'model.onnx');
  if (!fs.existsSync(file)) return null;
  let size = 576;
  try { size = JSON.parse(fs.readFileSync(path.join(dir, 'preprocessor_config.json'), 'utf8')).size.height || size; } catch {}
  const s = await onnxSession(file);
  if (!s) return null;
  const { ort, session, device } = s;
  const input = new Float32Array(3 * size * size), plane = size * size;
  return {
    name: 'rf-detr-m', device, unsure: 0.5,
    async detect(rgb, w, h) {
      for (let y = 0; y < size; y++) {
        const sy = Math.floor(y * h / size) * w;
        for (let x = 0; x < size; x++) {
          const i = (sy + Math.floor(x * w / size)) * 3, d = y * size + x;
          input[d] = rgb[i] / 255; input[plane + d] = rgb[i + 1] / 255; input[2 * plane + d] = rgb[i + 2] / 255;
        }
      }
      const out = await session.run({ pixel_values: new ort.Tensor('float32', input, [1, 3, size, size]) });
      const boxes = out.pred_boxes.data, logits = out.logits.data, [, q, k] = out.logits.dims;
      const found = [];
      for (let i = 0; i < q; i++) {
        let best = -Infinity, cls = -1;
        for (const c in RFDETR_CLASSES) {
          const l = logits[i * k + +c];
          if (l > best) { best = l; cls = +c; }
        }
        const score = 1 / (1 + Math.exp(-best));
        if (score < 0.3) continue;
        const cx = boxes[i * 4] * w, cy = boxes[i * 4 + 1] * h, bw = boxes[i * 4 + 2] * w, bh = boxes[i * 4 + 3] * h;
        found.push({ cls, score, x: cx - bw / 2, y: cy - bh / 2, w: bw, h: bh });
      }
      return nms(found, 0.45).slice(0, 20).map(f => ({ class: RFDETR_CLASSES[f.cls], score: f.score, bbox: [f.x, f.y, f.w, f.h] }));
    },
  };
}

// ---------- YOLO11 (ONNX Runtime) ----------
async function loadYolo(file) {
  if (!fs.existsSync(file)) return null;
  const s = await onnxSession(file);
  if (!s) return null;
  const { ort, session, device } = s;
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
