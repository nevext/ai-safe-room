// Endereço do computador na internet, sem abrir porta no roteador.
// O cloudflared (server/bin) abre um túnel rápido da Cloudflare até o camera-ai nesta máquina.
// O endereço (https://….trycloudflare.com) muda quando o túnel reabre; o PC grava sozinho em reach/{casa}, assinado.
import { spawn } from 'node:child_process';
import fs from 'node:fs';

/**
 * @param {{ port: number, bin: string, onUrl: (url: string|null) => void, getKey: () => string,
 *           getHome: () => string|null, writeReach: (homeId: string, url: string, key: string) => Promise<boolean> }} o
 */
export function startAway(o) {
  if (!fs.existsSync(o.bin)) {
    console.log('Ver de fora: sem cloudflared (rode server/get-cloudflared.ps1). O app só alcança o computador na rede de casa.');
    return { stop() {} };
  }
  let child = null;
  let url = null;
  let stopping = false;
  let published = null;

  async function publish(next) {
    url = next;
    o.onUrl(next);
    const home = o.getHome(), key = o.getKey();
    const mark = `${home}|${next}`;
    if (!next || !home || !key || mark === published) return;
    if (await o.writeReach(home, next, key).catch(() => false)) {
      published = mark;
      console.log(`${new Date().toLocaleTimeString('pt-BR')} Ver de fora: endereço publicado na conta`);
    }
  }

  function start() {
    if (stopping) return;
    const c = spawn(o.bin, ['tunnel', '--no-autoupdate', '--url', `http://127.0.0.1:${o.port}`], { stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true });
    child = c;
    let buf = '';
    c.stderr.on('data', (d) => {
      buf += d.toString();
      const m = buf.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
      if (m && m[0] !== url) {
        console.log(`${new Date().toLocaleTimeString('pt-BR')} Ver de fora: ${m[0]}`);
        publish(m[0]);
      }
      if (buf.length > 8000) buf = buf.slice(-2000);
    });
    c.on('exit', () => {
      if (child === c) child = null;
      if (url) { url = null; o.onUrl(null); }
      if (!stopping) setTimeout(start, 5000);
    });
  }

  start();
  // Again when the last try failed (no internet yet) or the computer was linked to another house
  const beat = setInterval(() => { if (!stopping && url) publish(url); }, 30_000);
  return { stop() { stopping = true; clearInterval(beat); child?.kill(); } };
}
