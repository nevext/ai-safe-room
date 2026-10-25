// cam-control — detecção de pessoas/animais/objetos por IA, rodando NO NAVEGADOR.
// Modelo COCO-SSD (TensorFlow.js). Nenhuma imagem sai do aparelho.
// Verde = pessoa · Laranja = animal · Cinza = objeto conhecido · Vermelho = detectou mas sem certeza.

(() => {
  const live = document.getElementById('live');
  const canvas = document.getElementById('overlay');
  const ctx = canvas.getContext('2d');
  const row = document.getElementById('miDetect');
  const stateEl = document.getElementById('detState');
  const hudDetect = document.getElementById('hudDetect');
  const toastFn = (m) => { const t = document.getElementById('toast'); if (!t) return; t.textContent = m; t.classList.add('show'); clearTimeout(window.__dt); window.__dt = setTimeout(() => t.classList.remove('show'), 2600); };

  const ANIMAIS = { cat:'Gato', dog:'Cachorro', bird:'Pássaro', horse:'Cavalo', sheep:'Ovelha', cow:'Vaca', elephant:'Elefante', bear:'Urso', zebra:'Zebra', giraffe:'Girafa' };
  const OBJETOS = { 'cell phone':'Celular', laptop:'Notebook', chair:'Cadeira', couch:'Sofá', bottle:'Garrafa', cup:'Copo', book:'Livro', tv:'TV', keyboard:'Teclado', mouse:'Mouse', backpack:'Mochila', handbag:'Bolsa', remote:'Controle', 'dining table':'Mesa', bed:'Cama', 'potted plant':'Planta', car:'Carro', bicycle:'Bicicleta', motorcycle:'Moto' };

  let enabled = false, model = null, loading = false, raf = null;

  function loadScript(src){ return new Promise((ok, err) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = () => err(new Error('falhou ' + src)); document.head.appendChild(s); }); }

  async function ensureModel(){
    if (model) return model;
    if (!window.cocoSsd){
      toastFn('Baixando o modelo de IA…');
      await loadScript('https://cdn.jsdelivr.net/npm/@tensorflow/tfjs@4.22.0/dist/tf.min.js');
      await loadScript('https://cdn.jsdelivr.net/npm/@tensorflow-models/coco-ssd@2.2.3/dist/coco-ssd.min.js');
    }
    model = await window.cocoSsd.load({ base: 'lite_mobilenet_v2' }); // leve, bom p/ celular
    return model;
  }

  // Só interessa pessoa e animais. Objetos genéricos são ignorados (evita chutes).
  let showObjects = false; // ligável no futuro se quiser ver objetos também
  function classify(name){
    if (name === 'person') return { label: 'Pessoa', color: '#00bb7f', kind: 'person' };
    if (ANIMAIS[name])     return { label: ANIMAIS[name], color: '#f99c00', kind: 'animal' };
    if (showObjects)       return { label: OBJETOS[name] || (name[0].toUpperCase() + name.slice(1)), color: '#9aa4b2', kind: 'obj' };
    return null; // ignora
  }

  function fit(){
    const r = live.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = r.width * dpr; canvas.height = r.height * dpr;
    canvas.style.width = r.width + 'px'; canvas.style.height = r.height + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return r;
  }

  function draw(preds, r){
    ctx.clearRect(0, 0, r.width, r.height);
    const nw = live.naturalWidth || r.width, nh = live.naturalHeight || r.height;
    // Câmera dupla: quando mostra uma lente só, mapeia os quadrados pra metade visível.
    const mode = window.__lensMode || 'both';
    const sx = r.width / nw;
    const sy = mode === 'both' ? r.height / nh : r.height / (nh / 2);
    const yOff = mode === 'bottom' ? nh / 2 : 0;
    ctx.lineWidth = 3; ctx.font = '600 14px "Geist Mono", monospace'; ctx.textBaseline = 'top';
    for (const p of preds){
      const info = classify(p.class);
      if (!info) continue;                 // só pessoa/animais
      let [x, y, w, h] = p.bbox;
      if (mode !== 'both'){                 // ignora detecções da outra lente
        const cy = y + h / 2;
        if (mode === 'top' && cy > nh / 2) continue;
        if (mode === 'bottom' && cy < nh / 2) continue;
      }
      const uncertain = p.score < 0.6;
      const color = uncertain ? '#fb2c36' : info.color;
      const dx = x * sx, dw = w * sx, dy = (y - yOff) * sy, dh = h * sy;
      ctx.strokeStyle = color; ctx.strokeRect(dx, dy, dw, dh);
      const text = (uncertain ? '? ' : '') + info.label + ' ' + Math.round(p.score * 100) + '%';
      const tw = ctx.measureText(text).width + 12;
      ctx.fillStyle = color; ctx.fillRect(dx - 1.5, Math.max(0, dy - 22), tw, 22);
      ctx.fillStyle = '#fff'; ctx.fillText(text, dx + 4, Math.max(2, dy - 20));
    }
  }

  async function loop(){
    if (!enabled) return;
    try {
      if (live.naturalWidth){
        const r = fit();
        const preds = await model.detect(live, 20, 0.4);
        window.__lastDet = preds.map(p => p.class + ':' + Math.round(p.score * 100)); // debug
        // mostra pessoa, animais e objetos; vermelho quando pouca confiança
        draw(preds, r);
      }
    } catch {}
    raf = setTimeout(loop, 220); // ~4-5 análises por segundo
  }

  // A IA do camera-ai (server/ai.mjs): a mesma que a TV e o celular recebem. Só se ela não estiver
  // ligada é que o navegador roda a própria.
  async function serverAi(){
    try { const r = await fetch('/api/screen'); const j = await r.json(); return !!(j.ai && j.ai.status === 'on'); }
    catch { return false; }
  }
  async function followServer(){
    try {
      const res = await fetch('/api/screen/events');
      const reader = res.body.getReader(), dec = new TextDecoder();
      let buf = '';
      while (enabled){
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let i;
        while ((i = buf.indexOf('\n')) >= 0){
          const line = buf.slice(0, i); buf = buf.slice(i + 1);
          if (!line) continue;
          const ev = JSON.parse(line);
          if (ev.type !== 'detections' || !live.naturalWidth) continue;
          const nw = live.naturalWidth, nh = live.naturalHeight;
          draw(ev.items.map(it => ({ class: it.class, score: it.score,
            bbox: [it.box[0] * nw, it.box[1] * nh, it.box[2] * nw, it.box[3] * nh] })), fit());
        }
      }
      reader.cancel().catch(() => {});
    } catch {}
    if (enabled) raf = setTimeout(followServer, 3000);   // caiu: tenta de novo
  }

  async function enable(){
    if (loading) return;
    loading = true; stateEl.textContent = 'carregando…';
    try {
      if (await serverAi()){
        enabled = true;
        stateEl.textContent = 'ligado (camera-ai)';
        hudDetect.hidden = false;
        canvas.style.display = 'block';
        toastFn('Detecção ligada pelo camera-ai: pessoa (verde), animal (laranja).');
        followServer();
        return;
      }
      await ensureModel();
      enabled = true;
      stateEl.textContent = 'ligado';
      hudDetect.hidden = false;
      canvas.style.display = 'block';
      toastFn('Detecção ligada: pessoa (verde), animal (laranja).');
      loop();
    } catch (e){
      stateEl.textContent = 'desligado';
      toastFn('Não deu pra carregar a IA: ' + e.message + ' (precisa de internet 1ª vez)');
    } finally { loading = false; }
  }
  function disable(){
    enabled = false; clearTimeout(raf);
    stateEl.textContent = 'desligado'; hudDetect.hidden = true;
    const r = live.getBoundingClientRect(); ctx.clearRect(0, 0, r.width, r.height);
    canvas.style.display = 'none';
  }

  row?.addEventListener('click', () => { enabled ? disable() : enable(); });
  window.addEventListener('resize', () => { if (enabled) fit(); });
})();
