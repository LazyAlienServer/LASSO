(() => {
  "use strict";

  const widget = document.getElementById("altcha-widget");
  if (!widget) return;

  const form = document.getElementById("auth-form");
  const submitButton = form.querySelector('button[type="submit"]');
  const captchaFrame = document.getElementById("captcha-frame");
  const captchaStatus = document.getElementById("captcha-status");
  const captchaError = document.getElementById("captcha-error");

  submitButton.disabled = true;

  async function issueChallenge() {
    const csrfResponse = await fetch("/api/v1/csrf", {
      credentials: "same-origin",
      headers: { Accept: "application/json" }
    });
    if (!csrfResponse.ok) throw new Error("无法读取页面安全状态。");
    const csrf = await csrfResponse.json();

    const response = await fetch("/api/v1/captcha/challenges", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        [csrf.headerName]: csrf.token
      },
      body: JSON.stringify({ purpose: "LOGIN_RISK" })
    });
    if (!response.ok) throw new Error("暂时无法获取人机验证。");
    const challenge = await response.json();

    await customElements.whenDefined("altcha-widget");
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

  widget.addEventListener("statechange", (event) => {
    const state = event.detail?.state;
    const verified = state === "verified";
    submitButton.disabled = !verified;
    captchaStatus.textContent = verified
      ? "验证完成"
      : state === "verifying" ? "正在计算…" : "等待验证";
    if (verified) captchaError.textContent = "";
  });

  issueChallenge().catch((error) => {
    captchaFrame.setAttribute("aria-busy", "false");
    captchaStatus.textContent = "验证不可用";
    captchaError.textContent = error.message || "暂时无法载入验证，请稍后刷新页面。";
  });
})();
