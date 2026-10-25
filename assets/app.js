// cam-control — interface mobile. Fala com o proxy local (server/proxy.mjs),
// que traduz ONVIF/RTSP para uma API HTTP simples.
// Sem ffmpeg: o "ao vivo" é montado puxando JPEG (snapshot) da câmera em loop.
// Com ffmpeg: o proxy serve MJPEG contínuo e a interface usa isso automaticamente.

const cfg = loadCfg();
const $ = (s) => document.querySelector(s);

const live = $('#live');
const overlay = $('#videoOverlay');
const overlayMsg = $('#overlayMsg');
const dot = $('#statusDot');
const hud = { fps: $('#hudFps'), res: $('#hudRes'), mode: $('#hudMode') };

let serverState = { ffmpeg: false, profiles: [], info: {} };
let snapTimer = null, snapBusy = false, frameTimes = [];

function loadCfg(){
  // proxy vazio = mesma origem da página (a própria porta 8787 serve UI + API),
  // assim funciona igual no PC e no celular sem configurar nada.
  // lens: top=Câmera 1, bottom=Câmera 2, both=as duas. quality: auto|alta|fluido.
  let c = { addr:'192.168.0.41', user:'admin', pass:'', proxy:'', stream:'main', lens:'top', quality:'auto' };
  try { Object.assign(c, JSON.parse(localStorage.getItem('cam-control') || '{}')); } catch {}
  return c;
}
function saveCfg(){ try { localStorage.setItem('cam-control', JSON.stringify(cfg)); } catch {} }
function setStatus(state, msg){ dot.dataset.state = state; if (msg){ overlayMsg.textContent = msg; overlay.classList.remove('hidden'); } }

async function api(path, opts){
  const r = await fetch(cfg.proxy + path, { headers:{'content-type':'application/json'}, ...opts });
  const ct = r.headers.get('content-type') || '';
  const data = ct.includes('json') ? await r.json() : await r.blob();
  if (!r.ok) throw new Error((data && data.error) || (path + ' → ' + r.status));
  return data;
}

// --- Conexão ---
async function connect(){
  setStatus('connecting', 'Conectando…');
  $('#camAddr').textContent = cfg.addr;
  stopLive();
  try {
    const info = await api('/api/connect', {
      method:'POST', body: JSON.stringify({ addr: cfg.addr, user: cfg.user, pass: cfg.pass })
    });
    if (!info.ok) return setStatus('bad', info.error || 'Falha ao conectar.');
    serverState = info;
    showInfo(info);
    const prof = info.profiles[cfg.stream === 'main' ? 0 : (info.profiles.length>1?1:0)] || {};
    hud.res.textContent = prof.resolution || '--';
    hud.mode.textContent = info.ffmpeg ? 'MJPEG' : 'JPEG';
    startLive();
    if (info.optimized) toast('Câmera otimizada automaticamente (FPS/bitrate).');
  } catch (e){
    setStatus('bad', 'Sem proxy em ' + cfg.proxy + '. Rode: node server/proxy.mjs  (' + e.message + ')');
  }
}
function showInfo(info){
  const i = info.info || {};
  $('#devInfo').textContent = [i.manufacturer, i.model, i.firmware && ('fw ' + i.firmware)]
    .filter(Boolean).join(' · ') + (info.blankPass ? '  — senha padrão em uso' : '');
  if (i.model) $('#camName').textContent = i.model;
}

// --- Vídeo ao vivo ---
function startLive(){
  if (serverState.ffmpeg){
    live.src = cfg.proxy + '/api/stream?stream=' + cfg.stream + '&t=' + Date.now();
    live.onload = () => { overlay.classList.add('hidden'); setStatus('ok'); };
    live.onerror = () => setStatus('bad', 'Stream caiu.');
  } else {
    pollSnapshot();
  }
}
function stopLive(){ clearTimeout(snapTimer); live.onload = live.onerror = null; }
async function pollSnapshot(){
  if (snapBusy) return;
  snapBusy = true;
  try {
    const blob = await api('/api/snapshot');
    const url = URL.createObjectURL(blob);
    live.onload = () => { URL.revokeObjectURL(url); overlay.classList.add('hidden'); setStatus('ok'); tickFps(); };
    live.src = url;
  } catch (e){ setStatus('bad', 'Sem imagem: ' + e.message); }
  finally {
    snapBusy = false;
    snapTimer = setTimeout(pollSnapshot, 120); // ~8 fps de atualização do painel
  }
}
function tickFps(){
  const now = performance.now(); frameTimes.push(now);
  frameTimes = frameTimes.filter(t => now - t < 1000);
  hud.fps.textContent = frameTimes.length + ' fps';
}

