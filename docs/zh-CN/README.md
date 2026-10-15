# LAS SSO 操作手册

[English](../en/README.md)

| 要做什么 | 阅读 |
| --- | --- |
| 了解模块与身份模型 | [架构](architecture.md) |
| 了解信任、凭据与会话规则 | [安全](security.md) |
| 配合同机单层外部Nginx安装 | [部署](deployment.md) |
| 准备配置与秘密 | [配置](configuration.md) |
| 重启、管理员重置、升级 | [运维](operations.md) |
| 备份或恢复 | [恢复](recovery.md) |
| 查看指标、配置告警 | [监控](monitoring.md) |
| 启动或请求失败 | [排障](troubleshooting.md) |
| 注册、改密、管理本人账号 | [用户](users.md) |
| 管理用户与OIDC客户端 | [管理员](administrators.md) |
| 调用已实现端点 | [API](api.md)、[OpenAPI](../../api/openapi.json) |
| 接入后端/BFF | [接入](integration.md) |
| 运行参考BFF及可选撤销队列 | [参考样例](reference-bff.md) |

精确制品以选定版本的README和manifest为准。部署/运维命令在解压包根执行，开发命令在源码目录执行；示例路径按实际目标调整。

默认拓扑为SSO/PG Compose加同机单层外部Nginx。正式域名/public HTTPS在正式上线时供应；软件隔离fixture不能替代目标验收。

当前指南只保操作规则。[ADR](https://github.com/LazyAlienServer/LASSO/tree/main/docs/zh-CN/adr)保决策，[历史证据](https://github.com/LazyAlienServer/LASSO/tree/main/docs/archive)保日期结果。构建与协作见仓库CONTRIBUTING.md。
