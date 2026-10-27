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
  if (r.status !== 200) return null;
  const doc = await r.json();
  return Object.fromEntries(Object.entries(doc.fields || {}).map(([k, v]) => [k, plain(v)]));
}

/**
 * Quem é a conta nesta casa: { id, name, email, role, until } ou null (não é da casa, convidado vencido, token
 * inválido). Guarda por 1 minuto para não perguntar ao Google a cada pedido.
 */
const cache = new Map();
export async function accountOf(token, homeId) {
  if (!homeId || !token) return null;
  const hit = cache.get(token);
  if (hit && Date.now() - hit.at < 60000) return hit.who;
  const user = await verifyToken(token);
  let who = null;
  if (user) {
    const home = await readHome(homeId, token).catch(() => null);
    const m = home?.members?.[user.uid];
    if (m && !(m.role === 'guest' && m.until && Date.now() > m.until))
      who = { id: 'g:' + user.uid, uid: user.uid, name: m.name || user.name, email: user.email, role: m.role || 'member', until: m.until || null, google: true };
  }
  cache.set(token, { at: Date.now(), who });
  if (cache.size > 200) cache.delete(cache.keys().next().value);
  return who;
}

/** Ligar este PC a uma casa: só um dono dela (conferido no Firestore com o token dele). */
export async function ownerOf(token, homeId) {
  const user = await verifyToken(token);
  if (!user) return null;
  const home = await readHome(homeId, token).catch(() => null);
  return home?.owners?.includes(user.uid) ? { ...user, home } : null;
}
