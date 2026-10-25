// Prueba de "Ver juntos": dos equipos en la misma sala; lo que manda uno le llega al otro.
const U = (process.env.U || "https://jox3-sync.jox3tv.workers.dev").replace(/\/$/, "");
const wsUrl = c => U.replace("https://", "wss://") + `/v1/room/${c}/ws`;
const { code } = await (await fetch(U + "/v1/room", { method: "POST" })).json();
console.log("sala", code);
function open(name) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(wsUrl(code));
    const inbox = [];
    ws.onmessage = e => inbox.push(JSON.parse(e.data));
    ws.onopen = () => { ws.send(JSON.stringify({ type: "hello", name, emoji: "🙂" })); res({ ws, inbox }); };
    ws.onerror = e => rej(new Error("ws error " + name));
  });
}
const wait = ms => new Promise(r => setTimeout(r, ms));
async function until(inbox, type, ms = 5000) {
  const t = Date.now();
  while (Date.now() - t < ms) { const m = inbox.find(x => x.type === type); if (m) return m; await wait(100); }
  throw new Error("no llegó " + type + " (llegó: " + JSON.stringify(inbox) + ")");
}
const a = await open("Ana");
a.ws.send(JSON.stringify({ type: "media", kind: "movie", title: "Guerra Mundial Z", season: 0, episode: 0, image: "" }));
a.ws.send(JSON.stringify({ type: "state", playing: true, pos: 60000, rate: 1 }));
await wait(500);
const b = await open("Beto");
const snap = await until(b.inbox, "snapshot");
if (snap.media?.title !== "Guerra Mundial Z") throw new Error("snapshot sin película: " + JSON.stringify(snap));
if (!(snap.state?.pos >= 60000)) throw new Error("snapshot sin posición: " + JSON.stringify(snap));
const peers = await until(a.inbox, "peers");
console.log("personas", JSON.stringify(peers.peers));
b.ws.send(JSON.stringify({ type: "cmd", playing: false, pos: 61234, rate: 1 }));
const cmd = await until(a.inbox, "cmd");
if (cmd.playing !== false || cmd.pos !== 61234 || cmd.from !== "Beto") throw new Error("cmd mal: " + JSON.stringify(cmd));
a.ws.send(JSON.stringify({ type: "react", emoji: "😂" }));
const r = await until(b.inbox, "react");
if (r.emoji !== "😂" || r.from !== "Ana") throw new Error("react mal: " + JSON.stringify(r));
// Sala que no existe
const bad = new WebSocket(wsUrl("000000"));
const closeCode = await new Promise(res => { bad.onclose = e => res(e.code); setTimeout(() => res(-1), 5000); });
if (closeCode !== 4004) throw new Error("sala inexistente cerró con " + closeCode);
const page = await (await fetch(U + "/v/" + code)).text();
if (!page.includes("Guerra Mundial Z") || !page.includes("og:title")) throw new Error("página de invitación sin película");
a.ws.close(); b.ws.close();
console.log("Ver juntos OK: snapshot, personas, órdenes, reacciones y sala inexistente");
process.exit(0);
