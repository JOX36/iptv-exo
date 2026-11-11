// JOX3 Sync — guarda y reparte el historial de cada cuenta entre sus dispositivos.
//
// POST /v1/sync  { acc, profile, since, changes: [{ list, item, data, ts, deleted }] }
//   profile = perfil de la casa ("principal", "p…"); "_perfiles" guarda la lista de perfiles.
//   → guarda los cambios (gana el de ts más nuevo) y devuelve lo que cambió después de `since`.
// GET  /         → estado del servidor.
// GET  /v1/tz    → zona horaria de quien pregunta (según su conexión), p. ej. America/Bogota.
//
// Saltar intro y créditos (compartido entre quienes usan el mismo servidor IPTV):
// GET  /v1/skip?s=<srv>&series=<id>                          → tiempos por capítulo + plantillas.
// GET  /v1/skip/prints?s=&series=&season=&kind=intro|credits  → huellas para comparar.
// POST /v1/skip { s, series, season, eps, tpl, prints, bad } → guardar lo aprendido.
// /v2/skip: igual, con el método nuevo; además ?name=&also=srv1,srv2 trae las plantillas de esa
//   serie (por nombre) en tus otros servidores, y el POST guarda el nombre.
//
// Ver juntos:
// POST /v1/room              → crea una sala y devuelve su código de 6 números.
// GET  /v1/room/<código>/ws  → WebSocket de la sala (órdenes de reproducir, pausar, adelantar…).
// GET  /v/<código>            → página de la invitación (póster, sinopsis, botón para abrir la app).

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
      // Zona horaria de la conexión (para corregir TV Box con la hora mal puesta)
      if (url.pathname === "/v1/tz" && req.method === "GET")
        return json({ tz: (req.cf && req.cf.timezone) || "", country: (req.cf && req.cf.country) || "" });
      if (url.pathname === "/v1/sync" && req.method === "POST") return await sync(req, env);
      if (url.pathname === "/v1/skip" && req.method === "GET") return await skipGet(url, env, 1);
      if (url.pathname === "/v2/skip" && req.method === "GET") return await skipGet(url, env, 2);
      if (url.pathname === "/v1/skip/prints" && req.method === "GET") return await skipPrints(url, env);
      if (url.pathname === "/v1/skip" && req.method === "POST") return await skipPost(req, env, 1);
      if (url.pathname === "/v2/skip" && req.method === "POST") return await skipPost(req, env, 2);
      if (url.pathname === "/v1/room" && req.method === "POST") return await createRoom(env);
      const inv = url.pathname.match(/^\/v\/(\d{6})$/);
      if (inv && req.method === "GET") return await invitePage(env, inv[1]);
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

// ───────────────────────── Saltar intro y créditos ─────────────────────────

const SRV_RE = /^[0-9a-f]{32}$/;
const ID_RE = /^[\w.-]{1,40}$/;
const B64_RE = /^[A-Za-z0-9+/=]+$/;
const KINDS = new Set(["intro", "credits"]);
const MAX_MS = 6 * 3600 * 1000;
const ms = v => {
  const n = Math.floor(Number(v));
  if (!Number.isFinite(n)) return null;
  if (n === -2) return -2;           // "no tiene" / se reportó mal
  return n >= 0 && n <= MAX_MS ? n : null;
};
const season = v => Math.max(0, Math.min(999, Math.floor(Number(v) || 0)));

const NAME_RE = /^[a-z0-9 ]{1,80}$/;
const T = v => (v === 2 ? { ep: "skip_ep2", tpl: "skip_tpl2" } : { ep: "skip_ep", tpl: "skip_tpl" });

