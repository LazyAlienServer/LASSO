# 历史开发交付 0.1.0-dev.20261007.1

[English](../en/development-delivery-2026-10-07-1.md) · [打包入口](../../../release/README.md) · [包后验收记录](../../../release/verification/0.1.0-dev.20261007.1.json)

完整本地独立交付位于 release/0.1.0-dev.20261007.1/ 和 release/las-sso-0.1.0-dev.20261007.1.zip。生成制品忽略，打包/扫描工具与双语手册源跟踪；历史 .20261006.7 制品/manifest不改。

## 来源与内容

- 精确干净源码 a442b0b1b9c5d7668c6dc214b5e6440ee927f443，tree 56f696e02361b575d32c75581796867133863c8d；后续验收记录提交不替换该快照。
- JDK21.0.2/Zulu、Wrapper9.6.1、Docker29.4.0，server/Starter显式本版本。
- 服务端、Starter JAR/POM/native metadata/checksums/file Maven仓库、可运行参考BFF、git archive源码ZIP、内嵌Flyway V1–V15摘要、实际nested libraries/锁/catalog/Wrapper来源。
- 每语言九文件：目录、部署、运维、接入、用户、恢复、监控、排障、验收；外部配置及Nginx/systemd/监控模板无真实秘密。
- 维护脚本精确JAR/hash必填、默认production、保留Java隐藏密码输入；校验/只读冒烟不依赖源码工作区。

## 本轮验证

clean/rerun全量构建与本地发布：**505server+193Starter+37reference=735**，失败/错误/跳过零，必需PostgreSQL覆盖。staging校验发现版本替换误改校验器检测字符串，在a442b0b修复后完整重建/重扫。首轮未发布尝试仅忽略诊断记录，没有覆盖已交付版本。

Trivy0.75.0、2026-10-07漏洞DB：241锁依赖/157解包JAR依赖行，范围内零报告漏洞。Semgrep1.179.0：409path/6发现/0error，固定重定向当前调用图误报、两条运维本地网络/固定URL提示、三条可信本地XML提示均明确复核。Gitleaks8.30.1源码/程序/历史零；完整解压包archive depth3零。退出0/零发现不保证绝无风险，不覆盖生产image/JDK/proxy/DAST。

独立ZIP解压：70文件/104包内链接，源码git archive commit、版本/POM/native metadata/内嵌迁移/hash一致；篡改文件/多余文件负例拒绝，恢复原包后通过。四PowerShell语法通过，维护错误hash在Java前拒绝。独立native/POM-only消费者从解压包file仓库编译公共协议API，并验Boot4.1.1/Security7.1.1/Framework7.0.9/Log4j2.25.5。

Prometheus3.6配置语法/七规则/规则fixture、Alertmanager0.28.1配置验证通过。Nginx1.29用临时证书/路径替换执行-t通过。systemd为已审查模板，Windows未执行目标服务/systemd-analyze。

包JAR+复制production/monitoring模板、隔离PostgreSQL18.4、外供fixture PKCS12/秘密启动成功。验空库V1–V15、唯一HTTPS issuer Discovery/公有持久RSA kid、管理无认证metrics401/独立collector200/app口拒绝、真实DB断连readiness **200→503→200**、liveness200、粗DOWN。一次8秒probe短于默认pool等待；另一次随机Docker端口重启变化，固定端口/较长timeout后通过，JVM/DB已停止删除。仅loopback HTTP/进程/配置验证，不是公网TLS/浏览器/生产部署。

## 不可变摘要

| 项目 | SHA-256 |
| --- | --- |
| 分发ZIP | `9f41ee29b14efae8534ca052678af48d01a27d115cfc9949a9c63669cbe88d1b` |
| 源码ZIP | `ecb3bb8b04a526b58eff6987216adb7f68cec8e91af2db8287dd4cd170593605` |
| Server | `5e82b30de6e316a35eef3cee80bda9cde539bc906e290d31c925f3a3dc06fe81` |
| Starter | `7f4caa2dfff814499bc628e0654371cfadda6df4474b0b8eb0af1e09409fbe32` |
| Reference BFF | `b6576673ee5a2660c4d558b7cdbfc88da10ca46ab71cacc4e476e19b6d782cce` |

解压根运行python scripts/verify-package.py。SHA-256为完整性非签名。包后验收记于此，不编辑完成的package/provenance/sourceZIP。私有构建/扫描/运行日志与fixture秘密不交付。

## 剩余范围

开发交付完成，未push/托管CI或release/操作生产DB/生产批准。未重跑新版本浏览器/refresh/logout/restore/Grafana/真人通知，旧.5/.6/.7仍历史。完整startup/admin维护门禁、可靠消费方撤销、真实第一方15分钟/一分钟/epoch门禁、目标TLS/key/备份/RPO/RTO、小时负载/history保留及分批上线仍按[生产计划](https://github.com/LazyAlienServer/LASSO/blob/8390243505b781c25447fa4e125532f421f56b76/docs/zh-CN/implementation/production-launch-plan.md)。
