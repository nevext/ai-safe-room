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

  const box = document.getElementById('zoomBox');
  const zoomRow = document.getElementById('miAutoZoom');
  const zoomState = document.getElementById('zoomState');

  // Onde a imagem está de verdade dentro do palco: com object-fit cover (uma lente) ela é cortada em
  // cima e embaixo, e a lente de baixo fica deslocada (top:-100%). Medidas de layout (offset*), que o
  // zoom (transform) não altera.
  function geom(){
    const W = box.offsetWidth, H = box.offsetHeight;
    const lw = live.offsetWidth, lh = live.offsetHeight;
    const nw = live.naturalWidth || lw, nh = live.naturalHeight || lh;
    const fitMode = getComputedStyle(live).objectFit;
    const s = fitMode === 'cover' ? Math.max(lw / nw, lh / nh) : fitMode === 'contain' ? Math.min(lw / nw, lh / nh) : null;
    const sx = s ?? lw / nw, sy = s ?? lh / nh;
    return { W, H, nw, nh, sx, sy, ox: live.offsetLeft + (lw - nw * sx) / 2, oy: live.offsetTop + (lh - nh * sy) / 2 };
  }

  function fit(){
    const g = geom();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = g.W * dpr; canvas.height = g.H * dpr;
    canvas.style.width = g.W + 'px'; canvas.style.height = g.H + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return g;
  }

  function draw(preds, g){
    ctx.clearRect(0, 0, g.W, g.H);
    const mode = window.__lensMode || 'both';
    ctx.lineWidth = 3; ctx.font = '600 14px "Geist Mono", monospace'; ctx.textBaseline = 'top';
    const people = [];
    for (const p of preds){
      const info = classify(p.class);
      if (!info) continue;                 // só pessoa/animais
      const [x, y, w, h] = p.bbox;
      if (mode !== 'both'){                 // ignora detecções da outra lente
        const cy = y + h / 2;
        if (mode === 'top' && cy > g.nh / 2) continue;
        if (mode === 'bottom' && cy < g.nh / 2) continue;
      }
      const uncertain = p.score < 0.6;
      const color = uncertain ? '#fb2c36' : info.color;
      const dx = g.ox + x * g.sx, dy = g.oy + y * g.sy, dw = w * g.sx, dh = h * g.sy;
      if (info.kind === 'person' && !uncertain) people.push({ x: dx, y: dy, w: dw, h: dh, score: p.score });
      ctx.strokeStyle = color; ctx.strokeRect(dx, dy, dw, dh);
      const text = (uncertain ? '? ' : '') + info.label + ' ' + Math.round(p.score * 100) + '%';
      const tw = ctx.measureText(text).width + 12;
      ctx.fillStyle = color; ctx.fillRect(dx - 1.5, Math.max(0, dy - 22), tw, 22);
      ctx.fillStyle = '#fff'; ctx.fillText(text, dx + 4, Math.max(2, dy - 20));
    }
    autoZoom(people, g);
  }

  // --- Zoom que acompanha (seguir digital): funciona em qualquer câmera, sem motor ---
  // Amplia em volta das pessoas visíveis e desliza junto. Movimentos pequenos são ignorados (senão a
  // imagem treme com cada análise); sem ninguém por 3 s, volta à imagem inteira.
  let zoomOn = false, zoom = { s: 1, tx: 0, ty: 0 }, lastSeen = 0;
  try { zoomOn = localStorage.getItem('cam-autozoom') === '1'; } catch {}
  function setZoom(z){
    zoom = z;
    box.style.transform = z.s === 1 ? '' : `translate(${z.tx.toFixed(1)}px,${z.ty.toFixed(1)}px) scale(${z.s.toFixed(3)})`;
  }
  function autoZoom(people, g){
    if (!zoomOn) return;
    const now = Date.now();
    if (!people.length){
      if (zoom.s !== 1 && now - lastSeen > 3000) setZoom({ s: 1, tx: 0, ty: 0 });
      return;
    }
    lastSeen = now;
    // Um retângulo que cabe todas as pessoas, com folga
    const x0 = Math.min(...people.map(p => p.x)), y0 = Math.min(...people.map(p => p.y));
    const x1 = Math.max(...people.map(p => p.x + p.w)), y1 = Math.max(...people.map(p => p.y + p.h));
    const s = Math.max(1, Math.min(3, g.W / ((x1 - x0) * 2.2), g.H / ((y1 - y0) * 1.15)));
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
    const next = { s, tx: clamp(g.W / 2 - cx * s, g.W - g.W * s, 0), ty: clamp(g.H / 2 - cy * s, g.H - g.H * s, 0) };
    const moved = Math.hypot(next.tx - zoom.tx, next.ty - zoom.ty) / Math.max(g.W, g.H);
    if (Math.abs(next.s - zoom.s) < 0.2 && moved < 0.06) return;
    setZoom(next);
  }
  function showZoomState(){
    zoomState.textContent = zoomOn ? 'ligado' : 'desligado';
    zoomRow?.classList.toggle('is-active', zoomOn);
  }
  showZoomState();
  zoomRow?.addEventListener('click', async () => {
    zoomOn = !zoomOn;
    try { localStorage.setItem('cam-autozoom', zoomOn ? '1' : '0'); } catch {}
    if (zoomOn && !enabled) await enable();
    if (!zoomOn) setZoom({ s: 1, tx: 0, ty: 0 });
    showZoomState();
    toastFn(zoomOn ? 'Zoom que acompanha ligado: amplia em volta de quem aparecer.' : 'Zoom que acompanha desligado.');
  });
  window.__testZoom = (items) => draw(items, fit());   // para testar sem ninguém na frente da câmera

  async function loop(){
    if (!enabled) return;
    try {
      if (live.naturalWidth){
        const preds = await model.detect(live, 20, 0.4);
        window.__lastDet = preds.map(p => p.class + ':' + Math.round(p.score * 100)); // debug
        // mostra pessoa, animais e objetos; vermelho quando pouca confiança
        draw(preds, fit());
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
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    canvas.style.display = 'none';
    setZoom({ s: 1, tx: 0, ty: 0 });   // sem IA não há quem acompanhar
  }

  window.__enablePersonDetection = async () => {
    if (!enabled) await enable();
    return enabled && await serverAi();
  };

  row?.addEventListener('click', () => { enabled ? disable() : enable(); });
  if (zoomOn) enable();   // o zoom estava ligado da última vez: precisa da IA
  window.addEventListener('resize', () => { if (enabled) fit(); });
})();
