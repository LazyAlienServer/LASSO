(() => {
  "use strict";

  const csrf = document.getElementById("authorization-csrf");
  const dialog = document.getElementById("authorization-revoke-dialog");
  const form = document.getElementById("authorization-revoke-form");
  if (!csrf || !dialog || !form) return;

  const clientName = document.getElementById("authorization-client-name");
  const error = document.getElementById("authorization-dialog-error");
  const submit = document.getElementById("authorization-dialog-submit");
  let pendingClientId = null;

  function setBusy(busy) {
    submit.disabled = busy;
    submit.setAttribute("aria-busy", busy ? "true" : "false");
    submit.textContent = busy ? "撤销中…" : "确认撤销";
  }

  document.querySelectorAll(".authorization-revoke").forEach((button) => {
    button.addEventListener("click", () => {
      pendingClientId = button.dataset.clientId;
      clientName.textContent = button.dataset.clientName || pendingClientId;
      error.textContent = "";
      dialog.showModal();
      submit.focus({ preventScroll: true });
    });
  });

  document.getElementById("authorization-dialog-cancel")
    .addEventListener("click", () => dialog.close());

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!pendingClientId) return;
    setBusy(true);
    error.textContent = "";
    try {
      const response = await fetch(
        `/api/v1/me/authorizations/${encodeURIComponent(pendingClientId)}`,
        {
          method: "DELETE",
          credentials: "same-origin",
          headers: {
            Accept: "application/json",
            [csrf.dataset.header]: csrf.value
          }
        });
      if (!response.ok) {
        let problem = {};
        try { problem = await response.json(); } catch { /* status fallback */ }
        throw new Error(problem.detail || "请求失败，请刷新页面后重试。");
      }
      window.location.reload();
    } catch (failure) {
      error.textContent = failure.message;
      setBusy(false);
    }
  });
})();
