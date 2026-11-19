// GroveVault: shows your logins under username/password boxes and fills them in.
(() => {
  if (window.__gv) return;
  window.__gv = true;
  let box = null, field = null, cache = null, cacheAt = 0;

  const isPass = (el) => el instanceof HTMLInputElement && el.type === "password";
  const isUser = (el) => el instanceof HTMLInputElement && /^(text|email|tel|)$/.test(el.type) &&
    (/user|login|email|mail|account|identifier|phone/i.test((el.name || "") + " " + (el.id || "") + " " + (el.autocomplete || "") + " " + (el.placeholder || "")) || hasPassNear(el));
  const isOtp = (el) => el instanceof HTMLInputElement && (el.autocomplete === "one-time-code" || /otp|totp|2fa|one.?time|verification|authenticator/i.test((el.name || "") + " " + (el.id || "") + " " + (el.placeholder || "")));
  const hasPassNear = (el) => !!(el.form || document).querySelector("input[type=password]");
  const visible = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };

  function setValue(el, v) {
    if (!el || v == null) return;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
    el.focus();
    setter.call(el, v);
    el.dispatchEvent(new Event("input", {bubbles: true}));
    el.dispatchEvent(new Event("change", {bubbles: true}));
  }

  function fill(item, kind) {
    if (kind === "otp") { setValue(field, item.otp); return close(); }
    const scope = (field && field.form) || document;
    const pass = [...scope.querySelectorAll("input[type=password]")].filter(visible);
    const users = [...scope.querySelectorAll("input")].filter((e) => visible(e) && isUser(e) && !isPass(e));
    let user = users.length ? users[0] : null;
    if (pass.length) {
      // the text box just before the password box is the username
      const all = [...scope.querySelectorAll("input")].filter(visible);
      const i = all.indexOf(pass[0]);
      for (let k = i - 1; k >= 0; k--) if (!isPass(all[k]) && /^(text|email|tel|)$/.test(all[k].type)) { user = all[k]; break; }
    }
    if (user && item.user) setValue(user, item.user);
    if (pass.length && item.pass) setValue(pass[0], item.pass);
    else if (!pass.length && isPass(field)) setValue(field, item.pass);
    close();
  }

  function close() { if (box) box.remove(); box = null; }

  async function logins() {
    if (cache && Date.now() - cacheAt < 20000) return cache;
    const r = await chrome.runtime.sendMessage({type: "logins"});
    if (r && !r.error && !r.locked) { cache = r; cacheAt = Date.now(); }
    return r;
  }

  function el(tag, css, text) { const e = document.createElement(tag); if (css) e.style.cssText = css; if (text != null) e.textContent = text; return e; }

  async function show(target) {
    field = target;
    const r = await logins();
    if (document.activeElement !== target) return;
    const otpMode = isOtp(target);
    let rows = [];
    if (!r || r.error === "unpaired") rows = [["Pair GroveVault in the extension's settings", null]];
    else if (r.error === "offline") rows = [["Open Grove Vault to log in", "open"]];
    else if (r.error) rows = [["GroveVault: pairing code doesn't match", null]];
    else if (r.locked) rows = [["Unlock Grove Vault to log in", "open"]];
    else {
      const items = (r.items || []).filter((i) => otpMode ? i.otp : (i.user || i.pass));
      if (!items.length) return close();
      rows = items.slice(0, 6).map((i) => [i, otpMode ? "otp" : "login"]);
    }
    close();
    const dark = matchMedia("(prefers-color-scheme: dark)").matches;
    const rect = target.getBoundingClientRect();
    box = el("div", `position:fixed;z-index:2147483647;left:${Math.max(8, rect.left)}px;top:${rect.bottom + 4}px;min-width:${Math.min(Math.max(rect.width, 220), innerWidth - 16)}px;max-width:${innerWidth - 16}px;` +
      `background:${dark ? "#2c2c2c" : "#fff"};color:${dark ? "#fff" : "#1b1b1b"};border:1px solid ${dark ? "#3a3a3a" : "#e5e5e5"};border-radius:8px;` +
      `box-shadow:0 8px 16px rgba(0,0,0,.18);padding:4px;font:14px system-ui,sans-serif;`);
    for (const [it, kind] of rows) {
      const row = el("div", "display:flex;align-items:center;gap:10px;padding:8px 10px;border-radius:4px;cursor:pointer;");
      row.onmouseenter = () => row.style.background = dark ? "rgba(255,255,255,.08)" : "rgba(0,0,0,.05)";
      row.onmouseleave = () => row.style.background = "";
      const ic = el("div", "width:28px;height:28px;border-radius:6px;background:#0f7b3f;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:600;flex:none;", "🔑");
      row.appendChild(ic);
      const tx = el("div", "min-width:0;");
      if (typeof it === "string") tx.appendChild(el("div", "", it));
      else {
        tx.appendChild(el("div", "font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;", kind === "otp" ? "Code " + it.otp : (it.title || "Login")));
        tx.appendChild(el("div", "opacity:.7;font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;", it.user || ""));
      }
      row.appendChild(tx);
      row.addEventListener("mousedown", (e) => e.preventDefault()); // keep the focus in the field
      row.addEventListener("click", () => {
        if (kind === "open") { chrome.runtime.sendMessage({type: "openApp"}); close(); cache = null; }
        else if (kind) fill(it, kind);
      });
      box.appendChild(row);
    }
    document.documentElement.appendChild(box);
  }

  document.addEventListener("focusin", (e) => {
    const t = e.target;
    if (isPass(t) || isOtp(t) || isUser(t)) show(t);
    else close();
  }, true);
  document.addEventListener("click", (e) => { if (box && !box.contains(e.target) && e.target !== field) close(); }, true);
  addEventListener("scroll", () => close(), true);
})();

// "Fill" from the toolbar pop-up
chrome.runtime.onMessage.addListener((m) => {
  if (m.type !== "gvFill" || !m.item) return;
  // only fill if this page is still the site the login belongs to (the app matched it to this tab's site)
  const f = document.activeElement instanceof HTMLInputElement ? document.activeElement : document.querySelector("input[type=password], input[type=email], input[type=text]");
  if (!f) return;
  f.focus();
  const ev = new FocusEvent("focusin", {bubbles: true});
  // reuse the in-page filler
  const scope = f.form || document;
  const pass = [...scope.querySelectorAll("input[type=password]")].find((e) => e.getBoundingClientRect().width > 0);
  const inputs = [...scope.querySelectorAll("input")].filter((e) => e.getBoundingClientRect().width > 0);
  let user = null;
  if (pass) { const i = inputs.indexOf(pass); for (let k = i - 1; k >= 0; k--) if (inputs[k].type !== "password" && /^(text|email|tel|)$/.test(inputs[k].type)) { user = inputs[k]; break; } }
  else user = inputs.find((e) => /^(text|email|tel|)$/.test(e.type));
  const set = (el, v) => { if (!el || v == null) return; const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set; el.focus(); s.call(el, v); el.dispatchEvent(new Event("input", {bubbles: true})); el.dispatchEvent(new Event("change", {bubbles: true})); };
  set(user, m.item.user);
  set(pass, m.item.pass);
});
