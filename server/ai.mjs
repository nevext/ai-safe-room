// camera-ai — a IA roda AQUI, uma vez por câmera, e manda o resultado para todas as telas
// (TV com nevtv, celular, navegador). Mesmo modelo que rodava no navegador: COCO-SSD lite
// (TensorFlow.js com backend WASM, sem compilar nada). Nenhuma imagem sai do PC.
//
// Sem as dependências (npm install), loadModel() devolve null e o resto do camera-ai segue
// funcionando sem IA.

import fs from 'node:fs';
import path from 'node:path';

const MODEL_URL = 'https://storage.googleapis.com/tfjs-models/savedmodel/ssdlite_mobilenet_v2/';

// Só pessoa e animais, como no navegador. label em pt/en para qualquer tela mostrar.
const PERSON = { pt: 'Pessoa', en: 'Person' };
const ANIMALS = {
  cat: { pt: 'Gato', en: 'Cat' }, dog: { pt: 'Cachorro', en: 'Dog' }, bird: { pt: 'Pássaro', en: 'Bird' },
  horse: { pt: 'Cavalo', en: 'Horse' }, sheep: { pt: 'Ovelha', en: 'Sheep' }, cow: { pt: 'Vaca', en: 'Cow' },
  elephant: { pt: 'Elefante', en: 'Elephant' }, bear: { pt: 'Urso', en: 'Bear' }, zebra: { pt: 'Zebra', en: 'Zebra' },
  giraffe: { pt: 'Girafa', en: 'Giraffe' },
};

let tf = null;

/** Carrega o modelo (baixa na 1ª vez e guarda em `dir`, depois funciona sem internet). */
export async function loadModel(dir) {
  let coco;
  try {
    tf = await import('@tensorflow/tfjs');
    await import('@tensorflow/tfjs-backend-wasm');
    coco = await import('@tensorflow-models/coco-ssd');
  } catch {
    return null;   // sem npm install: sem IA
  }
  await tf.setBackend('wasm');
  await tf.ready();
  const json = await cached(dir, 'model.json', () => fetchBuffer(MODEL_URL + 'model.json'));
  const model = JSON.parse(json.toString('utf8'));
  const shards = [];
  for (const group of model.weightsManifest) {
    for (const p of group.paths) shards.push(await cached(dir, p, () => fetchBuffer(MODEL_URL + p)));
  }
  const all = Buffer.concat(shards);
  const { weightsManifest, ...rest } = model;
  const handler = tf.io.fromMemory({
    ...rest,
    weightSpecs: weightsManifest.flatMap(g => g.weights),
    weightData: all.buffer.slice(all.byteOffset, all.byteOffset + all.byteLength),
  });
  return coco.load({ base: 'lite_mobilenet_v2', modelUrl: handler });
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
      const image = tf.tensor3d(new Uint8Array(frame.buffer, frame.byteOffset, width * height * 3), [height, width, 3]);
      const preds = await model.detect(image, 20, 0.4);
      image.dispose();
      const items = [];
      for (const p of preds) {
        const label = p.class === 'person' ? PERSON : ANIMALS[p.class];
        if (!label) continue;
        const [x, y, w, h] = p.bbox;
        items.push({
          kind: p.class === 'person' ? 'person' : 'animal', class: p.class, label,
          score: Math.round(p.score * 100) / 100, uncertain: p.score < 0.6,
          box: [x / width, y / height, w / width, h / height].map(v => Math.round(v * 1000) / 1000),
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
