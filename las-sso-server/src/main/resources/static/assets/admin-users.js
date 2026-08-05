(() => {
  "use strict";

  const csrf = document.getElementById("admin-csrf");
  const dialog = document.getElementById("admin-action-dialog");
  const form = document.getElementById("admin-action-form");
  const credentialDialog = document.getElementById("admin-credential-dialog");
  const credentialValue = document.getElementById("admin-credential-value");
  const credentialStatus = document.getElementById("admin-credential-status");
  const credentialCopy = document.getElementById("admin-credential-copy");
  if (!csrf || !dialog || !form) return;
  let credentialOpen = false;

  const descriptions = {
    freeze: "冻结后该账号将立即退出全部设备和应用，解除冻结前不能再次登录。",
    unfreeze: "解除冻结后账号可以重新登录，但不会恢复已撤销的会话。",
    "revoke-sessions": "撤销该账号的全部 SSO 会话与业务应用授权，账号本身仍保持当前状态。",
    "recovery-credentials": "签发一次性人工恢复凭据：30 分钟内有效，用户可用它在恢复页面设置新密码。凭据只显示一次，请通过可信渠道交付。"
  };
  const titles = {
    freeze: "冻结账号",
    unfreeze: "解除冻结",
    "revoke-sessions": "强制下线",
    "recovery-credentials": "签发恢复凭据"
  };
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
    if (response.ok) return response.status === 204 ? null : response.json();
    let problem = {};
    try { problem = await response.json(); } catch { /* status fallback */ }
    const error = new Error(problem.detail || "请求失败，请刷新后重试。");
    error.code = problem.code;
    throw error;
  }

  document.querySelectorAll(".admin-action").forEach((button) => {
    button.addEventListener("click", () => {
      pending = { action: button.dataset.action, userId: button.dataset.userId };
      document.getElementById("admin-dialog-title").textContent = titles[pending.action];
      document.getElementById("admin-dialog-description").textContent = descriptions[pending.action];
      document.getElementById("admin-dialog-error").textContent = "";
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
    submit.disabled = true;
    submit.textContent = "处理中…";
    error.textContent = "";
    try {
      await request("/api/v1/me/reauthentication", { password: form.elements.password.value });
      const result = await request(`/api/v1/admin/users/${encodeURIComponent(pending.userId)}/${pending.action}`,
        { reason: form.elements.reason.value.trim() });
      dialog.close();
      if (pending.action === "recovery-credentials") {
        showCredential(result.credential);
      } else {
        window.location.reload();
      }
    } catch (failure) {
      error.textContent = failure.message;
      form.elements.password.value = "";
      form.elements.password.focus({ preventScroll: true });
    } finally {
      submit.disabled = false;
      submit.textContent = "确认执行";
    }
  });

  function showCredential(credential) {
    credentialValue.textContent = credential;
    credentialStatus.textContent = "";
    credentialStatus.removeAttribute("data-state");
    credentialCopy.textContent = "复制凭据";
    credentialOpen = true;
    credentialDialog.showModal();
  }

  document.getElementById("admin-credential-saved").addEventListener("click", () => {
    credentialOpen = false;
    credentialDialog.close();
  });
  credentialDialog.addEventListener("close", () => { credentialOpen = false; });
  document.getElementById("admin-credential-close").addEventListener("click", () => credentialDialog.close());

  credentialCopy.addEventListener("click", async () => {
    const text = credentialValue.textContent;
    credentialStatus.textContent = "";
    try {
      if (!navigator.clipboard?.writeText) throw new Error("CLIPBOARD_UNAVAILABLE");
      await navigator.clipboard.writeText(text);
      credentialCopy.textContent = "已复制";
      credentialStatus.dataset.state = "success";
      credentialStatus.textContent = "凭据已复制到剪贴板，请立即交付给用户。";
    } catch {
      credentialCopy.textContent = "复制凭据";
      credentialStatus.dataset.state = "error";
      credentialStatus.textContent = "无法自动复制，请手动选择凭据文本。";
    }
  });

  window.addEventListener("beforeunload", (event) => {
    if (credentialOpen) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
})();
