# LAS SSO {{VERSION}} — 操作手册

[交付入口](../../README.md) · [English](../en/README.md) · [发行说明](../../RELEASE-NOTES.zh-CN.md)

## 按任务阅读

| 任务 | 从哪开始 | 完成后 |
| --- | --- | --- |
| 第一次部署 | **[Docker 部署教程](docker-deployment.md)** | [监控](monitoring.md)、[开放前验收](acceptance.md) |
| 已有主机 JAR/systemd 方案 | [JAR 部署](jar-deployment.md) | [开放前验收](acceptance.md) |
| 服务打不开、无法登录 | **[排障速查](troubleshooting.md)** | 按症状选分支 |
| 重启、管理员重置、升级 | [运维操作](operations.md) | 核预期结果再开放 |
| 做备份或灾难恢复 | [备份与恢复](backup-and-recovery.md) | 记录恢复时间与旧状态拒绝结果 |
| 接入业务网站 | [外部接入](external-integration.md) | 完成消费方测试表 |
| 使用账号或管理用户 | [用户与管理员](user-guide.md) | 保存一次性输出 |
| 历史表增长、清理指标异常 | [历史清理](history-retention.md) | 查看只读报告 |
| 参考 BFF 撤销任务死信 | [参考撤销队列](reference-revocation.md) | 精确任务恢复；仅使用该样例时适用 |

## 阅读约定

- **命令执行位置**写在步骤开头；Docker 教程以发行包根目录为基准。
- **预期结果**是继续下一步的判据；失败时先看该步骤的处理表。
- `{{VERSION}}` 是本包版本；`<...>` 是需要你填写的值。
- 路径和资源上限必须按目标主机填写；模板空值会被校验器拒绝。
- 命令中的密码由受保护文件或隐藏终端输入提供。

## 首发拓扑

```text
浏览器 → Nginx HTTPS → SSO → PostgreSQL
                       └→ 私有管理端口 9091 → 采集器
```

| 组件 | 对外方式 |
| --- | --- |
| Nginx | 发布已审查的 HTTP/HTTPS 接口 |
| SSO 9000、管理 9091、数据库 | 无宿主机端口映射 |
| 备份 | 加密、异机保存，需另配置 |

SSO 拥有身份、凭据、账号生命周期和服务资格。业务角色、Minecraft 准入和消费方高风险操作由业务系统负责。
