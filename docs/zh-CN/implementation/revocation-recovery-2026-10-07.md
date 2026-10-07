# P03 撤销恢复实现

[English](../../implementation/revocation-recovery-2026-10-07.md) · [ADR-0013](../adr/0013-reference-revocation-operator-recovery.md) · [操作/接入](../integration/reference-durable-revocation.md)

## 范围与结果

P03实现参考队列人工replay、完成回执有界保留与排空key轮换。Starter增加request级提交结果，默认queue拒无效/未advertise提交。仍先本地退出再submit，接受task不是OP完成回执。不实现消费schema/完整跨存储原子退出，消费方补偿意图/采用留P11，pre-submit crash未关闭。

参考V2加replay_count/key持有HMAC registry/operator audit。短DB advisory锁串行key/变更，lock5秒/事务15秒，不持锁HTTP。死信核task/generation/认证payload，新campaign0..8次、审计同事务。purge只完成且超过配置1–365天（默认30），每小时batch1–1000（默认100），不碰pending/dead/lease。rotate须全部未完成排空、新material/从未复用ID、核旧proof/同审计；旧producer/claim不能静默用退休key，旧V1程序必须先停止升级。

V2在PostgreSQL插入边界配合轮换锁核task key ID，轮换后旧key legacy插入拒绝；首次registry建立认证全部未完成加密任务，非抽查一条。仍须停旧程序，trigger不会给旧worker补key proof/claim校验。

维护用精确reference JAR/hash、真实console确认、actor/reason/目标key，独立non-Web上下文，无OIDC registration/discovery/scheduler，拒key/password命令行。操作者权限来自主机/DB控制，actor不是身份证明，无浏览器admin replay API。

## 验证

JDK21/Docker参考/Starter聚焦通过，真实PostgreSQL18.4/HTTP/AES-GCM。新增V1→V2保留投递、新store/restart、replay generation/race/认证payload、replay/purge/rotate审计失败rollback、pending/dead/lease排空、rotate与submit竞争、wrong/same-ID/退休key ID复用、旧进程拒绝、完成保留边界、无console/取消/worker-active拒绝、offline无OIDC、本地退出失败结果及默认sink拒绝。fixture SQL加速lease/dead/retention时间，不模拟所有live crash指令。

严格JDK21/Gradle/Docker全量 **562server+196 Starter+50 reference=808项**，失败/错误/跳过零。首次全量Docker停止导致309 DBskip，门禁拒绝，重启Desktop/全部rerun后通过，不豁免。真实参考SNAPSHOT bootJAR/PowerShell/Windows PTY/隔离PG18.4验无console拒、内嵌V1–V2、取消purge不变、确认purge审计、排空轮换、旧key进程拒/新key重启；无SSO/OIDC Discovery依赖，fixtureDB/秘密文件清理。

真实进程replay：fixture按当前reference JAR类/依赖编译，seed JVM提交AES-GCM死信后退出，真实bootJAR/PowerShell/PTY恢复，新worker JVM向loopback RFC7009接收端投递一次，generation1/attempt1/擦密文。是真进程边界，非OP/浏览器采用。首轮候选扫描有原P02虚构key路径误报；将无功能含义fixture路径改名后，刷新514文件候选Gitleaks8.30.1零发现。五PowerShell语法、492text/1169本地链接/旧包完整性通过。不宣称本轮依赖/SAST/production image扫描。

历史.4 Edge/OP结果仅旧证据，不算新浏览器验收。目标TLS/容量/池/key保管/异地备份/通知/业务采用及SSO outbox操作恢复独立，无新依赖/SSO迁移，参考V2非SSO V16。现有release字节不改，新打包需新版本。
