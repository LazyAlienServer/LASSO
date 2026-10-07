# LAS SSO {{VERSION}} — 手册目录

面向运维、客户端开发者与账号用户。所有路径以解压后的交付包为基准。[English](../en/README.md) · [交付入口](../../README.md) · [版本说明](../../RELEASE-NOTES.md)

| 手册 | 用途 |
| --- | --- |
| [部署](deployment.md) | 外部配置、数据库、签名密钥、代理、初始化与启动 |
| [运维](operations.md) | 日常检查、维护命令、升级与故障处理 |
| [外部接入](external-integration.md) | 通用 OIDC、Starter 与 LAS 第一方契约 |
| [用户操作](user-guide.md) | 注册、账号安全、恢复与管理员页面 |
| [备份与恢复](backup-and-recovery.md) | 保管、PostgreSQL 恢复、epoch/密钥及消费方屏障 |
| [监控](monitoring.md) | 私有指标、Prometheus/Grafana/Alertmanager 与告警处理 |
| [排障](troubleshooting.md) | Discovery、登录、刷新、退出、迁移与指标故障 |
| [验收](acceptance.md) | 包/构建证据与环境专项验收要求 |

SSO 负责身份、凭据、账号生命周期和服务资格。消费方负责成员关系、业务角色、Minecraft 身份/准入及高风险操作。参考 BFF 是样例；SSO 登录成功不授予业务权限。本开发交付不代表生产批准。
