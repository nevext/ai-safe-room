import net from 'node:net';
import crypto from 'node:crypto';

const HEADER = 20;
const LOGIN = 1000;
const KEEPALIVE = 1006;

export function xmHash(password = '') {
  const digest = crypto.createHash('md5').update(password).digest();
  const chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
  return Array.from({ length: 8 }, (_, i) => chars[(digest[i * 2] + digest[i * 2 + 1]) % 62]).join('');
}

/** Open one serialized DVRIP/Sofia control session and authenticate it. */
export async function dvrip(host, user = 'admin', password = '', port = 34567) {
  const socket = net.createConnection({ host, port });
  socket.setTimeout(30000);

  let session = 0;
  let sequence = 0;
  let buffer = Buffer.alloc(0);
  let pending = [];
  let queue = Promise.resolve();

  const failAll = error => {
    for (const item of pending.splice(0)) item.reject(error);
  };

  socket.on('data', chunk => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= HEADER) {
      if (buffer[0] !== 0xff) {
        const marker = buffer.indexOf(0xff, 1);
        buffer = marker < 0 ? Buffer.alloc(0) : buffer.subarray(marker);
        continue;
      }
      const size = buffer.readUInt32LE(16);
      if (size > 1024 * 1024) {
        failAll(new Error('DVRIP respondeu com um pacote grande demais'));
        socket.destroy();
        return;
      }
      if (buffer.length < HEADER + size) return;

      const header = buffer.subarray(0, HEADER);
      const body = buffer.subarray(HEADER, HEADER + size);
      buffer = buffer.subarray(HEADER + size);
      session = header.readUInt32LE(4) || session;
      const messageId = header.readUInt16LE(14);

      const item = pending.findIndex(waiter => waiter.messageId === messageId);
      if (item < 0) continue;
      const waiter = pending.splice(item, 1)[0];
      try {
        const reply = JSON.parse(body.toString('utf8').replace(/[\0\n\r]+$/, ''));
        if (!session && reply.SessionID) session = Number.parseInt(reply.SessionID, 16) >>> 0;
        waiter.resolve(reply);
      } catch {
        waiter.reject(new Error('DVRIP retornou JSON inválido'));
      }
    }
  });

  socket.on('error', failAll);
  socket.on('timeout', () => {
    failAll(new Error('A câmera não respondeu pelo DVRIP'));
    socket.destroy();
  });
  socket.on('close', () => failAll(new Error('A sessão DVRIP foi encerrada')));

  await new Promise((resolve, reject) => {
    socket.once('connect', resolve);
    socket.once('error', reject);
  });

  const send = (messageId, payload) => new Promise((resolve, reject) => {
    const json = Buffer.from(JSON.stringify(payload) + '\n\0', 'utf8');
    const header = Buffer.alloc(HEADER);
    header[0] = 0xff;
    header[1] = 0;
    header.writeUInt32LE(session, 4);
    header.writeUInt32LE(sequence++, 8);
    header[12] = 1;
    header[13] = 0;
    header.writeUInt16LE(messageId, 14);
    header.writeUInt32LE(json.length, 16);
    pending.push({ messageId: messageId + 1, resolve, reject });
    socket.write(Buffer.concat([header, json]), error => {
      if (error) {
        const index = pending.findIndex(item => item.resolve === resolve);
        if (index >= 0) pending.splice(index, 1);
        reject(error);
      }
    });
  });

  const request = (messageId, payload) => {
    const operation = queue.then(() => send(messageId, { ...payload, SessionID: sessionHex() }));
    queue = operation.catch(() => {});
    return operation;
  };
  function sessionHex() {
    return '0x' + session.toString(16).padStart(8, '0');
  }

  try {
    const login = await send(LOGIN, {
      EncryptType: 'MD5',
      LoginType: 'DVRIP-Web',
      PassWord: xmHash(password),
      UserName: user,
    });
    if (login.Ret !== 100) throw new Error('Login DVRIP recusado (' + (login.Ret ?? 'sem código') + ')');

    const keepalive = setInterval(() => {
      request(KEEPALIVE, { Name: 'KeepAlive', SessionID: sessionHex() }).catch(() => {});
    }, 15000);
    keepalive.unref?.();

    return {
      get sessionId() { return sessionHex(); },
      request,
      close() {
        clearInterval(keepalive);
        socket.end();
      },
    };
  } catch (error) {
    socket.destroy();
    throw error;
  }
}