// --- Lente (câmera dupla) + qualidade ---
function currentStream(){
  if (cfg.quality === 'alta') return 'main';
  if (cfg.quality === 'fluido') return 'sub';
  return cfg.lens === 'both' ? 'sub' : 'main'; // auto: lente única em alta, ambas em fluido
}
function applyLensClass(){
  const st = $('#stage');
  st.classList.remove('lens-top','lens-bottom','lens-both');
  st.classList.add('lens-' + cfg.lens);
  window.__lensMode = cfg.lens; // detect.js usa p/ mapear os quadrados
}
function selectLens(lens){
  cfg.lens = lens; cfg.stream = currentStream(); saveCfg();
  document.querySelectorAll('.seg-item[data-lens]').forEach(b => b.classList.toggle('is-active', b.dataset.lens === lens));
  applyLensClass();
  startLive();
}
function cycleQuality(){
  cfg.quality = { auto:'alta', alta:'fluido', fluido:'auto' }[cfg.quality] || 'auto';
  const q = $('#qState'); if (q) q.textContent = cfg.quality === 'auto' ? 'automática' : cfg.quality;
  cfg.stream = currentStream(); saveCfg();
  startLive();
  toast('Qualidade: ' + (cfg.quality === 'auto' ? 'automática' : cfg.quality));
}
// --- PTZ ---
let ptzTimer = null;
function ptz(dir, pressing){
  if (pressing){
    api('/api/ptz', { method:'POST', body: JSON.stringify({ dir, action:'start' }) }).catch(()=>{});
    clearTimeout(ptzTimer); ptzTimer = setTimeout(()=>ptz(dir,false), 1500);
  } else {
    clearTimeout(ptzTimer);
    api('/api/ptz', { method:'POST', body: JSON.stringify({ dir, action:'stop' }) }).catch(()=>{});
  }
}

// --- Ações ---
async function snapshot(){
  try {
    const blob = await api('/api/snapshot');
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = 'cam-' + Date.now() + '.jpg'; a.click();
    URL.revokeObjectURL(url); toast('Foto salva.');
  } catch (e){ toast('Sem foto: ' + e.message); }
}
function toggle(btn, attr, states){ const cur = btn.dataset[attr]; const next = states[(states.indexOf(cur)+1)%states.length]; btn.dataset[attr]=next; return next; }

let toastTimer = null;
function toast(msg){
  let el = $('#toast'); if (!el){ el = document.createElement('div'); el.id='toast'; document.body.appendChild(el); }
  el.textContent = msg; el.classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(()=>el.classList.remove('show'), 2600);
}

// --- Áudio (ouvir o microfone) ---
const audioEl = $('#audioEl');
function toggleListen(btn){
  const on = toggle(btn, 'on', ['off','on']) === 'on';
  if (on){ audioEl.src = cfg.proxy + '/api/audio?t=' + Date.now(); audioEl.play().then(()=>toast('Ouvindo o microfone.')).catch(e=>{ btn.dataset.on='off'; toast('Não deu pra ouvir: ' + e.message); }); }
  else { audioEl.pause(); audioEl.removeAttribute('src'); audioEl.load(); toast('Áudio parado.'); }
}

