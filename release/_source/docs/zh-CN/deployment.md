# 选择部署方式

[目录](README.md) · [English](../en/deployment.md)

## 当前选定方式

**单机 Docker Compose + 同机 Nginx + 独立 PostgreSQL，首发 SSO 账号站。**

直接从 **[Docker 部署教程](docker-deployment.md)** 开始。

| 方式 | 文档 | 适用场景 |
| --- | --- | --- |
| Docker Compose | [完整步骤](docker-deployment.md) | 当前首发方案 |
| 主机 JAR / systemd | [完整步骤](jar-deployment.md) | 已采用独立主机进程的环境 |

## 开始前需确定

| 输入 | 用途 |
| --- | --- |
| 正式域名、HTTPS Issuer、证书和续期 | 浏览器入口与身份命名空间 |
| 主机 OS/架构/CPU/内存/磁盘、资源上限 | 运行与容量验证 |
| 秘密、签名 key/kid 的保管方式 | 重启和维护保持稳定身份 |
| 异机备份、保留、RPO/RTO | 灾难恢复 |
| 真实通知渠道、维护窗口 | 运维响应 |

SSO 账号站首发可没有业务客户端。消费方自己的回调、权限和联调在其上线时完成。

## 两种方式共同的启动顺序

```text
验证发行包 → 配置和密钥 → 启动数据库 → 离线迁移
→ 隐藏输入 bootstrap → 启动 Web → 首登改密 → 目标验收 → 开放
```

| 规则 | 检查 |
| --- | --- |
| 非开发必须 `production` | 不与 `local` / `test` 混用 |
| 空库先初始化管理员 | 无可用管理员时 Web 启动会拒绝 |
| 保持内嵌 Flyway 校验 | 不 repair checksum 或改已发布迁移 |
| 应用、数据库、指标私有 | 只通过选定 Nginx 入口访问 |

故障入口：[排障速查](troubleshooting.md)。开放判据：[验收表](acceptance.md)。
