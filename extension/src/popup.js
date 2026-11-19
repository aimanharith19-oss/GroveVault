const $ = (id) => document.getElementById(id);
let tab = null;

function status(t) { $("status").textContent = t; }

async function copy(text, what) {
  await navigator.clipboard.writeText(text);
  status(what + " copied (clears in 30 s)");
  setTimeout(() => navigator.clipboard.writeText("").catch(() => {}), 30000);
}

async function fillInTab(item) {
  // hand the login to the page's GroveVault script, which fills the form on that same site
  await chrome.tabs.sendMessage(tab.id, {type: "gvFill", item}).catch(() => {});
  window.close();
}

function render(items, here) {
  const list = $("list");
  list.textContent = "";
  for (const it of items) {
    const row = document.createElement("div"); row.className = "row";
    const tx = document.createElement("div"); tx.className = "tx";
    const t = document.createElement("div"); t.className = "t"; t.textContent = it.title || "Login";
    const s = document.createElement("div"); s.className = "s"; s.textContent = it.user || it.url || "";
    tx.append(t, s);
    row.append(tx);
    const btn = (label, fn) => { const b = document.createElement("button"); b.textContent = label; b.onclick = fn; row.append(b); };
    const full = async () => it.pass !== undefined ? it : ((await chrome.runtime.sendMessage({type: "get", id: it.id})).items || [])[0];
    if (here) btn("Fill", async () => fillInTab(await full()));
    btn("User", async () => copy((await full()).user || "", "Username"));
    btn("Pass", async () => copy((await full()).pass || "", "Password"));
    list.append(row);
  }
}

async function load() {
  [tab] = await chrome.tabs.query({active: true, currentWindow: true});
  const r = await chrome.runtime.sendMessage({type: "logins", url: tab && tab.url});
  if (r.error === "unpaired") return status("Not paired yet. Tap ⚙ and enter the code from Grove Vault → Settings → Browser extension.");
  if (r.error === "offline") { $("open").hidden = false; return status("Grove Vault isn't running. Open it, then try again."); }
  if (r.error) return status("The pairing code doesn't match. Pair again in ⚙.");
  if (r.locked) { $("open").hidden = false; return status("Grove Vault is locked."); }
  status(r.items.length ? "Logins for this site" : "No logins for this site. Search below.");
  render(r.items, true);
}

$("q").addEventListener("input", async () => {
  const q = $("q").value.trim();
  if (!q) return load();
  const r = await chrome.runtime.sendMessage({type: "search", q});
  if (r.error || r.locked) return;
  status(r.items.length + " found");
  render(r.items, true);
});
$("open").onclick = () => chrome.runtime.sendMessage({type: "openApp"});
load();
