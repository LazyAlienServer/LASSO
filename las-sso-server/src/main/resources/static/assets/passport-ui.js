(() => {
  "use strict";

  document.querySelectorAll("[data-password-toggle]").forEach((button) => {
    const input = document.getElementById(button.dataset.passwordToggle);
    if (!input) return;
    button.addEventListener("click", () => {
      const reveal = input.type === "password";
      input.type = reveal ? "text" : "password";
      button.setAttribute("aria-pressed", String(reveal));
      button.setAttribute("aria-label", reveal ? "隐藏密码" : "显示密码");
      input.focus({ preventScroll: true });
    });
  });

  // Native dialog close/Escape must not leave a password visible in a retained form.
  document.querySelectorAll("dialog").forEach((dialog) => {
    const clearPasswords = () => {
      dialog.querySelectorAll('input[type="password"], input[data-secret-input]').forEach((input) => {
        input.value = "";
        input.type = "password";
      });
      dialog.querySelectorAll("[data-password-toggle]").forEach((button) => {
        const input = document.getElementById(button.dataset.passwordToggle);
        if (input) { input.value = ""; input.type = "password"; }
        button.setAttribute("aria-pressed", "false");
        button.setAttribute("aria-label", "显示密码");
      });
    };
    dialog.addEventListener("close", clearPasswords);
    dialog.addEventListener("cancel", clearPasswords);
    window.addEventListener("pagehide", clearPasswords);
  });

  const loginForm = document.querySelector('form[action="/login"], form[data-password-verification]');
  loginForm?.addEventListener("submit", () => {
    const button = loginForm.querySelector('button[type="submit"]');
    if (button) {
      button.disabled = true;
      button.querySelector("span").textContent = "正在验证…";
    }
  });
})();
