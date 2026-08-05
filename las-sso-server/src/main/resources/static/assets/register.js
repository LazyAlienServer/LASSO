(() => {
  "use strict";

  const form = document.getElementById("registration-form");
  if (!form) return;

  const widget = document.getElementById("altcha-widget");
  const captchaFrame = document.getElementById("captcha-frame");
  const captchaStatus = document.getElementById("captcha-status");
  const captchaError = document.getElementById("captcha-error");
  const agreement = document.getElementById("agreement");
  const submitButton = document.getElementById("register-submit");
  const summary = document.getElementById("error-summary");
  const summaryText = document.getElementById("error-summary-text");
  const successPanel = document.getElementById("registration-success");
  const codeList = document.getElementById("recovery-code-list");
  const recoveryActions = document.querySelector(".recovery-actions");
  const recoveryActionStatus = document.getElementById("recovery-action-status");
  const codesSaved = document.getElementById("codes-saved");
  const continueLogin = document.getElementById("continue-login");

  let csrf = null;
  let challengeId = null;
  let captchaVerified = false;
  let submitting = false;
  let idempotencyKey = crypto.randomUUID();
  let recoveryCodes = [];
  let unsavedSecrets = false;

  const fieldMessages = {
    username: "请输入 3–24 位字母、数字或下划线。",
    displayName: "请输入不超过 64 个字符的显示名称。",
    password: "请输入至少 12 个字符的密码。"
  };

  function setBusy(busy) {
    submitting = busy;
    submitButton.disabled = busy || !captchaVerified || !agreement.checked;
    submitButton.classList.toggle("loading", busy);
    submitButton.querySelector(".button-label").textContent = busy ? "正在创建…" : "创建通行证";
    form.setAttribute("aria-busy", String(busy));
    form.querySelectorAll("input, button").forEach((element) => {
      if (element !== submitButton) element.disabled = busy;
    });
  }

  function updateSubmitState() {
    if (!submitting) submitButton.disabled = !captchaVerified || !agreement.checked;
  }

  function clearErrors() {
    summary.hidden = true;
    for (const name of Object.keys(fieldMessages)) {
      const input = form.elements.namedItem(name);
      input?.removeAttribute("aria-invalid");
      const error = document.getElementById(`${name}-error`);
      if (error) error.textContent = "";
    }
    captchaError.textContent = "";
  }

  function showSummary(message, focus = true) {
    summaryText.textContent = message;
    summary.hidden = false;
    if (focus) summary.focus({ preventScroll: true });
  }

  function validateForm() {
    clearErrors();
    let firstInvalid = null;
    for (const [name, message] of Object.entries(fieldMessages)) {
      const input = form.elements.namedItem(name);
      if (!input.checkValidity()) {
        input.setAttribute("aria-invalid", "true");
        document.getElementById(`${name}-error`).textContent = message;
        firstInvalid ||= input;
      }
    }
    if (!agreement.checked) firstInvalid ||= agreement;
    if (!captchaVerified) {
      captchaError.textContent = "请先完成人机验证。";
      firstInvalid ||= widget;
    }
    if (firstInvalid) {
      showSummary("请修正标出的字段，然后再次提交。", false);
      firstInvalid.focus?.();
      return false;
    }
    return true;
  }

  async function getCsrf() {
    const response = await fetch("/api/v1/csrf", {
      credentials: "same-origin",
      headers: { Accept: "application/json" }
    });
    if (!response.ok) throw new Error("CSRF_UNAVAILABLE");
    csrf = await response.json();
  }

  async function issueChallenge() {
    captchaVerified = false;
    challengeId = null;
    updateSubmitState();
    captchaFrame.setAttribute("aria-busy", "true");
    captchaStatus.textContent = "正在准备验证…";
    captchaError.textContent = "";

    if (!csrf) await getCsrf();
    const response = await fetch("/api/v1/captcha/challenges", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        [csrf.headerName]: csrf.token
      },
      body: JSON.stringify({ purpose: "REGISTER" })
    });
    if (!response.ok) throw await readProblem(response);
    const challenge = await response.json();
    challengeId = challenge.challengeId;

    await customElements.whenDefined("altcha-widget");
    widget.reset?.();
    widget.configure({
      challenge: {
        parameters: challenge.parameters,
        signature: challenge.signature
      },
      auto: "off",
      language: "zh-cn",
      hideFooter: true,
      hideLogo: true
    });
    captchaFrame.setAttribute("aria-busy", "false");
    captchaStatus.textContent = "等待验证";
  }

  function captchaPayload() {
    const hidden = form.elements.namedItem("altcha");
    return widget.value || hidden?.value || widget.getAttribute("value") || "";
  }

  async function readProblem(response) {
    let body = {};
    try { body = await response.json(); } catch { /* use status fallback */ }
    const problem = new Error(body.detail || "请求失败，请稍后重试。");
    problem.code = body.code || `HTTP_${response.status}`;
    problem.status = response.status;
    problem.retryAfter = Number(response.headers.get("Retry-After") || 0);
    return problem;
  }

  function friendlyProblem(problem) {
    switch (problem.code) {
      case "USERNAME_UNAVAILABLE": return "这个精确大小写的用户名已经使用过，请选择其他用户名。";
      case "CAPTCHA_INVALID": return "人机验证已过期或已使用，请重新完成验证。";
      case "RATE_LIMITED": return problem.retryAfter > 0
        ? `请求过于频繁，请在 ${formatWait(problem.retryAfter)} 后重试。`
        : "请求过于频繁，请稍后重试。";
      case "IDEMPOTENCY_KEY_REUSED": return "提交内容在响应不确定后发生了变化。请刷新页面并重新注册。";
      case "IDENTITY_SERVICE_UNAVAILABLE": return "身份服务暂时不可用，你的账号尚未创建，请稍后重试。";
      case "VALIDATION_FAILED": return "部分资料不符合要求，请检查后重试。";
      case "CSRF_INVALID": return "页面安全状态已过期，请刷新页面后重试。";
      default: return problem.message || "请求失败，请稍后重试。";
    }
  }

  function formatWait(seconds) {
    if (seconds < 60) return `${seconds} 秒`;
    const minutes = Math.ceil(seconds / 60);
    if (minutes < 60) return `${minutes} 分钟`;
    return `${Math.ceil(minutes / 60)} 小时`;
  }

  function renderSuccess(result) {
    recoveryCodes = result.recoveryCodes || [];
    form.hidden = true;
    summary.hidden = true;
    successPanel.hidden = false;
    codeList.replaceChildren();
    recoveryActions.hidden = false;
    recoveryActionStatus.textContent = "";
    recoveryActionStatus.removeAttribute("data-state");

    if (recoveryCodes.length === 0) {
      const item = document.createElement("li");
      item.textContent = "账号已创建，但出于安全原因无法再次显示恢复码。请登录后重新生成。";
      item.style.gridColumn = "1 / -1";
      codeList.append(item);
      recoveryActions.hidden = true;
      codesSaved.checked = true;
      enableContinue();
      unsavedSecrets = false;
    } else {
      for (const code of recoveryCodes) {
        const item = document.createElement("li");
        const value = document.createElement("span");
        value.textContent = code;
        item.append(value);
        codeList.append(item);
      }
      unsavedSecrets = true;
    }
    successPanel.focus?.();
    successPanel.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function enableContinue() {
    const enabled = codesSaved.checked;
    continueLogin.classList.toggle("disabled", !enabled);
    continueLogin.setAttribute("aria-disabled", String(!enabled));
    unsavedSecrets = recoveryCodes.length > 0 && !enabled;
  }

  widget.addEventListener("statechange", (event) => {
    const state = event.detail?.state;
    if (state === "verified") {
      captchaVerified = true;
      captchaStatus.textContent = "验证完成";
      captchaError.textContent = "";
    } else if (state === "verifying") {
      captchaStatus.textContent = "正在计算…";
    } else if (state === "expired" || state === "error") {
      captchaVerified = false;
      captchaStatus.textContent = state === "expired" ? "验证已过期" : "验证失败";
    } else {
      captchaVerified = false;
      captchaStatus.textContent = "等待验证";
    }
    updateSubmitState();
  });

  agreement.addEventListener("change", updateSubmitState);
  codesSaved.addEventListener("change", enableContinue);
  continueLogin.addEventListener("click", (event) => {
    if (continueLogin.getAttribute("aria-disabled") === "true") event.preventDefault();
  });

  form.querySelectorAll("input").forEach((input) => {
    input.addEventListener("blur", () => {
      if (input.name && fieldMessages[input.name] && !input.checkValidity()) {
        input.setAttribute("aria-invalid", "true");
        document.getElementById(`${input.name}-error`).textContent = fieldMessages[input.name];
      }
    });
    input.addEventListener("input", () => {
      if (input.name && input.checkValidity()) {
        input.removeAttribute("aria-invalid");
        const error = document.getElementById(`${input.name}-error`);
        if (error) error.textContent = "";
      }
    });
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitting || !validateForm()) return;
    setBusy(true);
    try {
      const response = await fetch("/api/v1/registrations", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          "Idempotency-Key": idempotencyKey,
          [csrf.headerName]: csrf.token
        },
        body: JSON.stringify({
          username: form.elements.username.value,
          displayName: form.elements.displayName.value,
          password: form.elements.password.value,
          captcha: { challengeId, payload: captchaPayload() }
        })
      });
      if (!response.ok) throw await readProblem(response);
      renderSuccess(await response.json());
    } catch (problem) {
      showSummary(friendlyProblem(problem));
      if (problem.code === "USERNAME_UNAVAILABLE") {
        form.elements.username.setAttribute("aria-invalid", "true");
        document.getElementById("username-error").textContent = friendlyProblem(problem);
        form.elements.username.focus();
      }
      if (problem.code === "CAPTCHA_INVALID") {
        try { await issueChallenge(); } catch { captchaError.textContent = "无法获取新的验证，请稍后重试。"; }
      }
      if (problem.code === "CSRF_INVALID") csrf = null;
    } finally {
      setBusy(false);
    }
  });

  document.getElementById("copy-codes").addEventListener("click", async (event) => {
    const text = recoveryCodes.join("\n");
    recoveryActionStatus.textContent = "";
    recoveryActionStatus.removeAttribute("data-state");
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error("CLIPBOARD_UNAVAILABLE");
      }
      await navigator.clipboard.writeText(text);
      event.currentTarget.textContent = "已复制";
      recoveryActionStatus.dataset.state = "success";
      recoveryActionStatus.textContent = "恢复码已复制到剪贴板。";
    } catch {
      event.currentTarget.textContent = "复制全部";
      recoveryActionStatus.dataset.state = "error";
      recoveryActionStatus.textContent = "无法自动复制，请使用“下载文本”或手动选择恢复码。";
    }
  });

  document.getElementById("download-codes").addEventListener("click", () => {
    const content = ["LazyAlienServer 通行证恢复码", "", ...recoveryCodes, "", "每个恢复码只能使用一次，请安全保存。"].join("\n");
    const url = URL.createObjectURL(new Blob([content], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "las-passport-recovery-codes.txt";
    link.click();
    URL.revokeObjectURL(url);
  });

  window.addEventListener("beforeunload", (event) => {
    if (unsavedSecrets) {
      event.preventDefault();
      event.returnValue = "";
    }
  });

  issueChallenge().catch((problem) => {
    captchaFrame.setAttribute("aria-busy", "false");
    captchaStatus.textContent = "验证不可用";
    captchaError.textContent = friendlyProblem(problem);
    showSummary("无法载入人机验证，请检查网络后刷新页面。", false);
  });
})();
