// camera-ai — o aviso de movimento da própria câmera (ONVIF PullPoint), sem login.
// A câmera responde o PullMessages na hora (não segura a pergunta), então pergunta-se a cada 1 s.
// Cada resposta repete o estado atual dos tópicos: tns1:VideoSource/MotionAlarm (State) e
// tns1:RuleEngine/CellMotionDetector/Motion (IsMotion). Vale o "ou" dos dois.
// A assinatura vence em 60 s: refaz a cada 50 s (ou na hora, se a câmera esquecer dela).

const ACTION = 'http://www.onvif.org/ver10/events/wsdl/';
const envelope = (to, action, body) => `<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://www.w3.org/2005/08/addressing" xmlns:tev="http://www.onvif.org/ver10/events/wsdl"><s:Header><a:Action s:mustUnderstand="1">${ACTION}${action}</a:Action><a:To s:mustUnderstand="1">${to}</a:To></s:Header><s:Body>${body}</s:Body></s:Envelope>`;

async function post(url, xml) {
  const r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/soap+xml; charset=utf-8' }, body: xml, signal: AbortSignal.timeout(5000) });
  const text = await r.text();
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return text;
}

/** O estado de movimento numa resposta do PullMessages, ou null se ela não falar de movimento. */
export function motionIn(xml) {
  let seen = false, active = false;
  for (const m of xml.matchAll(/<wsnt:NotificationMessage>([\s\S]*?)<\/wsnt:NotificationMessage>/g)) {
    const topic = m[1].match(/<wsnt:Topic[^>]*>([^<]+)/)?.[1] || '';
    if (!/MotionAlarm|CellMotionDetector/.test(topic)) continue;
    const value = m[1].match(/<tt:Data>[\s\S]*?Name="(?:State|IsMotion)"\s+Value="([^"]+)"/)?.[1];
    if (value == null) continue;
    seen = true;
    active ||= value === 'true';
  }
  return seen ? active : null;
}

/** Fica de olho no movimento da câmera em host(); chama onChange(active) quando muda. */
export function watchMotion({ host, onChange, onError = () => {} }) {
  let stopped = false, address = null, subscribedAt = 0, active = null, timer = null, failures = 0;

  async function subscribe(h) {
    const service = `http://${h}:8899/onvif/event_service`;
    const xml = await post(service, envelope(service, 'EventPortType/CreatePullPointSubscriptionRequest',
      '<tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT60S</tev:InitialTerminationTime></tev:CreatePullPointSubscription>'));
    const a = xml.match(/Address>\s*([^<\s]+)/)?.[1];
    if (!a) throw new Error('a câmera não deu o endereço da assinatura');
    // Algumas câmeras respondem com o IP de outra interface: troca pelo que estamos usando
    address = a.replace(/^(https?:\/\/)[^:/]+/, `$1${h}`);
    subscribedAt = Date.now();
  }

  async function tick() {
    if (stopped) return;
    const h = host();
    try {
      if (!h) throw new Error('sem câmera');
      if (!address || Date.now() - subscribedAt > 50000) await subscribe(h);
      const xml = await post(address, envelope(address, 'PullPointSubscription/PullMessagesRequest',
        '<tev:PullMessages><tev:Timeout>PT1S</tev:Timeout><tev:MessageLimit>20</tev:MessageLimit></tev:PullMessages>'));
      failures = 0;
      const now = motionIn(xml);
      if (now !== null && now !== active) { active = now; onChange(now); }
    } catch (e) {
      address = null;   // na próxima, assina de novo (a câmera reiniciou ou esqueceu a assinatura)
      if (++failures === 3 && h) onError(e);
    }
    timer = setTimeout(tick, failures ? Math.min(15000, 1000 * 2 ** failures) : 1000);
  }
  tick();

  return {
    get active() { return !!active; },
    stop() { stopped = true; clearTimeout(timer); },
  };
}
