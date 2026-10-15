# 架构与身份

[目录](README.md) · [English](../en/architecture.md)

## 模块

唯一Gradle构建位于`las-sso-server/`，基线Java21/Spring Boot/PostgreSQL：

| 模块 | 职责 |
| --- | --- |
| Server根项目 | 身份、密码、账号生命周期、SSO会话、OIDC、资格、审计 |
| Spring Boot Starter | Servlet BFF接入、会话拥有的Token、刷新和退出接点 |
| Reference BFF | 可运行样例与可选队列，不是LASLOG等业务后端 |

```text
浏览器 → 主机Nginx HTTPS → SSO → PG
浏览器 → 业务BFF ← OIDC/Back-channel → SSO
采集器 → SSO私有管理口
```

SSO管人类身份和凭据，消费方管成员、角色、业务权限和Minecraft链接/准入。身份或资格通过不自动授权业务动作。

## 身份与状态

- 人类主体为不变UUID；OIDC身份键为`(issuer, sub)`。
- `LasUserPrincipal.getName()`和`getUsername()`返回UUID，`loginUsername()`为可变登录名。
- 用户名大小写敏感，当前/历史名保留；当前实现拒绝本人旧名复用。
- 账号ACTIVE/FROZEN/DELETED，密码状态及注销申请状态分别管理。
- PENDING/HELD注销只允许状态/取消/退出。取消或解冻不复活旧会话/授权。
- 唯一SSO管理员受保护；提升前由本人先取消有效注销申请。

## 持久化

Flyway迁移是schema权威。应用V1–V16含身份、框架`oauth2_*`客户端/授权、JDBC Session、安全元数据/历史、短期challenge/limit及audit/outbox。参考队列在独立`reference_revocation`中使用V1/V2。

runtime无DDL权限，离线migrator拥有对象；runtime可读、不可写Flyway历史。授权框架需要的Token可能以可用值持久化，因此DB和backup按敏感存储保护；密码、恢复码、client secret使用摘要。

## 事务与期限

- 凭据/生命周期先锁用户再关联安全记录，失败回滚对应失效。
- 注销按稳定用户优先顺序加锁，保持批次原子性。
- refresh在重用/撤销副作用前核已认证owner client；轮换不延長family固定截止。
- epoch不透明；资格版本只在同issuer/subject/epoch比较。恢复需新epoch/key及消费方屏障。
- outbox接受、HTTP投递成功、消费方执行为不同阶段。

期限见[安全](security.md)，接口见[API](api.md)与[接入](integration.md)。初期Starter单JVM，BFF重启结束业务登录；多实例/持久业务登录及独立资格事件延期。
