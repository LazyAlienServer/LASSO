# 发行说明 — {{VERSION}}

[English](RELEASE-NOTES.md) · [交付入口](README.md)

## 相较 0.1.0-dev.20261009.1

| 范围 | 改进 |
| --- | --- |
| 入口 | 按任务导航，首页直接链接 Docker 教程与排障 |
| Docker | 独立八步教程，每步命令、预期结果、失败处理 |
| 手册 | 短章节、速查表，JAR/systemd 独立说明 |
| 专项 | 历史清理与参考撤销队列拆分 |
| 文件权限 | 明确 DB 秘密由 PostgreSQL 与 SSO 按用途共同读取 |
| 打包 | Docker 入口链接在解压包内可直接跳转 |

本次手册修订不改变认证运行逻辑、依赖锁或既有数据库迁移。

## 已包含功能

| 阶段 | 内容 |
| --- | --- |
| P02 | 生产 profile/config/key/admin/client 启动门禁 |
| P03 | 撤销提交结果、参考 V2 死信恢复/保留/排空换 key |
| P04 | 账号/恢复/生命周期界面修复、一次性秘密清理与人工核验 |
| P05 | V16 索引、90/30天有界运行历史清理、只读报告 |
| P06 | 单机 Compose、私有网络/文件秘密、独立DB角色、离线迁移 |

包含 server、Starter file Maven、参考 BFF、source ZIP、双语手册/配置/工具。容器镜像另构建或加载。

## 首发范围

- 仅 SSO 账号站：一个实例、独立 PostgreSQL、同机 Nginx、初期无 CDN。
- 各消费方自身上线前联调验收。
- 正式域名、硬件/流量、保管、备份/RPO/RTO、通知渠道仍由运维填写。

## 到哪里查证据

| 问题 | 文件 |
| --- | --- |
| 精确程序/来源 | provenance/release-manifest.json、SHA256SUMS |
| 本版本测试和扫描 | provenance/verification-summary.json |
| 依赖和迁移 | provenance/dependencies、database/migration-manifest.json |
| 目标部署需验什么 | [验收表](docs/zh-CN/acceptance.md) |

先前 `.20261009.1` 精确包 Compose 冒烟及 P02–P06 日期记录仍是历史，只有明确重跑才算本版本结果。旧 SSO OS Medium/Low、被替换 gosu 归档元信息、Java 映射缓存限制保留在其记录中。

## 兼容与限制

| 项目 | 规则 |
| --- | --- |
| 运行 | JDK21、Boot4.1/Security7.1；精确版本以锁/catalog为准 |
| 数据库 | SSO 内嵌 V1–V16；参考queue V1–V2独立 |
| Starter | 默认单JVM/HttpSession，BFF重启结束业务登录 |
| 持久撤销 | 仅已commit任务；提交前补偿由消费方实现 |
| 有效历史 | 保live hint/RT，不保证全存储有界或审计归档 |
| 恢复 | 新epoch/key/kid与消费方屏障；回binary须已验schema兼容 |
| 延期 | 独立资格事件、多实例/跨重启保登录；不加MFA/邮箱/业务Claim |

本包为开发交付。公共CA/续期/防火墙/Linux保管、当前镜像扫描/DAST、备份/持续负载和最终开放仍需目标证据，不自动执行生产或托管签名发行。