async function skipGet(url, env, v) {
  const s = url.searchParams.get("s") || "", series = url.searchParams.get("series") || "";
  if (!SRV_RE.test(s) || !ID_RE.test(series)) return json({ error: "datos inválidos" }, 400);
  const t = T(v);
  const name = url.searchParams.get("name") || "";
  const also = (url.searchParams.get("also") || "").split(",").filter(x => SRV_RE.test(x) && x !== s).slice(0, 9);
  const q = [
    env.DB.prepare(`SELECT ep, i_s, i_e, c_s, dur FROM ${t.ep} WHERE srv = ?1 AND series = ?2 LIMIT 3000`).bind(s, series).all(),
    env.DB.prepare(`SELECT kind, season, id, fp FROM ${t.tpl} WHERE srv = ?1 AND series = ?2 AND bad < 2 ORDER BY ts DESC LIMIT 40`).bind(s, series).all(),
  ];
  // Plantillas de la misma serie (mismo nombre) en tus otros servidores
  if (v === 2 && NAME_RE.test(name) && also.length) {
    const marks = also.map((_, i) => `?${i + 2}`).join(",");
    q.push(env.DB.prepare(`SELECT kind, season, id, fp FROM skip_tpl2 WHERE name = ?1 AND srv IN (${marks}) AND bad < 2 ORDER BY ts DESC LIMIT 12`)
      .bind(name, ...also).all());
  }
  const [eps, tpl, byName] = await Promise.all(q);
  const tl = r => (r?.results || []).map(x => ({ kind: x.kind, season: x.season, id: x.id, fp: x.fp }));
  return json({
    eps: (eps.results || []).map(r => ({ ep: r.ep, is: r.i_s ?? -1, ie: r.i_e ?? -1, cs: r.c_s ?? -1, d: r.dur ?? 0 })),
    tpl: tl(tpl),
    byName: tl(byName),
  });
}

async function skipPrints(url, env) {
  const s = url.searchParams.get("s") || "", series = url.searchParams.get("series") || "";
  const kind = url.searchParams.get("kind") || "";
  if (!SRV_RE.test(s) || !ID_RE.test(series) || !KINDS.has(kind)) return json({ error: "datos inválidos" }, 400);
  const r = await env.DB.prepare(
    `SELECT ep, first, dur, fp FROM skip_print WHERE srv = ?1 AND series = ?2 AND season = ?3 AND kind = ?4 ORDER BY ts DESC LIMIT 5`
  ).bind(s, series, season(url.searchParams.get("season")), kind).all();
  return json({ prints: (r.results || []).map(x => ({ ep: x.ep, first: x.first, d: x.dur ?? 0, fp: x.fp })) });
}

