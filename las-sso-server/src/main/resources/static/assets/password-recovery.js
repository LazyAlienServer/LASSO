(() => {
  "use strict";

  const credentialForm = document.getElementById("recovery-credential-form");
  if (!credentialForm) return;

  const passwordForm = document.getElementById("recovery-password-form");
  const completePanel = document.getElementById("recovery-complete");
  const footer = document.getElementById("recovery-footer");
  const stepLabel = document.getElementById("recovery-step-label");
  const errorBox = document.getElementById("recovery-error");
  const errorText = document.getElementById("recovery-error-text");
  const captchaGroup = document.getElementById("recovery-captcha-group");
  const captchaFrame = document.getElementById("captcha-frame");
  const captchaStatus = document.getElementById("captcha-status");
  const captchaError = document.getElementById("captcha-error");
  const widget = document.getElementById("altcha-widget");
  const verifyButton = document.getElementById("verify-recovery");
  const completeButton = document.getElementById("complete-recovery");

  let csrf = null;
  let challengeId = null;
  let captchaVerified = false;
  let busy = false;

  async function getCsrf() {
    const response = await fetch("/api/v1/csrf", {
      credentials: "same-origin",
      headers: { Accept: "application/json" }
    });
    if (!response.ok) throw new Error("CSRF_UNAVAILABLE");
    csrf = await response.json();
  }

  async function readProblem(response) {
    let body = {};
    try { body = await response.json(); } catch { /* use fallback */ }
    const problem = new Error(body.detail || "请求失败，请稍后重试。");
    problem.code = body.code || `HTTP_${response.status}`;
    problem.retryAfter = Number(response.headers.get("Retry-After") || 0);
    return problem;
  }

  function friendlyProblem(problem) {
    switch (problem.code) {
      case "RECOVERY_CREDENTIAL_INVALID":
        return "用户名或恢复码不正确、已使用或已失效。请检查大小写后重试。";
      case "CAPTCHA_REQUIRED":
        return "近期恢复尝试较多，请完成人机验证后再次提交。";
      case "CAPTCHA_INVALID":
        return "人机验证已过期或已使用，请重新验证。";
      case "SESSION_EXPIRED":
        return "恢复会话已经失效。该恢复码不能再次使用，请使用另一枚恢复码重新开始。";
      case "NEW_PASSWORD_REUSED":
        return "新密码不能与旧密码相同。";
      case "PASSWORD_TOO_SHORT":
        return "新密码至少需要 12 个字符。";
      case "PASSWORD_TOO_LONG":
        return "新密码不能超过 128 个字符。";
      case "PASSWORD_EQUALS_USERNAME":
        return "新密码不能与用户名完全相同。";
      case "PASSWORD_COMPROMISED":
        return "这个密码过于常见，请更换一个更难猜测的密码。";
      case "RATE_LIMITED":
        return problem.retryAfter > 0
          ? `尝试过于频繁，请在 ${formatWait(problem.retryAfter)} 后重试。`
          : "尝试过于频繁，请稍后重试。";
      case "CSRF_INVALID":
        return "页面安全状态已过期，请刷新页面后重试。";
      case "IDENTITY_SERVICE_UNAVAILABLE":
        return "身份服务暂时不可用，请稍后重试。";
      case "VALIDATION_FAILED":
        return "部分内容不符合要求，请检查后重试。";
      default:
        return problem.message || "请求失败，请稍后重试。";
    }
  }

  function formatWait(seconds) {
    if (seconds < 60) return `${seconds} 秒`;
    return `${Math.ceil(seconds / 60)} 分钟`;
  }

  function showError(message, focus = true) {
    errorText.textContent = message;
    errorBox.hidden = false;
    if (focus) errorBox.focus({ preventScroll: true });
  }

  function clearError() {
    errorBox.hidden = true;
    credentialForm.querySelectorAll("[aria-invalid='true']").forEach((field) => {
      field.removeAttribute("aria-invalid");
    });
    document.querySelectorAll(".field-error").forEach((field) => {
      field.textContent = "";
    });
  }

  function setBusy(button, value, busyLabel, idleLabel) {
    busy = value;
    button.disabled = value;
    button.classList.toggle("loading", value);
    button.querySelector(".button-label").textContent = value ? busyLabel : idleLabel;
    const form = button.form;
    form?.setAttribute("aria-busy", String(value));
    form?.querySelectorAll("input, button").forEach((control) => {
      if (control !== button) control.disabled = value;
    });
  }

  async function issueChallenge() {
    captchaGroup.hidden = false;
    captchaVerified = false;
    challengeId = null;
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
      body: JSON.stringify({ purpose: "RECOVERY_RISK" })
    });
    if (!response.ok) throw await readProblem(response);
    const challenge = await response.json();
    challengeId = challenge.challengeId;
    await customElements.whenDefined("altcha-widget");
    widget.reset?.();
    widget.configure({
      challenge: { parameters: challenge.parameters, signature: challenge.signature },
      auto: "off",
      language: "zh-cn",
      hideFooter: true,
      hideLogo: true
    });
    captchaFrame.setAttribute("aria-busy", "false");
    captchaStatus.textContent = "等待验证";
  }

  function captchaPayload() {
    const hidden = credentialForm.elements.namedItem("altcha");
    return widget.value || hidden?.value || widget.getAttribute("value") || "";
  }

  function validateCredentialForm() {
    clearError();
    const username = credentialForm.elements.username;
    const credential = credentialForm.elements.credential;
    if (!username.checkValidity()) {
      username.setAttribute("aria-invalid", "true");
      document.getElementById("recovery-username-error").textContent = "请输入 3–24 位字母、数字或下划线。";
      username.focus();
      return false;
    }
    if (!credential.checkValidity()) {
      credential.setAttribute("aria-invalid", "true");
      document.getElementById("recovery-code-error").textContent = "请输入一枚完整恢复码。";
      credential.focus();
      return false;
    }
    if (!captchaGroup.hidden && !captchaVerified) {
      captchaError.textContent = "请先完成人机验证。";
      widget.focus?.();
      return false;
    }
    return true;
  }

  function validatePasswordForm() {
    clearError();
    const password = passwordForm.elements.newPassword;
    const confirmation = passwordForm.elements.confirmPassword;
    if (!password.checkValidity()) {
      password.setAttribute("aria-invalid", "true");
      document.getElementById("recovery-new-password-error").textContent = "请输入 12–128 个字符的新密码。";
      password.focus();
      return false;
    }
    if (confirmation.value !== password.value) {
      confirmation.setAttribute("aria-invalid", "true");
      document.getElementById("recovery-confirm-password-error").textContent = "两次输入的新密码不一致。";
      confirmation.focus();
      return false;
    }
    return true;
  }

  widget.addEventListener("statechange", (event) => {
    const state = event.detail?.state;
    captchaVerified = state === "verified";
    captchaStatus.textContent = state === "verified"
      ? "验证完成"
      : state === "verifying" ? "正在计算…"
        : state === "expired" ? "验证已过期" : "等待验证";
    if (captchaVerified) captchaError.textContent = "";
  });

  credentialForm.elements.credential.addEventListener("blur", (event) => {
    event.currentTarget.value = event.currentTarget.value.trim().toUpperCase();
  });

  credentialForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !validateCredentialForm()) return;
    setBusy(verifyButton, true, "正在验证…", "验证恢复码");
    try {
      if (!csrf) await getCsrf();
      const captcha = captchaGroup.hidden ? null : {
        challengeId,
        payload: captchaPayload()
      };
      const response = await fetch("/api/v1/recovery/sessions", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          [csrf.headerName]: csrf.token
        },
        body: JSON.stringify({
          username: credentialForm.elements.username.value,
          credential: credentialForm.elements.credential.value,
          credentialType: "RECOVERY_CODE",
          captcha
        })
      });
      if (!response.ok) throw await readProblem(response);
      credentialForm.hidden = true;
      passwordForm.hidden = false;
      errorBox.hidden = true;
      stepLabel.textContent = "设置新密码 · 2 / 2";
      passwordForm.elements.newPassword.focus({ preventScroll: true });
    } catch (problem) {
      showError(friendlyProblem(problem));
      if (problem.code === "CAPTCHA_REQUIRED") {
        try { await issueChallenge(); } catch (challengeProblem) {
          captchaError.textContent = friendlyProblem(challengeProblem);
        }
      } else if (problem.code === "CAPTCHA_INVALID") {
        try { await issueChallenge(); } catch (challengeProblem) {
          captchaError.textContent = friendlyProblem(challengeProblem);
        }
      }
    } finally {
      setBusy(verifyButton, false, "正在验证…", "验证恢复码");
    }
  });

  passwordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !validatePasswordForm()) return;
    setBusy(completeButton, true, "正在更新…", "设置新密码");
    try {
      if (!csrf) await getCsrf();
      const response = await fetch("/api/v1/recovery/password", {
        method: "PUT",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          [csrf.headerName]: csrf.token
        },
        body: JSON.stringify({ newPassword: passwordForm.elements.newPassword.value })
      });
      if (!response.ok) throw await readProblem(response);
      passwordForm.reset();
      passwordForm.hidden = true;
      completePanel.hidden = false;
      footer.hidden = true;
      errorBox.hidden = true;
      stepLabel.textContent = "恢复完成";
      completePanel.focus({ preventScroll: true });
    } catch (problem) {
      showError(friendlyProblem(problem));
      if (problem.code === "SESSION_EXPIRED") {
        passwordForm.hidden = true;
        credentialForm.reset();
        credentialForm.hidden = false;
        captchaGroup.hidden = true;
        stepLabel.textContent = "验证恢复码 · 1 / 2";
      }
    } finally {
      setBusy(completeButton, false, "正在更新…", "设置新密码");
    }
  });
})();
