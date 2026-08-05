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

  const loginForm = document.querySelector('form[action="/login"]');
  loginForm?.addEventListener("submit", () => {
    const button = loginForm.querySelector('button[type="submit"]');
    if (button) {
      button.disabled = true;
      button.querySelector("span").textContent = "正在验证…";
    }
  });
})();
