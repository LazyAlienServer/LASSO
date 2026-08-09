(() => {
  "use strict";

  const csrf = document.getElementById("deletion-csrf");
  const form = document.getElementById("deletion-request-form");
  const cancelForm = document.getElementById("deletion-cancel-form");
  const message = document.getElementById("deletion-message");
  if (!csrf) return;
  if (!message) return;
  let requestIdempotencyKey = crypto.randomUUID();
  let cancelIdempotencyKey = crypto.randomUUID();

  function showError(text) {
    message.hidden = false;
    message.classList.add("error");
    message.querySelector("strong").textContent = "操作未完成";
    message.querySelector("span").textContent = text;
    message.focus({ preventScroll: true });
  }

  function showSuccess(text) {
    message.hidden = false;
    message.classList.remove("error");
    message.querySelector("strong").textContent = "已提交";
    message.querySelector("span").textContent = text;
    message.focus({ preventScroll: true });
  }

  async function send(method, url, body, idempotencyKey) {
    const response = await fetch(url, {
      method,
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [csrf.dataset.header]: csrf.value
      },
      body: body == null ? undefined : JSON.stringify(body)
    });
    if (response.ok) return response.status === 204 ? null : response.json();
    let problem = {};
    try { problem = await response.json(); } catch { /* status fallback */ }
    const error = new Error(problem.detail || "请求失败，请刷新后重试。");
    error.code = problem.code;
    throw error;
  }

  form?.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!form.reportValidity()) return;
    const button = form.querySelector(".deletion-submit");
    button.disabled = true;
    button.textContent = "提交中…";
    message.hidden = true;
    try {
      await send("POST", "/api/v1/me/deletion-request",
        { password: form.elements.password.value }, requestIdempotencyKey);
      requestIdempotencyKey = crypto.randomUUID();
      showSuccess("注销申请已提交，正在退出所有应用…");
      window.setTimeout(() => window.location.reload(), 800);
    } catch (failure) {
      showError(failure.message);
      form.elements.password.value = "";
      form.elements.password.focus({ preventScroll: true });
    } finally {
      button.disabled = false;
      button.textContent = "申请注销通行证";
    }
  });

  cancelForm?.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!cancelForm.reportValidity()) return;
    const cancelButton = cancelForm.querySelector("button[type='submit']");
    cancelButton.disabled = true;
    message.hidden = true;
    try {
      await send("DELETE", "/api/v1/me/deletion-request",
        { password: cancelForm.elements.password.value }, cancelIdempotencyKey);
      cancelIdempotencyKey = crypto.randomUUID();
      showSuccess("注销申请已取消，账号保持正常。");
      window.setTimeout(() => window.location.reload(), 800);
    } catch (failure) {
      showError(failure.message);
      cancelForm.elements.password.value = "";
      cancelForm.elements.password.focus({ preventScroll: true });
      cancelButton.disabled = false;
    }
  });
})();
