// IA de JOX3 TV. La app nunca habla con la IA directamente: pregunta aquí y este servidor
// decide cuál usar. Por defecto, Workers AI de Cloudflare (modelos abiertos, sin claves).
// Para usar otra (Gemini, OpenAI, OpenRouter…) basta con poner estos secretos en el Worker,
// sin actualizar la app:
//   AI_URL   = https://…/v1/chat/completions   (cualquier API compatible con OpenAI;
//              Gemini: https://generativelanguage.googleapis.com/v1beta/openai/chat/completions)
//   AI_KEY   = la clave
//   AI_MODEL = el modelo (p. ej. gemini-2.0-flash)
//
// POST /v1/ai/search { q, now }            → qué quiere ver la persona (plan de búsqueda).
// POST /v1/ai/epg    { items:[{n, c:[…]}] } → para cada canal, cuál guía de la lista es la suya.
// Lo que se resuelve se guarda (tabla ai_cache) y sirve para todos.

const MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const SEARCH_TTL = 6 * 3600 * 1000;
const EPG_TTL = 30 * 24 * 3600 * 1000;

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

/** Pregunta a la IA y devuelve el JSON que respondió (o lanza error). */
async function ask(env, system, user, maxTokens = 600) {
  const messages = [
    { role: "system", content: system },
    { role: "user", content: user },
  ];
  let text;
  if (env.AI_URL && env.AI_KEY) {
    const r = await fetch(env.AI_URL, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer " + env.AI_KEY },
      body: JSON.stringify({
        model: env.AI_MODEL || "gemini-2.0-flash",
        messages,
        temperature: 0.1,
        max_tokens: maxTokens,
        response_format: { type: "json_object" },
      }),
    });
    if (!r.ok) throw new Error("ia " + r.status);
    const o = await r.json();
    text = o.choices?.[0]?.message?.content || "";
  } else {
    if (!env.AI) throw new Error("sin ia");
    const o = await env.AI.run(MODEL, { messages, temperature: 0.1, max_tokens: maxTokens });
    text = typeof o.response === "string" ? o.response : JSON.stringify(o.response || "");
  }
  return parseJson(text);
}

/** El primer objeto JSON del texto (los modelos a veces agregan algo antes o después). */
export function parseJson(text) {
  const s = String(text || "");
  const a = s.indexOf("{");
  const b = s.lastIndexOf("}");
  if (a < 0 || b <= a) throw new Error("sin json");
  return JSON.parse(s.slice(a, b + 1));
}

async function cacheGet(env, k, ttl) {
  const row = await env.DB.prepare("SELECT v, ts FROM ai_cache WHERE k = ?").bind(k).first();
  if (!row || Date.now() - row.ts > ttl) return null;
  try { return JSON.parse(row.v); } catch { return null; }
}

async function cachePut(env, k, v) {
  await env.DB.prepare("INSERT OR REPLACE INTO ai_cache (k, v, ts) VALUES (?, ?, ?)")
    .bind(k, JSON.stringify(v), Date.now()).run();
}

export const norm = (s) =>
  String(s || "").toLowerCase().normalize("NFD").replace(/\p{M}+/gu, "").replace(/[^a-z0-9+ ]+/g, " ").replace(/\s+/g, " ").trim();

const str = (v, max = 60) => String(v ?? "").trim().slice(0, max);
const list = (v, n, max = 60) => (Array.isArray(v) ? v : []).map((x) => str(x, max)).filter(Boolean).slice(0, n);

// ── Búsqueda ──

const SEARCH_PROMPT = `Eres el buscador de una app de TV por internet (IPTV) en Latinoamérica (sobre todo Colombia).
La persona dijo o escribió lo que quiere ver. Responde SOLO con un objeto JSON con estas claves:
- "kind": "event" si busca un partido, pelea, carrera o evento en vivo; "channel" si nombra un canal;
  "title" si busca una película o serie concreta; "browse" si pide un tipo de contenido (género, actor, época, para niños…);
  "plain" si no se entiende.
- "terms": palabras clave para buscar en nombres de canales y en la guía de programación: equipos, ligas, luchadores,
  canales o títulos, sin artículos ni verbos. Equipos con su nombre corto habitual (ej. "millonarios", "nacional",
  "real madrid"). Máximo 6.
- "titles": si kind es "title" o "browse", hasta 12 títulos reales de películas o series que encajen, con el nombre
  como se conocen en Latinoamérica (y el original si es distinto). Si no, lista vacía.
- "type": "movie", "series", "live" o "any".
- "say": una frase corta en español para mostrar, p. ej. "Partidos de Millonarios en la guía".
No expliques nada fuera del JSON.`;

