# 外部配置 / External configuration

| 部署方式 / Layout | 配置位置 / Template | 操作说明 / Guide |
| --- | --- | --- |
| **Docker Compose** | `deploy/docker/server.properties.example` + `deployment.env.example` + 外部文件secrets | [中文](../docs/zh-CN/docker-deployment.md) / [English](../docs/en/docker-deployment.md) |
| 主机 JAR / Host | `server/server.properties.example` | [中文](../docs/zh-CN/jar-deployment.md) / [English](../docs/en/jar-deployment.md) |
| Host monitoring | `server/monitoring.properties.example` | [中文](../docs/zh-CN/monitoring.md) / [English](../docs/en/monitoring.md) |
| Reference BFF | `reference-bff/bff.properties.example` | [接入](../docs/zh-CN/external-integration.md) / [Integration](../docs/en/external-integration.md) |
| Reference queue | `reference-bff/durable-revocation.properties.example` | [维护](../docs/zh-CN/reference-revocation.md) / [Maintenance](../docs/en/reference-revocation.md) |

## 配置使用 / Use

1. 将模板复制到包外受保护位置 / Copy outside the immutable package.
2. 按选定拓扑填值 / Supply actual values for that layout.
3. 非开发显式production，不混local/test / Explicit production, never mixed with local/test.
4. 启动前校验来源、权限和缺失值 / Verify identity, readability and completeness.

| 边界 / Boundary | 说明 / Meaning |
| --- | --- |
| Host vs container | Host loopback与容器内部监听不同，不能互换 / Different bindings |
| 配置选择 / Selection | `.example`不是自动选中或ready-to-run / Explicit additional location required |
| 维护 / Maintenance | Server-only配置、nonWeb、jobs关闭；queue用独立参考配置 / Separate contexts |
| Secret | 外部文件/服务环境供应，不进包/Git/log / Protected external input |

签名/queue key、TLS私钥、pepper、采集/webhook原密码、恢复码、数据库backup都不放此目录。

Signing/queue/TLS keys, peppers, collector/webhook raw passwords, recovery codes and backups remain outside the package.
