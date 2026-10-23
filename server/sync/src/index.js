// JOX3 Sync — guarda y reparte el historial de cada cuenta entre sus dispositivos.
//
// POST /v1/sync  { acc, profile, since, changes: [{ list, item, data, ts, deleted }] }
//   profile = perfil de la casa ("principal", "p…"); "_perfiles" guarda la lista de perfiles.
//   → guarda los cambios (gana el de ts más nuevo) y devuelve lo que cambió después de `since`.
// GET  /         → estado del servidor.

const LISTS = new Set(["fav", "recent", "resume", "profile"]);
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
    if (data && data.length > 4000) continue;
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