// --- Imagem (brilho/contraste/saturação/nitidez + dia/noite + WDR) ---
const imgDlg = $('#imaging');
const imgEls = { B:$('#imgB'), C:$('#imgC'), S:$('#imgS'), N:$('#imgN'), wdr:$('#imgWdr') };
const imgOut = { B:$('#oB'), C:$('#oC'), S:$('#oS'), N:$('#oN') };
let imgTimer = null;
async function openImaging(){
  try {
    const s = await api('/api/imaging');
    imgEls.B.value = imgOut.B.value = Math.round(s.brightness);
    imgEls.C.value = imgOut.C.value = Math.round(s.contrast);
    imgEls.S.value = imgOut.S.value = Math.round(s.saturation);
    imgEls.N.value = imgOut.N.value = Math.round(s.sharpness);
    imgEls.wdr.checked = s.wdr === 'ON';
    document.querySelectorAll('#segDayNight .seg-item').forEach(b => b.classList.toggle('is-active', b.dataset.mode === (s.ircut||'AUTO')));
    imgDlg.showModal();
  } catch (e){ toast('Sem ajuste de imagem: ' + e.message); }
}
function pushImaging(body, note){
  clearTimeout(imgTimer);
  imgTimer = setTimeout(async () => {
    try { await api('/api/imaging', { method:'POST', body: JSON.stringify(body) }); if (note) toast(note); }
    catch (e){ toast('Falhou: ' + e.message); }
  }, 180);
}
[['B','brightness'],['C','contrast'],['S','saturation'],['N','sharpness']].forEach(([k,field]) => {
  imgEls[k].oninput = () => { imgOut[k].value = imgEls[k].value; pushImaging({ [field]: +imgEls[k].value }); };
});
imgEls.wdr.onchange = () => pushImaging({ wdr: imgEls.wdr.checked ? 'ON' : 'OFF' }, 'WDR ' + (imgEls.wdr.checked?'ligado':'desligado'));
document.querySelectorAll('#segDayNight .seg-item').forEach(b => b.onclick = () => {
  document.querySelectorAll('#segDayNight .seg-item').forEach(x => x.classList.remove('is-active'));
  b.classList.add('is-active');
  api('/api/daynight', { method:'POST', body: JSON.stringify({ mode: b.dataset.mode }) })
    .then(()=>toast('Modo ' + b.textContent + '.')).catch(e=>toast(e.message));
});

// --- Wiring ---
document.querySelectorAll('.seg-item[data-lens]').forEach(b => b.onclick = () => selectLens(b.dataset.lens));
$('#miQuality')?.addEventListener('click', cycleQuality);
$('#miFollow')?.addEventListener('click', async function(){
  const enabled = this.classList.contains('is-active');
  try {
    if (!enabled && window.__enablePersonDetection && !(await window.__enablePersonDetection()))
      throw new Error('A IA do servidor não está disponível.');
    const state = await api('/api/follow', { method:'POST', body: JSON.stringify({ enabled: !enabled }) });
    this.classList.toggle('is-active', state.enabled);
    this.querySelector('.mr-state').textContent = state.enabled ? 'ligado' : 'desligado';
    if (!state.enabled && state.reason) throw new Error(state.reason);
    toast(state.enabled ? 'Seguir pessoa ligado. Volta ao preset 1 após 12 s sem detecção.' : 'Seguir pessoa desligado.');
  } catch (e) { toast('Não foi possível seguir: ' + e.message); }
});
// O estado do seguir mora no camera-ai (lembrado entre reinícios): mostrar o de verdade
api('/api/follow').then(st => {
  const row = $('#miFollow'); if (!row) return;
  row.classList.toggle('is-active', st.enabled);
  row.querySelector('.mr-state').textContent = st.enabled ? 'ligado' : 'desligado';
}).catch(() => {});

