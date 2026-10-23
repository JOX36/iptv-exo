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
