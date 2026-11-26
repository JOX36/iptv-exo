-- Se aplica en cada publicación; solo crea lo que falte.
CREATE TABLE IF NOT EXISTS items (
  acc     TEXT    NOT NULL,   -- huella SHA-256 de la cuenta IPTV (nunca el usuario ni la contraseña)
  profile TEXT    NOT NULL,   -- perfil dentro de la cuenta ("principal" por ahora)
  list    TEXT    NOT NULL,   -- fav | recent | resume
  item    TEXT    NOT NULL,   -- "tipo:id", p. ej. "movie:1234"
  data    TEXT,               -- JSON del elemento (título, imagen, posición…)
  ts      INTEGER NOT NULL,   -- cuándo cambió en el equipo (gana el más nuevo)
  deleted INTEGER NOT NULL DEFAULT 0,
  seq     INTEGER NOT NULL,   -- cuándo llegó al servidor (para pedir solo lo nuevo)
  PRIMARY KEY (acc, profile, list, item)
);
CREATE INDEX IF NOT EXISTS items_seq ON items (acc, profile, seq);

-- ── Saltar intro y créditos ──
-- srv = huella del dominio del servidor IPTV (agrupa a quienes lo comparten; nunca usuario ni clave).
-- Tiempos en ms del video. NULL = no se sabe; -2 = no tiene / se reportó mal.
CREATE TABLE IF NOT EXISTS skip_ep (
  srv     TEXT    NOT NULL,
  ep      TEXT    NOT NULL,   -- id del capítulo en ese servidor
  series  TEXT    NOT NULL,
  season  INTEGER NOT NULL DEFAULT 0,
  i_s     INTEGER,            -- intro: empieza
  i_e     INTEGER,            -- intro: termina
  c_s     INTEGER,            -- créditos: empiezan
  dur     INTEGER,
  ts      INTEGER NOT NULL,
  PRIMARY KEY (srv, ep)
);
CREATE INDEX IF NOT EXISTS skip_ep_series ON skip_ep (srv, series);

-- Plantillas: huella de la intro / créditos de una temporada (hasta 3 por tipo)
CREATE TABLE IF NOT EXISTS skip_tpl (
  srv     TEXT    NOT NULL,
  series  TEXT    NOT NULL,
  season  INTEGER NOT NULL,
  kind    TEXT    NOT NULL,   -- intro | credits
  id      TEXT    NOT NULL,
  fp      TEXT    NOT NULL,   -- huella en base64
  bad     INTEGER NOT NULL DEFAULT 0,
  ts      INTEGER NOT NULL,
  PRIMARY KEY (srv, series, season, kind, id)
);

-- Huellas de capítulos sueltos (para comparar cuando aún no hay plantilla)
CREATE TABLE IF NOT EXISTS skip_print (
  srv     TEXT    NOT NULL,
  series  TEXT    NOT NULL,
  season  INTEGER NOT NULL,
  kind    TEXT    NOT NULL,
  ep      TEXT    NOT NULL,
  first   INTEGER NOT NULL DEFAULT 0,
  dur     INTEGER,
  fp      TEXT    NOT NULL,
  ts      INTEGER NOT NULL,
  PRIMARY KEY (srv, series, season, kind, ep)
);

-- Versión 2 del método (une las partes de intros que cambian en el medio, como Los Simpson).
-- Los tiempos y plantillas de la versión 1 no se usan más; las huellas (skip_print) sí sirven.
CREATE TABLE IF NOT EXISTS skip_ep2 (
  srv     TEXT    NOT NULL,
  ep      TEXT    NOT NULL,
  series  TEXT    NOT NULL,
  season  INTEGER NOT NULL DEFAULT 0,
  i_s     INTEGER,
  i_e     INTEGER,
  c_s     INTEGER,
  dur     INTEGER,
  ts      INTEGER NOT NULL,
  PRIMARY KEY (srv, ep)
);
CREATE INDEX IF NOT EXISTS skip_ep2_series ON skip_ep2 (srv, series);

CREATE TABLE IF NOT EXISTS skip_tpl2 (
  srv     TEXT    NOT NULL,
  series  TEXT    NOT NULL,
  season  INTEGER NOT NULL,
  kind    TEXT    NOT NULL,
  id      TEXT    NOT NULL,
  fp      TEXT    NOT NULL,
  name    TEXT    NOT NULL DEFAULT '',   -- nombre normalizado de la serie (para tus otros servidores)
  bad     INTEGER NOT NULL DEFAULT 0,
  ts      INTEGER NOT NULL,
  PRIMARY KEY (srv, series, season, kind, id)
);
CREATE INDEX IF NOT EXISTS skip_tpl2_name ON skip_tpl2 (name, srv);

-- Huellas de capítulos por NOMBRE de serie, de cualquier servidor: así lo que aprende un usuario
-- sirve para todos los que ven la misma serie (aunque sea en otro servidor IPTV).
CREATE TABLE IF NOT EXISTS skip_pname (
  name    TEXT    NOT NULL,
  season  INTEGER NOT NULL,
  kind    TEXT    NOT NULL,
  srv     TEXT    NOT NULL,
  ep      TEXT    NOT NULL,
  first   INTEGER NOT NULL DEFAULT 0,
  dur     INTEGER,
  fp      TEXT    NOT NULL,
  ts      INTEGER NOT NULL,
  PRIMARY KEY (name, season, kind, srv, ep)
);

-- IA: respuestas ya resueltas (búsquedas y guías emparejadas), compartidas por todos.
CREATE TABLE IF NOT EXISTS ai_cache (
  k   TEXT    PRIMARY KEY,
  v   TEXT    NOT NULL,
  ts  INTEGER NOT NULL
);

-- Estabilidad de cada señal en vivo (servidor + canal), sumada entre todos los equipos.
-- srv = huella del servidor (no la dirección); id = número del canal en ese servidor.
CREATE TABLE IF NOT EXISTS stab (
  srv     TEXT    NOT NULL,
  id      TEXT    NOT NULL,
  starts  INTEGER NOT NULL DEFAULT 0,
  ok      INTEGER NOT NULL DEFAULT 0,
  fails   INTEGER NOT NULL DEFAULT 0,
  stalls  INTEGER NOT NULL DEFAULT 0,
  ms      INTEGER NOT NULL DEFAULT 0,
  ts      INTEGER NOT NULL,
  PRIMARY KEY (srv, id)
);
