// A small help-chat bubble. The page gives it a function that asks the server; replies are shown as plain text.
//   ChatWidget.init({ title, welcome, ready: () => bool, notReady: "text", ask: async text => ({ text, suggestions: [] }) })
(function () {
  const css = `
    #cw-bubble { position: fixed; right: 18px; bottom: 18px; z-index: 9998; width: 54px; height: 54px; border-radius: 50%; border: 0; cursor: pointer;
      background: var(--primary, #7c3aed); color: #fff; font-size: 24px; box-shadow: 0 8px 24px rgba(15,23,42,.28); }
    #cw-panel { position: fixed; right: 18px; bottom: 84px; z-index: 9999; width: min(360px, calc(100vw - 36px)); height: min(480px, calc(100vh - 110px));
      display: none; flex-direction: column; background: var(--surface, #fff); color: var(--dark, #0f172a); border: 1px solid var(--border, #e2e8f0);
      border-radius: 14px; box-shadow: 0 18px 45px rgba(15,23,42,.28); overflow: hidden; font-family: inherit; }
    #cw-panel.open { display: flex; }
    #cw-head { padding: 12px 14px; font-weight: 700; color: #fff; background: var(--primary, #7c3aed); display: flex; justify-content: space-between; align-items: center; }
    #cw-head button { background: none; border: 0; color: #fff; font-size: 18px; cursor: pointer; }
    #cw-log { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
    .cw-msg { max-width: 85%; padding: 8px 11px; border-radius: 12px; font-size: 14px; line-height: 1.4; white-space: pre-wrap; word-wrap: break-word; }
    .cw-bot { align-self: flex-start; background: var(--surface-2, #f1f5f9); }
    .cw-me { align-self: flex-end; background: var(--primary, #7c3aed); color: #fff; }
    #cw-chips { padding: 0 12px 8px; display: flex; flex-wrap: wrap; gap: 6px; }
    #cw-chips button { border: 1px solid var(--border, #e2e8f0); background: var(--surface, #fff); color: inherit; border-radius: 14px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
    #cw-form { display: flex; gap: 6px; padding: 10px; border-top: 1px solid var(--border, #e2e8f0); }
    #cw-input { flex: 1; padding: 8px 10px; border: 1px solid var(--input-border, #cbd5e1); border-radius: 8px; font-size: 14px; background: var(--surface, #fff); color: inherit; }
    #cw-send { border: 0; border-radius: 8px; padding: 0 14px; background: var(--primary, #7c3aed); color: #fff; cursor: pointer; }
  `;

  function el(tag, attrs, text) {
    const node = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => node.setAttribute(k, v));
    if (text !== undefined) node.textContent = text;
    return node;
  }

  window.ChatWidget = {
    init(options) {
      if (document.getElementById("cw-bubble")) return;
      const style = el("style"); style.textContent = css; document.head.appendChild(style);
      const bubble = el("button", { id: "cw-bubble", type: "button", "aria-label": "Open help chat" }, "\u{1F4AC}");
      const panel = el("div", { id: "cw-panel", role: "dialog", "aria-label": options.title || "Help chat" });
      const head = el("div", { id: "cw-head" });
      head.appendChild(el("span", {}, options.title || "Help chat"));
      const close = el("button", { type: "button", "aria-label": "Close help chat" }, "×");
      head.appendChild(close);
      const log = el("div", { id: "cw-log", "aria-live": "polite" });
      const chips = el("div", { id: "cw-chips" });
      const form = el("form", { id: "cw-form" });
      const input = el("input", { id: "cw-input", type: "text", maxlength: "300", placeholder: "Type your question", autocomplete: "off" });
      const send = el("button", { id: "cw-send", type: "submit" }, "Send");
      form.append(input, send);
      panel.append(head, log, chips, form);
      document.body.append(bubble, panel);

      let greeted = false;
      const say = (text, who) => { log.appendChild(el("div", { class: "cw-msg " + (who === "me" ? "cw-me" : "cw-bot") }, text)); log.scrollTop = log.scrollHeight; };
      const setChips = list => {
        chips.textContent = "";
        (list || []).forEach(label => {
          const b = el("button", { type: "button" }, label);
          b.addEventListener("click", () => ask(label));
          chips.appendChild(b);
        });
      };
      async function ask(text) {
        text = (text || "").trim();
        if (!text) return;
        say(text, "me");
        input.value = "";
        if (options.ready && !options.ready()) { say(options.notReady || "Please sign in first.", "bot"); return; }
        send.disabled = true;
        try {
          const reply = await options.ask(text);
          say(reply.text, "bot");
          setChips(reply.suggestions);
        } catch (e) {
          say("Sorry, I couldn't answer that just now. " + (e && e.message && e.message.length < 120 ? e.message : "Please try again."), "bot");
        } finally {
          send.disabled = false;
          input.focus();
        }
      }
      function open() {
        panel.classList.add("open");
        if (!greeted) { greeted = true; say(options.welcome || "Hi! How can I help?", "bot"); setChips(options.suggestions); }
        input.focus();
      }
      bubble.addEventListener("click", () => panel.classList.contains("open") ? panel.classList.remove("open") : open());
      close.addEventListener("click", () => panel.classList.remove("open"));
      form.addEventListener("submit", e => { e.preventDefault(); ask(input.value); });
    }
  };
})();