async function skipPost(req, env, v) {
  const t = T(v);
  const len = Number(req.headers.get("content-length") || 0);
  if (len > MAX_BODY) return json({ error: "demasiado grande" }, 413);
  const text = await req.text();
  if (text.length > MAX_BODY) return json({ error: "demasiado grande" }, 413);
  let b;
  try { b = JSON.parse(text); } catch { return json({ error: "json inválido" }, 400); }
  const s = String(b.s || ""), series = String(b.series || ""), sea = season(b.season);
  if (!SRV_RE.test(s) || !ID_RE.test(series)) return json({ error: "datos inválidos" }, 400);
  const name = NAME_RE.test(String(b.name || "")) ? String(b.name) : "";
  const now = Date.now();
  const st = [];
  const list = v => (Array.isArray(v) ? v.slice(0, 20) : []);

  for (const e of list(b.eps)) {
    const ep = String(e.ep || "");
    if (!ID_RE.test(ep)) continue;
    let is = ms(e.is), ie = ms(e.ie), cs = ms(e.cs);
    // Una intro tiene que tener sentido (15 s a 4 min, dentro de los primeros 15 min)
    if (is != null && is >= 0 && (ie == null || ie - is < 10000 || ie - is > 240000 || is > 900000)) { is = null; ie = null; }
    if (is == null && ie != null && ie !== -2) ie = null;
    const dur = ms(e.d);
    if (is == null && ie == null && cs == null) continue;
    st.push(env.DB.prepare(
      `INSERT INTO ${t.ep} AS e (srv, ep, series, season, i_s, i_e, c_s, dur, ts) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
       ON CONFLICT (srv, ep) DO UPDATE SET
         i_s = CASE WHEN e.i_s = -2 THEN -2 ELSE COALESCE(excluded.i_s, e.i_s) END,
         i_e = CASE WHEN e.i_e = -2 THEN -2 ELSE COALESCE(excluded.i_e, e.i_e) END,
         c_s = CASE WHEN e.c_s = -2 THEN -2 ELSE COALESCE(excluded.c_s, e.c_s) END,
         dur = COALESCE(excluded.dur, e.dur), ts = excluded.ts`
    ).bind(s, ep, series, sea, is, ie, cs, dur && dur > 0 ? dur : null, now));
  }
  for (const tp of list(b.tpl).slice(0, 4)) {
    const kind = String(tp.kind || ""), fp = String(tp.fp || "");
    if (!KINDS.has(kind) || fp.length < 200 || fp.length > 40000 || !B64_RE.test(fp)) continue;
    const id = await shortHash(fp);
    st.push(env.DB.prepare(
      v === 2
        ? `INSERT OR IGNORE INTO skip_tpl2 (srv, series, season, kind, id, fp, name, bad, ts) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?8, 0, ?7)`
        : `INSERT OR IGNORE INTO skip_tpl (srv, series, season, kind, id, fp, bad, ts) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 0, ?7)`
    ).bind(...[s, series, season(tp.season ?? sea), kind, id, fp, now].concat(v === 2 ? [name] : [])));
    // No más de 3 por temporada y tipo (quedan las más nuevas)
    st.push(env.DB.prepare(
      `DELETE FROM ${t.tpl} WHERE srv = ?1 AND series = ?2 AND season = ?3 AND kind = ?4 AND id NOT IN
         (SELECT id FROM ${t.tpl} WHERE srv = ?1 AND series = ?2 AND season = ?3 AND kind = ?4 ORDER BY ts DESC LIMIT 3)`
    ).bind(s, series, season(tp.season ?? sea), kind));
  }
  for (const p of list(b.prints).slice(0, 2)) {
    const kind = String(p.kind || ""), ep = String(p.ep || ""), fp = String(p.fp || "");
    if (!KINDS.has(kind) || !ID_RE.test(ep) || fp.length < 200 || fp.length > 60000 || !B64_RE.test(fp)) continue;
    const first = Math.max(0, Math.min(400000, Math.floor(Number(p.first) || 0)));
    st.push(env.DB.prepare(
      `INSERT INTO skip_print (srv, series, season, kind, ep, first, dur, fp, ts) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
       ON CONFLICT (srv, series, season, kind, ep) DO UPDATE SET first = excluded.first, dur = excluded.dur, fp = excluded.fp, ts = excluded.ts`
    ).bind(s, series, sea, kind, ep, first, ms(p.d), fp, now));
    // Se guardan las 5 más nuevas por temporada y tipo
    st.push(env.DB.prepare(
      `DELETE FROM skip_print WHERE srv = ?1 AND series = ?2 AND season = ?3 AND kind = ?4 AND ep NOT IN
         (SELECT ep FROM skip_print WHERE srv = ?1 AND series = ?2 AND season = ?3 AND kind = ?4 ORDER BY ts DESC LIMIT 5)`
    ).bind(s, series, sea, kind));
  }
  for (const x of list(b.bad).slice(0, 5)) {
    const ep = String(x.ep || ""), kind = String(x.kind || "");
    if (!ID_RE.test(ep) || !KINDS.has(kind)) continue;
    // Ese capítulo queda marcado; si la plantilla de la temporada falla en 2 capítulos, se descarta
    st.push(kind === "intro"
      ? env.DB.prepare(`INSERT INTO ${t.ep} (srv, ep, series, season, i_s, i_e, ts) VALUES (?1, ?2, ?3, ?4, -2, -2, ?5)
          ON CONFLICT (srv, ep) DO UPDATE SET i_s = -2, i_e = -2, ts = excluded.ts`).bind(s, ep, series, sea, now)
      : env.DB.prepare(`INSERT INTO ${t.ep} (srv, ep, series, season, c_s, ts) VALUES (?1, ?2, ?3, ?4, -2, ?5)
          ON CONFLICT (srv, ep) DO UPDATE SET c_s = -2, ts = excluded.ts`).bind(s, ep, series, sea, now));
    st.push(env.DB.prepare(`UPDATE ${t.tpl} SET bad = bad + 1 WHERE srv = ?1 AND series = ?2 AND season = ?3 AND kind = ?4`).bind(s, series, sea, kind));
    st.push(env.DB.prepare(`DELETE FROM ${t.tpl} WHERE srv = ?1 AND series = ?2 AND season = ?3 AND kind = ?4 AND bad >= 2`).bind(s, series, sea, kind));
  }
  if (st.length) await env.DB.batch(st);
  return json({ ok: true, saved: st.length });
}