export async function aiSearch(req, env) {
  let body;
  try { body = await req.json(); } catch { return json({ error: "json inválido" }, 400); }
  const q = str(body.q, 200);
  if (q.length < 2) return json({ error: "falta q" }, 400);
  const key = "s|" + norm(q);
  const hit = await cacheGet(env, key, SEARCH_TTL);
  if (hit) return json({ ok: true, cached: true, ...hit });
  let plan;
  try {
    const now = str(body.now, 60);
    const o = await ask(env, SEARCH_PROMPT, (now ? `Ahora es ${now}.\n` : "") + `Pedido: ${q}`);
    const kinds = ["event", "channel", "title", "browse", "plain"];
    const types = ["movie", "series", "live", "any"];
    plan = {
      kind: kinds.includes(o.kind) ? o.kind : "plain",
      terms: list(o.terms, 6, 40),
      titles: list(o.titles, 12, 70),
      type: types.includes(o.type) ? o.type : "any",
      say: str(o.say, 90),
    };
  } catch (e) {
    return json({ ok: false, error: "ia no disponible" }, 503);
  }
  await cachePut(env, key, plan);
  return json({ ok: true, ...plan });
}

// ── Guía de programación ──

const EPG_PROMPT = `Empareja canales de TV con su guía de programación.
Para cada canal ("n") hay una lista de guías candidatas ("c", con índice desde 0). Elige la guía que es
EL MISMO canal (misma señal y mismo país o versión, si se nota). Si ninguna es claramente el mismo canal, usa -1.
Cuidado con números ("ESPN 2" no es "ESPN 3") y variantes ("+", "Plus", "Premium").
Responde SOLO con JSON: {"r":[índice por cada canal, en el mismo orden]}.`;

export async function aiEpg(req, env) {
  let body;
  try { body = await req.json(); } catch { return json({ error: "json inválido" }, 400); }
  const items = (Array.isArray(body.items) ? body.items : []).slice(0, 25).map((it) => ({
    n: str(it?.n, 80),
    c: list(it?.c, 12, 80),
  })).filter((it) => it.n && it.c.length);
  if (!items.length) return json({ ok: true, r: [] });
  // Lo ya resuelto (por nombre de canal + candidatos) no se vuelve a preguntar
  const keys = items.map((it) => "e|" + norm(it.n) + "|" + it.c.map(norm).join("~"));
  const out = new Array(items.length).fill(null);
  const ask1 = [];
  for (let i = 0; i < items.length; i++) {
    const hit = await cacheGet(env, keys[i], EPG_TTL);
    if (hit && typeof hit.g === "string") {
      out[i] = hit.g ? items[i].c.indexOf(hit.g) : -1;
    } else ask1.push(i);
  }
  if (ask1.length) {
    let r;
    try {
      const payload = ask1.map((i) => ({ n: items[i].n, c: items[i].c }));
      const o = await ask(env, EPG_PROMPT, JSON.stringify(payload), 40 + ask1.length * 6);
      r = Array.isArray(o.r) ? o.r : [];
    } catch (e) {
      return json({ ok: false, error: "ia no disponible", r: out }, 503);
    }
    for (let j = 0; j < ask1.length; j++) {
      const i = ask1[j];
      const v = Number.isInteger(r[j]) && r[j] >= 0 && r[j] < items[i].c.length ? r[j] : -1;
      out[i] = v;
      await cachePut(env, keys[i], { g: v >= 0 ? items[i].c[v] : "" });
    }
  }
  return json({ ok: true, r: out });
}
