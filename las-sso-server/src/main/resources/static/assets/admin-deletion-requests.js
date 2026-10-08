(() => {
  "use strict";

  const csrf = document.getElementById("admin-csrf");
  const dialog = document.getElementById("admin-action-dialog");
  const form = document.getElementById("admin-action-form");
  if (!csrf || !dialog || !form) return;

  const descriptions = {
    hold: "暂缓后注销申请进入人工处理，等待期倒计时暂停；到达暂缓截止时间后自动恢复执行。暂缓最长 90 天。",
    release: "释放后注销申请恢复等待期，到期自动执行。"
  };
  const titles = { hold: "暂缓注销申请", release: "释放注销申请" };
  let pending = null;

  async function request(url, body) {
    const response = await fetch(url, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        [csrf.dataset.header]: csrf.value
      },
      body: JSON.stringify(body)
    });
    if (response.ok) return;
    let problem = {};
    try { problem = await response.json(); } catch { /* status fallback */ }
    const error = new Error(problem.detail || "请求失败，请刷新后重试。");
    error.code = problem.code;
    throw error;
  }

  document.querySelectorAll(".admin-action").forEach((button) => {
    button.addEventListener("click", () => {
      pending = { action: button.dataset.action, requestId: button.dataset.requestId };
      document.getElementById("admin-dialog-target").textContent = `${button.dataset.username} · ${button.dataset.userId} · ${pending.requestId}`;
      document.getElementById("admin-dialog-title").textContent = titles[pending.action];
      document.getElementById("admin-dialog-description").textContent = descriptions[pending.action];
      document.getElementById("admin-dialog-error").textContent = "";
      document.getElementById("hold-until-group").hidden = pending.action !== "hold";
      document.getElementById("hold-public-summary-group").hidden = pending.action !== "hold";
      form.elements.holdUntil.required = pending.action === "hold";
      form.elements.publicSummary.required = pending.action === "hold";
      form.reset();
      dialog.showModal();
      form.elements.reason.focus({ preventScroll: true });
    });
  });

  document.getElementById("admin-dialog-cancel").addEventListener("click", () => dialog.close());

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!pending || !form.reportValidity()) return;
    const submit = document.getElementById("admin-dialog-submit");
    const error = document.getElementById("admin-dialog-error");
    const body = { reason: form.elements.reason.value.trim() };
    if (pending.action === "hold") {
      body.holdUntil = new Date(form.elements.holdUntil.value).toISOString();
      body.publicSummary = form.elements.publicSummary.value.trim();
    }
    submit.disabled = true;
    submit.textContent = "处理中…";
    error.textContent = "";
    try {
      await request("/api/v1/me/reauthentication", { password: form.elements.password.value });
      await request(`/api/v1/admin/deletion-requests/${encodeURIComponent(pending.requestId)}/${pending.action}`, body);
      dialog.close();
      window.location.reload();
    } catch (failure) {
      error.textContent = failure.message;
      form.elements.password.value = "";
      form.elements.password.focus({ preventScroll: true });
    } finally {
      submit.disabled = false;
      submit.textContent = "确认执行";
    }
  });

  const formatter = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
  });
  document.querySelectorAll(".audit-time").forEach((element) => {
    const iso = element.getAttribute("datetime");
    if (!iso) return;
    const date = new Date(iso);
    if (!Number.isNaN(date.getTime())) {
      element.textContent = formatter.format(date);
    }
  });
})();
