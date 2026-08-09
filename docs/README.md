# LAS SSO 文档目录

本目录按文档用途分组。产品总纲仍位于仓库根目录的 [LAS_SSO_DESIGN.md](../LAS_SSO_DESIGN.md)。

## 架构与协议契约

- [身份与数据模型](architecture/identity-and-data-model.md)：身份边界、状态机、数据库约束和并发规则。
- [账号与管理 API](architecture/api-contract.md)：页面与管理接口、错误语义和安全门禁。
- [OIDC 与 BFF 接入契约](architecture/oidc-and-bff-contract.md)：协议、Token、Claims、会话和退出契约。
- [安全、密码与恢复](architecture/security-and-recovery.md)：密码、恢复码、威胁控制和 Secret 规则。

## 业务系统接入

- [通用业务网站接入指南](integration/business-system-sso-integration-guide.md)：合作网站及不同技术栈的完整接入流程。
- [LAS 第一方网站接入指南](integration/first-party-system-sso-integration-guide.md)：LAS 自营系统的客户端登记与实现要求。

## 部署与运维

- [SSO 部署指南](operations/sso-deployment-guide.md)：当前仓库可执行的 JAR、PostgreSQL、反向代理部署步骤。
- [部署与运维手册](operations/operations-runbook.md)：备份、恢复、轮换、监控和故障 Runbook。

## 实施、验证与审查记录

- [实施准备度](implementation/implementation-readiness.md)：编码与生产上线门禁。
- [阶段 0 实施记录](implementation/phase-0-implementation.md)：工程、依赖、迁移和协议基础。
- [阶段 1 账号基础记录](implementation/phase-1-account-foundation.md)：账号、会话、恢复和管理员能力。
- [测试与验收计划](implementation/test-plan.md)：契约测试、集成测试和发布证据。
- [安全审查修复记录（2026-08-09）](implementation/security-review-remediation-2026-08-09.md)：已发现问题与修复记录。

## 界面规格

- [SSO UI 流程规格](ui/ui-flow-spec.md)：页面状态、跳转、危险操作和响应式要求。

## 阅读顺序

新参与者建议按以下顺序阅读：

1. [产品总纲](../LAS_SSO_DESIGN.md)；
2. [身份与数据模型](architecture/identity-and-data-model.md)；
3. [OIDC 与 BFF 接入契约](architecture/oidc-and-bff-contract.md)；
4. 根据任务选择[业务接入](integration/business-system-sso-integration-guide.md)或[SSO 部署](operations/sso-deployment-guide.md)；
5. 上线前检查[实施准备度](implementation/implementation-readiness.md)和[测试计划](implementation/test-plan.md)。
