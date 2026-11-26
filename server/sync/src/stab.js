// Puntajes de estabilidad compartidos: cada equipo sube lo que midió (arranques, fallas,
// cortes, tiempo de arranque) por señal, y todos consultan la suma. Así la "Mejor opción"
// aprende de todos los que usan la app con el mismo servidor.
//
// POST /v1/stab      { items:[{ s, id, st, ok, f, sl, ms }] }   → suma (máx. 150 por envío)
// POST /v1/stab/get  { q:[{ s, ids:[…] }] }                      → { r: { "s|id": [st, ok, f, sl, ms] } }

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });

const SRV = /^[a-f0-9]{16,64}$/;
const ID = /^[A-Za-z0-9_.-]{1,40}$/;
const n = (v, max) => Math.max(0, Math.min(max, Math.floor(Number(v) || 0)));
const WEEK = 7 * 24 * 3600 * 1000;

export async function stabPost(req, env) {
  let body;
  try { body = await req.json(); } catch { return json({ error: "json inválido" }, 400); }
  const items = (Array.isArray(body.items) ? body.items : []).slice(0, 150)
    .filter((x) => SRV.test(String(x?.s)) && ID.test(String(x?.id)));
  const now = Date.now();
  const stmts = items.map((x) => {
    // Un envío no puede pesar demasiado (lo de un equipo no tapa lo de todos)
    const st = n(x.st, 20), ok = Math.min(n(x.ok, 20), st || 20), f = n(x.f, 20), sl = n(x.sl, 40), ms = n(x.ms, 20 * 30000);
    // Lo viejo pesa menos: si la fila tiene más de una semana, se reduce a la mitad antes de sumar
    return env.DB.prepare(
      `INSERT INTO stab (srv, id, starts, ok, fails, stalls, ms, ts) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
       ON CONFLICT (srv, id) DO UPDATE SET
         starts = (CASE WHEN ?8 - ts > ${WEEK} THEN starts / 2 ELSE starts END) + ?3,
         ok     = (CASE WHEN ?8 - ts > ${WEEK} THEN ok / 2 ELSE ok END) + ?4,
         fails  = (CASE WHEN ?8 - ts > ${WEEK} THEN fails / 2 ELSE fails END) + ?5,
         stalls = (CASE WHEN ?8 - ts > ${WEEK} THEN stalls / 2 ELSE stalls END) + ?6,
         ms     = (CASE WHEN ?8 - ts > ${WEEK} THEN ms / 2 ELSE ms END) + ?7,
         ts = ?8`
    ).bind(String(x.s), String(x.id), st, ok, f, sl, ms, now);
  });
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true, n: stmts.length });
}

export async function stabGet(req, env) {
  let body;
  try { body = await req.json(); } catch { return json({ error: "json inválido" }, 400); }
  const out = {};
  let left = 300;
  for (const q of (Array.isArray(body.q) ? body.q : []).slice(0, 12)) {
    const s = String(q?.s || "");
    if (!SRV.test(s)) continue;
    const ids = (Array.isArray(q.ids) ? q.ids : []).map(String).filter((i) => ID.test(i)).slice(0, left);
    left -= ids.length;
    if (!ids.length) continue;
    const marks = ids.map((_, i) => "?" + (i + 2)).join(",");
    const rows = await env.DB.prepare(`SELECT id, starts, ok, fails, stalls, ms FROM stab WHERE srv = ?1 AND id IN (${marks})`)
      .bind(s, ...ids).all();
    for (const r of rows.results || []) out[s + "|" + r.id] = [r.starts, r.ok, r.fails, r.stalls, r.ms];
    if (left <= 0) break;
  }
  return json({ ok: true, r: out });
}
