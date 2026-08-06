(() => {
  "use strict";

  const csrf = document.getElementById("admin-csrf");
  if (!csrf) return;

  const detailDialog = document.getElementById("client-detail-dialog");
  const detailBody = document.getElementById("client-detail-body");
  const createDialog = document.getElementById("client-create-dialog");
  const createForm = document.getElementById("client-create-form");
  const editDialog = document.getElementById("client-edit-dialog");
  const editForm = document.getElementById("client-edit-form");
  const secretDialog = document.getElementById("client-secret-dialog");
  const secretValue = document.getElementById("client-secret-value");
  const secretStatus = document.getElementById("client-secret-status");
  const actionDialog = document.getElementById("client-action-dialog");
  const actionForm = document.getElementById("client-action-form");
  let currentClientId = null;
  let currentClient = null;
  let secretOpen = false;

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
    if (response.ok) return response.status === 204 ? null : response.json();
    let problem = {};
    try { problem = await response.json(); } catch { /* status fallback */ }
    const error = new Error(problem.detail || "请求失败，请刷新后重试。");
    error.code = problem.code;
    throw error;
  }

  function text(value) {
    return value == null ? "" : String(value);
  }

  function escapeHtml(value) {
    const div = document.createElement("div");
    div.textContent = text(value);
    return div.innerHTML;
  }

  function renderDetail(client) {
    const lines = [
      ["客户端 ID", client.clientId],
      ["名称", client.clientName],
      ["类型", client.clientType === "REVIEWED_PARTNER" ? "合作方" : "第一方"],
      ["状态", client.status === "DISABLED" ? "已禁用" : "正常"],
      ["配置版本", client.configVersion],
      ["授权数", client.authorizationCount],
      ["回调地址", client.redirectUris.join("，")],
      ["退出回调", client.postLogoutRedirectUris.join("，") || "—"],
      ["授权范围", client.scopes.join(", ")],
      ["资源 audience", client.resourceAudiences.join("，")],
      ["后端退出地址", client.backchannelLogoutUri || "—"],
      ["授权确认", client.requireConsent ? "需要" : "不需要"]
    ];
    detailBody.innerHTML = `<dl class="client-detail-grid">` +
      lines.map(([key, value]) =>
        `<div><dt>${escapeHtml(key)}</dt><dd>${escapeHtml(value)}</dd></div>`).join("") +
      `</dl>`;
    const toggle = document.getElementById("client-toggle-status");
    const rotate = document.getElementById("client-rotate-secret");
    document.getElementById("client-edit").hidden = false;
    toggle.hidden = false;
    rotate.hidden = false;
    toggle.textContent = client.status === "DISABLED" ? "启用客户端" : "禁用客户端";
    toggle.dataset.action = client.status === "DISABLED" ? "enable" : "disable";
    detailDialog.showModal();
  }

  async function openDetail(clientId) {
    currentClientId = clientId;
    detailBody.textContent = "加载中…";
    currentClient = await request("GET", `/api/v1/admin/clients/${encodeURIComponent(clientId)}`);
    renderDetail(currentClient);
  }

  document.getElementById("client-edit").addEventListener("click", () => {
    if (!currentClient) return;
    document.getElementById("client-edit-id").textContent = currentClient.clientId;
    document.getElementById("client-edit-error").textContent = "";
    editForm.reset();
    editForm.elements.clientName.value = currentClient.clientName;
    editForm.elements.clientType.value = currentClient.clientType;
    editForm.elements.redirectUris.value = currentClient.redirectUris.join("\n");
    editForm.elements.postLogoutRedirectUris.value = currentClient.postLogoutRedirectUris.join("\n");
    editForm.elements.resourceAudiences.value = currentClient.resourceAudiences.join("\n");
    editForm.elements.backchannelLogoutUri.value = currentClient.backchannelLogoutUri || "";
    editDialog.showModal();
    editForm.elements.clientName.focus({ preventScroll: true });
  });

  document.getElementById("client-edit-cancel").addEventListener("click", () => editDialog.close());
  document.getElementById("client-edit-close").addEventListener("click", () => editDialog.close());

  editForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!editForm.reportValidity()) return;
    const submit = document.getElementById("client-edit-submit");
    const error = document.getElementById("client-edit-error");
    const textareaList = (name) => editForm.elements[name].value.split("\n")
      .map((line) => line.trim()).filter((line) => line.length > 0);
    const body = {
      clientName: editForm.elements.clientName.value.trim(),
      clientType: editForm.elements.clientType.value,
      redirectUris: textareaList("redirectUris"),
      postLogoutRedirectUris: textareaList("postLogoutRedirectUris"),
      scopes: currentClient.scopes,
      resourceAudiences: textareaList("resourceAudiences"),
      backchannelLogoutUri: editForm.elements.backchannelLogoutUri.value.trim() || null,
      reason: editForm.elements.reason.value.trim()
    };
    submit.disabled = true;
    submit.textContent = "保存中…";
    error.textContent = "";
    try {
      await request("POST", "/api/v1/me/reauthentication", { password: editForm.elements.password.value });
      const result = await request("PATCH",
        `/api/v1/admin/clients/${encodeURIComponent(currentClient.clientId)}`, body);
      editDialog.close();
      detailDialog.close();
      if (result.revocationTriggered) {
        alert(`配置已保存（版本 ${result.configVersion}）。本次变更撤销了 ${result.revokedAuthorizationCount} 条授权，相关用户需要重新登录授权。`);
      }
      window.location.reload();
    } catch (failure) {
      error.textContent = failure.message;
      editForm.elements.password.value = "";
      editForm.elements.password.focus({ preventScroll: true });
    } finally {
      submit.disabled = false;
      submit.textContent = "保存修改";
    }
  });

  document.querySelectorAll("[data-action='detail']").forEach((button) => {
    button.addEventListener("click", () => openDetail(button.dataset.clientId));
  });
  document.getElementById("client-detail-close").addEventListener("click", () => detailDialog.close());
  document.getElementById("client-toggle-status").addEventListener("click", () => {
    const action = document.getElementById("client-toggle-status").dataset.action;
    openActionDialog(action, "确认" + (action === "disable" ? "禁用" : "启用") + "客户端",
      action === "disable"
        ? "禁用后该客户端所有授权、刷新令牌和 BFF 会话立即失效，用户需要重新登录授权。"
        : "启用后客户端可以重新发起登录授权，但不会恢复已撤销的授权。");
  });
  document.getElementById("client-rotate-secret").addEventListener("click", () => {
    openActionDialog("secret-rotation", "轮换客户端密钥",
      "轮换后旧密钥立即失效，该客户端所有授权、刷新令牌和 BFF 会话会被撤销。请先确认对接方已准备部署新密钥。");
  });

  const descriptions = {
    disable: "",
    enable: "",
    "secret-rotation": ""
  };

  function openActionDialog(action, title, description) {
    document.getElementById("client-dialog-title").textContent = title;
    document.getElementById("client-dialog-description").textContent = description;
    document.getElementById("client-dialog-error").textContent = "";
    actionForm.reset();
    actionForm.dataset.action = action;
    actionDialog.showModal();
    actionForm.elements.reason.focus({ preventScroll: true });
  }

  document.getElementById("client-dialog-cancel").addEventListener("click", () => actionDialog.close());

  actionForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!actionForm.reportValidity()) return;
    const submit = document.getElementById("client-dialog-submit");
    const error = document.getElementById("client-dialog-error");
    submit.disabled = true;
    submit.textContent = "处理中…";
    error.textContent = "";
    try {
      await request("POST", "/api/v1/me/reauthentication", { password: actionForm.elements.password.value });
      const result = await request("POST",
        `/api/v1/admin/clients/${encodeURIComponent(currentClientId)}/${actionForm.dataset.action}`,
        { reason: actionForm.elements.reason.value.trim() });
      actionDialog.close();
      if (actionForm.dataset.action === "secret-rotation") {
        showSecret(result.clientSecret);
      } else {
        detailDialog.close();
        window.location.reload();
      }
    } catch (failure) {
      error.textContent = failure.message;
      actionForm.elements.password.value = "";
      actionForm.elements.password.focus({ preventScroll: true });
    } finally {
      submit.disabled = false;
      submit.textContent = "确认执行";
    }
  });

  document.getElementById("open-create-client").addEventListener("click", () => {
    document.getElementById("client-create-error").textContent = "";
    createForm.reset();
    createDialog.showModal();
    createForm.elements.clientName.focus({ preventScroll: true });
  });
  document.getElementById("client-create-cancel").addEventListener("click", () => createDialog.close());
  document.getElementById("client-create-close").addEventListener("click", () => createDialog.close());

  createForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!createForm.reportValidity()) return;
    const submit = document.getElementById("client-create-submit");
    const error = document.getElementById("client-create-error");
    const textareaList = (name) => createForm.elements[name].value.split("\n")
      .map((line) => line.trim()).filter((line) => line.length > 0);
    const body = {
      clientId: createForm.elements.clientId.value.trim() || null,
      clientName: createForm.elements.clientName.value.trim(),
      clientType: createForm.elements.clientType.value,
      redirectUris: textareaList("redirectUris"),
      postLogoutRedirectUris: textareaList("postLogoutRedirectUris"),
      scopes: ["openid", "profile"],
      resourceAudiences: textareaList("resourceAudiences"),
      backchannelLogoutUri: createForm.elements.backchannelLogoutUri.value.trim() || null,
      reason: createForm.elements.reason.value.trim()
    };
    submit.disabled = true;
    submit.textContent = "创建中…";
    error.textContent = "";
    try {
      await request("POST", "/api/v1/me/reauthentication", { password: createForm.elements.password.value });
      const result = await request("POST", "/api/v1/admin/clients", body);
      createDialog.close();
      showSecret(result.clientSecret, result.clientId);
    } catch (failure) {
      error.textContent = failure.message;
      createForm.elements.password.value = "";
      createForm.elements.password.focus({ preventScroll: true });
    } finally {
      submit.disabled = false;
      submit.textContent = "创建";
    }
  });

  function showSecret(secret, clientId) {
    secretValue.textContent = secret;
    secretStatus.textContent = "";
    secretStatus.removeAttribute("data-state");
    document.getElementById("client-secret-copy").textContent = "复制密钥";
    secretOpen = true;
    secretDialog.showModal();
    if (clientId) {
      document.getElementById("client-secret-title").textContent = `客户端密钥 · ${clientId}`;
    }
  }

  document.getElementById("client-secret-saved").addEventListener("click", () => {
    secretOpen = false;
    secretDialog.close();
    window.location.reload();
  });
  document.getElementById("client-secret-close").addEventListener("click", () => {
    secretOpen = false;
    secretDialog.close();
    window.location.reload();
  });
  document.getElementById("client-secret-copy").addEventListener("click", async () => {
    secretStatus.textContent = "";
    try {
      if (!navigator.clipboard?.writeText) throw new Error("CLIPBOARD_UNAVAILABLE");
      await navigator.clipboard.writeText(secretValue.textContent);
      document.getElementById("client-secret-copy").textContent = "已复制";
      secretStatus.dataset.state = "success";
      secretStatus.textContent = "密钥已复制到剪贴板，请立即交付。";
    } catch {
      document.getElementById("client-secret-copy").textContent = "复制密钥";
      secretStatus.dataset.state = "error";
      secretStatus.textContent = "无法自动复制，请手动选择密钥文本。";
    }
  });

  window.addEventListener("beforeunload", (event) => {
    if (secretOpen) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
})();
