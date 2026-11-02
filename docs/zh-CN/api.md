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

- JSON 成功直接返回 DTO/列表，204 空正文。应用 JSON 使用 UTF-8，拒绝非法字节、UTF-16/32 和显式指定的其他 charset；重复字段（含转义后同名）、JSON 反转义后的孤立 Unicode 代理项、未知字段、数字/布尔值转字符串、client 配置数组中的 null 元素均返回 400。合法补充平面字符/emoji 原样往返；密码不做归一化或替换解码。
- 在安全过滤器写正文之前设置 Servlet 响应编码：`spring.servlet.encoding.charset=UTF-8` 和 `spring.servlet.encoding.force-response=true`（Boot 4 属性名）。这也覆盖框架 JWKS 接口；JSON Content-Type 不带 charset 或声明 UTF-8，不再声明 ISO-8859-1。OAuth/OIDC 正文不加业务包装。
- 时间为RFC3339 offset；lastUsedLabel等是显示文案，不是timestamp。
- X-Request-Id 覆盖应用处理和安全过滤器提前拒绝，同一值写入应用错误正文；它用于关联，不是授权/幂等证明。HTTP 服务器在 Servlet 处理前拒绝的请求可能只有容器响应。不记录认证 body/header。
- 注册Key为UUID；其他适用写需非空≤128字符、actor+operation/body作用域、24小时。同key重试仍需认证/CSRF，replay不再显秘密。
- 适用：logout-all、注销申请/取消、admin freeze/unfreeze/revoke-sessions/人工恢复、client-secret rotation；client创建/编辑、码换批、hold/release无此保证。
- 超时先查状态/audit再重试。一次性结果丢失需明确新生成/重发/轮换。

| 接口面 | 响应契约 |
| --- | --- |
| 应用/内部 API 错误 | application/problem+json，必有 type/title/status/code/requestId；可选 detail/instance/availableAt；no-store |
| 会话/强改/注销/CSRF/角色检查 | 相同 Problem 契约；API 401/403 不跳转 HTML 登录页 |
| 改密错误 | 相同 Problem 契约；当前密码错误仍为 401 |
| 内部 Basic | 相同 Problem 契约；401 保留带 `charset="UTF-8"` 的 WWW-Authenticate，429 保留 Retry-After |
| token/revoke/introspect 缺客户端认证 | 原生 OAuth `invalid_client`、401；Accept 缺失、`*/*`、HTML 或 JSON 均不跳登录 |
| 浏览器页面及密码登录 | HTML、跳转或文本 429/Retry-After |
| OAuth/OIDC、health、metrics | 保留协议及媒体原格式，不加业务包装 |

调用方依据 HTTP 状态和稳定 code 判断，不依赖可翻译的 title/detail。未处理应用异常返回安全通用 Problem，不暴露异常文字；Servlet 404/405/415 同样处理，并保留 Allow 等头。内部查询 Basic 为 UTF-8 `clientId:secret` 的 Base64，拒绝非法 UTF-8。OAuth `client_secret_basic` 先分别对 ID 和 secret 做 UTF-8 表单编码，再以 `:` 连接并 Base64；两个撤销 worker 均遵循此规则，包括 `+`、空格、`%`、`:` 和非 ASCII 凭据。仅含 ASCII 的 ID/码及二进制签名保持各自字节格式。

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

注册 Location 不表示存在 GET `/api/v1/users/{id}`；无通用 `/api/v1/me` 摘要/头像上传。注册和修改资料的显示名统一为 1–64 个 Unicode 码点（不是 UTF-16 单元、字节或字形簇）；修改资料会去除首尾空白。两个浏览器表单也按码点计数。头像 null 或 default-alien-01，login 字段为 **rememberDevice**。

## 内部资格

GET `/internal/v1/principals/{UUID}/service-access`，FIRST_PARTY Basic/no-store，精确七字段issuer/subject/decision/reason/stateEpoch/stateVersion/changedAt。未知主体200 DENIED/PRINCIPAL_NOT_FOUND、version0/changedAt=null。epoch/存储503，认证/预算/网络异常为不可用，不造账号封禁或ALLOWED。

版本仅同issuer/subject/epoch比较。consumer自行一分钟观测及本地generation/cache屏障，接口不建业务角色或事件流。

## 分页与协议限制

limit默认50、clamp1–200；user cursor为UUID，其余admin cursor原样时间/ID编码，不是签名能力。保filters，新筛选清cursor；client默认ACTIVE。

AT 默认 15 分钟，family 固定 14 天，refresh 不推进 auth_time。revoke 200 可为未知 Token，不证明后继清理。introspection 只反映存储 Token 状态，不证明资格或 family 截止。SSO `/logout` 和 `/connect/logout` 为用户全局退出，BFF 本地退出独立。

防漂移测试核实际MVC和标记DTO/OpenAPI，不代全协议认证或consumer验收。

Discovery 声明 openid/profile、authorization_code/refresh_token 及支持 sid 的 OIDC 后端退出。根路径/非根 Servlet 的真实 HTTP 回归还核安全链/MVC 错误、UTF-8 及非法输入，不仅检查 schema/路由。
