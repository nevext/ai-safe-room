// A conta Google do app (Firebase Auth do projeto ai-safe-room) no camera-ai.
//
// O celular manda "Authorization: Bearer <token do Firebase>". Aqui: confere a assinatura do Google (RS256, com os
// certificados públicos do Firebase), o projeto e a validade; e lê a casa no Firestore com o próprio token da pessoa
// (as regras só deixam quem é da casa ler) para saber o papel dela. Sem chave de serviço nem segredo no PC.
import crypto from 'node:crypto';

const PROJECT = 'ai-safe-room';
const CERTS = 'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com';
let certs = { keys: {}, until: 0 };

async function publicKey(kid) {
  if (Date.now() > certs.until || !certs.keys[kid]) {
    const r = await fetch(CERTS);
    const age = +(/max-age=(\d+)/.exec(r.headers.get('cache-control') || '')?.[1] || 3600);
    certs = { keys: await r.json(), until: Date.now() + age * 1000 };
  }
  const pem = certs.keys[kid];
  return pem ? new crypto.X509Certificate(pem).publicKey : null;
}

const b64 = (s) => Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');

/** { uid, email, name } se o token for do Google, deste projeto e válido; null se não. */
export async function verifyToken(token) {
  try {
    const [h, p, sig] = String(token).split('.');
    const head = JSON.parse(b64(h)), body = JSON.parse(b64(p));
    if (head.alg !== 'RS256') return null;
    const now = Date.now() / 1000;
    if (body.aud !== PROJECT || body.iss !== `https://securetoken.google.com/${PROJECT}` || !body.sub || body.exp < now || body.iat > now + 300) return null;
    const key = await publicKey(head.kid);
    if (!key || !crypto.verify('RSA-SHA256', Buffer.from(`${h}.${p}`), key, b64(sig))) return null;
    return { uid: body.sub, email: (body.email || '').toLowerCase(), name: body.name || body.email || 'Conta Google' };
  } catch { return null; }
}

// O valor de um campo do Firestore (REST) em JS simples
function plain(v) {
  if (!v) return null;
  if ('stringValue' in v) return v.stringValue;
  if ('integerValue' in v) return +v.integerValue;
  if ('doubleValue' in v) return v.doubleValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('nullValue' in v) return null;
  if ('arrayValue' in v) return (v.arrayValue.values || []).map(plain);
  if ('mapValue' in v) return Object.fromEntries(Object.entries(v.mapValue.fields || {}).map(([k, x]) => [k, plain(x)]));
  return null;
}

/** A casa, lida com o token de quem pede (as regras do Firestore decidem se pode). */
export async function readHome(homeId, token) {
  const r = await fetch(`https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/homes/${encodeURIComponent(homeId)}`,
    { headers: { authorization: `Bearer ${token}` } });
  // 403/404: really not this person's house. Anything else (Firebase slow, no internet): couldn't check now
  if (r.status === 403 || r.status === 404) return null;
  if (r.status !== 200) throw new Error(`Firestore ${r.status}`);
  const doc = await r.json();
  return Object.fromEntries(Object.entries(doc.fields || {}).map(([k, v]) => [k, plain(v)]));
}

/**
 * Quem é a conta nesta casa: { id, name, email, role, until } ou null (não é da casa, convidado vencido, token
 * inválido). Guarda por 1 minuto para não perguntar ao Google a cada pedido.
 */
const cache = new Map();
const lastGood = new Map();   // uid → { at, who }: the last time the house said who this is
const checkGuest = (w) => (w && !(w.role === 'guest' && w.until && Date.now() > w.until) ? w : null);
export async function accountOf(token, homeId) {
  if (!homeId || !token) return null;
  const hit = cache.get(token);
  if (hit && Date.now() - hit.at < 60000) return hit.who;
  const user = await verifyToken(token);
  if (!user) console.log(`${new Date().toLocaleTimeString('pt-BR')} Conta Google: token recusado (vencido ou de outro projeto)`);
  let who = null;
  if (user) {
    let home;
    try { home = await readHome(homeId, token); }
    catch (e) {
      console.log(`${new Date().toLocaleTimeString('pt-BR')} Conta Google: não deu para conferir a casa agora (${e.message}); vale a última resposta`);
      // Couldn't check right now: the last answer for this person (a day at most) instead of locking the owner out
      const g = lastGood.get(user.uid);
      return g && Date.now() - g.at < 86400000 ? checkGuest(g.who) : null;
    }
    const m = home?.members?.[user.uid];
    if (m) who = checkGuest({ id: 'g:' + user.uid, uid: user.uid, name: m.name || user.name, email: user.email, role: m.role || 'member', until: m.until || null, google: true });
    if (who) lastGood.set(user.uid, { at: Date.now(), who }); else lastGood.delete(user.uid);
  }
  cache.set(token, { at: Date.now(), who });
  if (cache.size > 200) cache.delete(cache.keys().next().value);
  return who;
}

/**
 * Publica o endereço de fora (o túnel) em reach/{casa}, sem conta nenhuma: o PC grava sozinho, mesmo logo depois de
 * reiniciar, sem esperar um celular em casa. Quem souber o id da casa pode sobrescrever, por isso o endereço vai
 * assinado (HMAC) com a chave da casa, que só a família lê (homes/{casa}.hub.reachKey): o celular confere antes de usar.
 */
export async function writeReach(homeId, url, reachKey) {
  const sig = crypto.createHmac('sha256', reachKey).update(url).digest('hex');
  const r = await fetch(`https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/reach/${encodeURIComponent(homeId)}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ fields: { url: { stringValue: url }, at: { timestampValue: new Date().toISOString() }, sig: { stringValue: sig } } }),
  });
  if (r.status !== 200) console.log(`Ver de fora: o Firebase recusou o endereço (${r.status}).`);
  return r.status === 200;
}

/** Põe a chave da casa em homes/{casa}.hub.reachKey, com o token de um dono (as regras só deixam dono mudar a casa). */
export async function shareReachKey(homeId, token, reachKey) {
  const r = await fetch(`https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents/homes/${encodeURIComponent(homeId)}?updateMask.fieldPaths=hub.reachKey`, {
    method: 'PATCH',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ fields: { hub: { mapValue: { fields: { reachKey: { stringValue: reachKey } } } } } }),
  });
  return r.status === 200;
}

/** Ligar este PC a uma casa: só um dono dela (conferido no Firestore com o token dele). */
export async function ownerOf(token, homeId) {
  const user = await verifyToken(token);
  if (!user) return null;
  const home = await readHome(homeId, token).catch(() => null);
  return home?.owners?.includes(user.uid) ? { ...user, home } : null;
}
