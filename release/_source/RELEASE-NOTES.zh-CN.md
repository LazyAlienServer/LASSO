# 发行说明 — {{VERSION}}

[English](RELEASE-NOTES.md)

## 范围

本次为当前干净提交Java21源码的独立开发交付，纳入P02–P06。包含SSO可执行程序、Servlet Starter/file Maven仓库、参考BFF、源码ZIP、双语部署/运维/接入/用户/恢复/监控/排障/验收手册、外部配置及Docker/Nginx/systemd/monitoring模板、维护和校验工具。

相较独立包 `0.1.0-dev.20261007.1`：

- P02生产profile/issuer/key/secret/Cookie/Flyway/epoch/admin/ACTIVE客户端启动门禁；无管理员先离线初始化再Web。
- P03显式本地撤销提交结果；参考V2精确代次死信恢复、完成任务有界保留、排空后key轮换；提交前崩溃缺口仍需消费方补偿。
- P04账号/恢复/生命周期UI修复、关闭/离页清一次性秘密，人工恢复按用户确认两项独立依据核验和最小工单。
- P05 SSO V16索引与90/30天有界运行历史清理、忙锁/超时/固定指标、只读 `scripts/history-status.sql`。有效Token/hint及审计/注销事实不自动删除，目标容量/归档仍运维任务；参考队列V1–V2独立。
- P06单机Compose、独立SSO/PG/Nginx、文件secrets、app/DB/management私有网络、迁移/runtime/backup角色及离线 `database-migrate`。独立镜像工具核精确JAR/hash/干净构建或包来源。基础设施安全层另构建/扫描，ZIP不内置容器镜像。

首发已确认仅SSO账号站、同机Nginx、初期无CDN；业务消费方各自上线时联调验收。域名/硬件/流量/备份/RPO/RTO/通知仍待运维输入。本次不是正式生产部署批准。

## 校验与证据

`provenance/verification-summary.json`记录本版本命令/工具/来源/三模块测试/扫描范围；完整构建要求数据库测试且拒skip。程序/SDK/配置/内嵌迁移/依赖/source摘要见manifest和SHA256SUMS。解压后在包根执行 `python scripts/verify-package.py`。

历史`.7`双BFF TLS/Grafana Viewer/两条五分钟混合、`.6`三类真实告警、`.5`管理员/恢复仍历史；P02–P06日期记录在源码快照。先前Compose用worktree fixture镜像测hidden bootstrap/reset/首登改密/权限/真实fixture TLS/伪头/重启/DB故障，不是本发行干净镜像。其SSO扫描Medium40/Low4，修补基础设施运行文件零报告，但保留已替换gosu旧基础层发现及较旧Java包映射缓存限制。本包依赖扫描只代表本版本精确JAR/锁；最终部署镜像/OS/JDK须独立当前扫描，未重跑的真实进程路径不自动当本版通过。

## 限制与兼容

- 开发交付；无远程push/托管签名/目标生产执行或容量保证。
- Starter默认状态在进程/HttpSession，初期BFF重启丢登录；已接受撤销持久恢复需实际消费Adapter或显式参考profile。无多实例/跨存储原子退出。
- 存活授权hint历史保持，删除授权级联清；不重建早期丢失hint，pepper轮换可使旧摘要查找失效。
- 正式域名/公共CA/续期/firewall/代理信任、key/secret保管、异机backup/恢复/RPO/RTO、真实通知、小时级负载、消费方15分钟发起会话证据/一分钟资格拒绝/epoch屏障和最终目标安全仍单独门禁。
- 不新增MFA/邮箱/业务权限/Minecraft Claim；独立资格事件、多实例/跨重启保登录仍延期。
- JDK21、Boot4.1/Security7.1；精确版本以catalog/锁和nested-library清单为准。Starter POM/native metadata对齐受应用BOM/锁约束。
- 数据库使用server内嵌Flyway V1–V16，不维护第二份手工迁移。回退只在已验schema兼容允许；恢复旧快照必须新epoch/key/kid、全局安全重置和消费方屏障。
