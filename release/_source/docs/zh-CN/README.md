# LAS SSO {{VERSION}} — 操作手册

[交付入口](../../README.md) · [English](../en/README.md) · [发行说明](../../RELEASE-NOTES.zh-CN.md)

## 按任务阅读

**[全部操作流程目录](operation-index.md)**：用户U、管理员A、API F、运维O、备份B、监控M等编号步骤。

| 任务 | 从哪开始 | 完成后 |
| --- | --- | --- |
| 已选首次部署 | **[Docker+外部主机Nginx](host-nginx-deployment.md)** | [监控](monitoring.md)、[开放前验收](acceptance.md) |
| 内置Nginx替代方案 | [内置Docker部署](docker-deployment.md) | 单独选择service/helper |
| 已有主机 JAR/systemd 方案 | [JAR 部署](jar-deployment.md) | [开放前验收](acceptance.md) |
| 服务打不开、无法登录 | **[排障速查](troubleshooting.md)** | 按症状选分支 |
| 重启、管理员重置、升级 | [运维操作](operations.md) | 核预期结果再开放 |
| 做备份或灾难恢复 | [备份与恢复](backup-and-recovery.md) | 记录恢复时间与旧状态拒绝结果 |
| 接入业务网站 | [外部接入](external-integration.md) | 完成消费方测试表 |
| 接口字段/认证/错误 | [API 参考](api-reference.md) | [调用流程](api-operations.md)、[OpenAPI 3.1](../../api/openapi.json) |
| 使用个人账号 | [用户流程](user-guide.md) | 保存一次性输出 |
| 管理用户/client/注销/审计 | [管理员流程](administrator-guide.md) | 核目标/审计/一次性输出 |
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
| SSO9000 | 主机入口仅loopback发布；内置入口无host映射 |
| 管理9091、数据库 | 无宿主机端口映射 |
| 备份 | 加密、异机保存，需另配置 |

SSO 拥有身份、凭据、账号生命周期和服务资格。业务角色、Minecraft 准入和消费方高风险操作由业务系统负责。