// --- Teste da câmera (vistoria) ---
const vDlg = $('#vistoria');
function showVistoria(v){
  const lv = v.level, lbl = v.label;
  $('#vState').textContent = lv ? 'nível ' + lv : '—';
  $('#vState').dataset.level = lv || '';
  if (!vDlg) return;
  $('#vLevel').textContent = lbl ? lbl.pt : 'Ainda medindo…';
  $('#vLevel').dataset.level = lv || '';
  const n = v.now || v.test?.streams?.main;
  $('#vSub').textContent = v.testing ? 'Testando a câmera (uns 60 s)…'
    : n?.reasons?.length ? n.reasons.join(' · ')
    : v.measuring ? 'Medindo o tempo todo enquanto o vídeo está aberto.' : 'Sem vídeo aberto agora: use “Testar agora”.';
  const m = n ? [['Quadros/s', n.fps + (n.filmedFps ? ' / ' + n.filmedFps : '')], ['Banda', (n.kbps / 1000).toFixed(1) + ' Mbps'],
    ['Rajadas', (n.burstMs / 1000).toFixed(1) + ' s'], ['Perdidos', n.lostPct + '%'], ['Travadas', n.stalls], ['Quedas (1 h)', n.reconnectsLastHour]] : [];
  $('#vMeasures').innerHTML = m.map(([k, x]) => `<div><dt>${k}</dt><dd>${x}</dd></div>`).join('');
  $('#vChecks').innerHTML = (v.checks || []).map(c => `<li class="${c.ok ? '' : 'no'}">${c.label.pt}</li>`).join('');
  $('#vTips').innerHTML = (v.tips || []).map(t => `<li>${t}</li>`).join('');
}
const refreshVistoria = () => api('/api/vistoria').then(showVistoria).catch(() => {});
refreshVistoria(); setInterval(refreshVistoria, 15000);
$('#miVistoria')?.addEventListener('click', () => { menuDlg.close(); refreshVistoria(); vDlg.showModal(); });
$('#vRun')?.addEventListener('click', async function(){
  this.disabled = true; $('#vSub').textContent = 'Testando a câmera (uns 60 s)…';
  try { await api('/api/vistoria', { method:'POST', body: JSON.stringify({ seconds: 30 }) }); }
  catch (e){ toast('Teste falhou: ' + e.message); }
  this.disabled = false; refreshVistoria();
});
$('#btnListen').onclick = (e) => toggleListen(e.currentTarget);
$('#btnImg').onclick = openImaging;
document.querySelectorAll('[data-ptz]').forEach(b => {
  const dir = b.dataset.ptz;
  b.addEventListener('pointerdown', e => { e.preventDefault(); ptz(dir, true); });
  b.addEventListener('pointerup',   () => ptz(dir, false));
  b.addEventListener('pointerleave',() => ptz(dir, false));
});
$('#btnSnap').onclick = snapshot;
$('#btnRec').onclick = async (e) => {
  const on = toggle(e.currentTarget, 'rec', ['off','on']) === 'on';
  try { const r = await api('/api/record', { method:'POST', body: JSON.stringify({ on }) });
    toast(on ? ('Gravando: ' + (r.file||'')) : 'Gravação parada.'); }
  catch (err){ e.currentTarget.dataset.rec='off'; toast(err.message); }
};
// Settings
const dlg = $('#settings');
$('#btnSettings').onclick = () => {
  $('#setAddr').value = cfg.addr; $('#setUser').value = cfg.user;
  $('#setPass').value = cfg.pass; $('#setProxy').value = cfg.proxy;
  dlg.showModal();
};
$('#setSave').onclick = () => {
  cfg.addr = $('#setAddr').value.trim(); cfg.user = $('#setUser').value.trim();
  cfg.pass = $('#setPass').value; cfg.proxy = $('#setProxy').value.trim().replace(/\/$/,''); saveCfg();
};
dlg.addEventListener('close', () => { if (dlg.returnValue === 'save') connect(); });

// --- Menu hambúrguer ---
const menuDlg = $('#menu');
$('#btnMenu').onclick = () => menuDlg.showModal();
$('#miImg').onclick = () => { menuDlg.close(); openImaging(); };
$('#miSettings').onclick = () => { menuDlg.close(); $('#btnSettings').click(); };
$('#miFull').onclick = () => { menuDlg.close(); toggleFullscreen(); };

// --- Tela cheia ---
const stage = $('#stage');
function toggleFullscreen(){
  if (document.fullscreenElement) document.exitFullscreen().catch(()=>{});
  else stage.requestFullscreen?.().catch(e => toast('Tela cheia indisponível: ' + e.message));
}
$('#btnExpand').onclick = toggleFullscreen;

// --- Controles que somem sozinhos (toque no vídeo mostra/esconde) ---
const ctrlEls = () => document.querySelectorAll('.controls-el');
let hideTimer = null;
function showControls(){
  ctrlEls().forEach(el => el.classList.remove('hidden'));
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => ctrlEls().forEach(el => el.classList.add('hidden')), 4000);
}
function controlsVisible(){ return !$('#controls').classList.contains('hidden'); }
stage.addEventListener('click', (e) => {
  // só alterna se tocou na área do vídeo, não num botão
  if (e.target.closest('.glass-btn, .pbtn, .seg-item')) { showControls(); return; }
  if (controlsVisible()) { clearTimeout(hideTimer); ctrlEls().forEach(el => el.classList.add('hidden')); }
  else showControls();
});

// Start
document.querySelectorAll('.seg-item[data-lens]').forEach(b => b.classList.toggle('is-active', b.dataset.lens === cfg.lens));
applyLensClass();
const q0 = $('#qState'); if (q0) q0.textContent = cfg.quality === 'auto' ? 'automática' : cfg.quality;
showControls();
connect();
