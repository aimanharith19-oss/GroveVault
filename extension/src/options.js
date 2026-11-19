const $ = (id) => document.getElementById(id);
chrome.storage.local.get("code").then((s) => { if (s.code) $("msg").textContent = "Paired"; });
$("save").onclick = async () => {
  const code = $("code").value.trim().toUpperCase();
  if (code.replace(/[^A-Z0-9]/g, "").length < 20) return ($("msg").textContent = "That code is too short.");
  $("msg").textContent = "Checking…";
  const r = await chrome.runtime.sendMessage({type: "ping", code});
  if (r.error === "offline") return ($("msg").textContent = "Open Grove Vault on this phone, then try again.");
  if (r.error) return ($("msg").textContent = "That code doesn't match. Check it in Grove Vault.");
  await chrome.storage.local.set({code});
  $("code").value = "";
  $("msg").textContent = "Paired ✓  You can close this page.";
};
