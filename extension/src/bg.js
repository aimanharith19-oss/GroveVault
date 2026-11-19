importScripts("common.js");

// Content scripts ask the background (only it may talk to 127.0.0.1). The URL always comes from the browser's own
// record of the tab/frame, never from the page, so a page can't ask for another site's logins.
chrome.runtime.onMessage.addListener((m, sender, reply) => {
  (async () => {
    if (m.type === "logins") {
      const url = sender.tab ? (sender.url || sender.tab.url) : m.url;
      if (!/^https?:/.test(url || "")) return reply({items: []});
      reply(await gvCall({op: "logins", url}));
    } else if (m.type === "search" && !sender.tab) {
      reply(await gvCall({op: "search", q: m.q || ""}));
    } else if (m.type === "get" && !sender.tab) {
      reply(await gvCall({op: "get", id: m.id}));
    } else if (m.type === "ping" && !sender.tab) {
      reply(await gvCall({op: "ping"}, m.code));
    } else if (m.type === "openApp") {
      chrome.tabs.create({url: GV_OPEN_APP});
      reply({});
    } else reply({});
  })();
  return true;
});
