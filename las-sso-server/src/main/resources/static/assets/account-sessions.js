(() => {
  "use strict";

  const csrf = document.getElementById("session-csrf");
  if (!csrf) return;
  const csrfHeader = csrf.dataset.header;
  const csrfToken = csrf.value;
  const message = document.getElementById("session-message");
  const messageText = document.getElementById("session-message-text");

  function setMessage(text, error = false) {
    message.classList.toggle("error", error);
    message.classList.toggle("success", !error);
    message.querySelector("strong").textContent = error ? "操作未完成" : "会话已更新";
    messageText.textContent = text;
    message.hidden = false;
    message.focus({ preventScroll: true });
  }

  async function request(url, method, headers = {}) {
    const response = await fetch(url, {
      method,
      credentials: "same-origin",
      headers: { Accept: "application/json", [csrfHeader]: csrfToken, ...headers }
    });
    if (response.ok) return response;
    let problem = {};
    try { problem = await response.json(); } catch { /* status fallback */ }
    throw new Error(problem.detail || "请求失败，请刷新页面后重试。");
  }

  function setBusy(button, busy) {
    if (busy) {
      button.dataset.label = button.textContent;
      button.textContent = "处理中…";
      button.setAttribute("aria-busy", "true");
      button.disabled = true;
      return;
    }
    button.textContent = button.dataset.label || button.textContent;
    button.removeAttribute("aria-busy");
    button.disabled = false;
  }

  document.querySelectorAll(".session-revoke").forEach((button) => {
    button.addEventListener("click", async () => {
      const row = button.closest(".session-item");
      const current = button.dataset.current === "true";
      const prompt = current
        ? "退出当前 SSO 会话会同时撤销全部应用授权，确定继续吗？"
        : "确定撤销这个会话吗？该设备需要重新登录。";
      if (!window.confirm(prompt)) return;
      setBusy(button, true);
      try {
        await request(`/api/v1/me/sessions/${encodeURIComponent(row.dataset.sid)}`, "DELETE");
        if (current) {
          window.location.assign("/login?logout");
          return;
        }
        row.remove();
        setMessage("指定设备的会话已经撤销。");
      } catch (error) {
        setBusy(button, false);
        setMessage(error.message, true);
      }
    });
  });

  document.getElementById("logout-all-sessions")?.addEventListener("click", async (event) => {
    const button = event.currentTarget;
    if (!window.confirm("确定退出所有设备和业务应用吗？完成后所有设备都需要重新登录。")) return;
    setBusy(button, true);
    try {
      await request("/api/v1/me/logout-all", "POST", { "Idempotency-Key": crypto.randomUUID() });
      window.location.assign("/login?logout");
    } catch (error) {
      setBusy(button, false);
      setMessage(error.message, true);
    }
  });
})();
