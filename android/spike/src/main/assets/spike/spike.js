// Spike B — WebView renderer. All content is LOCAL (WebViewAssetLoader);
// the CSP forbids any remote or inline script. The bridge is the narrow typed
// object injected by the Kotlin side: SpikeBridge.start/delta/done/clear.
"use strict";
var listEl = null;

function esc(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// Mini markdown: ### h3, **bold**, `inline`, fenced ``` blocks. Same feature
// set as the native pane so both render identical bytes.
function md(text) {
  var parts = text.split(/```/);
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    var chunk = parts[i];
    if (i % 2 === 1) {
      var code = chunk.replace(/^[a-z]*\n/, "");
      out.push("<pre><code>" + esc(code) + "</code></pre>");
      continue;
    }
    var h = esc(chunk)
      .replace(/###\s*([^\n]+)/g, "<h3>$1</h3>")
      .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>")
      .replace(/`([^`]+)`/g, "<code>$1</code>");
    out.push(h);
  }
  return out.join("");
}

function ensureMsg(id, cls) {
  var el = document.getElementById("m" + id);
  if (!el) {
    el = document.createElement("div");
    el.id = "m" + id;
    el.className = "msg " + cls;
    listEl.appendChild(el);
  }
  return el;
}

document.addEventListener("DOMContentLoaded", function () {
  listEl = document.getElementById("list");
});

var live = {};

// Narrow typed bridge surface driven by Kotlin (start/delta/done/clear).
window.pushDelta = function (id, chunk) {
  var el = ensureMsg(id, live[id] || "assistant");
  live[id] = (live[id] || "") + chunk;
  el.innerHTML = md(live[id]);
};

window.scrollBottom = function () {
  document.getElementById("list").scrollTop = 1e9;
};

window.clearAll = function () {
  live = {};
  document.getElementById("list").innerHTML = "";
};

SpikeBridge.setReady && SpikeBridge.setReady();
