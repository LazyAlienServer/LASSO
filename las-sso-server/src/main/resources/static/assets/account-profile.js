(() => {
  "use strict";

  const csrf = document.getElementById("account-csrf");
  if (!csrf) return;

  const displayNameDialog = document.getElementById("display-name-dialog");
  const displayNameForm = document.getElementById("display-name-form");
  const usernameDialog = document.getElementById("username-dialog");
  const usernameForm = document.getElementById("username-form");

  async function request(method, url, body) {
    const response = await fetch(url, {
      method,
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        [csrf.dataset.header]: csrf.value
      },
      body: body == null ? undefined : JSON.stringify(body)
    });
    if (response.ok) return response.json();
    let problem = {};
    try { problem = await response.json(); } catch { /* status fallback */ }
    const error = new Error(problem.detail || "请求失败，请刷新后重试。");
    error.code = problem.code;
    error.problem = problem;
    throw error;
  }

  document.getElementById("open-edit-display-name").addEventListener("click", () => {
    displayNameForm.reset();
    document.getElementById("display-name-error").textContent = "";
    displayNameDialog.showModal();
    displayNameForm.elements.displayName.focus({ preventScroll: true });
  });
  document.getElementById("display-name-cancel").addEventListener("click", () => displayNameDialog.close());
  document.getElementById("display-name-close").addEventListener("click", () => displayNameDialog.close());

  displayNameForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!displayNameForm.reportValidity()) return;
    const submit = document.getElementById("display-name-submit");
    const error = document.getElementById("display-name-error");
    submit.disabled = true;
    error.textContent = "";
    try {
      await request("PATCH", "/api/v1/me/profile",
        {
          displayName: displayNameForm.elements.displayName.value.trim(),
          avatarResourceId: displayNameForm.elements.avatarResourceId.value || null
        });
      displayNameDialog.close();
      window.location.reload();
    } catch (failure) {
      error.textContent = failure.message;
    } finally {
      submit.disabled = false;
    }
  });

  document.getElementById("open-change-username").addEventListener("click", () => {
    usernameForm.reset();
    document.getElementById("username-error").textContent = "";
    document.getElementById("username-dialog-error").textContent = "";
    usernameDialog.showModal();
    usernameForm.elements.newUsername.focus({ preventScroll: true });
  });
  document.getElementById("username-cancel").addEventListener("click", () => usernameDialog.close());
  document.getElementById("username-close").addEventListener("click", () => usernameDialog.close());

  usernameForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!usernameForm.reportValidity()) return;
    const submit = document.getElementById("username-submit");
    const error = document.getElementById("username-dialog-error");
    const fieldError = document.getElementById("username-error");
    submit.disabled = true;
    error.textContent = "";
    fieldError.textContent = "";
    try {
      const view = await request("POST", "/api/v1/me/username-change", {
        newUsername: usernameForm.elements.newUsername.value.trim(),
        currentPassword: usernameForm.elements.currentPassword.value
      });
      usernameDialog.close();
      window.location.reload();
    } catch (failure) {
      if (failure.code === "USERNAME_CHANGE_COOLDOWN") {
        const availableAt = failure.problem?.availableAt;
        error.textContent = availableAt
          ? `用户名修改冷却中，${new Date(availableAt).toLocaleString("zh-CN")} 后可再次修改。`
          : "用户名修改冷却中，请 90 天后再试。";
      } else if (failure.code === "USERNAME_UNAVAILABLE") {
        fieldError.textContent = "该用户名已被占用或属于历史保留名称，请换一个。";
      } else {
        error.textContent = failure.message;
      }
      usernameForm.elements.currentPassword.value = "";
      usernameForm.elements.currentPassword.focus({ preventScroll: true });
    } finally {
      submit.disabled = false;
    }
  });
})();
