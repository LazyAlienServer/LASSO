# 当前任务 / Roadmap

当前版本：[0.1.0-rc.2 预发布](https://github.com/LazyAlienServer/LASSO/releases/tag/v0.1.0-rc.2)，新构建/扫描/包证据随 Release 附件及包内 provenance 交付。[RC1 验收证据](https://github.com/LazyAlienServer/LASSO/blob/7f2b905e7d6b4161d1bf7d380558ead8e8415f2b/release/verification/0.1.0-rc.1.json)仅覆盖 RC1，不追认到 RC2。

| 顺序 | 软件发行任务 |
| --- | --- |
| 已整理 | 统一双语指南、API来源及显式发行清单，完成链接/API/独立包回归 |
| RC2 修复 | 应用/内部 API 错误和请求编号、严格 JSON/Unicode、64 码点显示名、协议机器端点错误、OAuth 撤销 Basic 编码、JWKS/Discovery；参数化 SQL 审查与隔离 PG 回归 |
| 下一项 | 移除或升级SSO基础镜像继承的pebble，复扫新镜像并核Java入口/健康 |
| 新候选 | 干净新版本构建、数据库测试、扫描、SDK/包/支持流程验收 |
| 正式版 | 核范围/限制，保存新版本来源及不可变制品，发布0.1.0 |

RC镜像有3条High来自未调用但仍存在的pebble；不能据Java依赖扫描0声明镜像无漏洞。细节在版本证据，不改写已发布RC字节。

## 目标上线

已选单机SSO/PG Compose及同机单层外部Nginx。正式域名/HTTPS按用户要求留正式上线；届时验证publicCA/续期、host权限/firewall、秘密/重启、监控真通知、加密异机恢复/RPO/RTO及实际资源上限。账号站可无consumer；每个业务接入自行验身份/资格/业务门禁。

独立资格事件、多实例/跨重启保业务登录、MFA/邮箱、历史RT后继补偿仍延期/可选，不新增首版门槛。

## English

The current version is RC2, not stable or production approval. Its build/scan/package evidence ships as Release assets and package provenance; historical RC1 acceptance is not transferred. Shared guides, the API source and explicit packaging have been consolidated and regression-checked. RC2 includes API errors/request IDs, strict JSON/Unicode, 64-code-point display names, machine-endpoint errors, OAuth revocation Basic encoding, JWKS and Discovery, with parameterized-SQL review and isolated PostgreSQL regressions. Next repair inherited pebble image findings and complete the remaining candidate acceptance before stable 0.1.0 with its own provenance.

Selected target is one host Nginx plus SSO/PostgreSQL Compose. Formal public domain/HTTPS is supplied at launch; actual Linux/network/custody/monitoring/off-host recovery/capacity and per-consumer enforcement need target evidence. Existing release bytes and dated evidence stay immutable. Deferred features remain separate decisions.
