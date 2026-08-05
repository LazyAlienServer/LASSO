(() => {
  "use strict";

  const form = document.getElementById("audit-filter-form");
  const fromLocal = document.getElementById("filter-from-local");
  const toLocal = document.getElementById("filter-to-local");
  const fromIso = document.getElementById("filter-from");
  const toIso = document.getElementById("filter-to");
  if (!form || !fromLocal || !toLocal || !fromIso || !toIso) return;

  const pad = (value) => String(value).padStart(2, "0");

  function toLocalValue(iso) {
    if (!iso) return "";
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return "";
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
      `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  fromLocal.value = toLocalValue(fromIso.value);
  toLocal.value = toLocalValue(toIso.value);

  function submitWithRange(start, end) {
    fromLocal.value = toLocalValue(start.toISOString());
    toLocal.value = toLocalValue(end.toISOString());
    form.requestSubmit();
  }

  document.querySelectorAll("[data-quick-range]").forEach((button) => {
    button.addEventListener("click", () => {
      const now = new Date();
      submitWithRange(new Date(now.getTime() - Number(button.dataset.quickRange)), now);
    });
  });

  form.addEventListener("submit", () => {
    fromIso.value = fromLocal.value ? new Date(fromLocal.value).toISOString() : "";
    toIso.value = toLocal.value ? new Date(toLocal.value).toISOString() : "";
  });

  const formatter = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });

  document.querySelectorAll(".audit-time").forEach((element) => {
    const iso = element.getAttribute("datetime");
    if (!iso) return;
    const date = new Date(iso);
    if (!Number.isNaN(date.getTime())) {
      element.textContent = formatter.format(date);
    }
  });
})();
