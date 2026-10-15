# 当前任务 / Roadmap

最新交付：[0.1.0-rc.1 预发布](https://github.com/LazyAlienServer/LASSO/releases/tag/v0.1.0-rc.1)。[验收证据](https://github.com/LazyAlienServer/LASSO/blob/7f2b905e7d6b4161d1bf7d380558ead8e8415f2b/release/verification/0.1.0-rc.1.json)只覆盖该版本。

| 顺序 | 软件发行任务 |
| --- | --- |
| 已整理 | 统一双语指南、API来源及显式发行清单，完成链接/API/独立包回归 |
| 下一项 | 移除或升级SSO基础镜像继承的pebble，复扫新镜像并核Java入口/健康 |
| 新候选 | 干净新版本构建、数据库测试、扫描、SDK/包/支持流程验收 |
| 正式版 | 核范围/限制，保存新版本来源及不可变制品，发布0.1.0 |

RC镜像有3条High来自未调用但仍存在的pebble；不能据Java依赖扫描0声明镜像无漏洞。细节在版本证据，不改写已发布RC字节。

## 目标上线

已选单机SSO/PG Compose及同机单层外部Nginx。正式域名/HTTPS按用户要求留正式上线；届时验证publicCA/续期、host权限/firewall、秘密/重启、监控真通知、加密异机恢复/RPO/RTO及实际资源上限。账号站可无consumer；每个业务接入自行验身份/资格/业务门禁。

独立资格事件、多实例/跨重启保业务登录、MFA/邮箱、历史RT后继补偿仍延期/可选，不新增首版门槛。

## English

The latest identified delivery is RC1, not stable or production approval. Shared guides, the API source and explicit packaging have been consolidated and regression-checked. Next repair inherited pebble image findings, then build/scan/accept a new clean candidate and issue stable 0.1.0 with its own provenance.

Selected target is one host Nginx plus SSO/PostgreSQL Compose. Formal public domain/HTTPS is supplied at launch; actual Linux/network/custody/monitoring/off-host recovery/capacity and per-consumer enforcement need target evidence. Existing release bytes and dated evidence stay immutable. Deferred features remain separate decisions.
