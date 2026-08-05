package org.las.sso.admin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计动作与安全事件的中文说明注册表。
 *
 * <p>新增事件类型时在此登记展示文案；未登记的类型在页面上显示原文和「未登记」标记，
 * 不阻塞查询，但说明运营侧尚未评审该类型的展示口径。
 */
public final class AuditLabels {

    private static final Map<String, String> AUDIT_ACTIONS = new LinkedHashMap<>();
    private static final Map<String, String> SECURITY_EVENTS = new LinkedHashMap<>();

    static {
        AUDIT_ACTIONS.put("ADMIN_BOOTSTRAP_CREATE", "初始化首个管理员");
        AUDIT_ACTIONS.put("ADMIN_BOOTSTRAP_PROMOTE", "提升账号为管理员");
        AUDIT_ACTIONS.put("ADMIN_USER_FREEZE", "冻结账号");
        AUDIT_ACTIONS.put("ADMIN_USER_UNFREEZE", "解除冻结");
        AUDIT_ACTIONS.put("ADMIN_USER_REVOKE_SESSIONS", "强制下线，撤销会话与授权");
        AUDIT_ACTIONS.put("ADMIN_RECOVERY_CREDENTIAL_ISSUE", "签发人工恢复凭据");
        AUDIT_ACTIONS.put("ADMIN_PASSWORD_RESET_BY_MAINTENANCE", "维护命令重置管理员密码");

        SECURITY_EVENTS.put("ACCOUNT_TEMPORARILY_LOCKED", "登录失败达到阈值，账号临时锁定");
        SECURITY_EVENTS.put("RECOVERY_SESSION_CREATED", "恢复凭据验证成功，恢复会话建立");
        SECURITY_EVENTS.put("PASSWORD_RECOVERED", "通过恢复流程设置新密码，旧会话与授权已撤销");
        SECURITY_EVENTS.put("ADMIN_RECOVERY_CREDENTIAL_ISSUED", "管理员签发人工恢复凭据");
        SECURITY_EVENTS.put("ACCOUNT_FROZEN_BY_ADMIN", "管理员冻结账号");
        SECURITY_EVENTS.put("ACCOUNT_UNFROZEN_BY_ADMIN", "管理员解除冻结");
        SECURITY_EVENTS.put("SESSIONS_REVOKED_BY_ADMIN", "管理员强制下线");
        SECURITY_EVENTS.put("RECOVERY_CODES_ROTATED", "恢复码重新生成，旧批次已废除");
        SECURITY_EVENTS.put("PASSWORD_CHANGED", "密码已修改");
        SECURITY_EVENTS.put("AUTHORIZATION_REVOKED", "用户撤销应用授权");
        SECURITY_EVENTS.put("ADMIN_PASSWORD_RESET_BY_MAINTENANCE", "维护命令重置密码");
        SECURITY_EVENTS.put("REFRESH_TOKEN_SECURITY_STATE_INVALID",
                "刷新令牌安全状态失效（账号或版本变化），令牌族已撤销");
        SECURITY_EVENTS.put("REFRESH_TOKEN_REUSE_DETECTED",
                "检测到旧刷新令牌重用，令牌族已撤销并通知业务系统退出");
    }

    private AuditLabels() {
    }

    public static Map<String, String> auditActions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(AUDIT_ACTIONS));
    }

    public static Map<String, String> securityEvents() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(SECURITY_EVENTS));
    }
}
