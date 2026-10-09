# Docker Compose 部署与确认输入矩阵

[English](../../operations/docker-compose-deployment.md) · [可执行步骤](../../../las-sso-server/deploy/docker/README.zh-CN.md) · [上线计划](../implementation/production-launch-plan.md)

更新2026-10-09。记录用户确认范围与本地实现，不等生产部署批准。

## 已确认范围和责任

| 输入 | 已确认 |
| --- | --- |
| 首次开放 | SSO账号站，不要求业务消费方提前完成 |
| 拓扑 | 单机Docker Compose，一个SSO实例，独立PostgreSQL/Nginx容器 |
| 公共入口 | 同机Nginx HTTPS，初期无CDN |
| 私有边界 | app9000、management9091、PostgreSQL无宿主机端口，只审查proxy入口 |
| 数据库 | 初期同机，备份独立异机 |
| 发行/部署/值班/人工恢复/key/备份保管 | 用户本人承担初期职责 |
| 消费方上线 | LASLOG/AlienCommon各自上线时登记、联调并执行门禁 |

## 仍需提供输入

| 输入 | 值/状态 | 必需证据 |
| --- | --- | --- |
| 预发布/生产域名、Issuer、DNS | 待定 | 精确canonical host、公共CA，不用fixture/example |
| 主机OS/架构/CPU/RAM/磁盘/可用空间/时钟 | 待定 | 目标配置身份及资源/余量限制 |
| 用户/数据量、峰值速率、并发、延迟/错误界限 | 待定 | P13代表ramp/soak和初期开放上限 |
| TLS续期/reload、外部防火墙/接口 | 待定 | P06外部探针及续期/到期演练 |
| 备份目标/加密/调度/保留 | 待定 | 独立异机可恢复backup及失败通知 |
| RPO/RTO | 待定 | 负责人确认目标及P10实测恢复 |
| 告警/支持渠道与升级 | 待定 | 真实收件人送达/确认/恢复通知 |
| key/secret/恢复码存放与访问 | 责任人确认，方式待定 | Linux权限/mount、分离、重启轮换/保管 |
| 候选版本/保存/维护窗口 | 待定 | 新不可变制品和最终目标演练 |

不把旧runbook人数/并发假设采为本轮目标。矩阵不存密码、key、Token、恢复码。非秘密 `deployment.env.example` 故意留镜像、host、路径和限额空值，验证器拒缺失/不一致。

## P06–P10进度与门禁

| 任务 | 本地证据 | 目标仍需 |
| --- | --- | --- |
| P06 | Compose隔离、rootless只读app/proxy、真实fixture TLS/canonical头/未知Host/私有端口、DB故障恢复 | 真实Linux/防火墙/正式DNS/公共CA/续期/外部检查；fixture SPKI例外不是CA验收 |
| P07 | configtree文件注入、不用明文秘密env/buildarg、key/session重启稳定、密码仅console | Linux UID/ACL、实际存放分离/轮换/可恢复保管 |
| P08 | 空库V1–V16迁移、可重复非Web命令、迁移/runtime/backup角色、Flyway只读/backup dump | 代表已填数据目标迁移/时间空间/回退/停writer-job流程 |
| P09 | 私有认证管理口、health故障行为/有界日志 | 实际collector/Grafana/真实通知渠道/覆盖/轮换 |
| P10 | 隐藏bootstrap/首登改密，backup角色能custom dump | 加密定时异机backup、实际恢复/key+epoch、RPO/RTO实测及操作员演练 |

精确制品/扫描/范围见[本地验收](../implementation/docker-compose-acceptance-2026-10-09.md)。systemd/JAR是另一部署方式，容器配置不能与host loopback互换。历史 `0.1.0-dev.20261007.1` 包保持字节不变、不含本轮改动；下一新包会复制Docker模板工具，本轮未新建/批准生产发行。