async function shortHash(text) {
  const d = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].slice(0, 6).map(x => x.toString(16).padStart(2, "0")).join("");
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
    if (url.pathname === "/info") {
      if (!(await this.state.storage.get("created"))) return new Response("no existe", { status: 404 });
      const media = await this.state.storage.get("media");
      return new Response(JSON.stringify({ media: media || null }), { headers: { "content-type": "application/json" } });
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
          episode: m.episode | 0, image: String(m.image || "").slice(0, 500),
          // Para la invitación: solo carátulas de TMDB (nunca la dirección del servidor IPTV)
          poster: /^https:\/\/image\.tmdb\.org\//.test(String(m.poster || "")) ? String(m.poster) : "",
          overview: String(m.overview || "").slice(0, 700), meta: String(m.meta || "").slice(0, 160),
          cast: String(m.cast || "").slice(0, 200) };
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

// ───────────────────────── Invitación ─────────────────────────

const esc = t => String(t || "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function invitePage(env, code) {
  let media = null;
  try {
    const r = await env.ROOMS.get(env.ROOMS.idFromName(code)).fetch("https://room/info");
    if (r.ok) media = (await r.json()).media;
  } catch (e) {}
  const pretty = code.slice(0, 3) + " " + code.slice(3);
  const title = media ? (media.kind === "episode" && media.season ? `${media.title} · T${media.season}E${media.episode}` : media.title) : "Ver juntos";
  const open = `intent://together/${code}#Intent;scheme=jox3;package=com.jox3.iptvexo;end`;
  const desc = media?.overview || "Te invitaron a ver esto al mismo tiempo en JOX3 TV.";
  const html = `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc("Ver juntos: " + title)}</title>
<meta property="og:title" content="${esc("🍿 Veamos juntos: " + title)}">
<meta property="og:description" content="${esc(desc.slice(0, 200))}">
${media?.poster ? `<meta property="og:image" content="${esc(media.poster)}">` : ""}
<meta property="og:type" content="website">
<style>
:root{color-scheme:dark}body{margin:0;background:#070A0F;color:#EAF4FA;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:520px;margin:0 auto;padding:24px 18px 40px}
.poster{width:100%;max-width:300px;display:block;margin:8px auto 18px;border-radius:14px;box-shadow:0 12px 40px #0009}
h1{font-size:26px;margin:0 0 6px}.meta{color:#9DB3C2;font-size:14px;margin:0 0 14px}
p{line-height:1.5;color:#C9D8E2}.cast{color:#9DB3C2;font-size:14px}
.code{margin:22px 0 8px;padding:16px;border:2px solid #00D4FF;border-radius:14px;text-align:center}
.code b{display:block;font-size:34px;letter-spacing:4px;color:#00D4FF}
a.btn{display:block;text-align:center;background:#00D4FF;color:#04121A;text-decoration:none;font-weight:700;padding:16px;border-radius:14px;margin-top:14px;font-size:17px}
small{display:block;color:#7F95A5;text-align:center;margin-top:12px}
</style></head><body><main>
<div style="color:#00D4FF;font-weight:700;letter-spacing:2px;font-size:12px">👥 VER JUNTOS · JOX3 TV</div>
${media?.poster ? `<img class="poster" src="${esc(media.poster)}" alt="">` : ""}
<h1>${esc(title)}</h1>
${media?.meta ? `<div class="meta">${esc(media.meta)}</div>` : ""}
${media?.overview ? `<p>${esc(media.overview)}</p>` : (media ? "" : "<p>Esta sala ya terminó o el código no existe.</p>")}
${media?.cast ? `<div class="cast">🎭 ${esc(media.cast)}</div>` : ""}
<div class="code">Código de la sala<b>${esc(pretty)}</b></div>
<a class="btn" href="${open}">Abrir en JOX3 TV y unirme</a>
<small>Si no abre: entra a JOX3 TV → 👥 Ver juntos → escribe el código.</small>
</main></body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}
