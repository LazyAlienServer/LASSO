# ADR-0016：单机 Compose、SSO账号站首发

- 状态：已确认范围/拓扑，2026-10-09；目标验收待完成
- [English](../../adr/0016-single-host-compose-account-site-launch.md)

## 决策

2026-10-11用户细化确认：首发使用**同机单层外部Nginx**，通过只绑定loopback的应用发布端口直达SSO容器；PG及management不发布。内置proxy保留为可选模板，历史`.2`包保持原拓扑。正式域名/public HTTPS输入在目标正式上线阶段供应，不阻塞软件候选准备；对外开放前仍须验证。见[ADR-0017](0017-single-external-nginx-container-edge.md)。

用户选单机Docker Compose、同机Nginx HTTPS、初期无CDN、SSO/PG独立容器，仅SSO账号站首次开放。app/DB/management端口私有。用户本人承担初期部署/值班/人工恢复/key/backup保管。数据库初期同机，备份独立异机。正式域名、硬件/流量、RPO/RTO、存放通知方式和候选/窗口仍待定，不推断默认。

实现不可变辨识镜像、受保护外部文件配置/秘密、非root只读SSO/proxy、独立迁移/runtime/backup角色，bootstrap/Web前离线迁移。保持内嵌Flyway启用，runtime只读迁移历史、无DDL。非交互 `database-migrate` 可无console验证离线完成；密码/epoch命令保持真实交互确认。Docker/Compose是可执行模板，不等生产资源供应批准。

## 后果

SSO账号站首发不等待LASLOG/AlienCommon P11/P12，各消费方自身上线前仍须完成；SSO本身目标传输/保管/备份/监控/容量/安全/最终候选门禁仍必需。不加MFA/邮箱/业务Claim或多实例范围。systemd/hostloopback是独立方式；后续CDN/多主机/副本/prefix须补对应网络/信任/保管验收。

固定上游基础设施镜像报告可修OS发现，构建时安全层保存精确输出/包/扫描身份；软件源可变，重建新字节。替换binary后归档扫描残留须核实际运行文件，保留原始发现。最终目标/当前发行扫描仍releasegate；见[输入和运维](../operations/docker-compose-deployment.md)。
