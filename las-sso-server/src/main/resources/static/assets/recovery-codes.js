(() => {
  "use strict";

  const csrfInput = document.getElementById("security-csrf");
  if (!csrfInput) return;

  const csrfHeader = csrfInput.dataset.header;
  const csrfToken = csrfInput.value;
  const summary = document.getElementById("recovery-summary");
  const summaryText = document.getElementById("recovery-summary-text");
  const rotateButton = document.getElementById("rotate-recovery-codes");
  const rotateConfirmation = document.getElementById("rotate-confirmation");
  const reauthenticationForm = document.getElementById("reauthentication-form");
  const recoveryResult = document.getElementById("rotated-recovery-codes");
  const passwordDialog = document.getElementById("password-change-dialog");
  const passwordForm = document.getElementById("password-change-form");
  const passwordSubmit = document.getElementById("password-change-submit");
  const passwordSummary = document.getElementById("password-change-summary");
  const passwordSummaryText = document.getElementById("password-change-summary-text");
  let recoveryCodes = [];
  let unsavedCodes = false;

  async function request(url, options = {}) {
    const response = await fetch(url, {
      credentials: "same-origin",
      ...options,
      headers: {
        Accept: "application/json",
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(options.method && options.method !== "GET" ? { [csrfHeader]: csrfToken } : {}),
        ...(options.headers || {})
      }
    });
    if (response.ok) return response;
    let problem = {};
    try { problem = await response.json(); } catch { /* use status fallback */ }
    const error = new Error(problem.detail || "请求失败，请稍后重试。");
    error.code = problem.code || `HTTP_${response.status}`;
    throw error;
  }

  function friendlyError(error) {
    switch (error.code) {
      case "CURRENT_PASSWORD_INVALID": return "当前密码不正确，请重新输入。";
      case "NEW_PASSWORD_REUSED": return "新密码不能与当前密码相同。";
      case "PASSWORD_TOO_SHORT": return "新密码至少需要 12 个字符。";
      case "PASSWORD_TOO_LONG": return "新密码过长，请控制在 128 个字符以内。";
      case "PASSWORD_COMPROMISED": return "该密码过于常见，请选择更独特的密码。";
      case "REAUTHENTICATION_FAILED": return "当前密码不正确，请重新输入。";
      case "RATE_LIMITED": return "尝试过于频繁，请稍后重试。";
      case "CSRF_INVALID": return "页面安全状态已过期，请刷新页面后重试。";
      case "SESSION_EXPIRED": return "会话已过期，请重新登录。";
      default: return error.message || "请求失败，请稍后重试。";
    }
  }

  function showMessage(title, message, state = "error", focus = true) {
    summary.classList.toggle("error", state === "error");
    summary.classList.toggle("success", state === "success");
    summary.querySelector("strong").textContent = title;
    summaryText.textContent = message;
    summary.hidden = false;
    if (focus) summary.focus({ preventScroll: true });
  }

  function setBusy(button, busy, busyLabel, idleLabel) {
    button.disabled = busy;
    button.classList.toggle("loading", busy);
    button.querySelector(".button-label").textContent = busy ? busyLabel : idleLabel;
  }

  function openPasswordDialog() {
    if (!passwordDialog.open) passwordDialog.showModal();
    history.replaceState(null, "", "#password-change");
    document.getElementById("current-password").focus({ preventScroll: true });
  }

  function closePasswordDialog() {
    passwordDialog.close();
    passwordForm.reset();
    passwordSummary.hidden = true;
    passwordForm.querySelectorAll(".field-error").forEach((element) => { element.textContent = ""; });
    passwordForm.querySelectorAll('[aria-invalid="true"]').forEach((element) => element.removeAttribute("aria-invalid"));
    history.replaceState(null, "", location.pathname);
  }

  function showPasswordError(message) {
    passwordSummaryText.textContent = message;
    passwordSummary.hidden = false;
    passwordSummary.focus({ preventScroll: true });
  }

  async function loadStatus() {
    try {
      const response = await request("/api/v1/me/recovery-codes/status");
      const status = await response.json();
      document.getElementById("recovery-count").textContent = `${status.availableCount} / 10`;
      document.getElementById("recovery-state").textContent = status.availableCount > 0 ? "状态正常" : "需要生成";
      document.getElementById("recovery-generated-at").textContent = status.generatedAt
        ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium" }).format(new Date(status.generatedAt))
        : "尚未生成";
    } catch (error) {
      document.getElementById("recovery-state").textContent = "读取失败";
      showMessage("无法读取恢复码状态", friendlyError(error), "error", false);
    }
  }

  rotateConfirmation.addEventListener("change", () => {
    rotateButton.disabled = !rotateConfirmation.checked;
  });

  rotateButton.addEventListener("click", async () => {
    summary.hidden = true;
    setBusy(rotateButton, true, "正在生成…", "重新生成恢复码");
    try {
      const response = await request("/api/v1/me/recovery-codes/rotate", { method: "POST", body: "{}" });
      renderRecoveryCodes((await response.json()).recoveryCodes || []);
    } catch (error) {
      if (error.code === "RECENT_AUTHENTICATION_REQUIRED") {
        reauthenticationForm.hidden = false;
        document.getElementById("reauthentication-password").focus({ preventScroll: true });
        showMessage("需要近期认证", "请先输入当前密码，完成后再点击生成按钮。", "error", false);
      } else {
        showMessage("操作未完成", friendlyError(error));
      }
      setBusy(rotateButton, false, "正在生成…", "重新生成恢复码");
      rotateButton.disabled = !rotateConfirmation.checked;
    }
  });

  reauthenticationForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const password = reauthenticationForm.elements.password;
    const submit = document.getElementById("reauthentication-submit");
    document.getElementById("reauthentication-error").textContent = "";
    password.removeAttribute("aria-invalid");
    submit.disabled = true;
    try {
      await request("/api/v1/me/reauthentication", {
        method: "POST",
        body: JSON.stringify({ password: password.value })
      });
      reauthenticationForm.hidden = true;
      password.value = "";
      showMessage("认证已完成", "请再次点击“重新生成恢复码”。", "success", false);
      rotateButton.focus({ preventScroll: true });
    } catch (error) {
      password.setAttribute("aria-invalid", "true");
      document.getElementById("reauthentication-error").textContent = friendlyError(error);
      password.focus();
    } finally {
      submit.disabled = false;
    }
  });

  function renderRecoveryCodes(codes) {
    recoveryCodes = codes;
    unsavedCodes = codes.length > 0;
    document.getElementById("rotated-codes-saved").checked = false;
    document.getElementById("recovery-action").hidden = true;
    reauthenticationForm.hidden = true;
    recoveryResult.hidden = false;
    const list = document.getElementById("rotated-recovery-code-list");
    list.replaceChildren();
    for (const code of codes) {
      const item = document.createElement("li");
      const value = document.createElement("span");
      value.textContent = code;
      item.append(value);
      list.append(item);
    }
    recoveryResult.focus({ preventScroll: true });
  }

  async function copyCodes() {
    const text = recoveryCodes.join("\n");
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      const textarea = document.createElement("textarea");
      textarea.value = text;
      textarea.setAttribute("readonly", "");
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      document.body.append(textarea);
      textarea.select();
      const copied = document.execCommand("copy");
      textarea.remove();
      return copied;
    }
  }

  document.getElementById("copy-rotated-codes").addEventListener("click", async () => {
    const status = document.getElementById("rotated-codes-status");
    const copied = await copyCodes();
    status.textContent = copied ? "已复制全部恢复码。" : "浏览器未允许复制，请使用下载功能。";
    status.dataset.state = copied ? "success" : "error";
  });

  document.getElementById("download-rotated-codes").addEventListener("click", () => {
    const blob = new Blob([`${recoveryCodes.join("\n")}\n`], { type: "text/plain;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "las-recovery-codes.txt";
    link.click();
    URL.revokeObjectURL(link.href);
    const status = document.getElementById("rotated-codes-status");
    status.textContent = "恢复码文本已下载。";
    status.dataset.state = "success";
  });

  document.getElementById("rotated-codes-saved").addEventListener("change", (event) => {
    unsavedCodes = recoveryCodes.length > 0 && !event.currentTarget.checked;
  });
  window.addEventListener("beforeunload", (event) => {
    if (unsavedCodes) { event.preventDefault(); event.returnValue = ""; }
  });
  window.addEventListener("pagehide", () => {
    recoveryCodes = [];
    unsavedCodes = false;
    document.getElementById("rotated-recovery-code-list").replaceChildren();
    passwordForm.reset();
    reauthenticationForm.reset();
  });

  document.getElementById("open-password-change").addEventListener("click", openPasswordDialog);
  document.getElementById("close-password-change").addEventListener("click", closePasswordDialog);
  document.getElementById("cancel-password-change").addEventListener("click", closePasswordDialog);
  passwordDialog.addEventListener("click", (event) => {
    if (event.target === passwordDialog) closePasswordDialog();
  });
  passwordDialog.addEventListener("cancel", (event) => {
    event.preventDefault();
    closePasswordDialog();
  });

  passwordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    passwordSummary.hidden = true;
    passwordForm.querySelectorAll(".field-error").forEach((element) => { element.textContent = ""; });
    passwordForm.querySelectorAll('[aria-invalid="true"]').forEach((element) => element.removeAttribute("aria-invalid"));
    const current = passwordForm.elements.currentPassword;
    const next = passwordForm.elements.newPassword;
    const confirmation = passwordForm.elements.confirmPassword;
    if (!current.value) {
      current.setAttribute("aria-invalid", "true");
      document.getElementById("current-password-error").textContent = "请输入当前密码。";
      current.focus();
      return;
    }
    if (!next.checkValidity()) {
      next.setAttribute("aria-invalid", "true");
      document.getElementById("new-account-password-error").textContent = "请输入至少 12 个字符的新密码。";
      next.focus();
      return;
    }
    if (next.value !== confirmation.value) {
      confirmation.setAttribute("aria-invalid", "true");
      document.getElementById("confirm-account-password-error").textContent = "两次输入的新密码不一致。";
      confirmation.focus();
      return;
    }

    setBusy(passwordSubmit, true, "正在更新…", "更新密码");
    try {
      await request("/api/v1/me/password-change", {
        method: "POST",
        body: JSON.stringify({ currentPassword: current.value, newPassword: next.value })
      });
      passwordForm.reset();
      passwordForm.hidden = true;
      document.getElementById("close-password-change").hidden = true;
      const success = document.getElementById("password-change-success");
      success.hidden = false;
      success.focus({ preventScroll: true });
    } catch (error) {
      showPasswordError(friendlyError(error));
      setBusy(passwordSubmit, false, "正在更新…", "更新密码");
    }
  });

  loadStatus();
  if (location.hash === "#password-change") openPasswordDialog();
})();
