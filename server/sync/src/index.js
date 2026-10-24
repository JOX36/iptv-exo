// JOX3 Sync — guarda y reparte el historial de cada cuenta entre sus dispositivos.
//
// POST /v1/sync  { acc, profile, since, changes: [{ list, item, data, ts, deleted }] }
//   profile = perfil de la casa ("principal", "p…"); "_perfiles" guarda la lista de perfiles.
//   → guarda los cambios (gana el de ts más nuevo) y devuelve lo que cambió después de `since`.
// GET  /         → estado del servidor.
//
// Ver juntos:
// POST /v1/room              → crea una sala y devuelve su código de 6 números.
// GET  /v1/room/<código>/ws  → WebSocket de la sala (órdenes de reproducir, pausar, adelantar…).

const LISTS = new Set(["fav", "recent", "resume", "profile", "vault"]);
const MAX_CHANGES = 500;
const MAX_BODY = 512 * 1024;
const PAGE = 1000;

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      if (url.pathname === "/" && req.method === "GET") return json({ ok: true, app: "jox3-sync", v: 1 });
      if (url.pathname === "/v1/sync" && req.method === "POST") return await sync(req, env);
      if (url.pathname === "/v1/room" && req.method === "POST") return await createRoom(env);
      const m = url.pathname.match(/^\/v1\/room\/(\d{6})\/ws$/);
      if (m) {
        if (req.headers.get("Upgrade") !== "websocket") return json({ error: "se necesita websocket" }, 426);
        return env.ROOMS.get(env.ROOMS.idFromName(m[1])).fetch(new Request("https://room/ws", req));
      }
      return json({ error: "no existe" }, 404);
    } catch (e) {
      return json({ error: "error del servidor" }, 500);
    }
  },
};

async function sync(req, env) {
  const len = Number(req.headers.get("content-length") || 0);
  if (len > MAX_BODY) return json({ error: "demasiado grande" }, 413);
  const text = await req.text();
  if (text.length > MAX_BODY) return json({ error: "demasiado grande" }, 413);
  let body;
  try { body = JSON.parse(text); } catch { return json({ error: "json inválido" }, 400); }

  const acc = String(body.acc || "");
  const profile = String(body.profile || "principal");
  if (!/^[0-9a-f]{64}$/.test(acc)) return json({ error: "cuenta inválida" }, 400);
  if (!/^[\w-]{1,32}$/.test(profile)) return json({ error: "perfil inválido" }, 400);
  const since = Math.max(0, Number(body.since) || 0);
  const changes = Array.isArray(body.changes) ? body.changes.slice(0, MAX_CHANGES) : [];

  const now = Date.now();
  const stmts = [];
  for (const c of changes) {
    const list = String(c.list || "");
    const item = String(c.item || "");
    const ts = Math.floor(Number(c.ts) || 0);
    const deleted = c.deleted ? 1 : 0;
    const data = deleted ? null : (c.data == null ? null : String(c.data));
    if (!LISTS.has(list) || !item || item.length > 100 || ts <= 0 || ts > now + 86400000) continue;
    // "vault": servidores cifrados en el equipo (el servidor nunca ve las contraseñas)
    if (data && data.length > (list === "vault" ? 16000 : 4000)) continue;
    stmts.push(env.DB.prepare(
      `INSERT INTO items (acc, profile, list, item, data, ts, deleted, seq) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
       ON CONFLICT (acc, profile, list, item) DO UPDATE SET
         data = excluded.data, ts = excluded.ts, deleted = excluded.deleted, seq = excluded.seq
       WHERE excluded.ts > items.ts`
    ).bind(acc, profile, list, item, data, ts, deleted, now));
  }
  if (stmts.length) await env.DB.batch(stmts);

  const rows = await env.DB.prepare(
    `SELECT list, item, data, ts, deleted, seq FROM items
     WHERE acc = ?1 AND profile = ?2 AND seq > ?3 ORDER BY seq LIMIT ${PAGE}`
  ).bind(acc, profile, since).all();
  const items = rows.results || [];
  // Si hubo más de una página, el equipo vuelve a pedir desde la última que recibió
  const more = items.length >= PAGE;
  const cursor = more ? items[items.length - 1].seq : now;

  // Limpieza de vez en cuando: borrados viejos (90 días)
  if (Math.random() < 0.02) {
    await env.DB.prepare(`DELETE FROM items WHERE deleted = 1 AND seq < ?1`).bind(now - 90 * 86400000).run();
  }

  return json({
    now: cursor,
    more,
    items: items.map(r => ({ list: r.list, item: r.item, data: r.data, ts: r.ts, deleted: !!r.deleted })),
  });
}

