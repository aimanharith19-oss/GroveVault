// GroveVault extension: encrypted messages to the Grove Vault app on this phone (127.0.0.1 only).
const GV_URL = "http://127.0.0.1:47920/v1";
const enc = new TextEncoder(), dec = new TextDecoder();
const b64 = (u8) => btoa(String.fromCharCode(...u8));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function gvKey(code) {
  const norm = code.toUpperCase().replace(/[^A-Z0-9]/g, "");
  const raw = await crypto.subtle.digest("SHA-256", enc.encode("GroveVault-ext-v1|" + norm));
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** Sends {op,...} to the app; resolves {ok, locked, items} or {error}. */
async function gvCall(msg, code) {
  if (code === undefined) code = (await chrome.storage.local.get("code")).code || "";
  if (!code) return {error: "unpaired"};
  const key = await gvKey(code);
  const nonce = b64(crypto.getRandomValues(new Uint8Array(16)));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = enc.encode(JSON.stringify(Object.assign({t: Date.now(), n: nonce}, msg)));
  const ct = new Uint8Array(await crypto.subtle.encrypt({name: "AES-GCM", iv, additionalData: enc.encode("gv1")}, key, plain));
  let res;
  try {
    res = await fetch(GV_URL, {method: "POST", headers: {"content-type": "application/json"}, body: JSON.stringify({iv: b64(iv), ct: b64(ct)}), cache: "no-store"});
  } catch (e) {
    return {error: "offline"};
  }
  if (res.status === 403) return {error: "badcode"};
  if (!res.ok) return {error: "offline"};
  try {
    const env = await res.json();
    const out = JSON.parse(dec.decode(await crypto.subtle.decrypt({name: "AES-GCM", iv: unb64(env.iv), additionalData: enc.encode("gv1")}, key, unb64(env.ct))));
    if (out.n !== nonce) return {error: "badcode"};
    return out;
  } catch (e) {
    return {error: "badcode"};
  }
}

const GV_OPEN_APP = "intent://open#Intent;scheme=grovevault;package=org.grove.vault;end";
