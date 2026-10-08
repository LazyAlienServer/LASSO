(() => {
  "use strict";

  const csrfInput = document.getElementById("security-csrf");
  const passwordForm = document.getElementById("password-change-form");
  if (!csrfInput || !passwordForm) return;
  const csrfHeader = csrfInput.dataset.header;
  const csrfToken = csrfInput.value;
  const summary = document.getElementById("security-summary");
  const summaryText = document.getElementById("security-summary-text");
  const submit = document.getElementById("password-submit");

  function showError(message) {
    summaryText.textContent = message;
    summary.hidden = false;
    summary.focus({ preventScroll: true });
  }

  function clearErrors() {
    summary.hidden = true;
    document.querySelectorAll(".field-error").forEach((element) => { element.textContent = ""; });
    document.querySelectorAll('[aria-invalid="true"]').forEach((element) => element.removeAttribute("aria-invalid"));
  }

  function setBusy(busy) {
    submit.disabled = busy;
    submit.classList.toggle("loading", busy);
    submit.querySelector(".button-label").textContent = busy ? "正在更新…" : "更新密码";
  }

  function friendlyError(code, fallback) {
    switch (code) {
      case "CURRENT_PASSWORD_INVALID": return "临时密码不正确，请重新输入。";
      case "NEW_PASSWORD_REUSED": return "新密码不能与临时密码相同。";
      case "PASSWORD_TOO_SHORT": return "新密码至少需要 12 个字符。";
      case "PASSWORD_TOO_LONG": return "新密码过长，请控制在 128 个字符以内。";
      case "PASSWORD_COMPROMISED": return "该密码过于常见，请选择更独特的密码。";
      case "TEMPORARY_PASSWORD_EXPIRED": return "临时密码已过期，请联系管理员重新处理。";
      case "RATE_LIMITED": return "尝试过于频繁，请稍后重试。";
      case "CSRF_INVALID": return "页面安全状态已过期，请刷新页面后重试。";
      default: return fallback || "请求失败，请稍后重试。";
    }
  }

  passwordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearErrors();
    const current = passwordForm.elements.currentCredential;
    const next = passwordForm.elements.newPassword;
    const confirmation = passwordForm.elements.confirmPassword;
    if (!current.value) {
      current.setAttribute("aria-invalid", "true");
      document.getElementById("current-credential-error").textContent = "请输入临时密码。";
      current.focus();
      return;
    }
    if (!next.checkValidity()) {
      next.setAttribute("aria-invalid", "true");
      document.getElementById("new-password-error").textContent = "请输入至少 12 个字符的新密码。";
      next.focus();
      return;
    }
    if (next.value !== confirmation.value) {
      confirmation.setAttribute("aria-invalid", "true");
      document.getElementById("confirm-password-error").textContent = "两次输入的新密码不一致。";
      confirmation.focus();
      return;
    }

    setBusy(true);
    try {
      const response = await fetch("/api/v1/me/password-change", {
        method: "POST",
        credentials: "same-origin",
        headers: { Accept: "application/json", "Content-Type": "application/json", [csrfHeader]: csrfToken },
        body: JSON.stringify({ temporaryPassword: current.value, newPassword: next.value })
      });
      if (!response.ok) {
        let problem = {};
        try { problem = await response.json(); } catch { /* status fallback */ }
        throw Object.assign(new Error(problem.detail), { code: problem.code });
      }
      passwordForm.reset();
      passwordForm.hidden = true;
      document.querySelector(".security-footer-link").hidden = true;
      const success = document.getElementById("password-success");
      success.hidden = false;
      success.focus({ preventScroll: true });
    } catch (error) {
      showError(friendlyError(error.code, error.message));
      setBusy(false);
    }
  });
})();