// ───────────────────────── Ver juntos ─────────────────────────

async function createRoom(env) {
  for (let i = 0; i < 8; i++) {
    const code = String(Math.floor(100000 + Math.random() * 900000));
    const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
    const r = await stub.fetch("https://room/init", { method: "POST" });
    if (r.ok) return json({ code });
  }
  return json({ error: "no se pudo crear la sala" }, 503);
}

const ROOM_TTL = 8 * 3600 * 1000;   // la sala se borra 8 h después de la última actividad

export class Room {
  constructor(state, env) { this.state = state; }

  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === "/init") {
      if (await this.state.storage.get("created")) return new Response("ocupada", { status: 409 });
      await this.state.storage.put("created", Date.now());
      await this.state.storage.setAlarm(Date.now() + ROOM_TTL);
      return new Response("ok");
    }
    if (url.pathname === "/ws") {
      const pair = new WebSocketPair();
      const [client, server] = [pair[0], pair[1]];
      if (!(await this.state.storage.get("created"))) {
        server.accept();
        server.close(4004, "La sala no existe o ya terminó");
        return new Response(null, { status: 101, webSocket: client });
      }
      this.state.acceptWebSocket(server);
      server.serializeAttachment({ name: "", emoji: "" });
      // Lo último que pasó en la sala, para quien llega
      const media = await this.state.storage.get("media");
      const st = await this.state.storage.get("state");
      server.send(JSON.stringify({ type: "snapshot", media: media || null, state: st ? this.now(st) : null }));
      await this.state.storage.setAlarm(Date.now() + ROOM_TTL);
      return new Response(null, { status: 101, webSocket: client });
    }
    return new Response("no existe", { status: 404 });
  }

  /** Posición estimada ahora (si estaba reproduciendo, avanzó desde que se envió). */
  now(st) {
    const out = { ...st };
    if (st.playing) out.pos = st.pos + (Date.now() - st.t) * (st.rate || 1);
    out.t = Date.now();
    return out;
  }

  peers() {
    return this.state.getWebSockets().map(ws => ws.deserializeAttachment() || {}).filter(p => p.name);
  }

  broadcast(obj, except) {
    const msg = JSON.stringify(obj);
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue;
      try { ws.send(msg); } catch (e) {}
    }
  }

  async webSocketMessage(ws, raw) {
    if (typeof raw !== "string" || raw.length > 4000) return;
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    const t = Date.now();
    switch (m.type) {
      case "hello": {
        ws.serializeAttachment({ name: String(m.name || "Invitado").slice(0, 20), emoji: String(m.emoji || "🙂").slice(0, 8) });
        this.broadcast({ type: "peers", peers: this.peers() });
        break;
      }
      case "media": {
        const media = { kind: String(m.kind || ""), title: String(m.title || "").slice(0, 200), season: m.season | 0,
          episode: m.episode | 0, image: String(m.image || "").slice(0, 500) };
        await this.state.storage.put("media", media);
        this.broadcast({ type: "media", media, from: (ws.deserializeAttachment() || {}).name || "" }, ws);
        break;
      }
      case "state": case "cmd": {
        const st = { playing: !!m.playing, pos: Math.max(0, Number(m.pos) || 0), rate: Number(m.rate) || 1, t };
        await this.state.storage.put("state", st);
        this.broadcast({ type: m.type, ...st, from: (ws.deserializeAttachment() || {}).name || "" }, ws);
        break;
      }
      case "react": {
        this.broadcast({ type: "react", emoji: String(m.emoji || "").slice(0, 8), from: (ws.deserializeAttachment() || {}).name || "" }, ws);
        break;
      }
      case "ping": { try { ws.send(JSON.stringify({ type: "pong", t })); } catch (e) {} break; }
    }
    await this.state.storage.setAlarm(Date.now() + ROOM_TTL);
  }

  async webSocketClose(ws) {
    try { ws.close(); } catch (e) {}
    const peers = this.state.getWebSockets().filter(w => w !== ws).map(w => w.deserializeAttachment() || {}).filter(p => p.name);
    this.broadcast({ type: "peers", peers }, ws);
  }

  async webSocketError(ws) { await this.webSocketClose(ws); }

  async alarm() {
    for (const ws of this.state.getWebSockets()) { try { ws.close(4000, "La sala terminó"); } catch (e) {} }
    await this.state.storage.deleteAll();
  }
}
