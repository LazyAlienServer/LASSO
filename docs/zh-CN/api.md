# 已实现API

[目录](README.md) · [English](../en/api.md) · [OpenAPI3.1](../../api/openapi.json)

OpenAPI是当前路由/字段/状态参考，导入本机可信工具并填canonical issuer。无运行时Swagger UI或`/v3/api-docs`，OAuth/OIDC实际URL以Discovery为准。

## 认证

| 接口面 | 身份/条件 |
| --- | --- |
| 注册/CAPTCHA/开始恢复 | 匿名，写需CSRF及同源 |
| `/api/v1/me/**` | 当前SSO Cookie，受账号/session限制 |
| `/api/v1/admin/**` | SSO admin Cookie；写需当前DB角色/session、近期密码、CSRF/同源/reason |
| 内部资格 | ACTIVE FIRST_PARTY Basic，不用人Cookie/Bearer |
| token/revoke/introspect | 机密client认证及协议参数 |
| UserInfo | 有效openid AT，不用ID Token/Cookie |
| metrics | 私有管理口独立collector Basic |

CSRF为session状态，不假设独立CSRF Cookie。同jar GET `/api/v1/csrf`，保headerName/parameterName/token，写时带返回header；Origin或Referer同canonical。POST `/api/v1/me/reauthentication`成功204后保新session cookie、刷新CSRF，再单独提交原动作。

## 请求与结果

- JSON成功直接DTO/列表，204空正文，拒未知body字段。
- 时间为RFC3339 offset；lastUsedLabel等是显示文案，不是timestamp。
- X-Request-Id用于关联，不是授权/幂等证明，不记录认证body/header。
- 注册Key为UUID；其他适用写需非空≤128字符、actor+operation/body作用域、24小时。同key重试仍需认证/CSRF，replay不再显秘密。
- 适用：logout-all、注销申请/取消、admin freeze/unfreeze/revoke-sessions/人工恢复、client-secret rotation；client创建/编辑、码换批、hold/release无此保证。
- 超时先查状态/audit再重试。一次性结果丢失需明确新生成/重发/轮换。

| 来源 | 响应例外 |
| --- | --- |
| MVC应用异常 | problem+json、code/requestId及可选availableAt |
| session/强改/注销filter | 401/403 JSON `{code}` |
| 改密局部错误 | 400/401 JSON `{code}`，错当前密码401 |
| 匿名浏览器 | 可重定向login |
| 内部Basic | 空401 challenge，安全429/503 Problem |
| login/CAPTCHA | redirect或文本429/Retry-After |
| OAuth/OIDC | 协议错误/Bearer challenge/redirect |

## 接口组

| 分组 | 操作/重点 |
| --- | --- |
| 注册/恢复 | [用户](users.md)；恢复改密**PUT**，step1消费凭据、step2十分钟 |
| profile/改名/密码/码 | [用户](users.md)；稳定subject、历史名保留、一次性码 |
| 设备/授权/退出 | [用户](users.md)；当前sid撤销为用户全部SSO退出 |
| 注销 | [用户](users.md)；DELETE取消带JSON密码body，HELD也受限 |
| admin/client/audit | [管理员](administrators.md)；client创建200、PATCH全组配置、replay secret=null |
| 协议接入 | [接入](integration.md)；Token服务端、业务授权独立 |
| bootstrap/reset/epoch | [运维](operations.md)、[恢复](recovery.md)；非HTTP管理端点 |

注册Location不表示存在GET `/api/v1/users/{id}`；无通用`/api/v1/me`摘要/头像上传。显示名trim后1–64 code point，头像null或default-alien-01。login字段为**rememberDevice**。

## 内部资格

GET `/internal/v1/principals/{UUID}/service-access`，FIRST_PARTY Basic/no-store，精确七字段issuer/subject/decision/reason/stateEpoch/stateVersion/changedAt。未知主体200 DENIED/PRINCIPAL_NOT_FOUND、version0/changedAt=null。epoch/存储503，认证/预算/网络异常为不可用，不造账号封禁或ALLOWED。

版本仅同issuer/subject/epoch比较。consumer自行一分钟观测及本地generation/cache屏障，接口不建业务角色或事件流。

## 分页与协议限制

limit默认50、clamp1–200；user cursor为UUID，其余admin cursor原样时间/ID编码，不是签名能力。保filters，新筛选清cursor；client默认ACTIVE。

AT 默认 15 分钟，family 固定 14 天，refresh 不推进 auth_time。revoke 200 可为未知 Token，不证明后继清理。introspection 只反映存储 Token 状态，不证明资格或 family 截止。SSO `/logout` 和 `/connect/logout` 为用户全局退出，BFF 本地退出独立。

防漂移测试核实际MVC和标记DTO/OpenAPI，不代全协议认证或consumer验收。
